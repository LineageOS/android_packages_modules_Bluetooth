/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.hap;

import static android.bluetooth.BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.hap.HapClientStateMachine.CONNECT_TIMEOUT;
import static com.android.bluetooth.hap.HapClientStateMachine.MESSAGE_CONNECT;
import static com.android.bluetooth.hap.HapClientStateMachine.MESSAGE_DISCONNECT;
import static com.android.bluetooth.hap.HapClientStateMachine.MESSAGE_STACK_EVENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;

import androidx.test.filters.SmallTest;
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

/** Test cases for {@link HapClientStateMachine}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HapClientStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HapClientService mService;
    @Mock private HapClientNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(23);

    private HapClientStateMachine mStateMachine;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).okToConnect(any());
        doReturn(mService).when(mService).getBaseContext();

        doReturn(true).when(mNativeInterface).connectHapClient(any());
        doReturn(true).when(mNativeInterface).disconnectHapClient(any());

        mInOrder = inOrder(mService);
        mLooper = new TestLooper();

        mStateMachine =
                new HapClientStateMachine(mService, mDevice, mNativeInterface, mLooper.getLooper());
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void incomingConnect_whenNotOkToConnect_isRejected() {
        doReturn(false).when(mService).okToConnect(any());

        generateUnexpectedConnectionMessageFromNative(STATE_CONNECTED);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void incomingConnect_whenOkToConnect_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connected.class);
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void disconnect_whenDisconnected_callNativeDisconnect() {
        mStateMachine.sendMessage(HapClientStateMachine.MESSAGE_DISCONNECT);
        mLooper.dispatchAll();

        verify(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));
    }

    @Test
    public void timeout_whenOutgoingConnect_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void disconnect_whenConnecting_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void remoteToggleDisconnect_whenConnecting_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Connecting.class);

        generateConnectionMessageFromNative(STATE_DISCONNECTING, STATE_CONNECTING);
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTING);
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(STATE_DISCONNECTING, STATE_CONNECTED);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void timeout_whenOutgoingDisConnect_isDisconnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(STATE_DISCONNECTING, STATE_CONNECTED);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HapClientStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenDisconnected_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
    }

    private void sendAndDispatchMessage(int what) {
        mStateMachine.sendMessage(what);
        mLooper.dispatchAll();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcastWithMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    private void verifyConnectionStateIntent(int newState, int oldState) {
        verifyIntentSent(
                hasAction(ACTION_HAP_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
        assertThat(mStateMachine.getConnectionState()).isEqualTo(newState);
    }

    private void generateConnectionMessageFromNative(int newState, int oldState) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = mDevice;
        event.valueInt1 = newState;

        mStateMachine.sendMessage(MESSAGE_STACK_EVENT, event);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(newState, oldState);
    }

    private void generateUnexpectedConnectionMessageFromNative(int newConnectionState) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = mDevice;
        event.valueInt1 = newConnectionState;

        mStateMachine.sendMessage(MESSAGE_STACK_EVENT, event);
        mLooper.dispatchAll();

        mInOrder.verify(mService, never()).sendBroadcast(any(), any(), any());
        mInOrder.verify(mService, never()).sendBroadcastMultiplePermissions(any(), any(), any());
    }
}
