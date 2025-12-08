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
package com.android.bluetooth.btservice;

import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_CLOSED;
import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_OFF;
import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_ON;
import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_WAKELOCK_ACQUIRED;
import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_WAKELOCK_RELEASED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.SystemProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.ActiveState;
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.BusyState;
import com.android.bluetooth.btservice.AdapterSuspendStateMachine.SuspendedState;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link AdapterSuspendStateMachine}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterSuspendStateMachineTest {
    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Mock private AdapterService mAdapterService;
    @Mock private AdapterSuspend mAdapterSuspend;

    private TestLooper mTestLooper;
    private AdapterSuspendStateMachine mStateMachine;

    @Before
    public void setUp() {
        mTestLooper = new TestLooper();

        mStateMachine =
                new AdapterSuspendStateMachine(
                        mAdapterService, mAdapterSuspend, mTestLooper.getLooper());
        mTestLooper.dispatchAll();
    }

    private void sendAndDispatchMessage(int what) {
        mStateMachine.sendMessage(what);
        TestUtils.syncHandler(mTestLooper, what);
    }

    @Test
    public void testInitialState() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
    }

    @Test
    public void testWakeLockAcquired() throws Exception {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
    }

    @Test
    public void testClosedSuspendFromActive() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        sendAndDispatchMessage(MSG_CLOSED);
        verify(mAdapterSuspend).handleSuspend(false);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
    }

    @Test
    public void testClosedSuspendFromBusy() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_CLOSED);
        verify(mAdapterSuspend).handleSuspend(false);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
    }

    @Test
    public void testScreenOffOnWhenBusy() throws Exception {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
    }

    @Test
    public void testWakeLockReleasedAtScreenOff() throws Exception {
        sendAndDispatchMessage(MSG_WAKELOCK_ACQUIRED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(BusyState.class);
        sendAndDispatchMessage(MSG_WAKELOCK_RELEASED);
        verify(mAdapterSuspend).handleSuspend(true);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        verify(mAdapterSuspend).handleResume();
    }

    @Test
    public void testScreenOff() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        verify(mAdapterSuspend).handleSuspend(true);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
    }

    @Test
    public void testScreenOffOnTabletMode() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        mStateMachine.setTabletMode(true);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        verify(mAdapterSuspend).handleSuspend(false);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        verify(mAdapterSuspend).handleResume();
        mStateMachine.setTabletMode(false);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        verify(mAdapterSuspend).handleSuspend(true);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        verify(mAdapterSuspend, times(2)).handleResume();
    }

    @Test
    public void testScreenOffThenClosed() throws Exception {
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        sendAndDispatchMessage(MSG_SCREEN_OFF);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        verify(mAdapterSuspend).handleSuspend(true);
        sendAndDispatchMessage(MSG_CLOSED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(SuspendedState.class);
        verify(mAdapterSuspend).handleSuspend(false);
        sendAndDispatchMessage(MSG_SCREEN_ON);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(ActiveState.class);
        verify(mAdapterSuspend).handleResume();
    }
}
