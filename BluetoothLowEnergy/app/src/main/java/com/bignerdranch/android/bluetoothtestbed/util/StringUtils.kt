package com.bignerdranch.android.bluetoothtestbed.util

object StringUtils {

    fun byteArrayInHexFormat(byteArray: ByteArray) =
            byteArray.joinToString(
                    " , ",
                    "{ ",
                    " }")
            { String.format("%02X", it) }
}