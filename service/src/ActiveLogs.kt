/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_DIED
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AUTO_ON
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH
import android.os.Binder
import androidx.annotation.VisibleForTesting
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__UNKNOWN
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import java.io.PrintWriter

private const val TAG = "ActiveLogs"
private const val DEFAULT_PACKAGE = "BluetoothSystemServer"

class ActiveLogs {
    private val activeLogs = ArrayDeque<ActiveLog>(MAX_ENTRIES_STORED)

    @JvmOverloads
    fun add(reason: Int, enable: Boolean, name: String = DEFAULT_PACKAGE, isBle: Boolean = false) {
        val last = activeLogs.lastOrNull()
        if (activeLogs.size == MAX_ENTRIES_STORED) {
            activeLogs.removeFirst()
        }
        val log = ActiveLog(Reason(reason), name, enable, isBle)
        Log.d(TAG, "$log")
        activeLogs.addLast(log)
        val state =
            if (enable) BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
            else BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED
        val lastState: Int
        val timeSinceLastChanged: Long
        if (last != null) {
            lastState =
                if (last.enable) BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
                else BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED
            timeSinceLastChanged = System.currentTimeMillis() - last.timestamp
        } else {
            lastState = BLUETOOTH_ENABLED_STATE_CHANGED__STATE__UNKNOWN
            timeSinceLastChanged = 0
        }

        BluetoothStatsLog.write_non_chained(
            BLUETOOTH_ENABLED_STATE_CHANGED,
            Binder.getCallingUid(),
            null,
            state,
            reason,
            name,
            lastState,
            timeSinceLastChanged,
        )
    }

    fun dump(writer: PrintWriter) {
        if (activeLogs.isEmpty()) {
            writer.println("Bluetooth never enabled!")
            return
        }

        writer.println("Enable log:")
        writer.println(
            activeLogs
                .toTable(
                    Column("TIMESTAMP", width = 18) { Log.timeToStringWithZone(it.timestamp) },
                    Column("ACTION", width = 10) { it.action },
                    Column("REASON", width = 20) { it.reason },
                    Column("PACKAGE") { it.packageName },
                )
                .indent("  ")
        )
    }

    companion object {
        @VisibleForTesting internal const val MAX_ENTRIES_STORED = 20
    }
}

@VisibleForTesting
internal class ActiveLog(
    internal val reason: Reason,
    internal val packageName: String,
    internal val enable: Boolean,
    private val isBle: Boolean,
) {
    val timestamp = System.currentTimeMillis()
    val action: String
        get() = (if (enable) "Enable" else "Disable") + (if (isBle) "Ble" else "")

    override fun toString() =
        Log.timeToStringWithZone(timestamp) +
            " \tPackage [$packageName] requested to [$action]. \tReason is $reason"
}

@JvmInline
internal value class Reason(val code: Int) {
    override fun toString() =
        when (code) {
            ENABLE_DISABLE_REASON_AIRPLANE_MODE -> "AIRPLANE_MODE"
            ENABLE_DISABLE_REASON_APPLICATION_DIED -> "APPLICATION_DIED"
            ENABLE_DISABLE_REASON_APPLICATION_REQUEST -> "APPLICATION_REQUEST"
            ENABLE_DISABLE_REASON_AUTO_ON -> "AUTO_ON"
            ENABLE_DISABLE_REASON_CRASH -> "CRASH"
            ENABLE_DISABLE_REASON_DISALLOWED -> "DISALLOWED"
            ENABLE_DISABLE_REASON_FACTORY_RESET -> "FACTORY_RESET"
            ENABLE_DISABLE_REASON_RESTARTED -> "RESTARTED"
            ENABLE_DISABLE_REASON_RESTORE_USER_SETTING -> "RESTORE_USER_SETTING"
            ENABLE_DISABLE_REASON_SATELLITE_MODE -> "SATELLITE MODE"
            ENABLE_DISABLE_REASON_START_ERROR -> "START_ERROR"
            ENABLE_DISABLE_REASON_SYSTEM_BOOT -> "SYSTEM_BOOT"
            ENABLE_DISABLE_REASON_USER_SWITCH -> "USER_SWITCH"
            else -> "UNKNOWN[$code]"
        }
}
