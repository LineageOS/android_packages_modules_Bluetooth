/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.bluetooth

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "BluetoothSystemServer"

object Log {

    private val DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    // Kotlin could shorten below method by having a Throwable? that is default to null but the
    // current implementation of util.Log is behaving differently depending if it is called with
    // 2 or 3 parameters. We do not want to change the behavior in this class, just add a common
    // TAG to all the Bluetooth System Server logs.

    @JvmStatic fun v(subtag: String, msg: String) = Log.v(TAG, "$subtag: $msg")

    @JvmStatic fun v(msg: String) = Log.v(TAG, msg)

    @JvmStatic fun d(subtag: String, msg: String) = Log.d(TAG, "$subtag: $msg")

    @JvmStatic fun d(msg: String) = Log.d(TAG, msg)

    @JvmStatic fun i(subtag: String, msg: String) = Log.i(TAG, "$subtag: $msg")

    @JvmStatic fun i(msg: String) = Log.i(TAG, msg)

    @JvmStatic fun w(subtag: String, msg: String) = Log.w(TAG, "$subtag: $msg")

    @JvmStatic fun w(msg: String) = Log.w(TAG, msg)

    @JvmStatic fun w(subtag: String, msg: String, tr: Throwable) = Log.w(TAG, "$subtag: $msg", tr)

    @JvmStatic fun wtf(subtag: String, msg: String) = Log.wtf(TAG, "$subtag: $msg")

    @JvmStatic fun wtf(msg: String) = Log.wtf(TAG, msg)

    @JvmStatic
    fun wtf(subtag: String, msg: String, tr: Throwable) = Log.wtf(TAG, "$subtag: $msg", tr)

    @JvmStatic fun e(subtag: String, msg: String) = Log.e(TAG, "$subtag: $msg")

    @JvmStatic fun e(msg: String) = Log.e(TAG, msg)

    @JvmStatic fun e(subtag: String, msg: String, tr: Throwable) = Log.e(TAG, "$subtag: $msg", tr)

    @JvmStatic
    fun timeToStringWithZone(timestamp: Long) =
        DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp))

    @JvmStatic
    fun address(address: String?): String {
        return when {
            address == null -> "[address is null]"
            address.length != 17 -> "[address invalid]"
            else -> "XX:XX:XX:XX:${address.takeLast(5)}"
        }
    }
}
