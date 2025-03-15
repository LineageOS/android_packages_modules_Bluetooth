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
package com.android.server.bluetooth.test

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
import com.android.server.bluetooth.ActiveLogs
import com.android.server.bluetooth.Log
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ActiveLogTest {
    @get:Rule val testName = TestName()

    @Before
    fun setUp() {
        Log.i("ActiveLogTest", "\t--> setup of " + testName.getMethodName())
        ActiveLogs.activeLogs.clear()
    }

    @Test
    fun dump_whenNoActiveLog_indicateNeverEnabled() {
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        ActiveLogs.dump(writer)

        assertThat(stringWriter.toString()).isEqualTo("Bluetooth never enabled!\n")
    }

    @Test
    fun dump_whenActiveLog_indicateAll() {
        val numberOfLogEntry = 3
        for (i in 1..numberOfLogEntry) {
            ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        }
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        ActiveLogs.dump(writer)

        assertThat(stringWriter.toString()).matches("Enable log:\n(.*\n){$numberOfLogEntry}")
    }

    @Test
    fun dump_overflowQueue_indicateFirstEntries() {
        for (i in 1..ActiveLogs.MAX_ENTRIES_STORED * 2) {
            ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        }
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        ActiveLogs.dump(writer)

        assertThat(stringWriter.toString())
            .matches("Enable log:\n(.*\n){${ActiveLogs.MAX_ENTRIES_STORED}}")
    }

    @Test
    fun dump_differentState_logsVariation() {
        ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, true, "Foo", true)
        ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, true)
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        ActiveLogs.dump(writer)

        assertThat(stringWriter.toString())
            .matches("Enable log:\n.*Disable.*\n.*EnableBle.*\n.*Enable.*\n")
    }

    @Test
    fun dump_allReason_stringIsKnown() {
        ActiveLogs.add(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_AIRPLANE_MODE, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_DISALLOWED, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_RESTARTED, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_SYSTEM_BOOT, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_CRASH, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_USER_SWITCH, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_RESTORE_USER_SETTING, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_FACTORY_RESET, false)
        ActiveLogs.add(ENABLE_DISABLE_REASON_SATELLITE_MODE, false)
        ActiveLogs.add(42, false)
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        ActiveLogs.dump(writer)
        assertThat(stringWriter.toString())
            .matches(
                "Enable log:\n" +
                    ".*APPLICATION_REQUEST\n" +
                    ".*AIRPLANE_MODE\n" +
                    ".*DISALLOWED\n" +
                    ".*RESTARTED\n" +
                    ".*START_ERROR\n" +
                    ".*SYSTEM_BOOT\n" +
                    ".*CRASH\n" +
                    ".*USER_SWITCH\n" +
                    ".*RESTORE_USER_SETTING\n" +
                    ".*FACTORY_RESET\n" +
                    ".*SATELLITE MODE\n" +
                    ".*UNKNOWN\\[\\d+\\]\n"
            )
    }
}
