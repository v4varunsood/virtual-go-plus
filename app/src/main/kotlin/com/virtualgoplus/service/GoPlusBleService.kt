package com.virtualgoplus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.virtualgoplus.gatt.GattServerManager
import com.virtualgoplus.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GoPlusBleService : Service() {

    companion object {
        private const val TAG = "GoPlusBleService"
        private const val CHANNEL_ID = "VirtualGoPlusChannel"
        private const val NOTIFICATION_ID = 1001

        // iAnyGo/iPogo custom SFIDA service UUID (reverse-engineered)
        val GOPLUS_SERVICE_UUID: java.util.UUID =
            java.util.UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e37")

        // The exact device name Niantic's app looks for
        const val TARGET_DEVICE_NAME = "Pokemon GO Plus"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServerManager: GattServerManager? = null
    private var isAdvertising = false

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    enum class ConnectionState { ADVERTISING, CONNECTED, DISCONNECTED, PAIRING }

    inner class LocalBinder : Binder() {
        fun getService(): GoPlusBleService = this@GoPlusBleService
    }

    // Pairing broadcast receiver
    private val pairingReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val pairingTransport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE)
                    val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    Log.i(TAG, "=== PAIRING REQUEST === from=${device?.address} transport=$pairingTransport variant=$pairingVariant (${
                        when (pairingVariant) {
                            0 -> "PASSKEY_CONFIRMATION"
                            1 -> "PASSKEY_DISPLAY"
                            2 -> "PASSKEY_ENTRY"
                            3 -> "CONSENT"
                            4 -> "PRE_SIX_DIGIT_DH"
                            5 -> "FINAL_DH"
                            else -> "UNKNOWN"
                        }
                    })")
                    connectionState = ConnectionState.PAIRING
                    updateNotification("Pairing...")

                    serviceScope.launch {
                        kotlinx.coroutines.delay(300L)
                        Log.i(TAG, "Auto-accepting pairing now...")
                        try {
                            // Try the standard 000000 PIN approach first
                            device?.setPin("000000".toByteArray())
                            device?.setPairingConfirmation(true)
                            Log.i(TAG, "✅ setPin + setPairingConfirmation succeeded")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "❌ setPin failed (need BLUETOOTH_CONNECT?): ${e.message}")
                            // Android 12+ requires BLUETOOTH_CONNECT for setPin
                            // Fall back to createBond which doesn't need explicit permission
                            try {
                                val bondResult = device?.createBond()
                                Log.i(TAG, "✅ createBond fallback result: $bondResult")
                            } catch (e2: SecurityException) {
                                Log.e(TAG, "❌ createBond also failed: ${e2.message}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Pairing error: ${e.message}")
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondStateStr = when (state) {
                        BluetoothDevice.BOND_BONDING -> "BONDING"
                        BluetoothDevice.BOND_BONDED -> "BONDED ✅"
                        BluetoothDevice.BOND_NONE -> "NONE ❌"
                        else -> "UNKNOWN($state)"
                    }
                    Log.i(TAG, "=== BOND STATE === $prevState -> $bondStateStr")
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "✅ PAIRING SUCCESS!")
                            notifyConnectionState(ConnectionState.CONNECTED)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            Log.i(TAG, "❌ Pairing failed / removed")
                            notifyConnectionState(ConnectionState.DISCONNECTED)
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            Log.i(TAG, "Pairing in progress...")
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    Log.i(TAG, "ACL connected: ${device?.address}")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    Log.i(TAG, "ACL disconnected: ${device?.address}")
                    notifyConnectionState(ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initGattServer()

        // Register for pairing broadcasts
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pairingReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register pairing receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startAdvertising()
            "STOP" -> stopAdvertising()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered: ${e.message}")
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Virtual GO Plus",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BLE peripheral service for Virtual GO Plus"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GoPlusBleService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual GO Plus")
            .setContentText(state)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun initGattServer() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            return
        }

        // Set the Bluetooth device name to "GO Plus" so Niantic's app finds it
        try {
            val setResult = bluetoothAdapter.setName(TARGET_DEVICE_NAME)
            Log.i(TAG, "Set device name result: $setResult, current: ${bluetoothAdapter.name}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot set device name: ${e.message}")
        }

        gattServerManager = GattServerManager(this, bluetoothAdapter)
        gattServerManager?.initialize()
    }

    @Suppress("DEPRECATION")
    fun startAdvertising() {
        if (isAdvertising) return

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            return
        }

        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }

        // Set the device name to exactly what Pokémon GO looks for
        bluetoothAdapter.name = TARGET_DEVICE_NAME

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // iAnyGo uses TWO separate advertising calls:
        // Call 1: Include device name but NO service UUID (for general discovery)
        val dataWithName = AdvertiseData.Builder()
            .setIncludeDeviceName(true)  // "Pokemon GO Plus" visible in iOS BLE scanner
            .build()

        // Call 2: Include service UUID but NO device name (for service-specific discovery)
        val dataWithService = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(GOPLUS_SERVICE_UUID))
            .build()

        // Start both advertising instances (this is what iAnyGo does)
        bluetoothLeAdvertiser?.startAdvertising(settings, dataWithName, advertiseCallback)
        isAdvertising = true
        Log.i(TAG, "Started advertising as '$TARGET_DEVICE_NAME'")
    }

    fun stopAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        connectionState = ConnectionState.DISCONNECTED
        updateNotification("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            connectionState = ConnectionState.ADVERTISING
            updateNotification("Advertising as '$TARGET_DEVICE_NAME' — Waiting for connection")
            Log.i(TAG, "BLE advertising started as '$TARGET_DEVICE_NAME'")
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                else -> "error $errorCode"
            }
            Log.e(TAG, "BLE advertising failed: $reason")
            connectionState = ConnectionState.DISCONNECTED
            updateNotification("Advertising failed: $reason")
        }
    }

    fun notifyConnectionState(state: ConnectionState) {
        connectionState = state
        val text = when (state) {
            ConnectionState.ADVERTISING -> "Advertising as '$TARGET_DEVICE_NAME' — Waiting for connection"
            ConnectionState.CONNECTED -> "Connected to Pokémon GO"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.PAIRING -> "Pairing in progress..."
        }
        updateNotification(text)
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun getGattServerManager(): GattServerManager? = gattServerManager
}
