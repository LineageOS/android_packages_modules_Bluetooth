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

package com.android.bluetooth.btservice

import android.os.SystemProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.ActiveState
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.BusyState
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_CLOSED
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_OFF
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_ON
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_WAKELOCK_ACQUIRED
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_WAKELOCK_RELEASED
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.SuspendedState
import com.android.tests.bluetooth.StaticMockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/** Test cases for [AdapterSuspendStateMachine]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdapterSuspendStateMachineTest {
    @get:Rule val mockitoRule = StaticMockitoRule(SystemProperties::class.java)

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var adapterSuspend: AdapterSuspend

    private lateinit var testLooper: TestLooper
    private lateinit var stateMachine: AdapterSuspendStateMachine

    @Before
    fun setUp() {
        testLooper = TestLooper()
        stateMachine = AdapterSuspendStateMachine(adapterService, adapterSuspend, testLooper.looper)
        testLooper.dispatchAll()
    }

    private fun sendAndDispatchMessage(what: Int) {
        stateMachine.sendMessage(what)
        TestUtils.syncHandler(testLooper, what)
    }

    @Test
    fun testInitialState() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
    }

    @Test
    fun testWakeLockAcquired() {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
    }

    @Test
    fun testClosedSuspendFromActive() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        sendAndDispatchMessage(MSG_CLOSED)
        verify(adapterSuspend).handleSuspend(false)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
    }

    @Test
    fun testClosedSuspendFromBusy() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_CLOSED)
        verify(adapterSuspend).handleSuspend(false)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
    }

    @Test
    fun testScreenOffOnWhenBusy() {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
    }

    @Test
    fun testWakeLockReleasedAtScreenOff() {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(BusyState::class.java)
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED)
        verify(adapterSuspend).handleSuspend(true)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        verify(adapterSuspend).handleResume()
    }

    @Test
    fun testScreenOff() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        verify(adapterSuspend).handleSuspend(true)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
    }

    @Test
    fun testScreenOffOnTabletMode() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        stateMachine.setTabletMode(true)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        verify(adapterSuspend).handleSuspend(false)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        verify(adapterSuspend).handleResume()
        stateMachine.setTabletMode(false)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        verify(adapterSuspend).handleSuspend(true)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        verify(adapterSuspend, times(2)).handleResume()
    }

    @Test
    fun testScreenOffThenClosed() {
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        sendAndDispatchMessage(MSG_SCREEN_OFF)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        verify(adapterSuspend).handleSuspend(true)
        sendAndDispatchMessage(MSG_CLOSED)
        assertThat(stateMachine.currentState).isInstanceOf(SuspendedState::class.java)
        verify(adapterSuspend).handleSuspend(false)
        sendAndDispatchMessage(MSG_SCREEN_ON)
        assertThat(stateMachine.currentState).isInstanceOf(ActiveState::class.java)
        verify(adapterSuspend).handleResume()
    }
}
