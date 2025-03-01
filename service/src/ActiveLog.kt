/*
 * Copyright 2024 The Android Open Source Project
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

// @file:JvmName("ActiveLogs")

package com.android.server.bluetooth

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST
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
import android.util.proto.ProtoOutputStream
import androidx.annotation.VisibleForTesting
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
import com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__UNKNOWN
import com.android.server.BluetoothManagerServiceDumpProto as BtProto
import java.io.PrintWriter

private const val TAG = "ActiveLogs"

object ActiveLogs {
    @VisibleForTesting internal const val MAX_ENTRIES_STORED = 20
    @VisibleForTesting
    internal val activeLogs: ArrayDeque<ActiveLog> = ArrayDeque(MAX_ENTRIES_STORED)

    @JvmStatic
    fun dump(writer: PrintWriter) {
        if (activeLogs.isEmpty()) {
            writer.println("Bluetooth never enabled!")
        } else {
            writer.println("Enable log:")
            activeLogs.forEach { writer.println("  $it") }
        }
    }

    @JvmStatic
    fun dumpProto(proto: ProtoOutputStream) {
        val token = proto.start(BtProto.ACTIVE_LOGS)
        activeLogs.forEach { it.dump(proto) }
        proto.end(token)
    }

    @JvmStatic
    fun add(reason: Int, enable: Boolean) {
        add(reason, enable, "BluetoothSystemServer", false)
    }

    @JvmStatic
    fun add(reason: Int, enable: Boolean, packageName: String, isBle: Boolean) {
        val last = activeLogs.lastOrNull()
        if (activeLogs.size == MAX_ENTRIES_STORED) {
            activeLogs.removeFirst()
        }
        activeLogs.addLast(ActiveLog(reason, packageName, enable, isBle))
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
            packageName,
            lastState,
            timeSinceLastChanged,
        )
    }
}

@VisibleForTesting
internal class ActiveLog(
    private val reason: Int,
    private val packageName: String,
    val enable: Boolean,
    private val isBle: Boolean,
) {
    val timestamp = System.currentTimeMillis()

    init {
        Log.d(TAG, this.toString())
    }

    override fun toString() =
        Log.timeToStringWithZone(timestamp) +
            " \tPackage [$packageName] requested to [" +
            (if (enable) "Enable" else "Disable") +
            (if (isBle) "Ble" else "") +
            "]. \tReason is " +
            getEnableDisableReasonString(reason)

    fun dump(proto: ProtoOutputStream) {
        proto.write(BtProto.ActiveLog.TIMESTAMP_MS, timestamp)
        proto.write(BtProto.ActiveLog.ENABLE, enable)
        proto.write(BtProto.ActiveLog.PACKAGE_NAME, packageName)
        proto.write(BtProto.ActiveLog.REASON, reason)
    }
}

private fun getEnableDisableReasonString(reason: Int): String {
    return when (reason) {
        ENABLE_DISABLE_REASON_APPLICATION_REQUEST -> "APPLICATION_REQUEST"
        ENABLE_DISABLE_REASON_AIRPLANE_MODE -> "AIRPLANE_MODE"
        ENABLE_DISABLE_REASON_DISALLOWED -> "DISALLOWED"
        ENABLE_DISABLE_REASON_RESTARTED -> "RESTARTED"
        ENABLE_DISABLE_REASON_START_ERROR -> "START_ERROR"
        ENABLE_DISABLE_REASON_SYSTEM_BOOT -> "SYSTEM_BOOT"
        ENABLE_DISABLE_REASON_CRASH -> "CRASH"
        ENABLE_DISABLE_REASON_USER_SWITCH -> "USER_SWITCH"
        ENABLE_DISABLE_REASON_RESTORE_USER_SETTING -> "RESTORE_USER_SETTING"
        ENABLE_DISABLE_REASON_FACTORY_RESET -> "FACTORY_RESET"
        ENABLE_DISABLE_REASON_SATELLITE_MODE -> "SATELLITE MODE"
        else -> "UNKNOWN[$reason]"
    }
}
