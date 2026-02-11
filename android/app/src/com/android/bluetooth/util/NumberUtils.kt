/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.util

/** Utility for parsing numbers in Bluetooth. */
object NumberUtils {

    /** Convert a little endian byte array to integer. */
    fun littleEndianByteArrayToInt(bytes: ByteArray): Int {
        val length = bytes.size
        if (length == 0) {
            return 0
        }
        var result = 0
        for (i in length - 1 downTo 0) {
            val value = unsignedByteToInt(bytes[i])
            result += (value shl (i * 8))
        }
        return result
    }

    /** Convert a byte to unsigned int. */
    private fun unsignedByteToInt(b: Byte): Int {
        return b.toInt() and 0xFF
    }
}
