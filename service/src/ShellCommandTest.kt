/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.bluetooth.IBluetoothManager
import android.bluetooth.State
import android.os.Binder
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.server.bluetooth.BluetoothManagerServiceApi
import com.android.server.bluetooth.PermissionChecker
import com.android.server.bluetooth.ServiceMessenger
import com.android.server.bluetooth.ShellCommand
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.shadows.ShadowBinder

@SmallTest
@RunWith(ParameterizedRobolectricTestRunner::class)
class ShellCommandTest(private val flags: FlagsWrapper, private val returnValue: Boolean) {
    @get:Rule val mSetFlagsRule: SetFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val testName = TestName()

    private val mockApi: BluetoothManagerServiceApi = mock()
    private val mockPermissionChecker: PermissionChecker = mock()
    private val mockBinder: IBluetoothManager.Stub = mock()

    private val testWaitForState: (Int) -> Boolean = {
        waitForStateCalledWith = it
        returnValue
    }
    private var waitForStateCalledWith: Int? = null

    private lateinit var shellCommand: ShellCommand
    private lateinit var outPipe: Array<ParcelFileDescriptor>
    private lateinit var handlerThread: HandlerThread

    @Before
    fun setUp() {
        waitForStateCalledWith = null
        outPipe = ParcelFileDescriptor.createPipe()
        handlerThread = HandlerThread("ShellCommandTestHandler").apply { start() }

        val serviceMessenger =
            ServiceMessenger(handlerThread.looper, mockPermissionChecker, mockApi)
        shellCommand = ShellCommand(mockBinder, serviceMessenger.messenger, testWaitForState)

        shellCommand.init(
            Binder(),
            null,
            outPipe[1].fileDescriptor,
            outPipe[1].fileDescriptor,
            arrayOf(),
            -1,
        )

        // Mock API and Binder calls to return the parameterized value
        doReturn(returnValue).whenever(mockApi).enable(any(), any())
        doReturn(returnValue).whenever(mockApi).enableBle(any(), any())
        doReturn(returnValue).whenever(mockApi).enableNoAutoConnect(any())
        doReturn(returnValue).whenever(mockApi).disable(any(), any())
        doReturn(returnValue).whenever(mockApi).disableBle(any(), any())
        doReturn(returnValue).whenever(mockApi).factoryReset(any())
        doReturn(returnValue).whenever(mockBinder).enable(any())
        doReturn(returnValue).whenever(mockBinder).disable(any(), any())
        doReturn(returnValue).whenever(mockBinder).enableBle(any(), any())
        doReturn(returnValue).whenever(mockBinder).disableBle(any(), any())
        doReturn(returnValue).whenever(mockBinder).factoryReset(any())
    }

    @After
    fun tearDown() {
        outPipe.forEach { it.close() }
        handlerThread.quitSafely()
    }

    @Test
    fun onHelp_doNotCrash() {
        shellCommand.onHelp()
    }

    @Test
    fun onCommand_null_doNotCrash() {
        assertThat(shellCommand.onCommand(null)).isEqualTo(-1)
    }

    @Test
    fun onCommand_unknown_doNotCrash() {
        assertThat(shellCommand.onCommand("not a known command")).isEqualTo(-1)
    }

    @Test
    fun onCommand_enable() {
        assertThat(shellCommand.onCommand("enable")).isEqualTo(if (returnValue) 0 else -1)
        if (Flags.systemServerMessenger()) {
            verify(mockApi).enable(any(), any())
        } else {
            verify(mockBinder).enable(any())
        }
    }

    @Test
    fun onCommand_enableBle() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        assertThat(shellCommand.onCommand("enableBle")).isEqualTo(if (returnValue) 0 else -1)
        if (Flags.systemServerMessenger()) {
            verify(mockApi).enableBle(any(), eq(mockBinder))
        } else {
            verify(mockBinder).enableBle(any(), eq(mockBinder))
        }
    }

    @Test
    fun onCommand_disable() {
        assertThat(shellCommand.onCommand("disable")).isEqualTo(if (returnValue) 0 else -1)
        if (Flags.systemServerMessenger()) {
            verify(mockApi).disable(any(), eq(true))
        } else {
            verify(mockBinder).disable(any(), eq(true))
        }
    }

    @Test
    fun onCommand_disableBle() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        assertThat(shellCommand.onCommand("disableBle")).isEqualTo(if (returnValue) 0 else -1)
        if (Flags.systemServerMessenger()) {
            verify(mockApi).disableBle(any(), eq(mockBinder))
        } else {
            verify(mockBinder).disableBle(any(), eq(mockBinder))
        }
    }

    @Test
    fun onCommand_withoutPrivilegedShell_throwsSecurityException() {
        val privilegedCommands = listOf("enableBle", "disableBle", "factoryReset")
        for (cmd in privilegedCommands) {
            assertThrows(SecurityException::class.java) { shellCommand.onCommand(cmd) }
        }
    }

    @Test
    fun onCommand_factoryReset() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)

        assertThat(shellCommand.onCommand("factoryReset")).isEqualTo(if (returnValue) 0 else -1)
        if (Flags.systemServerMessenger()) {
            verify(mockApi).factoryReset(any())
        } else {
            verify(mockBinder).factoryReset(any())
        }
    }

    @Test
    fun onCommand_waitForState_on() {
        assertThat(shellCommand.onCommand("wait-for-state:STATE_ON"))
            .isEqualTo(if (returnValue) 0 else -1)
        assertThat(waitForStateCalledWith).isEqualTo(State.ON)
    }

    @Test
    fun onCommand_waitForState_off() {
        assertThat(shellCommand.onCommand("wait-for-state:STATE_OFF"))
            .isEqualTo(if (returnValue) 0 else -1)
        assertThat(waitForStateCalledWith).isEqualTo(State.OFF)
    }

    @Test
    fun onCommand_waitForState_invalidState() {
        assertThat(shellCommand.onCommand("wait-for-state:STATE_INVALID")).isEqualTo(-1)
        assertThat(waitForStateCalledWith).isNull()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}|{1}")
        fun getParams() =
            FlagsWrapper.progressionOf(Flags.FLAG_SYSTEM_SERVER_MESSENGER).flatMap { flag ->
                listOf(arrayOf(flag, true), arrayOf(flag, false))
            }
    }
}
