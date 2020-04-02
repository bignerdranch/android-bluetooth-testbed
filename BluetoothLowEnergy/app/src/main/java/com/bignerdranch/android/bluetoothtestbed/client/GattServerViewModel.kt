package com.bignerdranch.android.bluetoothtestbed.client

import android.bluetooth.BluetoothDevice
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable

class GattServerViewModel(private val mBluetoothDevice: BluetoothDevice?) : BaseObservable() {
    @get:Bindable val serverName: String
        get() = mBluetoothDevice?.address ?: ""
}