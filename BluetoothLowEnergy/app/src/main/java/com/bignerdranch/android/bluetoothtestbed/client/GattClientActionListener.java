package com.bignerdranch.android.bluetoothtestbed.client;

public interface GattClientActionListener {

    void log(String message);

    void logError(String message);

    void setConnected(boolean connected);

    void initializeTime();

    void initializeEcho();

    void disconnectGattServer();
}
