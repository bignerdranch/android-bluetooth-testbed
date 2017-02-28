package com.bignerdranch.android.bluetoothtestbed.server;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothProfile;

import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils;
import com.bignerdranch.android.bluetoothtestbed.util.ByteUtils;
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils;

import static com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_ECHO_UUID;
import static com.bignerdranch.android.bluetoothtestbed.Constants.CLIENT_CONFIGURATION_DESCRIPTOR_UUID;

public class GattServerCallback extends BluetoothGattServerCallback {

    private GattServerActionListener mServerActionListener;

    public GattServerCallback(GattServerActionListener serverActionListener) {
        mServerActionListener = serverActionListener;
    }

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        super.onConnectionStateChange(device, status, newState);
        mServerActionListener.log("onConnectionStateChange " + device.getAddress()
                + "\nstatus " + status
                + "\nnewState " + newState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            mServerActionListener.addDevice(device);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            mServerActionListener.removeDevice(device);
        }
    }

    // The Gatt will reject Characteristic Read requests that do not have the permission set,
    // so there is no need to check inside the callback
    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device,
                                            int requestId,
                                            int offset,
                                            BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

        mServerActionListener.log("onCharacteristicReadRequest " + characteristic.getUuid().toString());

        if (BluetoothUtils.requiresResponse(characteristic)) {
            // Unknown read characteristic requiring response, send failure
            mServerActionListener.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
        }
        // Not one of our characteristics or has NO_RESPONSE property set
    }

    // The Gatt will reject Characteristic Write requests that do not have the permission set,
    // so there is no need to check inside the callback
    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
        super.onCharacteristicWriteRequest(device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value);
        mServerActionListener.log("onCharacteristicWriteRequest" + characteristic.getUuid().toString()
                + "\nReceived: " + StringUtils.byteArrayInHexFormat(value));

        if (CHARACTERISTIC_ECHO_UUID.equals(characteristic.getUuid())) {
            mServerActionListener.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);

            // Reverse message to differentiate original message & response
            byte[] response = ByteUtils.reverse(value);
            characteristic.setValue(response);
            mServerActionListener.log("Sending: " + StringUtils.byteArrayInHexFormat(response));
            mServerActionListener.notifyCharacteristicEcho(response);
        }
    }

    // The Gatt will reject Descriptor Read requests that do not have the permission set,
    // so there is no need to check inside the callback
    @Override
    public void onDescriptorReadRequest(BluetoothDevice device,
                                        int requestId,
                                        int offset,
                                        BluetoothGattDescriptor descriptor) {
        super.onDescriptorReadRequest(device, requestId, offset, descriptor);
        mServerActionListener.log("onDescriptorReadRequest" + descriptor.getUuid().toString());
    }

    // The Gatt will reject Descriptor Write requests that do not have the permission set,
    // so there is no need to check inside the callback
    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device,
                                         int requestId,
                                         BluetoothGattDescriptor descriptor,
                                         boolean preparedWrite,
                                         boolean responseNeeded,
                                         int offset,
                                         byte[] value) {
        super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
        mServerActionListener.log("onDescriptorWriteRequest: " + descriptor.getUuid().toString()
                + "\nvalue: " + StringUtils.byteArrayInHexFormat(value));

        if (CLIENT_CONFIGURATION_DESCRIPTOR_UUID.equals(descriptor.getUuid())) {
            mServerActionListener.addClientConfiguration(device, value);
            mServerActionListener.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status) {
        super.onNotificationSent(device, status);
        mServerActionListener.log("onNotificationSent");
    }
}
