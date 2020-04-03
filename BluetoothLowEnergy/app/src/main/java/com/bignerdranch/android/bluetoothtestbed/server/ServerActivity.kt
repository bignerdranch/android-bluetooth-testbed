package com.bignerdranch.android.bluetoothtestbed.server

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_ECHO_UUID
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_TIME_UUID
import com.bignerdranch.android.bluetoothtestbed.Constants.CLIENT_CONFIGURATION_DESCRIPTOR_UUID
import com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_UUID
import com.bignerdranch.android.bluetoothtestbed.R
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityServerBinding
import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and

class ServerActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityServerBinding
    private var mHandler: Handler? = null
    private var mLogHandler: Handler? = null
    private var mDevices: MutableList<BluetoothDevice>? = null
    private var mClientConfigurations: MutableMap<String, ByteArray>? = null
    private var mGattServer: BluetoothGattServer? = null
    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mHandler = Handler()
        mLogHandler = Handler(Looper.getMainLooper())
        mDevices = ArrayList()
        mClientConfigurations = HashMap()
        mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = mBluetoothManager!!.adapter
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_server)
        mBinding.sendTimestampButton.setOnClickListener { v: View? -> sendTimestamp() }
        mBinding.restartServerButton.setOnClickListener { v: View? -> restartServer() }
        mBinding.viewServerLog.clearLogButton.setOnClickListener { v: View? -> clearLogs() }
    }

    override fun onResume() {
        super.onResume()
        // Check if bluetooth is enabled
        if (mBluetoothAdapter == null || !mBluetoothAdapter!!.isEnabled) { // Request user to enable it
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBtIntent)
            finish()
            return
        }
        // Check low energy support
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { // Get a newer device
            log("No LE Support.")
            finish()
            return
        }
        // Check advertising
        if (!mBluetoothAdapter!!.isMultipleAdvertisementSupported) { // Unable to run the server on this device, get a better device
            log("No Advertising Support.")
            finish()
            return
        }
        mBluetoothLeAdvertiser = mBluetoothAdapter!!.bluetoothLeAdvertiser
        val gattServerCallback = GattServerCallback()
        mGattServer = mBluetoothManager!!.openGattServer(this, gattServerCallback)
        @SuppressLint("HardwareIds") val deviceInfo = ("Device Info"
                + "\nName: " + mBluetoothAdapter!!.name
                + "\nAddress: " + mBluetoothAdapter!!.address)
        mBinding!!.serverDeviceInfoTextView.text = deviceInfo
        setupServer()
        startAdvertising()
    }

    override fun onPause() {
        super.onPause()
        stopAdvertising()
        stopServer()
    }

    // GattServer
    private fun setupServer() {
        val service = BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // Write characteristic
        val writeCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_ECHO_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,  // Somehow this is not necessary, the client can still enable notifications
//                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
        // Characteristic with Descriptor
        val notifyCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_TIME_UUID,  // Somehow this is not necessary, the client can still enable notifications
//                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
                0)
        val clientConfigurationDescriptor = BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ
                or BluetoothGattDescriptor.PERMISSION_WRITE)
        clientConfigurationDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        notifyCharacteristic.addDescriptor(clientConfigurationDescriptor)
        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)
        mGattServer!!.addService(service)
    }

    private fun stopServer() {
        if (mGattServer != null) {
            mGattServer!!.close()
        }
    }

    private fun restartServer() {
        stopAdvertising()
        stopServer()
        setupServer()
        startAdvertising()
    }

    // Advertising
    private fun startAdvertising() {
        if (mBluetoothLeAdvertiser == null) {
            return
        }
        val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build()
        val parcelUuid = ParcelUuid(SERVICE_UUID)
        val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(parcelUuid)
                .build()
        mBluetoothLeAdvertiser!!.startAdvertising(settings, data, mAdvertiseCallback)
    }

    private fun stopAdvertising() {
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser!!.stopAdvertising(mAdvertiseCallback)
        }
    }

    private val mAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("Peripheral advertising started.")
        }

        override fun onStartFailure(errorCode: Int) {
            log("Peripheral advertising failed: $errorCode")
        }
    }

    // Notifications
    private fun notifyCharacteristicTime(value: ByteArray) {
        notifyCharacteristic(value, CHARACTERISTIC_TIME_UUID)
    }

    private fun notifyCharacteristic(value: ByteArray, uuid: UUID) {
        mHandler!!.post {
            val service = mGattServer!!.getService(SERVICE_UUID)
            val characteristic = service.getCharacteristic(uuid)
            log("Notifying characteristic " + characteristic.uuid.toString()
                    + ", new value: " + StringUtils.byteArrayInHexFormat(value))
            characteristic.value = value
            // Indications require confirmation, notifications do not
            val confirm = BluetoothUtils.requiresConfirmation(characteristic)
            for (device in mDevices!!) {
                if (clientEnabledNotifications(device, characteristic)) {
                    mGattServer!!.notifyCharacteristicChanged(device, characteristic, confirm)
                }
            }
        }
    }

    private fun clientEnabledNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptorList = characteristic.descriptors
        val descriptor = BluetoothUtils.findClientConfigurationDescriptor(descriptorList)
                ?: // There is no client configuration descriptor, treat as true
                return true
        val deviceAddress = device.address
        val clientConfiguration = mClientConfigurations!![deviceAddress]
                ?: // Descriptor has not been set
                return false
        val notificationEnabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return clientConfiguration.size == notificationEnabled.size
                && clientConfiguration[0] and notificationEnabled[0] == notificationEnabled[0]
                && clientConfiguration[1] and notificationEnabled[1] == notificationEnabled[1]
    }

    // Characteristic operations
    private val timestampBytes: ByteArray
        private get() {
            @SuppressLint("SimpleDateFormat") val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val timestamp = dateFormat.format(Date())
            return timestamp.toByteArray()
        }

    private fun sendTimestamp() {
        val timestampBytes = timestampBytes
        notifyCharacteristicTime(timestampBytes)
    }

    // Logging
    private fun clearLogs() {
        mLogHandler!!.post { mBinding!!.viewServerLog.logTextView.text = "" }
    }

    // Gatt Server Actions
    fun log(msg: String) {
        Log.d(TAG, msg)
        mLogHandler!!.post {
            mBinding!!.viewServerLog.logTextView.append(msg + "\n")
            mBinding!!.viewServerLog.logScrollView.post { mBinding!!.viewServerLog.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun addDevice(device: BluetoothDevice) {
        log("Deviced added: " + device.address)
        mHandler!!.post { mDevices!!.add(device) }
    }

    fun removeDevice(device: BluetoothDevice) {
        log("Deviced removed: " + device.address)
        mHandler!!.post {
            mDevices!!.remove(device)
            val deviceAddress = device.address
            mClientConfigurations!!.remove(deviceAddress)
        }
    }

    fun addClientConfiguration(device: BluetoothDevice, value: ByteArray) {
        val deviceAddress = device.address
        mClientConfigurations!![deviceAddress] = value
    }

    fun sendResponse(device: BluetoothDevice?, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        mGattServer!!.sendResponse(device, requestId, status, 0, null)
    }

    fun notifyCharacteristicEcho(value: ByteArray) {
        notifyCharacteristic(value, CHARACTERISTIC_ECHO_UUID)
    }

    // Gatt Callback
    private inner class GattServerCallback : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            log("onConnectionStateChange " + device.address
                    + "\nstatus " + status
                    + "\nnewState " + newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addDevice(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                removeDevice(device)
            }
        }

        // The Gatt will reject Characteristic Read requests that do not have the permission set,
// so there is no need to check inside the callback
        override fun onCharacteristicReadRequest(device: BluetoothDevice,
                                                 requestId: Int,
                                                 offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            log("onCharacteristicReadRequest " + characteristic.uuid.toString())
            if (BluetoothUtils.requiresResponse(characteristic)) { // Unknown read characteristic requiring response, send failure
                sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
            // Not one of our characteristics or has NO_RESPONSE property set
        }

        // The Gatt will reject Characteristic Write requests that do not have the permission set,
// so there is no need to check inside the callback
        override fun onCharacteristicWriteRequest(device: BluetoothDevice,
                                                  requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean,
                                                  responseNeeded: Boolean,
                                                  offset: Int,
                                                  value: ByteArray) {
            super.onCharacteristicWriteRequest(device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value)
            log("onCharacteristicWriteRequest" + characteristic.uuid.toString()
                    + "\nReceived: " + StringUtils.byteArrayInHexFormat(value))
            if (CHARACTERISTIC_ECHO_UUID.equals(characteristic.uuid)) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                // Reverse message to differentiate original message & response
                val response = value.reversedArray()
                characteristic.value = response
                log("Sending: " + StringUtils.byteArrayInHexFormat(response))
                notifyCharacteristicEcho(response)
            }
        }

        // The Gatt will reject Descriptor Read requests that do not have the permission set,
// so there is no need to check inside the callback
        override fun onDescriptorReadRequest(device: BluetoothDevice,
                                             requestId: Int,
                                             offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            log("onDescriptorReadRequest" + descriptor.uuid.toString())
        }

        // The Gatt will reject Descriptor Write requests that do not have the permission set,
// so there is no need to check inside the callback
        override fun onDescriptorWriteRequest(device: BluetoothDevice,
                                              requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean,
                                              responseNeeded: Boolean,
                                              offset: Int,
                                              value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            log("onDescriptorWriteRequest: " + descriptor.uuid.toString()
                    + "\nvalue: " + StringUtils.byteArrayInHexFormat(value))
            if (CLIENT_CONFIGURATION_DESCRIPTOR_UUID.equals(descriptor.uuid)) {
                addClientConfiguration(device, value)
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            log("onNotificationSent")
        }
    }

    companion object {
        private const val TAG = "ServerActivity"
    }
}