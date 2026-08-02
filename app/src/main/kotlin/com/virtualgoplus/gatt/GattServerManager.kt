package com.virtualgoplus.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class GattServerManager(
    private val context: Context,
    private val onDeviceConnected: () -> Unit,
    private val onDeviceDisconnected: () -> Unit,
    private val onServiceReady: () -> Unit
) {
    companion object {
        private const val TAG = "GattServerManager"

        // iAnyGo/iPogo custom SFIDA-like UUIDs (reverse-engineered from iAnyGo)
        val SERVICE_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e37")
        val STATE_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e38")
        val CONFIG_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e39")
        val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e3a")

        // Battery Service
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aeb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aec")
        val MODEL_CHAR_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aed")
        val SERIAL_CHAR_UUID: UUID = UUID.fromString("21C50462-67CB-63A3-5C4C-82B5B9939AEE")

        // CCCD Descriptor UUID
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // iAnyGo uses button press byte 0x01 (confirmed from smali)
        const val BUTTON_PRESS_BYTE: Byte = 0x01
        const val BUTTON_RELEASE_BYTE: Byte = 0x00
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notifyDevice: BluetoothDevice? = null

    // PGPCert native handle (0 = not initialized)
    private var pgpCertHandle: Long = 0

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun start() {
        initGattServer()
        initNativeCert()
    }

    private fun initNativeCert() {
        try {
            System.loadLibrary("_pgp_cert")
            val cls = Class.forName("com.pogoskill.fakegps.PGPCert")
            // We'll init with empty string for now - real app needs userId from their server
            pgpCertHandle = 0
            Log.i(TAG, "PGPCert native library loaded")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load PGPCert: ${e.message}")
        }
    }

    private fun initGattServer() {
        val gattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                Log.i(TAG, "=== CONNECTION STATE === status=$status newState=$newState device=${device?.address}")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        notifyDevice = device
                        Log.i(TAG, "Device connected: ${device?.address}")
                        onDeviceConnected()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Device disconnected")
                        connectedDevice = null
                        notifyDevice = null
                        onDeviceDisconnected()
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                Log.i(TAG, "=== SERVICE ADDED status=$status service=${service?.uuid}")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onServiceReady()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                val uuid = characteristic?.uuid
                Log.i(TAG, "=== CHAR READ requestId=$requestId offset=$offset char=$uuid")
                
                when (uuid) {
                    STATE_CHAR_UUID -> {
                        // Return button press state (0x01 = pressed)
                        val value = byteArrayOf(BUTTON_RELEASE_BYTE)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                        Log.i(TAG, "Read STATE_CHAR -> ${value[0].toHex()}")
                    }
                    DEVICE_INFO_CHAR_UUID -> {
                        // Return "Virtual GO Plus" device info
                        val info = "Virtual GO Plus".toByteArray()
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, info)
                        Log.i(TAG, "Read DEVICE_INFO -> Virtual GO Plus")
                    }
                    BATTERY_CHAR_UUID -> {
                        val battery = byteArrayOf(100) // 100%
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, battery)
                    }
                    MODEL_CHAR_UUID -> {
                        val model = "GO Plus".toByteArray()
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, model)
                    }
                    SERIAL_CHAR_UUID -> {
                        val serial = "VGP001".toByteArray()
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, serial)
                    }
                    else -> {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                prepareWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val uuid = characteristic?.uuid
                val dataHex = value?.joinToString(" ") { "%02X".format(it) } ?: "null"
                Log.i(TAG, "=== CHAR WRITE requestId=$requestId prepareWrite=$prepareWrite responseNeeded=$responseNeeded char=$uuid offset=$offset data=$dataHex")

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                when (uuid) {
                    STATE_CHAR_UUID -> {
                        Log.i(TAG, "STATE_CHAR write -> $dataHex")
                    }
                    CONFIG_CHAR_UUID -> {
                        Log.i(TAG, "CONFIG_CHAR write -> $dataHex")
                    }
                    else -> {
                        Log.i(TAG, "Unknown char write: $uuid = $dataHex")
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                prepareWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val descUuid = descriptor?.uuid
                val charUuid = descriptor?.characteristic?.uuid
                val dataHex = value?.joinToString(" ") { "%02X".format(it) } ?: "null"
                Log.i(TAG, "=== DESCRIPTOR WRITE requestId=$requestId desc=$descUuid char=$charUuid value=$dataHex")

                // CRITICAL: Set the descriptor value BEFORE responding
                // This is what iAnyGo does - the key difference from our old code
                if (value != null) {
                    descriptor?.setValue(value)
                }

                // Send GATT_SUCCESS response immediately
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Check if this is CCCD enabling notifications on STATE_CHAR
                // iAnyGo uses CCCD value 0x0100 to enable notifications
                if (descUuid == CLIENT_CONFIG_DESCRIPTOR_UUID && charUuid == STATE_CHAR_UUID) {
                    val cccd = descriptor?.getValue()
                    val cccdInt = if (cccd != null && cccd.size >= 2) {
                        (cccd[0].toInt() and 0xFF) or ((cccd[1].toInt() and 0xFF) shl 8)
                    } else 0
                    
                    Log.i(TAG, "CCCD for STATE_CHAR = 0x${Integer.toHexString(cccdInt)}")
                    
                    if (cccdInt and 0x01 != 0) {
                        // Notifications enabled
                        notifyDevice = device
                        Log.i(TAG, "Notifications ENABLED on STATE_CHAR")
                        
                        // CRITICAL: Auto-send button press when notifications are enabled
                        // This is what advances the pairing past "Press the button" screen
                        // iAnyGo does this in onDescriptorWriteRequest
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            sendButtonPress()
                        }, 100)
                    }
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor?
            ) {
                Log.i(TAG, "=== DESCRIPTOR READ requestId=$requestId desc=${descriptor?.uuid}")
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor?.getValue())
            }

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                Log.i(TAG, "Notification sent status=$status")
            }

            override fun onExecuteWriteRequest(device: BluetoothDevice?, requestId: Int, executeWrite: Boolean) {
                Log.i(TAG, "Execute write requestId=$requestId execute=$executeWrite")
                if (executeWrite) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        Log.i(TAG, "GATT server opened: $gattServer")

        // Add services
        val services = createServices()
        for (service in services) {
            val added = gattServer?.addService(service) ?: false
            Log.i(TAG, "Service ${service.uuid} added: $added")
        }
    }

    private fun createServices(): List<BluetoothGattService> {
        val services = mutableListOf<BluetoothGattService>()

        // Primary GO Plus Service
        val goPlusService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // State characteristic - READ + NOTIFY (properties 0x08 | 0x10 = 0x18)
        val stateChar = BluetoothGattCharacteristic(
            STATE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // CCCD descriptor for notifications
        val stateCccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        stateChar.addDescriptor(stateCccd)
        goPlusService.addCharacteristic(stateChar)

        // Config characteristic - WRITE (property 0x04)
        val configChar = BluetoothGattCharacteristic(
            CONFIG_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        goPlusService.addCharacteristic(configChar)

        // Device Info characteristic - READ (property 0x02)
        val deviceInfoChar = BluetoothGattCharacteristic(
            DEVICE_INFO_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        goPlusService.addCharacteristic(deviceInfoChar)

        services.add(goPlusService)
        Log.i(TAG, "Created GO Plus service with UUID $SERVICE_UUID")

        // Battery Service
        val batteryService = BluetoothGattService(BATTERY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val batteryChar = BluetoothGattCharacteristic(
            BATTERY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val batteryCccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        batteryChar.addDescriptor(batteryCccd)
        batteryService.addCharacteristic(batteryChar)

        val modelChar = BluetoothGattCharacteristic(
            MODEL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        batteryService.addCharacteristic(modelChar)

        val serialChar = BluetoothGattCharacteristic(
            SERIAL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        batteryService.addCharacteristic(serialChar)

        services.add(batteryService)
        Log.i(TAG, "Created Battery service with UUID $BATTERY_SERVICE_UUID")

        return services
    }

    fun sendButtonPress() {
        val device = notifyDevice ?: connectedDevice
        val server = gattServer
        
        if (device == null || server == null) {
            Log.w(TAG, "Cannot send button press - no device or server")
            return
        }

        val service = server.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(STATE_CHAR_UUID)
        
        if (characteristic == null) {
            Log.w(TAG, "STATE_CHAR not found")
            return
        }

        // Send button press (0x01) - iAnyGo's exact value confirmed from smali
        characteristic.setValue(byteArrayOf(BUTTON_PRESS_BYTE))
        val sent = server.notifyCharacteristicChanged(device, characteristic, false)
        
        val dataHex = "01"
        Log.i(TAG, "=== SEND BUTTON PRESS -> $dataHex sent=$sent")

        // Follow with button release after short delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            characteristic.setValue(byteArrayOf(BUTTON_RELEASE_BYTE))
            server.notifyCharacteristicChanged(device, characteristic, false)
            Log.i(TAG, "=== SEND BUTTON RELEASE -> 00")
        }, 200)
    }

    fun getServer(): BluetoothGattServer? = gattServer
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice
}
}
