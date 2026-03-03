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

package com.android.bluetooth.pbap

import android.app.NotificationManager
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothSocket
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [PbapStateMachine]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class PbapStateMachineTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var bluetoothPbapService: BluetoothPbapService
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var socket: BluetoothSocket
    @Mock private lateinit var inputStream: InputStream

    private val testNotificationId = 1000000

    private val device = getTestDevice(36)

    private lateinit var handler: Handler
    private lateinit var looper: TestLooper
    private lateinit var stateMachine: PbapStateMachine

    @Before
    fun setUp() {
        doReturn(inputStream).whenever(socket).inputStream

        looper = TestLooper()
        handler = Handler(looper.looper)

        stateMachine =
            PbapStateMachine(
                adapterService,
                bluetoothPbapService,
                notificationManager,
                looper.looper,
                device,
                socket,
                handler,
                testNotificationId,
            )
    }

    /** Test that initial state is WaitingForAuth */
    @Test
    fun initialState_isConnecting() {
        assertThat(stateMachine.connectionState).isEqualTo(STATE_CONNECTING)
        assertThat(stateMachine.currentState)
            .isInstanceOf(PbapStateMachine.WaitingForAuth::class.java)
    }

    /** Test state transition from WaitingForAuth to Finished when the user rejected */
    @Test
    fun testStateTransition_WaitingForAuthToFinished() {
        sendAndDispatchMessage(PbapStateMachine.REJECTED)

        assertThat(stateMachine.connectionState).isEqualTo(STATE_DISCONNECTED)
        assertThat(stateMachine.currentState).isInstanceOf(PbapStateMachine.Finished::class.java)
    }

    /** Test state transition from WaitingForAuth to Finished when the user rejected */
    @Test
    fun testStateTransition_WaitingForAuthToConnected() {
        sendAndDispatchMessage(PbapStateMachine.AUTHORIZED)

        assertThat(stateMachine.connectionState).isEqualTo(STATE_CONNECTED)
        assertThat(stateMachine.currentState).isInstanceOf(PbapStateMachine.Connected::class.java)
    }

    /** Test state transition from Connected to Finished when the OBEX server is done */
    @Test
    fun testStateTransition_ConnectedToFinished() {
        sendAndDispatchMessage(PbapStateMachine.AUTHORIZED)

        assertThat(stateMachine.connectionState).isEqualTo(STATE_CONNECTED)
        assertThat(stateMachine.currentState).isInstanceOf(PbapStateMachine.Connected::class.java)

        sendAndDispatchMessage(PbapStateMachine.DISCONNECT)

        assertThat(stateMachine.connectionState).isEqualTo(STATE_DISCONNECTED)
        assertThat(stateMachine.currentState).isInstanceOf(PbapStateMachine.Finished::class.java)
    }

    private fun sendAndDispatchMessage(what: Int) {
        stateMachine.sendMessage(what)
        looper.dispatchAll()
    }
}
