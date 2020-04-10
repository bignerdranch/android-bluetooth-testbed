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
    private lateinit var binding: ActivityServerBinding

    private val handler = Handler()
    private val logHandler = Handler(Looper.getMainLooper())

    private val devices = mutableListOf<BluetoothDevice>()
    private var clientConfigurations = mutableMapOf<String, ByteArray>()

    private var gattServer: BluetoothGattServer? = null
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        binding = DataBindingUtil.setContentView(this, R.layout.activity_server)
        with(binding) {
            sendTimestampButton.setOnClickListener { sendTimestamp() }
            restartServerButton.setOnClickListener { restartServer() }
            viewServerLog.clearLogButton.setOnClickListener { clearLogs() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if bluetooth is enabled
        if (!bluetoothAdapter.isEnabled) { // Request user to enable it
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
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) { // Unable to run the server on this device, get a better device
            log("No Advertising Support.")
            finish()
            return
        }
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val gattServerCallback = GattServerCallback()
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        @SuppressLint("HardwareIds")
        val deviceInfo = ("Device Info"
                + "\nName: " + bluetoothAdapter.name
                + "\nAddress: " + bluetoothAdapter.address)
        binding.serverDeviceInfoTextView.text = deviceInfo
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
        // Write characteristic
        val writeCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_ECHO_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                // Somehow this is not necessary, the client can still enable notifications
//                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE)

        // Characteristic with Descriptor
        val clientConfigurationDescriptor = BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE).apply {
            value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        val notifyCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_TIME_UUID,
                // Somehow this is not necessary, the client can still enable notifications
//                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
                0).apply {
            addDescriptor(clientConfigurationDescriptor)
        }

        gattServer!!.addService(
                BluetoothGattService(SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    addCharacteristic(writeCharacteristic)
                    addCharacteristic(notifyCharacteristic)
                }
        )
    }

    private fun stopServer() =
            gattServer?.close()

    private fun restartServer() {
        stopAdvertising()
        stopServer()
        setupServer()
        startAdvertising()
    }

    // Advertising
    private fun startAdvertising() {
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
        bluetoothLeAdvertiser?.startAdvertising(settings, data, mAdvertiseCallback)
    }

    private fun stopAdvertising() =
            bluetoothLeAdvertiser?.stopAdvertising(mAdvertiseCallback)

    private val mAdvertiseCallback: AdvertiseCallback =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) =
                        log("Peripheral advertising started.")

                override fun onStartFailure(errorCode: Int) =
                        log("Peripheral advertising failed: $errorCode")
            }

    // Notifications
    private fun notifyCharacteristicTime(value: ByteArray) =
            notifyCharacteristic(value, CHARACTERISTIC_TIME_UUID)

    private fun notifyCharacteristic(value: ByteArray, uuid: UUID) {
        handler.post {
            gattServer?.getService(SERVICE_UUID)?.let { service ->
                with(service.getCharacteristic(uuid)) {
                    log("Notifying characteristic "
                            + this.uuid.toString()
                            + ", new value: " + StringUtils.byteArrayInHexFormat(value))
                    this.value = value
                    // Indications require confirmation, notifications do not
                    val confirm = BluetoothUtils.requiresConfirmation(this)
                    devices.forEach {
                        if (clientEnabledNotifications(it, this)) {
                            gattServer!!.notifyCharacteristicChanged(it, this, confirm)
                        }
                    }
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
        val clientConfiguration = clientConfigurations[deviceAddress]
                ?: // Descriptor has not been set
                return false
        return Arrays.equals(clientConfiguration, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    // Characteristic operations
    private val timestampBytes: ByteArray
        get() {
            @SuppressLint("SimpleDateFormat")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val timestamp = dateFormat.format(Date())
            return timestamp.toByteArray()
        }

    private fun sendTimestamp() =
            notifyCharacteristicTime(timestampBytes)

    // Logging
    private fun clearLogs() =
            logHandler.post { binding.viewServerLog.logTextView.text = "" }

    // Gatt Server Actions
    fun log(msg: String) {
        Log.d(TAG, msg)
        logHandler.post {
            with(binding.viewServerLog) {
                logTextView.append(msg + "\n")
                logScrollView.post { binding.viewServerLog.logScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    fun addDevice(device: BluetoothDevice) {
        log("Device added: " + device.address)
        handler.post { devices.add(device) }
    }

    fun removeDevice(device: BluetoothDevice) {
        log("Device removed: " + device.address)
        handler.post {
            devices.remove(device)
            val deviceAddress = device.address
            clientConfigurations.remove(deviceAddress)
        }
    }

    fun addClientConfiguration(device: BluetoothDevice, value: ByteArray) {
        clientConfigurations[device.address] = value
    }

    fun sendResponse(device: BluetoothDevice?, requestId: Int, status: Int, offset: Int, value: ByteArray?) =
            gattServer!!.sendResponse(device, requestId, status, offset, null)

    fun notifyCharacteristicEcho(value: ByteArray) =
            notifyCharacteristic(value, CHARACTERISTIC_ECHO_UUID)

    // Gatt Callback
    private inner class GattServerCallback : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            log("onConnectionStateChange "
                    + device.address
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
            if (CHARACTERISTIC_ECHO_UUID == characteristic.uuid) {
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
            if (CLIENT_CONFIGURATION_DESCRIPTOR_UUID == descriptor.uuid) {
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