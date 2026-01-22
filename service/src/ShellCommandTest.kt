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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.app.Application
import android.bluetooth.State
import android.content.Context
import android.os.Binder
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.permission.PermissionManager
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.server.bluetooth.BluetoothManagerServiceApi
import com.android.server.bluetooth.ServerBinder
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder

@SmallTest
@RunWith(ParameterizedRobolectricTestRunner::class)
class ShellCommandTest(flags: FlagsWrapper, private val returnValue: Boolean) {
    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val testName = TestName()

    private val api: BluetoothManagerServiceApi = mock()
    private val permissionManager: PermissionManager = mock()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val looper = HandlerThread("ServerBinderTest").apply { start() }.looper
    private val testWaitForState: (Int) -> Boolean = {
        waitForStateCalledWith = it
        returnValue
    }
    private var waitForStateCalledWith: Int? = null

    private lateinit var shellCommand: ShellCommand
    private lateinit var outPipe: Array<ParcelFileDescriptor>
    private lateinit var binder: ServerBinder

    @Before
    fun setUp() {
        BluetoothComponentTest.setup()
        BluetoothRestrictionTest.setup()

        doReturn(PermissionManager.PERMISSION_HARD_DENIED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())

        binder = ServerBinder(looper, api, context, permissionManager)

        waitForStateCalledWith = null
        outPipe = ParcelFileDescriptor.createPipe()

        shellCommand = ShellCommand(binder, testWaitForState)

        shellCommand.init(
            Binder(),
            null,
            outPipe[1].fileDescriptor,
            outPipe[1].fileDescriptor,
            arrayOf(),
            -1,
        )

        // Mock API and Binder calls to return the parameterized value
        doReturn(returnValue).whenever(api).enable(any(), any())
        doReturn(returnValue).whenever(api).enableBle(any(), any())
        doReturn(returnValue).whenever(api).enableNoAutoConnect(any())
        doReturn(returnValue).whenever(api).disable(any(), any())
        doReturn(returnValue).whenever(api).disableBle(any(), any())
        doReturn(returnValue).whenever(api).factoryReset()
    }

    @After
    fun tearDown() {
        outPipe.forEach { it.close() }
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
        assertThat(
                binder.handleShellCommand(
                    outPipe[1],
                    outPipe[1],
                    outPipe[1],
                    arrayOf("not a known command"),
                )
            )
            .isEqualTo(-1)
        assertThat(shellCommand.onCommand("not a known command")).isEqualTo(-1)
    }

    @Test
    fun onCommand_enable() {
        assertThrows(SecurityException::class.java) { shellCommand.onCommand("enable") }
        grantConnect()
        assertThat(shellCommand.onCommand("enable")).isEqualTo(if (returnValue) 0 else -1)
        verify(api).enable(any(), any())
    }

    @Test
    fun onCommand_enableBle() {
        assertThrows(SecurityException::class.java) { shellCommand.onCommand("enableBle") }
        grantConnect()
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        assertThat(shellCommand.onCommand("enableBle")).isEqualTo(if (returnValue) 0 else -1)
        verify(api).enableBle(any(), eq(binder))
    }

    @Test
    fun onCommand_disable() {
        assertThrows(SecurityException::class.java) { shellCommand.onCommand("disable") }
        grantConnect()
        assertThat(shellCommand.onCommand("disable")).isEqualTo(if (returnValue) 0 else -1)
        verify(api).disable(any(), eq(true))
    }

    @Test
    fun onCommand_disableBle() {
        assertThrows(SecurityException::class.java) { shellCommand.onCommand("disableBle") }
        grantConnect()
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        assertThat(shellCommand.onCommand("disableBle")).isEqualTo(if (returnValue) 0 else -1)
        verify(api).disableBle(any(), eq(binder))
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
        assertThrows(SecurityException::class.java) { shellCommand.onCommand("factoryReset") }
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        grantConnect()
        grantPrivileged()

        assertThat(shellCommand.onCommand("factoryReset")).isEqualTo(if (returnValue) 0 else -1)
        verify(api).factoryReset()
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

    private fun grantConnect() {
        shadowOf(application).grantPermissions(BLUETOOTH_CONNECT)
        doReturn(PermissionManager.PERMISSION_GRANTED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())
    }

    private fun grantPrivileged() = shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)

    companion object {
        @JvmStatic
        @Parameters(name = "{0}|returnValue={1}")
        fun getParams() =
            FlagsWrapper.progressionOf().flatMap { flag ->
                listOf(arrayOf(flag, true), arrayOf(flag, false))
            }
    }
}
