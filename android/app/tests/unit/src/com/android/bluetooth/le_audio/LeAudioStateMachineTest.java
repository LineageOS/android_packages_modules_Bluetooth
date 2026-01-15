/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.le_audio;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.CONNECT;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.CONNECT_TIMEOUT;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.DISCONNECT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

/** Test cases for {@link LeAudioStateMachine}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private LeAudioService mService;
    @Mock private LeAudioNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(68);

    private LeAudioStateMachine mStateMachine;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).okToConnect(any());

        doReturn(true).when(mNativeInterface).connectLeAudio(any());
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any());

        mInOrder = inOrder(mService);
        mLooper = new TestLooper();

        mStateMachine =
                new LeAudioStateMachine(mDevice, mService, mNativeInterface, mLooper.getLooper());
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    /** Test that an incoming connection with low priority is rejected */
    @Test
    public void incomingConnect_whenNotOkToConnect_isRejected() {
        doReturn(false).when(mService).okToConnect(any());

        generateUnexpectedConnectionMessageFromNative(STATE_CONNECTED);

        verify(mService, never()).notifyConnectionStateChanged(any(), anyInt(), anyInt());
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenOkToConnect_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connected.class);
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        sendAndDispatchMessage(LeAudioStateMachine.CONNECT, mDevice);
        verifyConnectionStateChanged(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenTimeOut_isDisconnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    @Test
    public void connectEventNeglectedWhileInConnectingState() {
        sendAndDispatchMessage(CONNECT, mDevice);
        verifyConnectionStateChanged(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        // Dispatch CONNECT event twice more
        sendAndDispatchMessage(CONNECT, mDevice);
        sendAndDispatchMessage(CONNECT, mDevice);
        sendAndDispatchMessage(DISCONNECT, mDevice);

        verifyConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    @Test
    public void handleMultipleConnectDisconnect_onDisconnectingState() {
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTED);

        sendAndDispatchMessage(DISCONNECT);
        verifyConnectionStateChanged(STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);

        /* While being in disconnecting state defer the Connect message */
        sendAndDispatchMessage(CONNECT);

        /* This one will be ignored and previous Connect will be removed  */
        sendAndDispatchMessage(DISCONNECT);

        /* Now move to Disconnected state and make sure we are going to Connecting state */
        generateConnectionMessageFromNative(STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_INTENT_BROADCAST_IN_STATE_MACHINE_CLEANUP)
    public void testDoQuit_inConnectedState_broadcastsDisconnectedIntent() {
        // Set up the state machine in a connected state
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTED);

        // Verify the state is Connected before calling doQuit()
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);

        // Call the method under test
        mStateMachine.doQuit();

        // Verify the connection state broadcast was made
        verifyConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTED);
    }

    private void sendAndDispatchMessage(int what) {
        sendAndDispatchMessage(what, null);
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    private void verifyConnectionStateChanged(int newState, int previousState) {
        mInOrder.verify(mService)
                .notifyConnectionStateChanged(any(), eq(newState), eq(previousState));
    }

    private void generateConnectionMessageFromNative(int newState, int previousState) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = mDevice;
        event.valueInt1 = newState;

        sendAndDispatchMessage(LeAudioStateMachine.STACK_EVENT, event);
        verifyConnectionStateChanged(newState, previousState);
    }

    private void generateUnexpectedConnectionMessageFromNative(int newConnectionState) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = mDevice;
        event.valueInt1 = newConnectionState;

        sendAndDispatchMessage(LeAudioStateMachine.STACK_EVENT, event);
    }
}
