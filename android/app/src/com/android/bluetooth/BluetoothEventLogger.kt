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

package com.android.bluetooth

import android.util.Log
import java.util.ArrayDeque
import java.util.Queue

/** This class is to store logs for given size. */
class BluetoothEventLogger(private val size: Int, private val title: String) {
    private val events: Queue<String> = ArrayDeque(size)

    init {
        require(size > 0) { "Size must be > 0" }
    }

    /** Add the event record */
    @Synchronized
    fun add(msg: String) {
        if (events.size == size) {
            events.remove()
        }
        events.add("${Utils.getLocalTimeString()} $msg")
    }

    /** Add the event record */
    @Synchronized
    fun clear() {
        events.clear()
    }

    /** Add the event record and log message */
    @Synchronized
    fun logv(tag: String, msg: String) {
        add(msg)
        Log.v(tag, msg)
    }

    /** Add the event record and log debug message */
    @Synchronized
    fun logd(tag: String, msg: String) {
        add(msg)
        Log.d(tag, msg)
    }

    /** Add the event record and log info message */
    @Synchronized
    fun logi(tag: String, msg: String) {
        add(msg)
        Log.i(tag, msg)
    }

    /** Add the event record and log warning message */
    @Synchronized
    fun logw(tag: String, msg: String) {
        add(msg)
        Log.w(tag, msg)
    }

    /** Add the event record and log error message */
    @Synchronized
    fun loge(tag: String, msg: String) {
        add(msg)
        Log.e(tag, msg)
    }

    /** Dump all the events */
    @Synchronized
    fun dump(sb: StringBuilder) {
        sb.append("$title:\n")
        events.forEach { msg -> sb.append("  $msg\n") }
    }
}
