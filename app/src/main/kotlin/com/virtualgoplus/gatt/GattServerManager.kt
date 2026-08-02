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
import android.bluetooth.BluetoothProfile
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

        // iAnyGo/iPogo custom SFIDA-like UUIDs (reverse-engineered from iAnyGo v1.0.0)
        val SERVICE_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e37")
        val STATE_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e38")
        val CONFIG_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e39")
        val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e3a")
        val CONFIG2_CHAR_UUID: UUID = UUID.fromString("bbe87709-5b89-4433-ab7f-8b8eef0d8e3b")

        // Battery Service
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aeb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aec")
        val MODEL_CHAR_UUID: UUID = UUID.fromString("21c50462-67cb-63a3-5c4c-82b5b9939aed")
        val SERIAL_CHAR_UUID: UUID = UUID.fromString("21C50462-67CB-63A3-5C4C-82B5B9939AEE")

        // CCCD Descriptor UUID
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Protocol response bytes (from iAnyGo smali reverse-engineering)
        // Case "00 00 00 00" (button press) → send [0x04, 0x00, 0x23, 0x00]
        val RESPONSE_00_00_00_00 = byteArrayOf(0x04, 0x00, 0x23, 0x00)
        // Case "01 00 00 00" (button release) → send [0x01, 0x00, 0x00, 0x00]
        val RESPONSE_01_00_00_00 = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        // Case "02 00 00 00" (connected/pairing) → send [0x02, 0x00, 0x00, 0x00]
        val RESPONSE_02_00_00_00 = byteArrayOf(0x02, 0x00, 0x00, 0x00)
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notifyDevice: BluetoothDevice? = null

    // PGPCert native handle (0 = not initialized)
    private var pgpCertHandle: Long = 0

    // Track connection/auth state
    private var isAuthenticated = false
    private var isButtonPressed = false

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun start() {
        initNativeCert()
        initGattServer()
    }

    private fun initNativeCert() {
        try {
            System.loadLibrary("_pgp_cert")
            // Initialize with empty userId — real app needs valid userId from iAnyGo account
            // pgpCertHandle = PGPCert.initInstance("", "", false)
            Log.i(TAG, "PGPCert native library loaded (not initialized — no valid userId)")
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
                        isAuthenticated = false
                        isButtonPressed = false
                        Log.i(TAG, "Device connected: ${device?.address}")
                        onDeviceConnected()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Device disconnected")
                        connectedDevice = null
                        notifyDevice = null
                        isAuthenticated = false
                        isButtonPressed = false
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
                        // Return current button state
                        val value = if (isButtonPressed) byteArrayOf(0x01) else byteArrayOf(0x00)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                        Log.i(TAG, "Read STATE_CHAR -> %02X".format(value[0]))
                    }
                    DEVICE_INFO_CHAR_UUID -> {
                        val info = "Pokemon GO Plus".toByteArray()
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, info)
                        Log.i(TAG, "Read DEVICE_INFO -> Pokemon GO Plus")
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
                    STATE_CHAR_UUID -> handleStateCharWrite(device, value)
                    CONFIG_CHAR_UUID -> handleConfigCharWrite(device, value)
                    else -> Log.i(TAG, "Unknown char write: $uuid = $dataHex")
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

                // CRITICAL: Set the descriptor value BEFORE responding (iAnyGo's approach)
                if (value != null) {
                    descriptor?.setValue(value)
                }

                // Send GATT_SUCCESS response immediately
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Check if this is CCCD enabling notifications on STATE_CHAR
                if (descUuid == CLIENT_CONFIG_DESCRIPTOR_UUID && charUuid == STATE_CHAR_UUID) {
                    val cccd = descriptor?.getValue()
                    val cccdInt = if (cccd != null && cccd.size >= 2) {
                        (cccd[0].toInt() and 0xFF) or ((cccd[1].toInt() and 0xFF) shl 8)
                    } else 0

                    Log.i(TAG, "CCCD for STATE_CHAR = 0x${Integer.toHexString(cccdInt)}")

                    if (cccdInt and 0x01 != 0) {
                        // Notifications enabled — this is the "Press the button" trigger
                        // iAnyGo auto-sends button press here
                        notifyDevice = device
                        Log.i(TAG, "Notifications ENABLED on STATE_CHAR — sending auto button press")

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
        }

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        Log.i(TAG, "GATT server opened: $gattServer")

        val services = createServices()
        for (service in services) {
            val added = gattServer?.addService(service) ?: false
            Log.i(TAG, "Service ${service.uuid} added: $added")
        }
    }

    private fun handleStateCharWrite(device: BluetoothDevice?, data: ByteArray?) {
        if (data == null || data.size < 4) return

        val dataStr = data.take(4).joinToString(" ") { "%02X".format(it) }
        Log.i(TAG, "STATE_CHAR write: [$dataStr]")

        // Protocol from iAnyGo reverse-engineering:
        // The FIRST 4 bytes determine the response type
        val first4 = data.copyOfRange(0, minOf(4, data.size))

        val response: ByteArray = when {
            // Case 1: "00 00 00 00" → button press ( Pokémon encounters)
            first4.contentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00)) -> {
                Log.i(TAG, "→ Case: BUTTON PRESS (00 00 00 00)")
                isButtonPressed = true
                RESPONSE_00_00_00_00
            }
            // Case 2: "01 00 00 00" → button release
            first4.contentEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00)) -> {
                Log.i(TAG, "→ Case: BUTTON RELEASE (01 00 00 00)")
                isButtonPressed = false
                RESPONSE_01_00_00_00
            }
            // Case 3: "02 00 00 00" → connected/pairing confirmation
            first4.contentEquals(byteArrayOf(0x02, 0x00, 0x00, 0x00)) -> {
                Log.i(TAG, "→ Case: CONNECTED (02 00 00 00)")
                isAuthenticated = true
                RESPONSE_02_00_00_00
            }
            else -> {
                Log.i(TAG, "→ Case: UNKNOWN — sending default response")
                RESPONSE_02_00_00_00
            }
        }

        // Send response via notification
        sendNotification(response)
    }

    private fun handleConfigCharWrite(device: BluetoothDevice?, data: ByteArray?) {
        val dataHex = data?.joinToString(" ") { "%02X".format(it) } ?: "null"
        Log.i(TAG, "CONFIG_CHAR write: [$dataHex]")
        // Config writes don't typically get a response notification
    }

    private fun sendNotification(data: ByteArray) {
        val device = notifyDevice ?: connectedDevice
        val server = gattServer

        if (device == null || server == null) {
            Log.w(TAG, "Cannot send notification — no device or server")
            return
        }

        val service = server.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(STATE_CHAR_UUID)

        if (characteristic == null) {
            Log.w(TAG, "STATE_CHAR not found in service")
            return
        }

        characteristic.setValue(data)
        val sent = server.notifyCharacteristicChanged(device, characteristic, false)
        val dataHex = data.joinToString(" ") { "%02X".format(it) }
        Log.i(TAG, "=== NOTIFY [$dataHex] sent=$sent")
    }

    fun sendButtonPress() {
        Log.i(TAG, "=== AUTO BUTTON PRESS ===")
        isButtonPressed = true
        sendNotification(RESPONSE_00_00_00_00)

        // Follow with button release after short delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isButtonPressed = false
            sendNotification(RESPONSE_01_00_00_00)
            Log.i(TAG, "=== BUTTON RELEASE ===")
        }, 200)
    }

    fun getServer(): BluetoothGattServer? = gattServer
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice
}
