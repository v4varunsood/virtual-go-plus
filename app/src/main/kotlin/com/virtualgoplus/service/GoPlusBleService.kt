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

        // Official GO Plus BLE service UUID
        val GOPLUS_SERVICE_UUID: java.util.UUID =
            java.util.UUID.fromString("0000FEBE-0000-1000-8000-00805F9B34FB")

        // The exact device name Niantic's app looks for
        const val TARGET_DEVICE_NAME = "GO Plus"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServerManager: GattServerManager? = null
    private var isAdvertising = false

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    enum class ConnectionState { ADVERTISING, CONNECTED, DISCONNECTED, PAIRING }

    inner class LocalBinder : Binder {
        fun getService(): GoPlusBleService = this@GoPlusBleService
    }

    // Pairing broadcast receiver
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val pairingTransport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE)
                    Log.i(TAG, "Pairing request from: ${device?.address}, transport=$pairingTransport")
                    connectionState = ConnectionState.PAIRING
                    updateNotification("Pairing...")

                    // Give the BLE stack 200ms to process before auto-accepting
                    // This is critical — responding too fast gets ignored
                    serviceScope.launch {
                        kotlinx.coroutines.delay(200L)
                        try {
                            device?.setPin("000000".toByteArray())
                            device?.setPairingConfirmation(true)
                            Log.i(TAG, "Pairing auto-accepted (PIN=000000, confirm=true)")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Pairing security error: ${e.message}")
                            // Try downgrade to just confirmation without PIN
                            try {
                                device?.setPairingConfirmation(true)
                            } catch (e2: SecurityException) {
                                Log.e(TAG, "Confirm also failed: ${e2.message}")
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    Log.i(TAG, "Bond state: $prevState -> $state")
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "✅ Paired successfully!")
                            notifyConnectionState(ConnectionState.CONNECTED)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            Log.i(TAG, "Pairing removed")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
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

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Include the GO Plus service UUID in the advertising data
        // and the specific device name Niantic looks for
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Don't include generic name, set explicitly below
            .addServiceUuid(ParcelUuid(GOPLUS_SERVICE_UUID))
            .build()

        // Also set device name via BluetoothAdapter (done in initGattServer)
        // The name is what the phone advertises as
        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
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
