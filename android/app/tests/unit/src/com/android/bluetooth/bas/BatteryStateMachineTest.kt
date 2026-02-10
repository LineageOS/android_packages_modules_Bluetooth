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

package com.android.bluetooth.bas

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BATTERY_LEVEL_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_CONNECT
import com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_CONNECTION_STATE_CHANGED
import com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_DISCONNECT
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [BatteryStateMachine]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BatteryStateMachineTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var service: BatteryService

    private val device = getTestDevice(93)

    private lateinit var looper: TestLooper
    private lateinit var batteryStateMachine: FakeBatteryStateMachine

    @Before
    fun setUp() {
        doReturn(true).whenever(service).canConnect(any())

        looper = TestLooper()

        batteryStateMachine = FakeBatteryStateMachine(service, device, looper.looper)
        batteryStateMachine.shouldAllowGatt = true
    }

    @Test
    fun initialState_isDisconnected() {
        assertThat(batteryStateMachine.connectionState).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun connect_whenNotAllowed_stayDisconnected() {
        doReturn(false).whenever(service).canConnect(any())

        sendAndDispatchMessage(MESSAGE_CONNECT)

        verify(service, never()).handleConnectionStateChanged(any(), any<Int>(), any<Int>())
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnected::class.java)
    }

    @Test
    fun connect_whenGattCanNotConnect_stayDisconnected() {
        batteryStateMachine.shouldAllowGatt = false

        sendAndDispatchMessage(MESSAGE_CONNECT)

        verify(service, never()).handleConnectionStateChanged(any(), any<Int>(), any<Int>())
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnected::class.java)
    }

    @Test
    fun connect_successCase_isConnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING))
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connecting::class.java)

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_CONNECTED))
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)
    }

    @Test
    fun disconnect_whenConnecting_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING))

        sendAndDispatchMessage(MESSAGE_DISCONNECT)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_DISCONNECTED))
    }

    private fun goToStateConnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT)
        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)
    }

    @Test
    fun connect_whenConnected_doNothing() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_CONNECT)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)
    }

    @Test
    fun disconnect_whenConnected_isDisconnected() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_DISCONNECT)
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnecting::class.java)

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnected::class.java)
    }

    @Test
    fun disconnectWithTimeout_whenConnected_isDisconnected() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_DISCONNECT)
        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnecting::class.java)

        looper.moveTimeForward(BatteryStateMachine.CONNECT_TIMEOUT.toMillis())
        looper.dispatchAll()

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnected::class.java)
    }

    @Test
    fun disconnectNotification_whenConnected_isDisconnected() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Disconnected::class.java)
    }

    @Test
    fun connectNotification_whenConnected_stayConnected() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)
    }

    @Test
    fun unknownStateNotification_whenConnected_stayConnected() {
        goToStateConnected()

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, -1)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)
    }

    @Test
    fun unknownMessage_whenConnected_stayConnected() {
        goToStateConnected()

        sendAndDispatchMessage(12312)

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)
    }

    @Test
    fun testBatteryLevelChanged() {
        batteryStateMachine.updateBatteryLevel(byteArrayOf(0x30.toByte()))

        verify(service)
            .handleBatteryChanged(ArgumentMatchers.any(BluetoothDevice::class.java), eq(0x30))
    }

    @Test
    fun testEmptyBatteryLevelIgnored() {
        batteryStateMachine.updateBatteryLevel(byteArrayOf())

        verify(service, never()).handleBatteryChanged(any(), any<Int>())
    }

    @Test
    fun testDisconnectResetBatteryLevel() {
        sendAndDispatchMessage(MESSAGE_CONNECT)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING))

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connecting::class.java)

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)

        verify(service)
            .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_CONNECTED))

        assertThat(batteryStateMachine.currentState)
            .isInstanceOf(BatteryStateMachine.Connected::class.java)

        batteryStateMachine.updateBatteryLevel(byteArrayOf(0x30.toByte()))
        verify(service).handleBatteryChanged(any(), eq(0x30))

        sendAndDispatchMessage(MESSAGE_DISCONNECT)
        verify(service).handleBatteryChanged(any(), eq(BATTERY_LEVEL_UNKNOWN))
    }

    private fun sendAndDispatchMessage(what: Int, arg1: Int) {
        batteryStateMachine.sendMessage(what, arg1)
        looper.dispatchAll()
    }

    private fun sendAndDispatchMessage(what: Int) {
        batteryStateMachine.sendMessage(what)
        looper.dispatchAll()
    }

    // Simulates GATT connection for testing.
    private class FakeBatteryStateMachine(
        service: BatteryService,
        device: BluetoothDevice,
        looper: Looper,
    ) : BatteryStateMachine(service, device, looper) {
        var shouldAllowGatt = true

        override fun connectGatt(): Boolean {
            return shouldAllowGatt
        }

        override fun disconnectGatt() {
            // Do nothing as there is no BluetoothGatt available during test
        }

        override fun discoverServicesGatt() {
            // Do nothing as there is no BluetoothGatt available during test
        }
    }
}
