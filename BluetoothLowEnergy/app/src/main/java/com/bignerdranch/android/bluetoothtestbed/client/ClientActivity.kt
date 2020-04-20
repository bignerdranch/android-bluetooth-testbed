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
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_ECHO_UUID
import com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_TIME_UUID
import com.bignerdranch.android.bluetoothtestbed.Constants.SCAN_PERIOD
import com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_UUID
import com.bignerdranch.android.bluetoothtestbed.R
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityClientBinding
import com.bignerdranch.android.bluetoothtestbed.databinding.ViewGattServerBinding
import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils


class ClientActivity : AppCompatActivity() {
    private lateinit var binding: ActivityClientBinding

    private var isScanning = false
    private var isConnected = false
    private var timeInitialized = false
    private var echoInitialized = false

    private var handler: Handler? = null
    private var logHandler = Handler(Looper.getMainLooper())

    private var scanResults = HashMap<String, BluetoothDevice>()
    private var scanCallback: ScanCallback? = null

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        @SuppressLint("HardwareIds")
        val deviceInfo = ("Device Info"
                + "\nName: " + bluetoothAdapter.name
                + "\nAddress: " + bluetoothAdapter.address)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_client)
        with(binding) {
            clientDeviceInfoTextView.text = deviceInfo
            startScanningButton.setOnClickListener { startScan() }
            stopScanningButton.setOnClickListener { stopScan() }
            sendMessageButton.setOnClickListener { sendMessage() }
            disconnectButton.setOnClickListener { disconnectGattServer() }
            viewClientLog.clearLogButton.setOnClickListener { clearLogs() }
        }
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
        if (!hasPermissions() || isScanning) {
            return
        }
        disconnectGattServer()
        binding.serverListContainer.removeAllViews()
        scanResults = HashMap()
        scanCallback = BtleScanCallback(scanResults)
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        // Note: Filtering does not work the same (or at all) on most devices. It also is unable to
        // search for a mask or anything less than a full UUID.
        // Unless the full UUID of the server is known, manual filtering may be necessary.
        // For example, when looking for a brand of device that contains a char sequence in the UUID
        val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        val filters = mutableListOf(scanFilter)
        val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
        bluetoothLeScanner.startScan(filters, settings, scanCallback)
        handler = Handler()
        handler!!.postDelayed({ stopScan() }, SCAN_PERIOD)
        isScanning = true
        log("Started scanning.")
    }

    private fun stopScan() {
        if (isScanning && bluetoothAdapter.isEnabled) {
            bluetoothLeScanner.stopScan(scanCallback)
            scanComplete()
        }
        scanCallback = null
        isScanning = false
        handler = null
        log("Stopped scanning.")
    }

    private fun scanComplete() {
        if (scanResults.isEmpty()) {
            return
        }
        scanResults.keys.forEach { deviceAddress ->
            with(DataBindingUtil.inflate(LayoutInflater.from(this),
                    R.layout.view_gatt_server,
                    binding.serverListContainer,
                    true) as ViewGattServerBinding) {
                val device = scanResults[deviceAddress]
                this.viewModel = GattServerViewModel(device)
                connectGattServerButton.setOnClickListener { connectDevice(device) }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
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

    private fun hasLocationPermissions() =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_FINE_LOCATION)
        log("Requested user enable Location. Try starting the scan again.")
    }

    // Gatt connection
    private fun connectDevice(device: BluetoothDevice?) {
        log("Connecting to " + device?.address)
        val gattClientCallback = GattClientCallback()
        gatt = device?.connectGatt(this, false, gattClientCallback)
    }

    // Messaging
    private fun sendMessage() {
        if (gatt == null || !isConnected || !echoInitialized) {
            return
        }
        val characteristic = BluetoothUtils.findEchoCharacteristic(gatt!!)
        if (characteristic == null) {
            logError("Unable to find echo characteristic.")
            disconnectGattServer()
            return
        }
        val message = binding.messageEditText.text.toString()
        log("Sending message: $message")
        val messageBytes = message.toByteArray()
        if (messageBytes.isEmpty()) {
            logError("Unable to convert message to bytes")
            return
        }
        characteristic.value = messageBytes
        val success = gatt!!.writeCharacteristic(characteristic)
        if (success) {
            log("Wrote: " + StringUtils.byteArrayInHexFormat(messageBytes))
        } else {
            logError("Failed to write data")
        }
    }

    // Logging
    private fun clearLogs() =
            logHandler.post { binding.viewClientLog.logTextView.text = "" }

    // Gat Client Actions
    fun log(msg: String) {
        Log.d("ClientActivity", msg)
        logHandler.post {
            with(binding.viewClientLog) {
                logTextView.append(msg + "\n")
                logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    fun logError(msg: String) =
            log("Error: $msg")

    fun disconnectGattServer() {
        log("Closing Gatt connection")
        clearLogs()
        isConnected = false
        echoInitialized = false
        timeInitialized = false
        if (gatt != null) {
            gatt!!.disconnect()
            gatt!!.close()
        }
    }

    // Callbacks
    private inner class BtleScanCallback internal constructor(private val mScanResults: MutableMap<String, BluetoothDevice>) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) =
                results.forEach { addScanResult(it) }

        override fun onScanFailed(errorCode: Int) =
                logError("BLE Scan Failed with code $errorCode")

        private fun addScanResult(result: ScanResult) =
                with(result.device) {
                    mScanResults[address] = this
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
                logError("Connection not GATT success status $status")
                disconnectGattServer()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected to device " + gatt.device.address)
                isConnected = true
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
            matchingCharacteristics.forEach {
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                enableCharacteristicNotification(gatt, it)
            }

            /*
            gatt.services.find { it.uuid == SERVICE_UUID }
                    ?.characteristics?.find { it.uuid == CHARACTERISTIC_TIME_UUID }
                    ?.let {
                        if (gatt.setCharacteristicNotification(it, true)) {
                            log("Characteristic notification set successfully for " + it.uuid.toString())
                            enableCharacteristicConfigurationDescriptor(gatt, it)
                        }
                    }
             */
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
                // Trying to read from the Time Characteristic? It doesn't have the property or permissions
                // set to allow this. Normally this would be an error and you would want to call
                // disconnectGattServer()
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
                timeInitialized = true
            } else {
                logError("Descriptor write unsuccessful: " + descriptor.uuid.toString())
            }
        }

        private fun enableCharacteristicNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val characteristicWriteSuccess = gatt.setCharacteristicNotification(characteristic, true)
            if (characteristicWriteSuccess) {
                log("Characteristic notification set successfully for " + characteristic.uuid.toString())
                if (BluetoothUtils.isEchoCharacteristic(characteristic)) {
                    echoInitialized = true
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
            val descriptor = BluetoothUtils.findClientConfigurationDescriptor(characteristic.descriptors)
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
            val message = String(messageBytes)
            log("Received message: $message")
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_FINE_LOCATION = 2
    }
}