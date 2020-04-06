package com.bignerdranch.android.bluetoothtestbed.util

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_ECHO_STRING
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_TIME_STRING
import com.bignerdranch.android.bluetoothtestbed.Constants.CLIENT_CONFIGURATION_DESCRIPTOR_SHORT_ID
import com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_STRING
import java.util.*

object BluetoothUtils {

    // Characteristics
    fun findCharacteristics(bluetoothGatt: BluetoothGatt): List<BluetoothGattCharacteristic> =
            findService(bluetoothGatt.services)?.characteristics?.filter { isMatchingCharacteristic(it) }
                    ?: listOf()

    fun findEchoCharacteristic(bluetoothGatt: BluetoothGatt): BluetoothGattCharacteristic? =
            findCharacteristic(bluetoothGatt, CHARACTERISTIC_ECHO_STRING)

    fun findTimeCharacteristic(bluetoothGatt: BluetoothGatt): BluetoothGattCharacteristic? =
            findCharacteristic(bluetoothGatt, CHARACTERISTIC_TIME_STRING)

    private fun findCharacteristic(bluetoothGatt: BluetoothGatt, uuidString: String) =
            findService(bluetoothGatt.services)?.characteristics?.find { characteristicMatches(it, uuidString) }

    fun isEchoCharacteristic(characteristic: BluetoothGattCharacteristic?) =
            characteristicMatches(characteristic, CHARACTERISTIC_ECHO_STRING)

    fun isTimeCharacteristic(characteristic: BluetoothGattCharacteristic?) =
            characteristicMatches(characteristic, CHARACTERISTIC_TIME_STRING)

    private fun characteristicMatches(characteristic: BluetoothGattCharacteristic?, uuidString: String) =
            characteristic?.let {
                uuidMatches(it.uuid.toString(), uuidString)
            } ?: false

    private fun isMatchingCharacteristic(characteristic: BluetoothGattCharacteristic?) =
            characteristic?.let {
                matchesCharacteristicUuidString(it.uuid.toString())
            } ?: false

    private fun matchesCharacteristicUuidString(characteristicIdString: String) =
            uuidMatches(characteristicIdString, CHARACTERISTIC_ECHO_STRING, CHARACTERISTIC_TIME_STRING)

    fun requiresResponse(characteristic: BluetoothGattCharacteristic) =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    fun requiresConfirmation(characteristic: BluetoothGattCharacteristic) =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == BluetoothGattCharacteristic.PROPERTY_INDICATE

    // Descriptor
    fun findClientConfigurationDescriptor(descriptorList: List<BluetoothGattDescriptor?>): BluetoothGattDescriptor? =
            descriptorList.find { isClientConfigurationDescriptor(it) }

    private fun isClientConfigurationDescriptor(descriptor: BluetoothGattDescriptor?) =
            descriptor?.let {
                uuidMatches(descriptor.uuid.toString().substring(4, 8),
                        CLIENT_CONFIGURATION_DESCRIPTOR_SHORT_ID)
            } ?: false

    // Service
    private fun findService(serviceList: List<BluetoothGattService>): BluetoothGattService? =
            serviceList.find { uuidMatches(it.uuid.toString(), SERVICE_STRING) }

    // String matching
    // If manually filtering, substring to match:
    // 0000XXXX-0000-0000-0000-000000000000
    private fun uuidMatches(uuidString: String, vararg matches: String): Boolean =
            matches.any { uuidString.equals(it, ignoreCase = true) }
}