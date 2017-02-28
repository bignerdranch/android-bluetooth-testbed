package com.bignerdranch.android.bluetoothtestbed.server;

import android.bluetooth.BluetoothDevice;

public interface GattServerActionListener {

    void log(String message);

    void addDevice(BluetoothDevice device);

    void removeDevice(BluetoothDevice device);

    void addClientConfiguration(BluetoothDevice device, byte[] value);

    void sendResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value);

    void notifyCharacteristicEcho(byte[] value);
}
