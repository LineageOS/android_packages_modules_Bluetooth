/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.bluetooth.pbap;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.IOException;
import java.io.InputStream;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothPbapService mBluetoothPbapService;
    @Mock private BluetoothSocket mSocket;
    @Mock private InputStream mInputStream;

    private static final int TEST_NOTIFICATION_ID = 1000000;

    private final BluetoothDevice mDevice = getTestDevice(36);

    private Handler mHandler;
    private TestLooper mLooper;
    private PbapStateMachine mStateMachine;

    @Before
    public void setUp() throws IOException {
        doReturn(mInputStream).when(mSocket).getInputStream();
        doReturn(mInputStream).when(mSocket).getInputStream();

        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());

        mStateMachine =
                PbapStateMachine.make(
                        mBluetoothPbapService,
                        mLooper.getLooper(),
                        mDevice,
                        mSocket,
                        mHandler,
                        TEST_NOTIFICATION_ID);
    }

    /** Test that initial state is WaitingForAuth */
    @Test
    public void initialState_isConnecting() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(PbapStateMachine.WaitingForAuth.class);
    }

    /** Test state transition from WaitingForAuth to Finished when the user rejected */
    @Test
    public void testStateTransition_WaitingForAuthToFinished() {
        sendAndDispatchMessage(PbapStateMachine.REJECTED);

        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(PbapStateMachine.Finished.class);
    }

    /** Test state transition from WaitingForAuth to Finished when the user rejected */
    @Test
    public void testStateTransition_WaitingForAuthToConnected() {
        sendAndDispatchMessage(PbapStateMachine.AUTHORIZED);

        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(PbapStateMachine.Connected.class);
    }

    /** Test state transition from Connected to Finished when the OBEX server is done */
    @Test
    public void testStateTransition_ConnectedToFinished() {
        sendAndDispatchMessage(PbapStateMachine.AUTHORIZED);

        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(PbapStateMachine.Connected.class);

        sendAndDispatchMessage(PbapStateMachine.DISCONNECT);

        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(PbapStateMachine.Finished.class);
    }

    private void sendAndDispatchMessage(int what) {
        mStateMachine.sendMessage(what);
        mLooper.dispatchAll();
    }
}
