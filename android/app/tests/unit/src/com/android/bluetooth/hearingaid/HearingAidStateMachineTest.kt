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

package com.android.bluetooth.hearingaid

import android.bluetooth.BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED
import android.bluetooth.BluetoothProfile.EXTRA_STATE
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Intent
import android.os.Bundle
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.hearingaid.HearingAidStateMachine.MESSAGE_CONNECTION_STATE_CHANGED
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [HearingAidStateMachine]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HearingAidStateMachineTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var service: HearingAidService
    @Mock private lateinit var nativeInterface: HearingAidNativeInterface

    private val device = getTestDevice(0xDA)

    private lateinit var stateMachine: HearingAidStateMachine
    private lateinit var inOrder: InOrder
    private lateinit var looper: TestLooper

    @Before
    fun setUp() {
        inOrder = inOrder(service)
        looper = TestLooper()

        doReturn(true).whenever(service).okToConnect(any())
        doReturn(true).whenever(service).isConnectedPeerDevices(device)
        doReturn(true).whenever(nativeInterface).connectHearingAid(any())
        doReturn(true).whenever(nativeInterface).disconnectHearingAid(any())

        stateMachine = HearingAidStateMachine(service, device, nativeInterface, looper.looper)
        stateMachine.start()
    }

    @Test
    fun initialState_isDisconnected() {
        assertThat(stateMachine.connectionState).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun incomingConnect_whenNotOkToConnect_isRejected() {
        doReturn(false).whenever(service).okToConnect(any())
        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)
        looper.dispatchAll()

        verify(service, never()).sendBroadcastAsUser(any(), any(), any<String>(), any<Bundle>())
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Disconnected::class.java)
    }

    @Test
    fun incomingConnect_whenOkToConnect_isConnected() {
        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTING)
        looper.dispatchAll()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_CONNECTING),
        )

        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Connecting::class.java)

        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)
        looper.dispatchAll()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_CONNECTED),
        )
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Connected::class.java)
    }

    @Test
    fun outgoingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_CONNECT, device)

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_CONNECTING),
        )
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Connecting::class.java)

        looper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis())
        looper.dispatchAll()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
        )
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Disconnected::class.java)

        verify(nativeInterface).addToAcceptlist(eq(device))
    }

    @Test
    fun incomingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTING)
        looper.dispatchAll()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_CONNECTING),
        )
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Connecting::class.java)

        looper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis())
        looper.dispatchAll()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
        )
        assertThat(stateMachine.currentState)
            .isInstanceOf(HearingAidStateMachine.Disconnected::class.java)

        verify(nativeInterface).addToAcceptlist(eq(device))
    }

    @Test
    fun testDoQuit_inConnectedState_broadcastsDisconnectedIntent() {
        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTING)
        looper.dispatchAll()
        stateMachine.sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED)
        looper.dispatchAll()

        stateMachine.doQuit()

        verifyIntentSent(
            hasAction(ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
        )
    }

    private fun sendAndDispatchMessage(what: Int, obj: Any?) {
        stateMachine.sendMessage(what, obj)
        looper.dispatchAll()
    }

    @SafeVarargs
    private fun verifyIntentSent(vararg matchers: Matcher<Intent?>?) {
        inOrder
            .verify(service)
            .sendBroadcast(
                MockitoHamcrest.argThat(AllOf.allOf(*matchers)),
                any<String>(),
                any<Bundle>(),
            )
    }
}
