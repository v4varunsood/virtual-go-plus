package com.virtualgoplus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.virtualgoplus.R
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

        // Official GO Plus BLE UUID
        val GOPLUS_SERVICE_UUID: java.util.UUID =
            java.util.UUID.fromString("0000FEBE-0000-1000-8000-00805F9B34FB")
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServerManager: GattServerManager? = null
    private var isAdvertising = false

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    enum class ConnectionState { ADVERTISING, CONNECTED, DISCONNECTED }

    inner class LocalBinder : Binder() {
        fun getService(): GoPlusBleService = this@GoPlusBleService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initGattServer()
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

        gattServerManager = GattServerManager(this, bluetoothAdapter)
        gattServerManager?.initialize()
    }

    fun startAdvertising() {
        if (isAdvertising) return

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothManager? = bluetoothManager
        bluetoothLeAdvertiser = bluetoothAdapter?.adapter?.bluetoothLeAdvertiser

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

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(android.os.ParcelUuid(GOPLUS_SERVICE_UUID))
            .build()

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
            updateNotification("Advertising — Waiting for connection")
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            connectionState = ConnectionState.DISCONNECTED
            updateNotification("Advertising failed (error: $errorCode)")
        }
    }

    fun notifyConnectionState(state: ConnectionState) {
        connectionState = state
        val text = when (state) {
            ConnectionState.ADVERTISING -> "Advertising — Waiting for connection"
            ConnectionState.CONNECTED -> "Connected to game"
            ConnectionState.DISCONNECTED -> "Disconnected"
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
