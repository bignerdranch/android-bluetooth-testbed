package com.bignerdranch.android.bluetoothtestbed.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.bignerdranch.android.bluetoothtestbed.Constants.SCAN_PERIOD
import com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_UUID
import com.bignerdranch.android.bluetoothtestbed.R
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityClientBinding
import com.bignerdranch.android.bluetoothtestbed.databinding.ViewGattServerBinding
import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils
import java.util.*
import kotlin.collections.HashMap

class ClientActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityClientBinding
    private var mScanning = false
    private var mHandler: Handler? = null
    private var mLogHandler: Handler? = null
    private var mScanResults = HashMap<String, BluetoothDevice>()
    private var mConnected = false
    private var mTimeInitialized = false
    private var mEchoInitialized = false
    private lateinit var mBluetoothAdapter: BluetoothAdapter
    private lateinit var mBluetoothLeScanner: BluetoothLeScanner
    private var mScanCallback: ScanCallback? = null
    private var mGatt: BluetoothGatt? = null
    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mLogHandler = Handler(Looper.getMainLooper())
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_client)
        @SuppressLint("HardwareIds") val deviceInfo = ("Device Info"
                + "\nName: " + mBluetoothAdapter.name
                + "\nAddress: " + mBluetoothAdapter.address)
        mBinding.clientDeviceInfoTextView.text = deviceInfo
        mBinding.startScanningButton.setOnClickListener { v: View? -> startScan() }
        mBinding.stopScanningButton.setOnClickListener { v: View? -> stopScan() }
        mBinding.sendMessageButton.setOnClickListener { v: View? -> sendMessage() }
        mBinding.disconnectButton.setOnClickListener { v: View? -> disconnectGattServer() }
        mBinding.viewClientLog.clearLogButton.setOnClickListener { v: View? -> clearLogs() }
    }

    override fun onResume() {
        super.onResume()
        // Check low energy support
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { // Get a newer device
            logError("No LE Support.")
            finish()
        }
    }

    // Scanning
    private fun startScan() {
        if (!hasPermissions() || mScanning) {
            return
        }
        disconnectGattServer()
        mBinding!!.serverListContainer.removeAllViews()
        mScanResults = HashMap()
        mScanCallback = BtleScanCallback(mScanResults)
        mBluetoothLeScanner = mBluetoothAdapter!!.bluetoothLeScanner
        // Note: Filtering does not work the same (or at all) on most devices. It also is unable to
// search for a mask or anything less than a full UUID.
// Unless the full UUID of the server is known, manual filtering may be necessary.
// For example, when looking for a brand of device that contains a char sequence in the UUID
        val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        val filters: MutableList<ScanFilter> = ArrayList()
        filters.add(scanFilter)
        val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
        mBluetoothLeScanner.startScan(filters, settings, mScanCallback)
        mHandler = Handler()
        mHandler!!.postDelayed({ stopScan() }, SCAN_PERIOD)
        mScanning = true
        log("Started scanning.")
    }

    private fun stopScan() {
        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter!!.isEnabled && mBluetoothLeScanner != null) {
            mBluetoothLeScanner!!.stopScan(mScanCallback)
            scanComplete()
        }
        mScanCallback = null
        mScanning = false
        mHandler = null
        log("Stopped scanning.")
    }

    private fun scanComplete() {
        if (mScanResults!!.isEmpty()) {
            return
        }
        for (deviceAddress in mScanResults!!.keys) {
            val device = mScanResults!![deviceAddress]
            val viewModel = GattServerViewModel(device)
            val binding: ViewGattServerBinding = DataBindingUtil.inflate(LayoutInflater.from(this),
                    R.layout.view_gatt_server,
                    mBinding!!.serverListContainer,
                    true)
            binding.viewModel = viewModel
            binding.connectGattServerButton.setOnClickListener { v: View? -> connectDevice(device) }
        }
    }

    private fun hasPermissions(): Boolean {
        if (mBluetoothAdapter == null || !mBluetoothAdapter!!.isEnabled) {
            requestBluetoothEnable()
            return false
        } else if (!hasLocationPermissions()) {
            requestLocationPermission()
            return false
        }
        return true
    }

    private fun requestBluetoothEnable() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        log("Requested user enables Bluetooth. Try starting the scan again.")
    }

    private fun hasLocationPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_FINE_LOCATION)
        log("Requested user enable Location. Try starting the scan again.")
    }

    // Gatt connection
    private fun connectDevice(device: BluetoothDevice?) {
        log("Connecting to " + device!!.address)
        val gattClientCallback = GattClientCallback()
        mGatt = device.connectGatt(this, false, gattClientCallback)
    }

    // Messaging
    private fun sendMessage() {
        if (!mConnected || !mEchoInitialized) {
            return
        }
        val characteristic = BluetoothUtils.findEchoCharacteristic(mGatt)
        if (characteristic == null) {
            logError("Unable to find echo characteristic.")
            disconnectGattServer()
            return
        }
        val message = mBinding!!.messageEditText.text.toString()
        log("Sending message: $message")
        val messageBytes = StringUtils.bytesFromString(message)
        if (messageBytes.size == 0) {
            logError("Unable to convert message to bytes")
            return
        }
        characteristic.value = messageBytes
        val success = mGatt!!.writeCharacteristic(characteristic)
        if (success) {
            log("Wrote: " + StringUtils.byteArrayInHexFormat(messageBytes))
        } else {
            logError("Failed to write data")
        }
    }

    // Logging
    private fun clearLogs() {
        mLogHandler!!.post { mBinding!!.viewClientLog.logTextView.text = "" }
    }

    // Gat Client Actions
    fun log(msg: String) {
        Log.d(TAG, msg)
        mLogHandler!!.post {
            mBinding!!.viewClientLog.logTextView.append(msg + "\n")
            mBinding!!.viewClientLog.logScrollView.post { mBinding!!.viewClientLog.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun logError(msg: String) {
        log("Error: $msg")
    }

    fun setConnected(connected: Boolean) {
        mConnected = connected
    }

    fun initializeTime() {
        mTimeInitialized = true
    }

    fun initializeEcho() {
        mEchoInitialized = true
    }

    fun disconnectGattServer() {
        log("Closing Gatt connection")
        clearLogs()
        mConnected = false
        mEchoInitialized = false
        mTimeInitialized = false
        if (mGatt != null) {
            mGatt!!.disconnect()
            mGatt!!.close()
        }
    }

    // Callbacks
    private inner class BtleScanCallback internal constructor(private val mScanResults: MutableMap<String, BluetoothDevice>) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            for (result in results) {
                addScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logError("BLE Scan Failed with code $errorCode")
        }

        private fun addScanResult(result: ScanResult) {
            val device = result.device
            val deviceAddress = device.address
            mScanResults[deviceAddress] = device
        }

    }

    private inner class GattClientCallback : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            log("onConnectionStateChange newState: $newState")
            if (status == BluetoothGatt.GATT_FAILURE) {
                logError("Connection Gatt failure status $status")
                disconnectGattServer()
                return
            } else if (status != BluetoothGatt.GATT_SUCCESS) { // handle anything not SUCCESS as failure
                logError("Connection not GATT sucess status $status")
                disconnectGattServer()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected to device " + gatt.device.address)
                setConnected(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected from device")
                disconnectGattServer()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Device service discovery unsuccessful, status $status")
                return
            }
            val matchingCharacteristics = BluetoothUtils.findCharacteristics(gatt)
            if (matchingCharacteristics.isEmpty()) {
                logError("Unable to find characteristics.")
                return
            }
            log("Initializing: setting write type and enabling notification")
            for (characteristic in matchingCharacteristics) {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                enableCharacteristicNotification(gatt, characteristic)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic written successfully")
            } else {
                logError("Characteristic write unsuccessful, status: $status")
                disconnectGattServer()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic read successfully")
                readCharacteristic(characteristic)
            } else {
                logError("Characteristic read unsuccessful, status: $status")
                // Trying to read from the Time Characteristic? It doesnt have the property or permissions
// set to allow this. Normally this would be an error and you would want to:
// disconnectGattServer();
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            log("Characteristic changed, " + characteristic.uuid.toString())
            readCharacteristic(characteristic)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Descriptor written successfully: " + descriptor.uuid.toString())
                initializeTime()
            } else {
                logError("Descriptor write unsuccessful: " + descriptor.uuid.toString())
            }
        }

        private fun enableCharacteristicNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val characteristicWriteSuccess = gatt.setCharacteristicNotification(characteristic, true)
            if (characteristicWriteSuccess) {
                log("Characteristic notification set successfully for " + characteristic.uuid.toString())
                if (BluetoothUtils.isEchoCharacteristic(characteristic)) {
                    initializeEcho()
                } else if (BluetoothUtils.isTimeCharacteristic(characteristic)) {
                    enableCharacteristicConfigurationDescriptor(gatt, characteristic)
                }
            } else {
                logError("Characteristic notification set failure for " + characteristic.uuid.toString())
            }
        }

        // Sometimes the Characteristic does not have permissions, and instead its Descriptor holds them
// See https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
        private fun enableCharacteristicConfigurationDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val descriptorList = characteristic.descriptors
            val descriptor = BluetoothUtils.findClientConfigurationDescriptor(descriptorList)
            if (descriptor == null) {
                logError("Unable to find Characteristic Configuration Descriptor")
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val descriptorWriteInitiated = gatt.writeDescriptor(descriptor)
            if (descriptorWriteInitiated) {
                log("Characteristic Configuration Descriptor write initiated: " + descriptor.uuid.toString())
            } else {
                logError("Characteristic Configuration Descriptor write failed to initiate: " + descriptor.uuid.toString())
            }
        }

        private fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
            val messageBytes = characteristic.value
            log("Read: " + StringUtils.byteArrayInHexFormat(messageBytes))
            val message = StringUtils.stringFromBytes(messageBytes)
            if (message == null) {
                logError("Unable to convert bytes to string")
                return
            }
            log("Received message: $message")
        }
    }

    companion object {
        private const val TAG = "ClientActivity"
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_FINE_LOCATION = 2
    }
}