package com.bignerdranch.android.bluetoothtestbed.client;

import android.bluetooth.BluetoothDevice;
import android.databinding.BaseObservable;
import android.databinding.Bindable;

public class GattServerViewModel extends BaseObservable {

    private BluetoothDevice mBluetoothDevice;

    public GattServerViewModel(BluetoothDevice bluetoothDevice) {
        mBluetoothDevice = bluetoothDevice;
    }

    @Bindable
    public String getServerName() {
        if (mBluetoothDevice == null) {
            return "";
        }
        return mBluetoothDevice.getAddress();
    }
}
