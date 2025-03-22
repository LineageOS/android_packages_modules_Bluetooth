/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.hearingaid;

import static android.bluetooth.BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

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
import android.os.UserHandle;

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

/** Test cases for {@link HearingAidStateMachine}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HearingAidStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HearingAidService mService;
    @Mock private HearingAidNativeInterface mNativeInterface;

    private final BluetoothDevice mTestDevice = getTestDevice(0xDA);

    private HearingAidStateMachine mStateMachine;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() {
        mInOrder = inOrder(mService);
        mLooper = new TestLooper();

        doReturn(true).when(mService).okToConnect(any());
        doReturn(true).when(mService).isConnectedPeerDevices(mTestDevice);

        doReturn(true).when(mNativeInterface).connectHearingAid(any());
        doReturn(true).when(mNativeInterface).disconnectHearingAid(any());

        mStateMachine =
                new HearingAidStateMachine(
                        mService, mTestDevice, mNativeInterface, mLooper.getLooper());
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
        HearingAidStackEvent connStCh =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = STATE_CONNECTED;
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_STACK_EVENT, connStCh);

        verify(mService, never()).sendBroadcastAsUser(any(), any(), anyString(), any());
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenOkToConnect_isConnected() {
        // Inject an event for when incoming connection is requested
        HearingAidStackEvent connStCh =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = STATE_CONNECTING;
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_STACK_EVENT, connStCh);

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_CONNECTING));

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Connecting.class);

        // Send a message to trigger connection completed
        HearingAidStackEvent connCompletedEvent =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mTestDevice;
        connCompletedEvent.valueInt1 = STATE_CONNECTED;
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_STACK_EVENT, connCompletedEvent);

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED), hasExtra(EXTRA_STATE, STATE_CONNECTED));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Connected.class);
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_CONNECT, mTestDevice);

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_CONNECTING));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Connecting.class);

        mLooper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_DISCONNECTED));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Disconnected.class);

        verify(mNativeInterface).addToAcceptlist(eq(mTestDevice));
    }

    @Test
    public void incomingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        HearingAidStackEvent connStCh =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = STATE_CONNECTING;
        sendAndDispatchMessage(HearingAidStateMachine.MESSAGE_STACK_EVENT, connStCh);

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_CONNECTING));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Connecting.class);

        mLooper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_DISCONNECTED));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HearingAidStateMachine.Disconnected.class);

        verify(mNativeInterface).addToAcceptlist(eq(mTestDevice));
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(UserHandle.ALL),
                        any(),
                        any());
    }
}
