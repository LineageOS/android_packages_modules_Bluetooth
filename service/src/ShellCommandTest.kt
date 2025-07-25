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

package com.android.server.bluetooth

import android.bluetooth.IBluetoothManager
import android.bluetooth.IBluetoothManagerCallback
import android.bluetooth.State
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.shadows.ShadowBinder

@SmallTest
@RunWith(ParameterizedRobolectricTestRunner::class)
class ShellCommandTest(private val flags: FlagsWrapper) {
    @get:Rule val mSetFlagsRule: SetFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val testName = TestName()

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

        shellCommand = ShellCommand(testBinder, testBinder.messenger, testWaitForState)

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
        testBinder.cleanup()
    }

    @Test
    fun onCommand_enable() {
        testBinder.returnValue = true
        assertThat(shellCommand.onCommand("enable")).isEqualTo(0)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.enableMessageReceived).isTrue()
            assertThat(testBinder.enableCalled).isFalse()
        } else {
            assertThat(testBinder.enableMessageReceived).isFalse()
            assertThat(testBinder.enableCalled).isTrue()
        }
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
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.enableMessageReceived).isTrue()
            assertThat(testBinder.enableCalled).isFalse()
        } else {
            assertThat(testBinder.enableMessageReceived).isFalse()
            assertThat(testBinder.enableCalled).isTrue()
        }
    }

    @Test
    fun onCommand_disable() {
        testBinder.returnValue = true
        assertThat(shellCommand.onCommand("disable")).isEqualTo(0)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.disableMessageReceived).isTrue()
            assertThat(testBinder.disableCalledWithPersist).isNull()
        } else {
            assertThat(testBinder.disableMessageReceived).isFalse()
            assertThat(testBinder.disableCalledWithPersist).isTrue()
        }
    }

    @Test
    fun onCommand_disable_returnsError() {
        testBinder.returnValue = false
        assertThat(shellCommand.onCommand("disable")).isEqualTo(-1)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.disableMessageReceived).isTrue()
            assertThat(testBinder.disableCalledWithPersist).isNull()
        } else {
            assertThat(testBinder.disableMessageReceived).isFalse()
            assertThat(testBinder.disableCalledWithPersist).isTrue()
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
    fun onCommand_privileged_asRoot() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        testBinder.returnValue = true

        assertThat(shellCommand.onCommand("enableBle")).isEqualTo(0)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.enableBleMessageReceived).isTrue()
            assertThat(testBinder.enableBleCalledWithBinder).isNull()
        } else {
            assertThat(testBinder.enableBleMessageReceived).isFalse()
            assertThat(testBinder.enableBleCalledWithBinder).isSameInstanceAs(testBinder)
        }

        assertThat(shellCommand.onCommand("disableBle")).isEqualTo(0)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.disableBleMessageReceived).isTrue()
            assertThat(testBinder.disableBleCalledWithBinder).isNull()
        } else {
            assertThat(testBinder.disableBleMessageReceived).isFalse()
            assertThat(testBinder.disableBleCalledWithBinder).isSameInstanceAs(testBinder)
        }

        assertThat(shellCommand.onCommand("factoryReset")).isEqualTo(0)
        if (Flags.systemServerMessenger()) {
            assertThat(testBinder.factoryResetMessageReceived).isTrue()
            assertThat(testBinder.factoryResetCalled).isFalse()
        } else {
            assertThat(testBinder.factoryResetMessageReceived).isFalse()
            assertThat(testBinder.factoryResetCalled).isTrue()
        }
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

        var enableMessageReceived = false
        var disableMessageReceived = false
        var enableBleMessageReceived = false
        var disableBleMessageReceived = false
        var factoryResetMessageReceived = false

        private val handlerThread = HandlerThread("FakeBinderHandler").apply { start() }

        private val handler =
            object : Handler(handlerThread.looper) {
                override fun handleMessage(msg: Message) {
                    val reply = Message.obtain()
                    try {
                        when (val received = msg.obj) {
                            is SystemServiceMessage.Enable -> {
                                if (received.bleToken != null) {
                                    enableBleMessageReceived = true
                                } else {
                                    enableMessageReceived = true
                                }
                                reply.obj =
                                    SystemServiceMessage.Enable.Reply().apply {
                                        value = returnValue
                                    }
                            }
                            is SystemServiceMessage.Disable -> {
                                if (received.bleToken != null) {
                                    disableBleMessageReceived = true
                                } else {
                                    disableMessageReceived = true
                                }
                                reply.obj =
                                    SystemServiceMessage.Disable.Reply().apply {
                                        value = returnValue
                                    }
                            }
                            is SystemServiceMessage.FactoryReset -> {
                                factoryResetMessageReceived = true
                                reply.obj =
                                    SystemServiceMessage.FactoryReset.Reply().apply {
                                        value = returnValue
                                    }
                            }
                            else -> {
                                super.handleMessage(msg)
                                return
                            }
                        }
                    } catch (e: RuntimeException) {
                        reply.data = Bundle().apply { putSerializable("exception", e) }
                    }

                    try {
                        msg.replyTo?.send(reply)
                    } catch (e: RemoteException) {
                        // Ignore
                    }
                }
            }

        val messenger = Messenger(handler)

        fun cleanup() {
            handlerThread.quitSafely()
        }

        override fun getServiceMessenger() = if (Flags.systemServerMessenger()) messenger else null

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

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() = FlagsWrapper.progressionOf(Flags.FLAG_SYSTEM_SERVER_MESSENGER)
    }
}
