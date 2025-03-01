/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.bluetooth.a2dpsink;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAudioConfig;
import android.bluetooth.BluetoothDevice;
import android.media.AudioFormat;

import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class A2dpSinkStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpSinkService mService;
    @Mock private A2dpSinkNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(11);

    private A2dpSinkStateMachine mStateMachine;
    private TestLooper mLooper;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();

        mStateMachine =
                new A2dpSinkStateMachine(mService, mDevice, mLooper.getLooper(), mNativeInterface);
        syncHandler(-2 /* SM_INIT_CMD */);

        assertThat(mStateMachine.getDevice()).isEqualTo(mDevice);
        assertThat(mStateMachine.getAudioConfig()).isNull();
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @After
    public void tearDown() throws Exception {
        assertThat(mLooper.nextMessage()).isNull();
    }

    private void syncHandler(int... what) {
        TestUtils.syncHandler(mLooper, what);
    }

    private void mockDeviceConnectionPolicy(BluetoothDevice device, int policy) {
        doReturn(policy).when(mService).getConnectionPolicy(device);
    }

    private void sendConnectionEvent(int state) {
        mStateMachine.sendMessage(
                A2dpSinkStateMachine.MESSAGE_STACK_EVENT,
                StackEvent.connectionStateChanged(mDevice, state));
        syncHandler(A2dpSinkStateMachine.MESSAGE_STACK_EVENT);
    }

    private void sendAudioConfigChangedEvent(int sampleRate, int channelCount) {
        mStateMachine.sendMessage(
                A2dpSinkStateMachine.MESSAGE_STACK_EVENT,
                StackEvent.audioConfigChanged(mDevice, sampleRate, channelCount));
        syncHandler(A2dpSinkStateMachine.MESSAGE_STACK_EVENT);
    }

    /**********************************************************************************************
     * DISCONNECTED STATE TESTS                                                                   *
     *********************************************************************************************/

    @Test
    public void testConnectInDisconnected() {
        mStateMachine.connect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        verify(mNativeInterface).connectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectInDisconnected() {
        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testAudioConfigChangedInDisconnected() {
        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mStateMachine.getAudioConfig()).isNull();
    }

    @Test
    public void testIncomingConnectedInDisconnected() {
        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testAllowedIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
        verify(mNativeInterface, times(0)).connectA2dpSink(mDevice);
    }

    @Test
    public void testForbiddenIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN);

        sendConnectionEvent(STATE_CONNECTING);
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testUnknownIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_UNKNOWN);

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
        verify(mNativeInterface, times(0)).connectA2dpSink(mDevice);
    }

    @Test
    public void testIncomingDisconnectInDisconnected() {
        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testIncomingDisconnectingInDisconnected() {
        sendConnectionEvent(STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        verify(mService, times(0)).removeStateMachine(mStateMachine);
    }

    @Test
    public void testIncomingConnectingInDisconnected() {
        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testUnhandledMessageInDisconnected() {
        final int UNHANDLED_MESSAGE = 9999;
        mStateMachine.sendMessage(UNHANDLED_MESSAGE);
        mStateMachine.sendMessage(UNHANDLED_MESSAGE, 0 /* arbitrary payload */);
        syncHandler(UNHANDLED_MESSAGE, UNHANDLED_MESSAGE);
    }

    /**********************************************************************************************
     * CONNECTING STATE TESTS                                                                     *
     *********************************************************************************************/

    @Test
    public void testConnectedInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testConnectingInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectingInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectedInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testConnectionTimeoutInConnecting() {
        testConnectInDisconnected();

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT_TIMEOUT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testAudioStateChangeInConnecting() {
        testConnectInDisconnected();

        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
        assertThat(mStateMachine.getAudioConfig()).isNull();
    }

    @Test
    public void testConnectInConnecting() {
        testConnectInDisconnected();

        mStateMachine.connect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectInConnecting_disconnectDeferredAndProcessed() {
        testConnectInDisconnected();

        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);

        // send connected, disconnect should get processed
        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);

        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT); // message was defer
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    /**********************************************************************************************
     * CONNECTED STATE TESTS                                                                      *
     *********************************************************************************************/

    @Test
    public void testConnectInConnected() {
        testConnectedInConnecting();

        mStateMachine.connect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testDisconnectInConnected() {
        testConnectedInConnecting();

        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testAudioStateChangeInConnected() {
        testConnectedInConnecting();

        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);

        BluetoothAudioConfig expected =
                new BluetoothAudioConfig(44, 1, AudioFormat.ENCODING_PCM_16BIT);
        assertThat(mStateMachine.getAudioConfig()).isEqualTo(expected);
    }

    @Test
    public void testConnectedInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testConnectingInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testDisconnectingInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testDisconnectedInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    /**********************************************************************************************
     * OTHER TESTS                                                                                *
     *********************************************************************************************/

    @Test
    public void testDump() {
        StringBuilder sb = new StringBuilder();
        mStateMachine.dump(sb);
        assertThat(sb.toString()).isNotNull();
    }
}
