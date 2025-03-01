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

package com.android.bluetooth.csip;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.Message;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.*;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class CsipSetCoordinatorStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private CsipSetCoordinatorService mService;
    @Mock private CsipSetCoordinatorNativeInterface mNativeInterface;

    private static final int TIMEOUT_MS = 1000;

    private final BluetoothDevice mDevice = getTestDevice(89);

    private HandlerThread mHandlerThread;
    private CsipSetCoordinatorStateMachine mStateMachine;

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);

        // Set up thread and looper
        mHandlerThread = new HandlerThread("CsipSetCoordinatorServiceTestHandlerThread");
        mHandlerThread.start();
        mStateMachine =
                spy(
                        new CsipSetCoordinatorStateMachine(
                                mDevice, mService, mNativeInterface, mHandlerThread.getLooper()));

        // Override the timeout value to speed up the test
        CsipSetCoordinatorStateMachine.sConnectTimeoutMs = 1000;
        mStateMachine.start();
    }

    @After
    public void tearDown() throws Exception {
        mStateMachine.doQuit();
        mHandlerThread.quit();
        TestUtils.clearAdapterService(mAdapterService);
    }

    /** Test that default state is disconnected */
    @Test
    public void testDefaultDisconnectedState() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    /**
     * Allow/disallow connection to any device
     *
     * @param allow if true, connection is allowed
     */
    private void allowConnection(boolean allow) {
        doReturn(allow).when(mService).okToConnect(any(BluetoothDevice.class));
    }

    /** Test that an incoming connection with policy forbidding connection is rejected */
    @Test
    public void testIncomingPolicyReject() {
        allowConnection(false);

        // Inject an event for when incoming connection is requested
        CsipSetCoordinatorStackEvent connStCh =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, connStCh);

        // Verify that no connection state broadcast is executed
        verify(mService, after(TIMEOUT_MS).never()).sendBroadcast(any(Intent.class), anyString());
        // Check that we are in Disconnected state
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    /** Test that an incoming connection with policy allowing connection is accepted */
    @Test
    public void testIncomingPolicyAccept() {
        allowConnection(true);

        // Inject an event for when incoming connection is requested
        CsipSetCoordinatorStackEvent connStCh =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = connStCh.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(TIMEOUT_MS).times(1))
                .sendBroadcast(intentArgument1.capture(), anyString());
        assertThat(intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_CONNECTING);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Connecting.class);

        // Send a message to trigger connection completed
        CsipSetCoordinatorStackEvent connCompletedEvent =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mDevice;
        connCompletedEvent.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, connCompletedEvent);

        // Verify that the expected number of broadcasts are executed:
        // - two calls to broadcastConnectionState(): Disconnected -> Connecting ->
        // Connected
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(TIMEOUT_MS).times(2))
                .sendBroadcast(intentArgument2.capture(), anyString());

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Connected.class);
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingTimeout() {
        allowConnection(true);
        doReturn(true).when(mNativeInterface).connect(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnect(any(BluetoothDevice.class));

        // Send a connect request
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.CONNECT, mDevice);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(TIMEOUT_MS).times(1))
                .sendBroadcast(intentArgument1.capture(), anyString());
        assertThat(intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_CONNECTING);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Connecting.class);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(CsipSetCoordinatorStateMachine.sConnectTimeoutMs * 2L).times(2))
                .sendBroadcast(intentArgument2.capture(), anyString());
        assertThat(intentArgument2.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_DISCONNECTED);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Disconnected.class);
        verify(mNativeInterface).disconnect(eq(mDevice));
    }

    /** Test that an incoming connection times out */
    @Test
    public void testIncomingTimeout() {
        allowConnection(true);
        doReturn(true).when(mNativeInterface).connect(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnect(any(BluetoothDevice.class));

        // Inject an event for when incoming connection is requested
        CsipSetCoordinatorStackEvent connStCh =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(TIMEOUT_MS).times(1))
                .sendBroadcast(intentArgument1.capture(), anyString());
        assertThat(intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_CONNECTING);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Connecting.class);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mService, timeout(CsipSetCoordinatorStateMachine.sConnectTimeoutMs * 2L).times(2))
                .sendBroadcast(intentArgument2.capture(), anyString());
        assertThat(intentArgument2.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_DISCONNECTED);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(CsipSetCoordinatorStateMachine.Disconnected.class);
        verify(mNativeInterface).disconnect(eq(mDevice));
    }

    @Test
    public void testGetDevice() {
        assertThat(mStateMachine.getDevice()).isEqualTo(mDevice);
    }

    @Test
    public void testIsConnected() {
        assertThat(mStateMachine.isConnected()).isFalse();

        initToConnectedState();
        assertThat(mStateMachine.isConnected()).isTrue();
    }

    @Test
    public void testDumpDoesNotCrash() {
        mStateMachine.dump(new StringBuilder());
    }

    @Test
    public void testProcessDisconnectMessage_onDisconnectedState() {
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.DISCONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testProcessConnectMessage_onDisconnectedState() {
        allowConnection(false);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);

        allowConnection(false);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);

        allowConnection(true);
        doReturn(true).when(mNativeInterface).connect(any(BluetoothDevice.class));
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.CONNECT),
                CsipSetCoordinatorStateMachine.Connecting.class);
    }

    @Test
    public void testStackEvent_withoutStateChange_onDisconnectedState() {
        allowConnection(false);

        CsipSetCoordinatorStackEvent event = new CsipSetCoordinatorStackEvent(-1);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mNativeInterface).disconnect(mDevice);

        Mockito.clearInvocations(mNativeInterface);
        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mNativeInterface).disconnect(mDevice);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = -1;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testStackEvent_toConnectingState_onDisconnectedState() {
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connecting.class);
    }

    @Test
    public void testStackEvent_toConnectedState_onDisconnectedState() {
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connected.class);
    }

    @Test
    public void testProcessConnectMessage_onConnectingState() {
        initToConnectingState();
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(
                        mStateMachine.doesSuperHaveDeferredMessages(
                                CsipSetCoordinatorStateMachine.CONNECT))
                .isTrue();
    }

    @Test
    public void testProcessConnectTimeoutMessage_onConnectingState() {
        initToConnectingState();
        Message msg = mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.CONNECT_TIMEOUT);
        sendMessageAndVerifyTransition(msg, CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testProcessDisconnectMessage_onConnectingState() {
        initToConnectingState();
        Message msg = mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.DISCONNECT);
        sendMessageAndVerifyTransition(msg, CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testStackEvent_withoutStateChange_onConnectingState() {
        initToConnectingState();
        CsipSetCoordinatorStackEvent event = new CsipSetCoordinatorStackEvent(-1);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = 10000;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testStackEvent_toDisconnectedState_onConnectingState() {
        initToConnectingState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testStackEvent_toConnectedState_onConnectingState() {
        initToConnectingState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connected.class);
    }

    @Test
    public void testStackEvent_toDisconnectingState_onConnectingState() {
        initToConnectingState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Disconnecting.class);
    }

    @Test
    public void testProcessConnectMessage_onConnectedState() {
        initToConnectedState();
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testProcessDisconnectMessage_onConnectedState() {
        initToConnectedState();
        doReturn(true).when(mNativeInterface).disconnect(any(BluetoothDevice.class));
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.DISCONNECT),
                CsipSetCoordinatorStateMachine.Disconnecting.class);
    }

    @Test
    public void testProcessDisconnectMessage_onConnectedState_withNativeError() {
        initToConnectedState();
        doReturn(false).when(mNativeInterface).disconnect(any(BluetoothDevice.class));
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.DISCONNECT),
                CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testStackEvent_withoutStateChange_onConnectedState() {
        initToConnectedState();
        CsipSetCoordinatorStackEvent event = new CsipSetCoordinatorStackEvent(-1);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testStackEvent_toDisconnectedState_onConnectedState() {
        initToConnectedState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testStackEvent_toDisconnectingState_onConnectedState() {
        initToConnectedState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Disconnecting.class);
    }

    @Test
    public void testProcessConnectMessage_onDisconnectingState() {
        initToDisconnectingState();
        Message msg = mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.CONNECT);
        mStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(
                        mStateMachine.doesSuperHaveDeferredMessages(
                                CsipSetCoordinatorStateMachine.CONNECT))
                .isTrue();
    }

    @Test
    public void testProcessConnectTimeoutMessage_onDisconnectingState() {
        initToConnectingState();
        Message msg = mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.CONNECT_TIMEOUT);
        sendMessageAndVerifyTransition(msg, CsipSetCoordinatorStateMachine.Disconnected.class);
    }

    @Test
    public void testProcessDisconnectMessage_onDisconnectingState() {
        initToDisconnectingState();
        Message msg = mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.DISCONNECT);
        mStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(
                        mStateMachine.doesSuperHaveDeferredMessages(
                                CsipSetCoordinatorStateMachine.DISCONNECT))
                .isTrue();
    }

    @Test
    public void testStackEvent_withoutStateChange_onDisconnectingState() {
        initToDisconnectingState();
        CsipSetCoordinatorStackEvent event = new CsipSetCoordinatorStackEvent(-1);
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);

        allowConnection(false);
        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mNativeInterface).disconnect(any());

        Mockito.clearInvocations(mNativeInterface);
        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mNativeInterface).disconnect(any());

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);

        event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = 10000;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);
    }

    @Test
    public void testStackEvent_toConnectedState_onDisconnectingState() {
        initToDisconnectingState();
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connected.class);
    }

    @Test
    public void testStackEvent_toConnectedState_butNotAllowed_onDisconnectingState() {
        initToDisconnectingState();
        allowConnection(false);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mNativeInterface).disconnect(any());
    }

    @Test
    public void testStackEvent_toConnectingState_onDisconnectingState() {
        initToDisconnectingState();
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connecting.class);
    }

    @Test
    public void testStackEvent_toConnectingState_butNotAllowed_onDisconnectingState() {
        initToDisconnectingState();
        allowConnection(false);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        mStateMachine.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mNativeInterface).disconnect(any());
    }

    private void initToConnectingState() {
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connecting.class);
        allowConnection(false);
    }

    private void initToConnectedState() {
        allowConnection(true);
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Connected.class);
        allowConnection(false);
    }

    private void initToDisconnectingState() {
        initToConnectingState();
        CsipSetCoordinatorStackEvent event =
                new CsipSetCoordinatorStackEvent(
                        CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt1 = CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, event),
                CsipSetCoordinatorStateMachine.Disconnecting.class);
    }

    private <T> void sendMessageAndVerifyTransition(Message msg, Class<T> type) {
        Mockito.clearInvocations(mService);
        mStateMachine.sendMessage(msg);
        // Verify that one connection state broadcast is executed
        verify(mService, timeout(TIMEOUT_MS)).sendBroadcast(any(Intent.class), anyString());
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(type);
    }
}
