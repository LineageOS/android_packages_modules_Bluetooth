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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.CONNECT;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.DISCONNECT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioStateMachineTest {
    private HandlerThread mHandlerThread;
    private LeAudioStateMachine mLeAudioStateMachine;
    private final BluetoothDevice mDevice = getTestDevice(68);
    private static final int TIMEOUT_MS = 1000;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private LeAudioNativeInterface mLeAudioNativeInterface;

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);

        // Set up thread and looper
        mHandlerThread = new HandlerThread("LeAudioStateMachineTestHandlerThread");
        mHandlerThread.start();
        // Override the timeout value to speed up the test
        LeAudioStateMachine.sConnectTimeoutMs = 1000; // 1s
        mLeAudioStateMachine =
                LeAudioStateMachine.make(
                        mDevice,
                        mLeAudioService,
                        mLeAudioNativeInterface,
                        mHandlerThread.getLooper());
    }

    @After
    public void tearDown() throws Exception {
        mLeAudioStateMachine.doQuit();
        mHandlerThread.quit();
        TestUtils.clearAdapterService(mAdapterService);
    }

    /** Test that default state is disconnected */
    @Test
    public void testDefaultDisconnectedState() {
        assertThat(mLeAudioStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    /**
     * Allow/disallow connection to any device.
     *
     * @param allow if true, connection is allowed
     */
    private void allowConnection(boolean allow) {
        doReturn(allow).when(mLeAudioService).okToConnect(any(BluetoothDevice.class));
    }

    /** Test that an incoming connection with low priority is rejected */
    @Test
    public void testIncomingPriorityReject() {
        allowConnection(false);

        // Inject an event for when incoming connection is requested
        LeAudioStackEvent connStCh =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mLeAudioStateMachine.sendMessage(LeAudioStateMachine.STACK_EVENT, connStCh);

        // Verify that no connection state broadcast is executed
        verify(mLeAudioService, after(TIMEOUT_MS).never())
                .sendBroadcast(any(Intent.class), anyString(), any(Bundle.class));
        // Check that we are in Disconnected state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    /** Test that an incoming connection with high priority is accepted */
    @Test
    public void testIncomingPriorityAccept() {
        allowConnection(true);

        // Inject an event for when incoming connection is requested
        LeAudioStackEvent connStCh =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTING;
        mLeAudioStateMachine.sendMessage(LeAudioStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTING), anyInt());

        // Check that we are in Connecting state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        // Send a message to trigger connection completed
        LeAudioStackEvent connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mLeAudioStateMachine.sendMessage(LeAudioStateMachine.STACK_EVENT, connCompletedEvent);

        // Verify that the expected number of notification are called:
        // - two calls to notifyConnectionStateChanged(): Disconnected -> Connecting -> Connected
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTING), anyInt());
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTED), anyInt());
        // Check that we are in Connected state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connected.class);
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingTimeout() {
        allowConnection(true);
        doReturn(true).when(mLeAudioNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mLeAudioNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Send a connect request
        mLeAudioStateMachine.sendMessage(LeAudioStateMachine.CONNECT, mDevice);

        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTING), anyInt());

        // Check that we are in Connecting state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(LeAudioStateMachine.sConnectTimeoutMs * 2L))
                .notifyConnectionStateChanged(any(), eq(STATE_DISCONNECTED), anyInt());

        // Check that we are in Disconnected state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    /** Test that an incoming connection times out */
    @Test
    public void testIncomingTimeout() {
        allowConnection(true);
        doReturn(true).when(mLeAudioNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mLeAudioNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        // Inject an event for when incoming connection is requested
        LeAudioStackEvent connStCh =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mDevice;
        connStCh.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTING;
        mLeAudioStateMachine.sendMessage(LeAudioStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTING), anyInt());

        // Check that we are in Connecting state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(LeAudioStateMachine.sConnectTimeoutMs * 2L))
                .notifyConnectionStateChanged(any(), eq(STATE_DISCONNECTED), anyInt());

        // Check that we are in Disconnected state
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mLeAudioStateMachine.sendMessage(what, obj);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_SM_IGNORE_CONNECT_EVENTS_IN_CONNECTING_STATE)
    public void connectEventNeglectedWhileInConnectingState() {
        allowConnection(true);
        doReturn(true).when(mLeAudioNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mLeAudioNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        sendAndDispatchMessage(CONNECT, mDevice);
        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Connecting.class);

        // Dispatch CONNECT event twice more
        sendAndDispatchMessage(CONNECT, mDevice);
        sendAndDispatchMessage(CONNECT, mDevice);
        sendAndDispatchMessage(DISCONNECT, mDevice);
        // Verify that one connection state change is notified
        verify(mLeAudioService, timeout(TIMEOUT_MS))
                .notifyConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));
        assertThat(mLeAudioStateMachine.getCurrentState())
                .isInstanceOf(LeAudioStateMachine.Disconnected.class);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
    }
}
