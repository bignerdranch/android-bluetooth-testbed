package com.bignerdranch.android.bluetoothtestbed.server;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import com.bignerdranch.android.bluetoothtestbed.R;
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityServerBinding;
import com.bignerdranch.android.bluetoothtestbed.util.BluetoothUtils;
import com.bignerdranch.android.bluetoothtestbed.util.ByteUtils;
import com.bignerdranch.android.bluetoothtestbed.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.bignerdranch.android.bluetoothtestbed.Constants.CHARACTERISTIC_ECHO_UUID;
import static com.bignerdranch.android.bluetoothtestbed.Constants.SERVICE_UUID;

public class ServerActivity extends AppCompatActivity {

    private static final String TAG = "ServerActivity";

    private ActivityServerBinding mBinding;

    private Handler mHandler;
    private Handler mLogHandler;
    private List<BluetoothDevice> mDevices;

    private BluetoothGattServer mGattServer;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    // Lifecycle

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();
        mLogHandler = new Handler(Looper.getMainLooper());
        mDevices = new ArrayList<>();

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_server);
        mBinding.restartServerButton.setOnClickListener(v -> restartServer());
        mBinding.viewServerLog.clearLogButton.setOnClickListener(v -> clearLogs());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if bluetooth is enabled
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            // Request user to enable it
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        // Check low energy support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // Get a newer device
            log("No LE Support.");
            finish();
            return;
        }

        // Check advertising
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            // Unable to run the server on this device, get a better device
            log("No Advertising Support.");
            finish();
            return;
        }

        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        GattServerCallback gattServerCallback = new GattServerCallback();
        mGattServer = mBluetoothManager.openGattServer(this, gattServerCallback);

        @SuppressLint("HardwareIds")
        String deviceInfo = "Device Info" + "\nName: " + mBluetoothAdapter.getName() + "\nAddress: " + mBluetoothAdapter.getAddress();
        mBinding.serverDeviceInfoTextView.setText(deviceInfo);

        setupServer();
        startAdvertising();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAdvertising();
        stopServer();
    }

    // GattServer

    private void setupServer() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Write characteristic
        BluetoothGattCharacteristic writeCharacteristic = new BluetoothGattCharacteristic(CHARACTERISTIC_ECHO_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                // Somehow this is not necessary, the client can still enable notifications
//                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        service.addCharacteristic(writeCharacteristic);

        mGattServer.addService(service);
    }

    private void stopServer() {
        if (mGattServer != null) {
            mGattServer.close();
        }
    }

    private void restartServer() {
        stopAdvertising();
        stopServer();
        setupServer();
        startAdvertising();
    }

    // Advertising

    private void startAdvertising() {
        if (mBluetoothLeAdvertiser == null) {
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build();

        ParcelUuid parcelUuid = new ParcelUuid(SERVICE_UUID);
        AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(true)
                .addServiceUuid(parcelUuid)
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        }
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            log("Peripheral advertising started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            log("Peripheral advertising failed: " + errorCode);
        }
    };

    // Notifications

    private void notifyCharacteristic(byte[] value, UUID uuid) {
        mHandler.post(() -> {
            BluetoothGattService service = mGattServer.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
            log("Notifying characteristic " + characteristic.getUuid().toString()
                    + ", new value: " + StringUtils.byteArrayInHexFormat(value));

            characteristic.setValue(value);
            boolean confirm = BluetoothUtils.requiresConfirmation(characteristic);
            for(BluetoothDevice device : mDevices) {
                mGattServer.notifyCharacteristicChanged(device, characteristic, confirm);
            }
        });
    }

    // Logging

    public void log(String msg) {
        Log.d(TAG, msg);
        mLogHandler.post(() -> {
            mBinding.viewServerLog.logTextView.append(msg + "\n");
            mBinding.viewServerLog.logScrollView.post(() -> mBinding.viewServerLog.logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearLogs() {
        mLogHandler.post(() -> mBinding.viewServerLog.logTextView.setText(""));
    }

    // Gatt Server Action Listener

    public void addDevice(BluetoothDevice device) {
        log("Deviced added: " + device.getAddress());
        mHandler.post(() -> mDevices.add(device));
    }

    public void removeDevice(BluetoothDevice device) {
        log("Deviced removed: " + device.getAddress());
        mHandler.post(() -> mDevices.remove(device));
    }

    public void sendResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        mHandler.post(() -> mGattServer.sendResponse(device, requestId, status, 0, null));
    }

    private void sendReverseMessage(byte[] message) {
        mHandler.post(() -> {
            // Reverse message to differentiate original message & response
            byte[] response = ByteUtils.reverse(message);
            log("Sending: " + StringUtils.byteArrayInHexFormat(response));
            notifyCharacteristicEcho(response);
        });
    }

    public void notifyCharacteristicEcho(byte[] value) {
        notifyCharacteristic(value, CHARACTERISTIC_ECHO_UUID);
    }

    // Gatt Callback

    private class GattServerCallback extends BluetoothGattServerCallback {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            log("onConnectionStateChange " + device.getAddress()
                    + "\nstatus " + status
                    + "\nnewState " + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addDevice(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                removeDevice(device);
            }
        }

        // The Gatt will reject Characteristic Read requests that do not have the permission set,
        // so there is no need to check inside the callback
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

            log("onCharacteristicReadRequest "
                    + characteristic.getUuid().toString());

            if (BluetoothUtils.requiresResponse(characteristic)) {
                // Unknown read characteristic requiring response, send failure
                sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
            // Not one of our characteristics or has NO_RESPONSE property set
        }

        // The Gatt will reject Characteristic Write requests that do not have the permission set,
        // so there is no need to check inside the callback
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value);
            log("onCharacteristicWriteRequest" + characteristic.getUuid().toString()
                    + "\nReceived: " + StringUtils.byteArrayInHexFormat(value));

            if (CHARACTERISTIC_ECHO_UUID.equals(characteristic.getUuid())) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                sendReverseMessage(value);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            log("onNotificationSent");
        }
    }
}
