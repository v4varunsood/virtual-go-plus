package com.virtualgoplus.gatt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
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

        // SFIDA Service & Characteristic UUIDs
        val SFIDA_SERVICE_UUID: UUID = UUID.fromString("20800001-1A0E-11E6-B67B-9E2114713E2C")
        val STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("20800002-1A0E-11E6-B67B-9E2114713E2C")

        // GO Plus Service UUID (official)
        val GOPLUS_SERVICE_UUID: UUID = GoPlusBleService.GOPLUS_SERVICE_UUID

        // Client characteristic configuration descriptor UUID (standard)
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGattServer: BluetoothGattServer? = null
    var autoCatcherEngine: AutoCatcherEngine? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null

    fun initialize() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            ?: run {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

        // Add SFIDA service
        val sfidaService = BluetoothGattService(
            SFIDA_SERVICE_UUID,
            BluetoothGatt.SERVICE_TYPE_PRIMARY
        )

        // State characteristic — Notify + Read
        stateCharacteristic = BluetoothGattCharacteristic(
            STATE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Add client config descriptor for notifications
        val clientConfig = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        stateCharacteristic?.addDescriptor(clientConfig)

        sfidaService.addCharacteristic(stateCharacteristic)
        bluetoothGattServer?.addService(sfidaService)

        // Also add GO Plus service
        val goPlusService = BluetoothGattService(
            GOPLUS_SERVICE_UUID,
            BluetoothGatt.SERVICE_TYPE_PRIMARY
        )
        bluetoothGattServer?.addService(goPlusService)

        Log.i(TAG, "GATT server initialized with SFIDA and GO Plus services")
    }

    fun shutdown() {
        bluetoothGattServer?.close()
        bluetoothGattServer = null
    }

    fun sendStateNotification(state: Byte) {
        stateCharacteristic?.let { char ->
            char.value = byteArrayOf(state)
            connectedDevice?.let { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    char,
                    false
                )
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    Log.i(TAG, "Device connected: ${device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Device disconnected")
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
            Log.i(TAG, "Characteristic read request: ${characteristic?.uuid}")
            characteristic?.let { char ->
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    byteArrayOf(0x02) // default connected state
                )
            }
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
            val charUuid = characteristic?.uuid
            val data = value?.toList() ?: emptyList()
            Log.i(TAG, "Characteristic write request — UUID: $charUuid, data: $data")

            // Route to auto-catcher engine for processing
            autoCatcherEngine?.onWriteRequest(charUuid, data, requestId, device)

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
            Log.i(TAG, "Descriptor write: ${descriptor?.uuid}, value: ${value?.toList()}")
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
