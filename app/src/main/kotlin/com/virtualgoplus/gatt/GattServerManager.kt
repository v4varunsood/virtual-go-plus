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

        // GO Plus protocol characteristic UUIDs (known from reverse engineering)
        val GOPLUS_WRITE_CHAR_UUID: UUID = UUID.fromString("0000FEBE-0000-1000-8000-00805F9B34FB")
        val GOPLUS_NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FEBD-0000-1000-8000-00805F9B34FB")

        // Default response bytes for reads
        private val DEFAULT_DEVICE_INFO = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    private var bluetoothGattServer: android.bluetooth.BluetoothGattServer? = null
    var autoCatcherEngine: AutoCatcherEngine? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    fun initialize() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            ?: run {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

        // Primary GO Plus service
        val goPlusService = BluetoothGattService(
            GOPLUS_SERVICE_UUID,
            0 // SERVICE_TYPE_PRIMARY
        )

        // Write characteristic (game -> GO Plus) — Write Without Response
        writeCharacteristic = BluetoothGattCharacteristic(
            GOPLUS_SERVICE_UUID, // same UUID for write
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        goPlusService.addCharacteristic(writeCharacteristic)

        // Notify characteristic (GO Plus -> game)
        notifyCharacteristic = BluetoothGattCharacteristic(
            GOPLUS_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val clientConfig = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        notifyCharacteristic?.addDescriptor(clientConfig)
        goPlusService.addCharacteristic(notifyCharacteristic)

        // Also add SFIDA service for compatibility
        val sfidaService = BluetoothGattService(
            SFIDA_SERVICE_UUID,
            0
        )
        val stateChar = BluetoothGattCharacteristic(
            STATE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        stateChar.addDescriptor(BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ))
        sfidaService.addCharacteristic(stateChar)

        bluetoothGattServer?.addService(goPlusService)
        bluetoothGattServer?.addService(sfidaService)

        Log.i(TAG, "GATT server initialized with GO Plus + SFIDA services")
    }

    fun shutdown() {
        bluetoothGattServer?.close()
        bluetoothGattServer = null
    }

    /**
     * Send a notification to the connected game app.
     * 0x01 = button pressed / action confirmed
     * 0x02 = connected idle
     * 0x03 = pokemon caught
     * 0x04 = pokestop spun
     */
    fun sendNotification(data: ByteArray) {
        notifyCharacteristic?.let { char ->
            char.value = data
            connectedDevice?.let { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    fun sendButtonPress() {
        sendNotification(byteArrayOf(0x01))
    }

    fun sendConnected() {
        sendNotification(byteArrayOf(0x02))
    }

    fun sendCatchSuccess() {
        sendNotification(byteArrayOf(0x03))
    }

    fun sendSpinSuccess() {
        sendNotification(byteArrayOf(0x04))
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    Log.i(TAG, "Game app connected: ${device?.address}")
                    // Send connected state
                    sendConnected()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Game app disconnected")
                    connectedDevice = null
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT service added: ${service?.uuid}")
            } else {
                Log.e(TAG, "Failed to add GATT service: $status")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val uuid = characteristic?.uuid
            Log.i(TAG, "Characteristic READ: $uuid (offset=$offset)")
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                DEFAULT_DEVICE_INFO
            )
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
            Log.i(TAG, "Characteristic WRITE: $uuid data=${data.joinToString { "%02X".format(it) }}")

            // Route to auto-catcher engine
            autoCatcherEngine?.onWriteRequest(uuid, data, requestId, device)

            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
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
            Log.i(TAG, "Descriptor write: ${descriptor?.uuid}, value=${value?.toList()?.joinToString { "%02X".format(it) }}")
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        override fun onExecuteWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            executeWrite: Boolean
        ) {
            Log.i(TAG, "Execute write: execute=$executeWrite")
            if (executeWrite) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }
    }
}
