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

package com.android.server.bluetooth.test

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

    private lateinit var activeLogs: ActiveLogs

    @Before
    fun setUp() {
        Log.i("ActiveLogTest", "\t--> setup of ${testName.methodName}")
        activeLogs = ActiveLogs()
    }

    @Test
    fun dump_whenNoActiveLog_indicateNeverEnabled() {
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        activeLogs.dump(writer)
        assertThat(stringWriter.toString()).isEqualTo("Bluetooth never enabled!\n")
    }

    @Test
    fun dump_whenActiveLog_indicateAll() {
        val numberOfLogEntry = 3
        for (i in 1..numberOfLogEntry) {
            activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        }
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        activeLogs.dump(writer)
        assertThat(stringWriter.toString())
            .matches(
                "Enable log:\n" +
                    ".*\n" + // Header
                    ".*\n" + // Separator
                    "(.*\n){$numberOfLogEntry}"
            )
    }

    @Test
    fun dump_overflowQueue_indicateFirstEntries() {
        for (i in 1..ActiveLogs.MAX_ENTRIES_STORED * 2) {
            activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        }
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        activeLogs.dump(writer)
        assertThat(stringWriter.toString())
            .matches(
                "Enable log:\n" +
                    ".*\n" + // Header
                    ".*\n" + // Separator
                    "(.*\n){${ActiveLogs.MAX_ENTRIES_STORED}}"
            )
    }

    @Test
    fun dump_differentState_logsVariation() {
        activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, true, "Foo", true)
        activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, true)
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        activeLogs.dump(writer)
        assertThat(stringWriter.toString())
            .matches(
                "Enable log:\n" +
                    ".*\n" + // Header
                    ".*\n" + // Separator
                    ".*Disable.*\n" +
                    ".*EnableBle.*\n" +
                    ".*Enable.*\n"
            )
    }

    @Test
    fun dump_allReason_stringIsKnown() {
        activeLogs.add(ENABLE_DISABLE_REASON_AIRPLANE_MODE, false)
        activeLogs.add(ENABLE_DISABLE_REASON_APPLICATION_DIED, false)
        activeLogs.add(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, false)
        activeLogs.add(ENABLE_DISABLE_REASON_AUTO_ON, false)
        activeLogs.add(ENABLE_DISABLE_REASON_CRASH, false)
        activeLogs.add(ENABLE_DISABLE_REASON_DISALLOWED, false)
        activeLogs.add(ENABLE_DISABLE_REASON_FACTORY_RESET, false)
        activeLogs.add(ENABLE_DISABLE_REASON_RESTARTED, false)
        activeLogs.add(ENABLE_DISABLE_REASON_RESTORE_USER_SETTING, false)
        activeLogs.add(ENABLE_DISABLE_REASON_SATELLITE_MODE, false)
        activeLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false)
        activeLogs.add(ENABLE_DISABLE_REASON_SYSTEM_BOOT, false)
        activeLogs.add(ENABLE_DISABLE_REASON_USER_SWITCH, false)
        activeLogs.add(42, false)
        val stringWriter = StringWriter()
        val writer = PrintWriter(stringWriter)

        activeLogs.dump(writer)
        assertThat(stringWriter.toString())
            .matches(
                "Enable log:\n" +
                    ".*\n" + // Header
                    ".*\n" + // Separator
                    ".*AIRPLANE_MODE.*\n" +
                    ".*APPLICATION_DIED.*\n" +
                    ".*APPLICATION_REQUEST.*\n" +
                    ".*AUTO_ON.*\n" +
                    ".*CRASH.*\n" +
                    ".*DISALLOWED.*\n" +
                    ".*FACTORY_RESET.*\n" +
                    ".*RESTARTED.*\n" +
                    ".*RESTORE_USER_SETTING.*\n" +
                    ".*SATELLITE MODE.*\n" +
                    ".*START_ERROR.*\n" +
                    ".*SYSTEM_BOOT.*\n" +
                    ".*USER_SWITCH.*\n" +
                    ".*UNKNOWN\\[\\d+\\].*\n"
            )
    }
}
