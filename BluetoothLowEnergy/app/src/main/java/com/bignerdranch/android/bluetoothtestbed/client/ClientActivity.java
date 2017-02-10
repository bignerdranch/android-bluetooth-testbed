package com.bignerdranch.android.bluetoothtestbed.client;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.bignerdranch.android.bluetoothtestbed.R;
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityClientBinding;
import com.bignerdranch.android.bluetoothtestbed.databinding.ViewGattServerBinding;
import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils;
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bignerdranch.android.bluetoothtestbed.Constants.SCAN_PERIOD;
import static com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_UUID;

public class ClientActivity extends AppCompatActivity {

    private static final String TAG = "ClientActivity";

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_FINE_LOCATION = 2;

    private ActivityClientBinding mBinding;

    private boolean mScanning;
    private Handler mHandler;
    private Handler mLogHandler;
    private Map<String, BluetoothDevice> mScanResults;

    private boolean mConnected;
    private boolean mTimeInitialized;
    private boolean mEchoInitialized;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private BluetoothGatt mGatt;

    // Lifecycle

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLogHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_client);
        @SuppressLint("HardwareIds")
        String deviceInfo = "Device Info"
                + "\nName: " + mBluetoothAdapter.getName()
                + "\nAddress: " + mBluetoothAdapter.getAddress();
        mBinding.clientDeviceInfoTextView.setText(deviceInfo);
        mBinding.startScanningButton.setOnClickListener(v -> startScan());
        mBinding.stopScanningButton.setOnClickListener(v -> stopScan());
        mBinding.sendMessageButton.setOnClickListener(v -> sendMessage());
        mBinding.requestTimestampButton.setOnClickListener(v -> requestTimestamp());
        mBinding.disconnectButton.setOnClickListener(v -> disconnectGattServer());
        mBinding.viewClientLog.clearLogButton.setOnClickListener(v -> clearLogs());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check low energy support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // Get a newer device
            logError("No LE Support.");
            finish();
        }
    }

    // Scanning

    private void startScan() {
        if (!hasPermissions() || mScanning) {
            return;
        }

        disconnectGattServer();

        mBinding.serverListContainer.removeAllViews();

        mScanResults = new HashMap<>();
        mScanCallback = new BtleScanCallback(mScanResults);

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Note: Filtering does not work the same (or at all) on most devices. It also is unable to
        // search for a mask or anything less than a full UUID.
        // Unless the full UUID of the server is known, manual filtering may be necessary.
        // For example, when looking for a brand of device that contains a char sequence in the UUID
        ScanFilter scanFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(scanFilter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        mHandler = new Handler();
        mHandler.postDelayed(this::stopScan, SCAN_PERIOD);

        mScanning = true;
        log("Started scanning.");
    }

    private void stopScan() {
        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mScanCallback);
            scanComplete();
        }

        mScanCallback = null;
        mScanning = false;
        mHandler = null;
        log("Stopped scanning.");
    }

    private void scanComplete() {
        if (mScanResults.isEmpty()) {
            return;
        }

        for (String deviceAddress : mScanResults.keySet()) {
            BluetoothDevice device = mScanResults.get(deviceAddress);
            GattServerViewModel viewModel = new GattServerViewModel(device);

            ViewGattServerBinding binding = DataBindingUtil.inflate(LayoutInflater.from(this),
                    R.layout.view_gatt_server,
                    mBinding.serverListContainer,
                    true);
            binding.setViewModel(viewModel);
            binding.connectGattServerButton.setOnClickListener(v -> connectDevice(device));
        }
    }

    private boolean hasPermissions() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            requestBluetoothEnable();
            return false;
        } else if (!hasLocationPermissions()) {
            requestLocationPermission();
            return false;
        }
        return true;
    }

    private void requestBluetoothEnable() {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        log("Requested user enables Bluetooth. Try starting the scan again.");
    }

    private boolean hasLocationPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
        log("Requested user enables Location. Try starting the scan again.");
    }

    private class BtleScanCallback extends ScanCallback {

        private Map<String, BluetoothDevice> mScanResults;

        BtleScanCallback(Map<String, BluetoothDevice> scanResults) {
            mScanResults = scanResults;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                addScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            logError("BLE Scan Failed with code " + errorCode);
        }

        private void addScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            mScanResults.put(deviceAddress, device);
        }
    }

    // Gatt connection

    private void connectDevice(BluetoothDevice device) {
        log("Connecting to " + device.getAddress());
        mGatt = device.connectGatt(this, false, mGattCallback);
    }

    private void disconnectGattServer() {
        log("Closing lock connection");
        clearLogs();
        mConnected = false;
        mEchoInitialized = false;
        mTimeInitialized = false;
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
    }

    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            log("onConnectionStateChange newState: " + newState);

            if (status == BluetoothGatt.GATT_FAILURE) {
                logError("Connection Gatt failure status " + status);
                disconnectGattServer();
                return;
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                // handle anything not SUCCESS as failure
                logError("Connection not GATT sucess status " + status);
                disconnectGattServer();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected to device " + gatt.getDevice().getAddress());
                mConnected = true;
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected from device");
                disconnectGattServer();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Device service discovery unsuccessful, status " + status);
                return;
            }

            List<BluetoothGattCharacteristic> matchingCharacteristics = BluetoothUtils.findCharacteristics(gatt);
            if (matchingCharacteristics.isEmpty()) {
                logError("Unable to find characteristics.");
                return;
            }

            log("Initializing: setting write type and enabling notification");
            for (BluetoothGattCharacteristic characteristic : matchingCharacteristics) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                enableCharacteristicNotification(gatt, characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            // TODO can we get a new value here?
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic written successfully");
            } else {
                logError("Characteristic write unsuccessful, status: " + status);
                disconnectGattServer();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic read successfully");
                readCharacteristic(characteristic);
            } else {
                logError("Characteristic write unsuccessful, status: " + status);
                disconnectGattServer();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            log("Characteristic changed, " + characteristic.getUuid().toString());
            readCharacteristic(characteristic);
        }
    };

    private void enableCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        boolean characteristicWriteSuccess = gatt.setCharacteristicNotification(characteristic, true);
        if (characteristicWriteSuccess) {
            log("Characteristic notification set successfully for " + characteristic.getUuid().toString());
            if (BluetoothUtils.isEchoCharacteristic(characteristic)) {
                mEchoInitialized = true;
            } else if (BluetoothUtils.isTimeCharacteristic(characteristic)) {
                mTimeInitialized = true;
            }
        } else {
            logError("Characteristic notification set failure for " + characteristic.getUuid().toString());
        }

        // If there are notification descriptors, they should be enabled here
        // The above booleans should be set later in onDescriptorWrite on BluetoothGatt.GATT_SUCCESS
        // disconnectGattServer() for any other status
        // See https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
        /*
            List<BluetoothGattDescriptor> descriptorList = characteristic.getDescriptors();
            BluetoothGattDescriptor descriptor = BluetoothUtils.findNotificationDescriptor(descriptorList);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean descriptorWriteInitiated = gatt.writeDescriptor(descriptor);
        */
    }

    // Messaging

    private void sendMessage() {
        if (!mConnected || !mEchoInitialized) {
            return;
        }

        BluetoothGattCharacteristic characteristic = BluetoothUtils.findEchoCharacteristic(mGatt);
        if (characteristic == null) {
            logError("Unable to find echo characteristic.");
            disconnectGattServer();
            return;
        }

        String message = mBinding.messageEditText.getText().toString();
        log("Sending message: " + message);

        byte[] messageBytes = StringUtils.bytesFromString(message);
        if (messageBytes.length == 0) {
            logError("Unable to convert message to bytes");
            return;
        }

        characteristic.setValue(messageBytes);
        boolean success = mGatt.writeCharacteristic(characteristic);
        if (success) {
            log("Wrote: " + StringUtils.byteArrayInHexFormat(messageBytes));
        } else {
            logError("Failed to write data");
        }
    }

    private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        byte[] messageBytes = characteristic.getValue();
        log("Read: " + StringUtils.byteArrayInHexFormat(messageBytes));
        String message = StringUtils.stringFromBytes(messageBytes);
        if (message == null) {
            logError("Unable to convert bytes to string");
            return;
        }

        log("Received message: " + message);
    }

    private void requestTimestamp() {
        if (!mConnected || !mTimeInitialized) {
            return;
        }

        BluetoothGattCharacteristic characteristic = BluetoothUtils.findTimeCharacteristic(mGatt);
        if (characteristic == null) {
            logError("Unable to find time charactaristic");
            return;
        }

        mGatt.readCharacteristic(characteristic);
    }

    // Logging

    private void log(String msg) {
        Log.d(TAG, msg);
        mLogHandler.post(() -> {
            mBinding.viewClientLog.logTextView.append(msg + "\n");
            mBinding.viewClientLog.logScrollView.post(() -> mBinding.viewClientLog.logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void logError(String msg) {
        log("Error: " + msg);
    }

    private void clearLogs() {
        mBinding.viewClientLog.logTextView.setText("");
    }
}
