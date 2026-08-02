package com.virtualgoplus.gatt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.virtualgoplus.engine.AutoCatcherEngine
import com.virtualgoplus.service.GoPlusBleService
import java.util.UUID

class GattServerManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "GattServerManager"

        // GO Plus Service UUID (official)
        val GOPLUS_SERVICE_UUID: UUID = GoPlusBleService.GOPLUS_SERVICE_UUID

        // SFIDA Service & Characteristic UUIDs (used by some GO Plus clones)
        val SFIDA_SERVICE_UUID: UUID = UUID.fromString("20800001-1A0E-11E6-B67B-9E2114713E2C")
        val STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("20800002-1A0E-11E6-B67B-9E2114713E2C")

        // Client characteristic configuration descriptor UUID
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Generic Access Service (0x1800) — required by iOS BLE specs
        val GENERIC_ACCESS_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val DEVICE_NAME_CHAR_UUID: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

        // GO Plus protocol characteristic UUIDs (known from reverse engineering)
        val GOPLUS_WRITE_CHAR_UUID: UUID = UUID.fromString("0000FEBE-0000-1000-8000-00805F9B34FB")
        val GOPLUS_NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FEBD-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothGattServer: android.bluetooth.BluetoothGattServer? = null
    var autoCatcherEngine: AutoCatcherEngine? = null
    private var connectedDevice: BluetoothDevice? = null

    // Track the SFIDA State characteristic so we can send notifications on it
    private var sfidaStateChar: BluetoothGattCharacteristic? = null
    private var goPlusNotifyChar: BluetoothGattCharacteristic? = null

    // Track which device has enabled notifications on SFIDA State char
    private var sfidaNotifyDevice: BluetoothDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            ?: run {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

        // ── Primary GO Plus service (0xFEBE) ──────────────────────────────
        val goPlusService = BluetoothGattService(
            GOPLUS_SERVICE_UUID,
            0 // SERVICE_TYPE_PRIMARY
        )

        // Write characteristic (game → GO Plus)
        val writeChar = BluetoothGattCharacteristic(
            GOPLUS_WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        goPlusService.addCharacteristic(writeChar)

        // Notify characteristic (GO Plus → game)
        goPlusNotifyChar = BluetoothGattCharacteristic(
            GOPLUS_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val goPlusCccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        goPlusNotifyChar?.addDescriptor(goPlusCccd)
        goPlusService.addCharacteristic(goPlusNotifyChar)

        // ── SFIDA service (for "Press the button" during pairing) ─────────
        val sfidaService = BluetoothGattService(
            SFIDA_SERVICE_UUID,
            0
        )
        // This is the key characteristic Pokémon GO reads/writes during pairing
        // It must support NOTIFY so we can send button press events
        sfidaStateChar = BluetoothGattCharacteristic(
            STATE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val sfidaCccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        sfidaStateChar?.addDescriptor(sfidaCccd)
        sfidaService.addCharacteristic(sfidaStateChar)

        // ── Generic Access Service (0x1800) — device name ─────────────────
        val gasService = BluetoothGattService(
            GENERIC_ACCESS_SERVICE_UUID,
            0
        )
        val deviceNameChar = BluetoothGattCharacteristic(
            DEVICE_NAME_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        deviceNameChar.value = GoPlusBleService.TARGET_DEVICE_NAME.toByteArray(Charsets.UTF_8)
        gasService.addCharacteristic(deviceNameChar)

        bluetoothGattServer?.addService(goPlusService)
        bluetoothGattServer?.addService(sfidaService)
        bluetoothGattServer?.addService(gasService)

        Log.i(TAG, "GATT server initialized: GO Plus + SFIDA + GenericAccess")
    }

    fun shutdown() {
        bluetoothGattServer?.close()
        bluetoothGattServer = null
    }

    /**
     * Send a notification on the SFIDA State characteristic (button press signal).
     * 0x02 = button press (the standard GO Plus button press byte)
     * Called automatically when Pokémon GO enables notifications on the State char.
     */
    fun sendButtonPress() {
        sfidaStateChar?.let { char ->
            char.value = byteArrayOf(0x02)
            sfidaNotifyDevice?.let { device ->
                Log.i(TAG, "Sending button press 0x02 to SFIDA State char for ${device.address}")
                bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
            } ?: run {
                Log.w(TAG, "No device subscribed to SFIDA State notifications yet")
            }
        } ?: Log.e(TAG, "SFIDA State char is null!")
    }

    /**
     * Send connected/idle notification on GO Plus notify characteristic.
     */
    fun sendConnected() {
        goPlusNotifyChar?.let { char ->
            char.value = byteArrayOf(0x02)
            connectedDevice?.let { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    fun sendCatchSuccess() {
        sfidaStateChar?.let { char ->
            char.value = byteArrayOf(0x03)
            sfidaNotifyDevice?.let { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    fun sendSpinSuccess() {
        sfidaStateChar?.let { char ->
            char.value = byteArrayOf(0x04)
            sfidaNotifyDevice?.let { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    Log.i(TAG, "Game connected: ${device?.address}")
                    // Auto-send connected notification
                    sendConnected()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Game disconnected")
                    if (device == sfidaNotifyDevice) {
                        sfidaNotifyDevice = null
                    }
                    if (device == connectedDevice) {
                        connectedDevice = null
                    }
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Service added: ${service?.uuid}")
            } else {
                Log.e(TAG, "Failed to add service ${service?.uuid}: $status")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val uuid = characteristic?.uuid
            Log.i(TAG, "READ: $uuid offset=$offset")

            val responseBytes = when (uuid) {
                DEVICE_NAME_CHAR_UUID -> GoPlusBleService.TARGET_DEVICE_NAME.toByteArray(Charsets.UTF_8)
                // Return a plausible SFIDA state value on read
                STATE_CHARACTERISTIC_UUID -> byteArrayOf(0x01, 0x00)
                GOPLUS_NOTIFY_CHAR_UUID -> byteArrayOf(0x01, 0x00)
                else -> byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            }
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, responseBytes)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val uuid = characteristic?.uuid
            val data = value?.toList() ?: emptyList()
            Log.i(TAG, "WRITE: $uuid data=${data.joinToString { "%02X".format(it) }}")

            // When Pokémon GO writes to SFIDA State char during pairing → send button press
            if (uuid == STATE_CHARACTERISTIC_UUID && data.isNotEmpty()) {
                Log.i(TAG, "SFIDA State write received — sending button press 0x02")
                // Send button press in a short delay to simulate real hardware
                mainHandler.postDelayed({ sendButtonPress() }, 100)
            }

            // Route to auto-catcher engine
            autoCatcherEngine?.onWriteRequest(uuid, data, requestId, device)

            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val charUuid = descriptor?.characteristic?.uuid
            val descUuid = descriptor?.uuid
            val data = value?.toList() ?: emptyList()
            Log.i(TAG, "DESC WRITE: desc=$descUuid char=$charUuid value=${data.joinToString { "%02X".format(it) }}")

            // Pokémon GO enabling notifications on SFIDA State characteristic
            if (descUuid == CLIENT_CONFIG_DESCRIPTOR_UUID && charUuid == STATE_CHARACTERISTIC_UUID) {
                if (data.getOrNull(0) == 0x01 && data.getOrNull(1) == 0x00) {
                    Log.i(TAG, "SFIDA State notifications ENABLED by ${device?.address}")
                    sfidaNotifyDevice = device
                    // Auto-send button press to trigger "Press the button" → paired flow
                    mainHandler.postDelayed({
                        Log.i(TAG, "Auto-sending button press 0x02 for pairing handshake")
                        sendButtonPress()
                    }, 200)
                } else if (data.getOrNull(0) == 0x00 && data.getOrNull(1) == 0x00) {
                    Log.i(TAG, "SFIDA State notifications disabled")
                    if (device == sfidaNotifyDevice) sfidaNotifyDevice = null
                }
            }

            // GO Plus notify characteristic CCCD
            if (descUuid == CLIENT_CONFIG_DESCRIPTOR_UUID && charUuid == GOPLUS_NOTIFY_CHAR_UUID) {
                Log.i(TAG, "GO Plus notify CCCD written: ${data.joinToString { "%02X".format(it) }}")
            }

            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }
}
