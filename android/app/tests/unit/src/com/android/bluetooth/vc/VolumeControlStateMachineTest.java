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

package com.android.bluetooth.vc;

import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothVolumeControl.ACTION_CONNECTION_STATE_CHANGED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.vc.VolumeControlStateMachine.MESSAGE_CONNECT;
import static com.android.bluetooth.vc.VolumeControlStateMachine.MESSAGE_CONNECT_TIMEOUT;
import static com.android.bluetooth.vc.VolumeControlStateMachine.MESSAGE_DISCONNECT;
import static com.android.bluetooth.vc.VolumeControlStateMachine.MESSAGE_STACK_EVENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

/** Test cases for {@link VolumeControlStateMachine}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private VolumeControlService mService;
    @Mock private VolumeControlNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(39);

    private VolumeControlStateMachine mStateMachine;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() {
        doReturn(true).when(mService).okToConnect(any());

        doReturn(true).when(mNativeInterface).connectVolumeControl(any());
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any());

        mInOrder = inOrder(mService);
        mLooper = new TestLooper();

        mStateMachine =
                new VolumeControlStateMachine(
                        mService, mDevice, mNativeInterface, mLooper.getLooper());
        mStateMachine.start();
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void incomingConnect_whenNotOkToConnect_isRejected() {
        doReturn(false).when(mService).okToConnect(any());

        // Inject an event for when incoming connection is requested
        VolumeControlStackEvent connStCh =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = STATE_CONNECTED;
        sendAndDispatchMessage(MESSAGE_STACK_EVENT, connStCh);

        verify(mService, never()).sendBroadcast(any(Intent.class), anyString());
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenOkToConnect_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Connecting.class);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Connected.class);
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        sendAndDispatchMessage(MESSAGE_CONNECT, mDevice);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Connecting.class);

        mLooper.moveTimeForward(VolumeControlStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Disconnected.class);

        verify(mNativeInterface).disconnectVolumeControl(eq(mDevice));
    }

    @Test
    public void incomingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Connecting.class);

        mLooper.moveTimeForward(VolumeControlStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Disconnected.class);
        verify(mNativeInterface).disconnectVolumeControl(eq(mDevice));
    }

    @Test
    public void disconnect_whenDisconnected_isDisconnectedWithoutBroadcast() {
        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(VolumeControlStateMachine.Disconnected.class);
        verify(mService, never()).sendBroadcast(any(), any());
    }

    @Test
    public void disconnect_whenConnecting_isDisconnectedWithBroadcast() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        sendAndDispatchMessage(MESSAGE_CONNECT_TIMEOUT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void disconnect_whenIncomingConnecting_isDisconnectedWithBroadcast() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void timeout_whenDisconnecting_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(STATE_DISCONNECTING, STATE_CONNECTING);
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTING);
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(STATE_DISCONNECTING, STATE_CONNECTED);
        sendAndDispatchMessage(MESSAGE_CONNECT_TIMEOUT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void incomingConnect_whenDisconnected_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void incomingConnect_whenDisconnecting_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        sendAndDispatchMessage(MESSAGE_DISCONNECT);

        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    private void sendAndDispatchMessage(int what) {
        sendAndDispatchMessage(what, null);
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    private void verifyConnectionStateIntent(int newState, int oldState) {
        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
        assertThat(mStateMachine.getConnectionState()).isEqualTo(newState);
    }

    private void generateConnectionMessageFromNative(int newState, int oldState) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = mDevice;
        event.valueInt1 = newState;

        sendAndDispatchMessage(MESSAGE_STACK_EVENT, event);
        verifyConnectionStateIntent(newState, oldState);
    }
}
