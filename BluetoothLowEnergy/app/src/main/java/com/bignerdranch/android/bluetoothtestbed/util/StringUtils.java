package com.bignerdranch.android.bluetoothtestbed.util;

import android.support.annotation.Nullable;
import android.util.Log;

import java.io.UnsupportedEncodingException;

/**
 * This class is meant to be a replacement for TextUtils to allow unit testing
 * of files that may want to use common TextUtils methods.
 */
public class StringUtils {

    private static final String TAG = "StringUtils";

    private static String byteToHex(byte b) {
        char char1 = Character.forDigit((b & 0xF0) >> 4, 16);
        char char2 = Character.forDigit((b & 0x0F), 16);

        return String.format("0x%1$s%2$s", char1, char2);
    }

    public static String byteArrayInHexFormat(byte[] byteArray) {
        if (byteArray == null) {
            return null;
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{ ");
        for (int i = 0; i < byteArray.length; i++) {
            if (i > 0) {
                stringBuilder.append(", ");
            }
            String hexString = byteToHex(byteArray[i]);
            stringBuilder.append(hexString);
        }
        stringBuilder.append(" }");

        return stringBuilder.toString();
    }

    public static byte[] bytesFromString(String string) {
        byte[] stringBytes = new byte[0];
        try {
            stringBytes = string.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Failed to convert message string to byte array");
        }

        return stringBytes;
    }

    @Nullable
    public static String stringFromBytes(byte[] bytes) {
        String byteString = null;
        try {
            byteString = new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Unable to convert message bytes to string");
        }
        return byteString;
    }
}
