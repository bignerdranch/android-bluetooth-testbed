package com.bignerdranch.android.bluetoothtestbed.client;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;

import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils;
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils;

import java.util.List;

public class GattClientCallback extends BluetoothGattCallback {

    private GattClientActionListener mClientActionListener;

    public GattClientCallback(GattClientActionListener clientActionListener) {
        mClientActionListener = clientActionListener;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        mClientActionListener.log("onConnectionStateChange newState: " + newState);

        if (status == BluetoothGatt.GATT_FAILURE) {
            mClientActionListener.logError("Connection Gatt failure status " + status);
            mClientActionListener.disconnectGattServer();
            return;
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            // handle anything not SUCCESS as failure
            mClientActionListener.logError("Connection not GATT sucess status " + status);
            mClientActionListener.disconnectGattServer();
            return;
        }

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            mClientActionListener.log("Connected to device " + gatt.getDevice().getAddress());
            mClientActionListener.setConnected(true);
            gatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            mClientActionListener.log("Disconnected from device");
            mClientActionListener.disconnectGattServer();
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        if (status != BluetoothGatt.GATT_SUCCESS) {
            mClientActionListener.log("Device service discovery unsuccessful, status " + status);
            return;
        }

        List<BluetoothGattCharacteristic> matchingCharacteristics = BluetoothUtils.findCharacteristics(gatt);
        if (matchingCharacteristics.isEmpty()) {
            mClientActionListener.logError("Unable to find characteristics.");
            return;
        }

        mClientActionListener.log("Initializing: setting write type and enabling notification");
        for (BluetoothGattCharacteristic characteristic : matchingCharacteristics) {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            enableCharacteristicNotification(gatt, characteristic);
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mClientActionListener.log("Characteristic written successfully");
        } else {
            mClientActionListener.logError("Characteristic write unsuccessful, status: " + status);
            mClientActionListener.disconnectGattServer();
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mClientActionListener.log("Characteristic read successfully");
            readCharacteristic(characteristic);
        } else {
            mClientActionListener.logError("Characteristic read unsuccessful, status: " + status);
            // Trying to read from the Time Characteristic? It doesnt have the property or permissions
            // set to allow this. Normally this would be an error and you would want to:
            // disconnectGattServer();
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        mClientActionListener.log("Characteristic changed, " + characteristic.getUuid().toString());
        readCharacteristic(characteristic);
    }


    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mClientActionListener.log("Descriptor written successfully: " + descriptor.getUuid().toString());
            mClientActionListener.initializeTime();
        } else {
            mClientActionListener.logError("Descriptor write unsuccessful: " + descriptor.getUuid().toString());
        }
    }


    private void enableCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        boolean characteristicWriteSuccess = gatt.setCharacteristicNotification(characteristic, true);
        if (characteristicWriteSuccess) {
            mClientActionListener.log("Characteristic notification set successfully for " + characteristic.getUuid().toString());
            if (BluetoothUtils.isEchoCharacteristic(characteristic)) {
                mClientActionListener.initializeEcho();
            } else if (BluetoothUtils.isTimeCharacteristic(characteristic)) {
                enableCharacteristicConfigurationDescriptor(gatt, characteristic);
            }
        } else {
            mClientActionListener.logError("Characteristic notification set failure for " + characteristic.getUuid().toString());
        }
    }

    // Sometimes the Characteristic does not have permissions, and instead its Descriptor holds them
    // See https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    private void enableCharacteristicConfigurationDescriptor(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

        List<BluetoothGattDescriptor> descriptorList = characteristic.getDescriptors();
        BluetoothGattDescriptor descriptor = BluetoothUtils.findClientConfigurationDescriptor(descriptorList);
        if (descriptor == null) {
            mClientActionListener.logError("Unable to find Characteristic Configuration Descriptor");
            return;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean descriptorWriteInitiated = gatt.writeDescriptor(descriptor);
        if (descriptorWriteInitiated) {
            mClientActionListener.log("Characteristic Configuration Descriptor write initiated: " + descriptor.getUuid().toString());
        } else {
            mClientActionListener.logError("Characteristic Configuration Descriptor write failed to initiate: " + descriptor.getUuid().toString());
        }
    }

    private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        byte[] messageBytes = characteristic.getValue();
        mClientActionListener.log("Read: " + StringUtils.byteArrayInHexFormat(messageBytes));
        String message = StringUtils.stringFromBytes(messageBytes);
        if (message == null) {
            mClientActionListener.logError("Unable to convert bytes to string");
            return;
        }

        mClientActionListener.log("Received message: " + message);
    }
}
