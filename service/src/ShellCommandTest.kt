/*
 * Copyright 2023 The Android Open Source Project
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

import android.bluetooth.IBluetoothManager
import android.bluetooth.IBluetoothManagerCallback
import android.bluetooth.State
import android.content.AttributionSource
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

@SmallTest
@RunWith(RobolectricTestRunner::class)
class ShellCommandTest {
    private lateinit var testBinder: FakeBinder
    private val testWaitForState: (Int) -> Boolean = {
        waitForStateCalledWith = it
        waitForStateReturnValue
    }
    private var waitForStateCalledWith: Int? = null
    private var waitForStateReturnValue: Boolean = true

    private lateinit var shellCommand: ShellCommand
    private lateinit var outPipe: Array<ParcelFileDescriptor>

    @Before
    fun setUp() {
        testBinder = FakeBinder()
        waitForStateCalledWith = null
        waitForStateReturnValue = true
        outPipe = ParcelFileDescriptor.createPipe()

        shellCommand = ShellCommand(testBinder, testWaitForState)

        shellCommand.init(
            Binder(),
            null,
            outPipe[1].fileDescriptor,
            outPipe[1].fileDescriptor,
            arrayOf(),
            -1,
        )
    }

    @After
    fun tearDown() {
        outPipe.forEach { it.close() }
    }

    @Test
    fun onCommand_enable() {
        testBinder.returnValue = true
        assertThat(shellCommand.onCommand("enable")).isEqualTo(0)
        assertThat(testBinder.enableCalled).isTrue()
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
    fun onCommand_enable_returnsError() {
        testBinder.returnValue = false
        assertThat(shellCommand.onCommand("enable")).isEqualTo(-1)
        assertThat(testBinder.enableCalled).isTrue()
    }

    @Test
    fun onCommand_disable() {
        testBinder.returnValue = true
        assertThat(shellCommand.onCommand("disable")).isEqualTo(0)
        assertThat(testBinder.disableCalledWithPersist).isTrue()
    }

    @Test
    fun onCommand_disable_returnsError() {
        testBinder.returnValue = false
        assertThat(shellCommand.onCommand("disable")).isEqualTo(-1)
        assertThat(testBinder.disableCalledWithPersist).isTrue()
    }

    @Test
    fun onCommand_withoutPrivilegedShell_throwsSecurityException() {
        val privilegedCommands = listOf("enableBle", "disableBle", "factoryReset")
        for (cmd in privilegedCommands) {
            assertThrows(SecurityException::class.java) { shellCommand.onCommand(cmd) }
        }
    }

    @Test
    fun onCommand_privileged_asRoot() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        testBinder.returnValue = true

        assertThat(shellCommand.onCommand("enableBle")).isEqualTo(0)
        assertThat(testBinder.enableBleCalledWithBinder).isSameInstanceAs(testBinder)

        assertThat(shellCommand.onCommand("disableBle")).isEqualTo(0)
        assertThat(testBinder.disableBleCalledWithBinder).isSameInstanceAs(testBinder)

        assertThat(shellCommand.onCommand("factoryReset")).isEqualTo(0)
        assertThat(testBinder.factoryResetCalled).isTrue()
    }

    @Test
    fun onCommand_waitForState_on() {
        waitForStateReturnValue = true
        assertThat(shellCommand.onCommand("wait-for-state:STATE_ON")).isEqualTo(0)
        assertThat(waitForStateCalledWith).isEqualTo(State.ON)
    }

    @Test
    fun onCommand_waitForState_off() {
        waitForStateReturnValue = true
        assertThat(shellCommand.onCommand("wait-for-state:STATE_OFF")).isEqualTo(0)
        assertThat(waitForStateCalledWith).isEqualTo(State.OFF)
    }

    @Test
    fun onCommand_waitForState_returnsError() {
        waitForStateReturnValue = false
        assertThat(shellCommand.onCommand("wait-for-state:STATE_ON")).isEqualTo(-1)
        assertThat(waitForStateCalledWith).isEqualTo(State.ON)
    }

    @Test
    fun onCommand_waitForState_invalidState() {
        assertThat(shellCommand.onCommand("wait-for-state:STATE_INVALID")).isEqualTo(-1)
        assertThat(waitForStateCalledWith).isNull()
    }

    class FakeBinder : IBluetoothManager.Stub() {
        var returnValue: Boolean = true

        var enableCalled: Boolean = false
        var disableCalledWithPersist: Boolean? = null
        var enableBleCalledWithBinder: IBinder? = null
        var disableBleCalledWithBinder: IBinder? = null
        var factoryResetCalled: Boolean = false

        override fun enable(source: AttributionSource): Boolean {
            enableCalled = true
            return returnValue
        }

        override fun disable(source: AttributionSource, persist: Boolean): Boolean {
            disableCalledWithPersist = persist
            return returnValue
        }

        override fun enableBle(source: AttributionSource, token: IBinder): Boolean {
            enableBleCalledWithBinder = token
            return returnValue
        }

        override fun disableBle(source: AttributionSource, token: IBinder): Boolean {
            disableBleCalledWithBinder = token
            return returnValue
        }

        override fun factoryReset(source: AttributionSource): Boolean {
            factoryResetCalled = true
            return returnValue
        }

        // Other method that we do not care about
        override fun registerAdapter(callback: IBluetoothManagerCallback) = null

        override fun unregisterAdapter(callback: IBluetoothManagerCallback) {}

        override fun getState() = State.OFF

        override fun getAddress(source: AttributionSource) = null

        override fun getName(source: AttributionSource) = null

        override fun isHearingAidProfileSupported() = false

        override fun isBleScanAvailable() = false

        override fun setBtHciSnoopLogMode(mode: Int) = 0

        override fun getBtHciSnoopLogMode() = 0

        override fun isAutoOnSupported() = false

        override fun isAutoOnEnabled() = false

        override fun setAutoOnEnabled(status: Boolean) {}

        override fun enableNoAutoConnect(source: AttributionSource) = false
    }
}
