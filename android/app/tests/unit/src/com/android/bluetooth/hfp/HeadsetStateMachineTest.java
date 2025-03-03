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

package com.android.bluetooth.hfp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.media.audio.Flags.FLAG_DEPRECATE_STREAM_BT_SCO;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothStatusCodes;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;

import com.google.common.util.concurrent.Uninterruptibles;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;

/** Tests for {@link HeadsetStateMachine} */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetStateMachineTest {
    private static final int CONNECT_TIMEOUT_TEST_MILLIS = 1000;
    private static final int CONNECT_TIMEOUT_TEST_WAIT_MILLIS = CONNECT_TIMEOUT_TEST_MILLIS * 3 / 2;
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 250;
    private static final String TEST_PHONE_NUMBER = "1234567890";
    private static final int MAX_RETRY_DISCONNECT_AUDIO = 3;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private HandlerThread mHandlerThread;
    private HeadsetStateMachine mHeadsetStateMachine;
    private final BluetoothDevice mDevice = getTestDevice(87);
    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HeadsetSystemInterface mSystemInterface;
    @Mock Resources mResources;
    @Mock private AudioManager mAudioManager;
    @Mock private HeadsetPhoneState mPhoneState;
    @Mock private Intent mIntent;
    private MockContentResolver mMockContentResolver;
    @Mock private HeadsetNativeInterface mNativeInterface;
    @Mock private RemoteDevices mRemoteDevices;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(READ_PRIVILEGED_PHONE_STATE);
        // Stub system interface
        doReturn(mPhoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(mAudioManager).when(mSystemInterface).getAudioManager();
        doReturn(true).when(mDatabaseManager).setAudioPolicyMetadata(anyObject(), anyObject());
        doReturn(true).when(mNativeInterface).connectHfp(mDevice);
        doReturn(true).when(mNativeInterface).disconnectHfp(mDevice);
        doReturn(true).when(mNativeInterface).connectAudio(mDevice);
        doReturn(true).when(mNativeInterface).disconnectAudio(mDevice);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        // Stub headset service
        mMockContentResolver = new MockContentResolver();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        when(mHeadsetService.bindService(any(Intent.class), any(ServiceConnection.class), anyInt()))
                .thenReturn(true);
        doReturn(mResources).when(mAdapterService).getResources();
        doReturn("").when(mResources).getString(anyInt());
        when(mHeadsetService.getPackageManager())
                .thenReturn(
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getPackageManager());
        when(mHeadsetService.getConnectionPolicy(any(BluetoothDevice.class)))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mHeadsetService.getForceScoAudio()).thenReturn(true);
        when(mHeadsetService.okToAcceptConnection(any(BluetoothDevice.class), anyBoolean()))
                .thenReturn(true);
        when(mHeadsetService.isScoAcceptable(any(BluetoothDevice.class)))
                .thenReturn(BluetoothStatusCodes.SUCCESS);
        // Setup thread and looper
        mHandlerThread = new HandlerThread("HeadsetStateMachineTestHandlerThread");
        mHandlerThread.start();
        // Modify CONNECT timeout to a smaller value for test only
        HeadsetStateMachine.sConnectTimeoutMs = CONNECT_TIMEOUT_TEST_MILLIS;
        mHeadsetStateMachine =
                HeadsetObjectsFactory.getInstance()
                        .makeStateMachine(
                                mDevice,
                                mHandlerThread.getLooper(),
                                mHeadsetService,
                                mAdapterService,
                                mNativeInterface,
                                mSystemInterface);
    }

    @After
    public void tearDown() throws Exception {
        HeadsetObjectsFactory.getInstance().destroyStateMachine(mHeadsetStateMachine);
        mHandlerThread.quit();
        Uninterruptibles.joinUninterruptibly(mHandlerThread);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    /** Test that default state is Disconnected */
    @Test
    public void testDefaultDisconnectedState() {
        assertThat(mHeadsetStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /** Test that state is Connected after calling setUpConnectedState() */
    @Test
    public void testSetupConnectedState() {
        setUpConnectedState();
        assertThat(mHeadsetStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /** Test state transition from Disconnected to Connecting state via CONNECT message */
    @Test
    public void testStateTransition_DisconnectedToConnecting_Connect() {
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.CONNECT, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTING, STATE_DISCONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);
    }

    /**
     * Test state transition from Disconnected to Connecting state via StackEvent.CONNECTED message
     */
    @Test
    public void testStateTransition_DisconnectedToConnecting_StackConnected() {
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTING, STATE_DISCONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);
    }

    /**
     * Test state transition from Disconnected to Connecting state via StackEvent.CONNECTING message
     */
    @Test
    public void testStateTransition_DisconnectedToConnecting_StackConnecting() {
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTING, STATE_DISCONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);
    }

    /**
     * Test state transition from Connecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_ConnectingToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpConnectingState();
        // Indicate disconnecting to test state machine, which should do nothing
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                        mDevice));
        // Should do nothing new
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class), any(UserHandle.class), anyString(), any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);

        // Indicate connection failed to test state machine
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));

        numBroadcastsSent++;
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTED, STATE_CONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /** Test state transition from Connecting to Disconnected state via CONNECT_TIMEOUT message */
    @Test
    public void testStateTransition_ConnectingToDisconnected_Timeout() {
        int numBroadcastsSent = setUpConnectingState();
        // Let the connection timeout
        numBroadcastsSent++;
        verify(mHeadsetService, timeout(CONNECT_TIMEOUT_TEST_WAIT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTED, STATE_CONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /**
     * Test state transition from Connecting to Connected state via StackEvent.SLC_CONNECTED message
     */
    @Test
    public void testStateTransition_ConnectingToConnected_StackSlcConnected() {
        int numBroadcastsSent = setUpConnectingState();
        // Indicate connecting to test state machine
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTING,
                        mDevice));
        // Should do nothing
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class), any(UserHandle.class), anyString(), any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);

        // Indicate RFCOMM connection is successful to test state machine
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                        mDevice));
        // Should do nothing
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class), any(UserHandle.class), anyString(), any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);

        // Indicate SLC connection is successful to test state machine
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTED, STATE_CONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from Disconnecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_DisconnectingToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpDisconnectingState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /**
     * Test state transition from Disconnecting to Disconnected state via CONNECT_TIMEOUT message
     */
    @Test
    public void testStateTransition_DisconnectingToDisconnected_Timeout() {
        int numBroadcastsSent = setUpDisconnectingState();
        // Let the connection timeout
        numBroadcastsSent++;
        verify(mHeadsetService, timeout(CONNECT_TIMEOUT_TEST_WAIT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /**
     * Test state transition from Disconnecting to Connected state via StackEvent.SLC_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_DisconnectingToConnected_StackSlcCconnected() {
        int numBroadcastsSent = setUpDisconnectingState();
        // Send StackEvent.SLC_CONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTED, STATE_DISCONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /** Test state transition from Connected to Disconnecting state via DISCONNECT message */
    @Test
    public void testStateTransition_ConnectedToDisconnecting_Disconnect() {
        int numBroadcastsSent = setUpConnectedState();
        // Send DISCONNECT message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTING, STATE_CONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
    }

    /**
     * Test state transition from Connected to Disconnecting state via StackEvent.DISCONNECTING
     * message
     */
    @Test
    public void testStateTransition_ConnectedToDisconnecting_StackDisconnecting() {
        int numBroadcastsSent = setUpConnectedState();
        // Send StackEvent.DISCONNECTING message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTING, STATE_CONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
    }

    /**
     * Test state transition from Connected to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_ConnectedToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpConnectedState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTED, STATE_CONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /** Test state transition from Connected to AudioConnecting state via CONNECT_AUDIO message */
    @Test
    public void testStateTransition_ConnectedToAudioConnecting_ConnectAudio() {
        int numBroadcastsSent = setUpConnectedState();
        // Send CONNECT_AUDIO message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    /**
     * Test state transition from Connected to AudioConnecting state via CONNECT_AUDIO message when
     * ScoManagedByAudioEnabled
     */
    @Test
    public void testStateTransition_ConnectedToAudioConnecting_ConnectAudio_ScoManagedbyAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        setUpConnectedState();
        // Send CONNECT_AUDIO message
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mDevice);
        // verify no native connect audio
        verify(mNativeInterface, never()).connectAudio(mDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * Test state transition from Connected to AudioConnecting state via StackEvent.AUDIO_CONNECTING
     * message
     */
    @Test
    public void testStateTransition_ConnectedToAudioConnecting_StackAudioConnecting() {
        int numBroadcastsSent = setUpConnectedState();
        // Send StackEvent.AUDIO_CONNECTING message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    /**
     * Test state transition from Connected to AudioOn state via StackEvent.AUDIO_CONNECTED message
     */
    @Test
    public void testStateTransition_ConnectedToAudioOn_StackAudioConnected() {
        int numBroadcastsSent = setUpConnectedState();
        // Send StackEvent.AUDIO_CONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /** Test state transition from AudioConnecting to Connected state via CONNECT_TIMEOUT message */
    @Test
    public void testStateTransition_AudioConnectingToConnected_Timeout() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Wait for connection to timeout
        numBroadcastsSent++;
        verify(mHeadsetService, timeout(CONNECT_TIMEOUT_TEST_WAIT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioConnecting to Connected state via
     * StackEvent.AUDIO_DISCONNECTED message
     */
    @Test
    public void testStateTransition_AudioConnectingToConnected_StackAudioDisconnected() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Send StackEvent.AUDIO_DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioConnecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioConnectingToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTED,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /**
     * Test state transition from AudioConnecting to Disconnecting state via
     * StackEvent.DISCONNECTING message
     */
    @Test
    public void testStateTransition_AudioConnectingToDisconnecting_StackDisconnecting() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTING,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
    }

    /**
     * Test state transition from AudioConnecting to AudioOn state via StackEvent.AUDIO_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioConnectingToAudioOn_StackAudioConnected() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Send StackEvent.AUDIO_DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via
     * StackEvent.AUDIO_DISCONNECTING message
     */
    @Test
    public void testStateTransition_AudioOnToAudioDisconnecting_StackAudioDisconnecting() {
        int numBroadcastsSent = setUpAudioOnState();
        // Send StackEvent.AUDIO_DISCONNECTING message
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTING,
                        mDevice));
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via DISCONNECT_AUDIO message
     */
    @Test
    public void testStateTransition_AudioOnToAudioDisconnecting_DisconnectAudio() {
        int numBroadcastsSent = setUpAudioOnState();
        // Send DISCONNECT_AUDIO message
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, mDevice);
        // Should not sent any broadcast due to lack of AUDIO_DISCONNECTING intent value
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via Stack.AUDIO_DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioOnToConnected_StackAudioDisconnected() {
        int numBroadcastsSent = setUpAudioOnState();
        // Send DISCONNECT_AUDIO message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /** Test state transition from AudioOn to Disconnected state via Stack.DISCONNECTED message */
    @Test
    public void testStateTransition_AudioOnToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpAudioOnState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTED,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /** Test state transition from AudioOn to Disconnecting state via Stack.DISCONNECTING message */
    @Test
    public void testStateTransition_AudioOnToDisconnecting_StackDisconnecting() {
        int numBroadcastsSent = setUpAudioOnState();
        // Send StackEvent.DISCONNECTING message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTING,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
    }

    /**
     * Test state transition from AudioDisconnecting to AudioOn state via CONNECT_TIMEOUT message
     * until retry count is reached, then test transition to Disconnecting state.
     */
    @Test
    public void testStateTransition_AudioDisconnectingToAudioOnAndDisconnecting_Timeout() {
        int numBroadcastsSent = setUpAudioDisconnectingState();
        // Wait for connection to timeout
        numBroadcastsSent++;
        for (int i = 0; i <= MAX_RETRY_DISCONNECT_AUDIO; i++) {
            if (i > 0) { // Skip first AUDIO_DISCONNECTING init as it was setup before the loop
                mHeadsetStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, mDevice);
                // No new broadcast due to lack of AUDIO_DISCONNECTING intent variable
                verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                        .sendBroadcastAsUser(
                                any(Intent.class),
                                eq(UserHandle.ALL),
                                eq(BLUETOOTH_CONNECT),
                                any(Bundle.class));
                assertThat(mHeadsetStateMachine.getCurrentState())
                        .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
                if (i == MAX_RETRY_DISCONNECT_AUDIO) {
                    // Increment twice numBroadcastsSent as DISCONNECT message is added on max retry
                    numBroadcastsSent += 2;
                } else {
                    numBroadcastsSent++;
                }
            }
            verify(
                            mHeadsetService,
                            timeout(CONNECT_TIMEOUT_TEST_WAIT_MILLIS).times(numBroadcastsSent))
                    .sendBroadcastAsUser(
                            mIntentArgument.capture(),
                            eq(UserHandle.ALL),
                            eq(BLUETOOTH_CONNECT),
                            any(Bundle.class));
            if (i < MAX_RETRY_DISCONNECT_AUDIO) { // Test if state is AudioOn before max retry
                HeadsetTestUtils.verifyAudioStateBroadcast(
                        mDevice,
                        BluetoothHeadset.STATE_AUDIO_CONNECTED,
                        BluetoothHeadset.STATE_AUDIO_CONNECTED,
                        mIntentArgument.getValue());
                assertThat(mHeadsetStateMachine.getCurrentState())
                        .isInstanceOf(HeadsetStateMachine.AudioOn.class);
            } else { // Max retry count reached, test Disconnecting state
                HeadsetTestUtils.verifyConnectionStateBroadcast(
                        mDevice,
                        BluetoothHeadset.STATE_DISCONNECTING,
                        BluetoothHeadset.STATE_CONNECTED,
                        mIntentArgument.getValue());
                assertThat(mHeadsetStateMachine.getCurrentState())
                        .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
            }
        }
    }

    /**
     * Test state transition from AudioDisconnecting to Connected state via Stack.AUDIO_DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToConnected_StackAudioDisconnected() {
        int numBroadcastsSent = setUpAudioDisconnectingState();
        // Send Stack.AUDIO_DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioDisconnecting to AudioOn state via Stack.AUDIO_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToAudioOn_StackAudioConnected() {
        int numBroadcastsSent = setUpAudioDisconnectingState();
        // Send Stack.AUDIO_CONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /**
     * Test state transition from AudioDisconnecting to Disconnecting state via Stack.DISCONNECTING
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToDisconnecting_StackDisconnecting() {
        int numBroadcastsSent = setUpAudioDisconnectingState();
        // Send StackEvent.DISCONNECTING message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTING,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
    }

    /**
     * Test state transition from AudioDisconnecting to Disconnecting state via Stack.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToDisconnected_StackDisconnected() {
        int numBroadcastsSent = setUpAudioDisconnectingState();
        // Send StackEvent.DISCONNECTED message
        numBroadcastsSent += 2;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice,
                STATE_DISCONNECTED,
                STATE_CONNECTED,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 1));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /**
     * A test to verify that we correctly subscribe to phone state updates for service and signal
     * strength information and further updates via AT+BIA command results in update
     */
    @Test
    public void testAtBiaEvent_initialSubscriptionWithUpdates() {
        setUpConnectedState();
        verify(mPhoneState)
                .listenForPhoneState(
                        mDevice,
                        PhoneStateListener.LISTEN_SERVICE_STATE
                                | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(true, true, false, false),
                        mDevice));
        verify(mPhoneState, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .listenForPhoneState(mDevice, PhoneStateListener.LISTEN_SERVICE_STATE);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(false, true, true, false),
                        mDevice));
        verify(mPhoneState, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .listenForPhoneState(mDevice, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(false, true, false, false),
                        mDevice));
        verify(mPhoneState, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .listenForPhoneState(mDevice, PhoneStateListener.LISTEN_NONE);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventWhenIdleAndAudioOff_dialCall() {
        setUpConnectedState();
        Cursor cursor = mock(Cursor.class);
        when(cursor.getCount()).thenReturn(1);
        when(cursor.moveToNext()).thenReturn(true);
        int magicNumber = 42;
        when(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)).thenReturn(magicNumber);
        when(cursor.getString(magicNumber)).thenReturn(TEST_PHONE_NUMBER);
        MockContentProvider mockContentProvider =
                new MockContentProvider() {
                    @Override
                    public Cursor query(
                            Uri uri,
                            String[] projection,
                            Bundle queryArgs,
                            CancellationSignal cancellationSignal) {
                        if (uri == null || !uri.equals(CallLog.Calls.CONTENT_URI)) {
                            return null;
                        }
                        if (projection == null
                                || (projection.length == 0)
                                || !projection[0].equals(CallLog.Calls.NUMBER)) {
                            return null;
                        }
                        if (queryArgs == null
                                || !queryArgs
                                        .getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                                        .equals(Calls.TYPE + "=" + Calls.OUTGOING_TYPE)
                                || !queryArgs
                                        .getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
                                        .equals(Calls.DEFAULT_SORT_ORDER)
                                || queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT) != 1) {
                            return null;
                        }
                        if (cancellationSignal != null) {
                            return null;
                        }
                        return cursor;
                    }
                };
        mMockContentResolver.addProvider(CallLog.AUTHORITY, mockContentProvider);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .dialOutgoingCall(mDevice, TEST_PHONE_NUMBER);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventDuringRinging_answerCall() {
        setUpConnectedState();
        when(mSystemInterface.isRinging()).thenReturn(true);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).answerCall(mDevice);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventInCallButAudioOff_setActiveDevice() {
        setUpConnectedState();
        when(mSystemInterface.isInCall()).thenReturn(true);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setActiveDevice(mDevice);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventInCallAndAudioOn_hangupCall() {
        setUpAudioOnState();
        when(mSystemInterface.isInCall()).thenReturn(true);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).hangupCall(mDevice);
    }

    /** A test to verify that we correctly send CIND response when a call is in progress */
    @Test
    public void testCindEventWhenCallIsInProgress() {
        when(mPhoneState.getCindService())
                .thenReturn(HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE);
        when(mHeadsetService.isVirtualCallStarted()).thenReturn(false);
        when(mPhoneState.getNumActiveCall()).thenReturn(1);

        setUpAudioOnState();

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_CIND, mDevice));
        // wait state machine to process the message
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .cindResponse(
                        eq(mDevice),
                        eq(HeadsetHalConstants.NETWORK_STATE_AVAILABLE),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventWhenIdleAndAudioOn_disconnectAudio() {
        setUpAudioOnState();
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).disconnectAudio(mDevice);
    }

    /** A test to verify that we correctly handles AT+BIND event with driver safety case from HF */
    @Test
    public void testAtBindWithDriverSafetyEventWhenConnecting() {
        setUpConnectingState();

        String atString = "1";
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcast(intentArgument.capture(), eq(BLUETOOTH_CONNECT), any(Bundle.class));
        verify(mHeadsetService).sendBroadcast(any(), any(), any());
        assertThat(
                        intentArgument
                                .getValue()
                                .getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(mDevice);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, -1))
                .isEqualTo(HeadsetHalConstants.HF_INDICATOR_ENHANCED_DRIVER_SAFETY);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, -2))
                .isEqualTo(-1);
    }

    /** A test to verify that we correctly handles AT+BIND event with battery level case from HF */
    @Test
    public void testAtBindEventWithBatteryLevelEventWhenConnecting() {
        setUpConnectingState();

        String atString = "2";
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcast(intentArgument.capture(), eq(BLUETOOTH_CONNECT), any(Bundle.class));
        verify(mHeadsetService).sendBroadcast(any(), any(), any());
        assertThat(
                        intentArgument
                                .getValue()
                                .getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(mDevice);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, -1))
                .isEqualTo(HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, -2))
                .isEqualTo(-1);
    }

    /** A test to verify that we correctly handles AT+BIND event with error case from HF */
    @Test
    public void testAtBindEventWithErrorEventWhenConnecting() {
        setUpConnectingState();

        String atString = "err,A,123,,1";
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcast(intentArgument.capture(), eq(BLUETOOTH_CONNECT), any(Bundle.class));
        verify(mHeadsetService).sendBroadcast(any(), any(), any());
        assertThat(
                        intentArgument
                                .getValue()
                                .getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(mDevice);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, -1))
                .isEqualTo(HeadsetHalConstants.HF_INDICATOR_ENHANCED_DRIVER_SAFETY);
        assertThat(
                        intentArgument
                                .getValue()
                                .getIntExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, -2))
                .isEqualTo(-1);
    }

    /** A test to verify that we correctly set AG indicator mask when enter/exit silence mode */
    @Test
    public void testSetSilenceDevice() {
        doNothing().when(mPhoneState).listenForPhoneState(any(BluetoothDevice.class), anyInt());
        mHeadsetStateMachine.setSilenceDevice(true);
        mHeadsetStateMachine.setSilenceDevice(false);
        verify(mPhoneState, times(2)).listenForPhoneState(mDevice, PhoneStateListener.LISTEN_NONE);
    }

    @Test
    public void testBroadcastVendorSpecificEventIntent() {
        mHeadsetStateMachine.broadcastVendorSpecificEventIntent("command", 1, 1, null, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
    }

    @Test
    public void testFindChar_withCharFound() {
        char ch = 's';
        String input = "test";
        int fromIndex = 0;

        assertThat(HeadsetStateMachine.findChar(ch, input, fromIndex)).isEqualTo(2);
    }

    @Test
    public void testFindChar_withCharNotFound() {
        char ch = 'x';
        String input = "test";
        int fromIndex = 0;

        assertThat(HeadsetStateMachine.findChar(ch, input, fromIndex)).isEqualTo(input.length());
    }

    @Test
    public void testFindChar_withQuotes() {
        char ch = 's';
        String input = "te\"st";
        int fromIndex = 0;

        assertThat(HeadsetStateMachine.findChar(ch, input, fromIndex)).isEqualTo(input.length());
    }

    @Test
    public void testGenerateArgs() {
        String input = "11,notint";
        ArrayList<Object> expected = new ArrayList<Object>();
        expected.add(11);
        expected.add("notint");

        assertThat(HeadsetStateMachine.generateArgs(input)).isEqualTo(expected.toArray());
    }

    @Test
    public void testGetAtCommandType() {
        String atCommand = "start?";
        assertThat(mHeadsetStateMachine.getAtCommandType(atCommand))
                .isEqualTo(AtPhonebook.TYPE_READ);

        atCommand = "start=?";
        assertThat(mHeadsetStateMachine.getAtCommandType(atCommand))
                .isEqualTo(AtPhonebook.TYPE_TEST);

        atCommand = "start=comm";
        assertThat(mHeadsetStateMachine.getAtCommandType(atCommand))
                .isEqualTo(AtPhonebook.TYPE_SET);

        atCommand = "start!";
        assertThat(mHeadsetStateMachine.getAtCommandType(atCommand))
                .isEqualTo(AtPhonebook.TYPE_UNKNOWN);
    }

    @Test
    public void testParseUnknownAt() {
        String atString = "\"command\"";

        assertThat(mHeadsetStateMachine.parseUnknownAt(atString)).isEqualTo("\"command\"");
    }

    @Test
    public void testParseUnknownAt_withUnmatchingQuotes() {
        String atString = "\"command";

        assertThat(mHeadsetStateMachine.parseUnknownAt(atString)).isEqualTo("\"command\"");
    }

    @Test
    public void testParseUnknownAt_withCharOutsideQuotes() {
        String atString = "a\"command\"";

        assertThat(mHeadsetStateMachine.parseUnknownAt(atString)).isEqualTo("A\"command\"");
    }

    @Ignore("b/265556073")
    @Test
    public void testHandleAccessPermissionResult_withNoChangeInAtCommandResult() {
        when(mIntent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)).thenReturn(null);
        when(mIntent.getAction()).thenReturn(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        when(mIntent.getIntExtra(
                        BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                        BluetoothDevice.CONNECTION_ACCESS_NO))
                .thenReturn(BluetoothDevice.CONNECTION_ACCESS_NO);
        when(mIntent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)).thenReturn(true);
        mHeadsetStateMachine.mPhonebook.setCheckingAccessPermission(true);

        mHeadsetStateMachine.handleAccessPermissionResult(mIntent);

        verify(mNativeInterface).atResponseCode(null, 0, 0);
    }

    @Test
    public void testProcessAtBievCommand() {
        mHeadsetStateMachine.processAtBiev(1, 1, mDevice);

        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcast(mIntentArgument.capture(), eq(BLUETOOTH_CONNECT), any(Bundle.class));
    }

    @Test
    public void testProcessAtChld_withProcessChldTrue() {
        int chld = 1;
        when(mSystemInterface.processChld(mHeadsetService, chld)).thenReturn(true);

        mHeadsetStateMachine.processAtChld(chld, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessAtChld_withProcessChldFalse() {
        int chld = 1;
        when(mSystemInterface.processChld(mHeadsetService, chld)).thenReturn(false);

        mHeadsetStateMachine.processAtChld(chld, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessAtClcc_withVirtualCallStarted() {
        when(mHeadsetService.isVirtualCallStarted()).thenReturn(true);
        when(mSystemInterface.getSubscriberNumber()).thenReturn(null);

        mHeadsetStateMachine.processAtClcc(mDevice);

        verify(mNativeInterface).clccResponse(mDevice, 0, 0, 0, 0, false, "", 0);
    }

    @Test
    public void testProcessAtClcc_withVirtualCallNotStarted() {
        doReturn(false).when(mHeadsetService).isVirtualCallStarted();
        doReturn(false).when(mSystemInterface).listCurrentCalls(any());

        mHeadsetStateMachine.processAtClcc(mDevice);

        verify(mNativeInterface).clccResponse(mDevice, 0, 0, 0, 0, false, "", 0);
    }

    @Test
    public void testProcessAtCops() {
        ServiceState serviceState = mock(ServiceState.class);
        when(serviceState.getOperatorAlphaLong()).thenReturn("");
        when(serviceState.getOperatorAlphaShort()).thenReturn("");
        HeadsetPhoneState phoneState = mock(HeadsetPhoneState.class);
        when(phoneState.getServiceState()).thenReturn(serviceState);
        when(mSystemInterface.getHeadsetPhoneState()).thenReturn(phoneState);
        when(mSystemInterface.isInCall()).thenReturn(true);
        when(mSystemInterface.getNetworkOperator()).thenReturn(null);

        mHeadsetStateMachine.processAtCops(mDevice);

        verify(mNativeInterface).copsResponse(mDevice, "");
    }

    @Test
    public void testProcessAtCpbr() {
        String atString = "command=ERR";
        int type = AtPhonebook.TYPE_SET;

        mHeadsetStateMachine.processAtCpbr(atString, type, mDevice);

        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.TEXT_HAS_INVALID_CHARS);
    }

    @Test
    public void testProcessAtCpbs() {
        String atString = "command=ERR";
        int type = AtPhonebook.TYPE_SET;

        mHeadsetStateMachine.processAtCpbs(atString, type, mDevice);

        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.OPERATION_NOT_ALLOWED);
    }

    @Test
    public void testProcessAtCscs() {
        String atString = "command=GSM";
        int type = AtPhonebook.TYPE_SET;

        mHeadsetStateMachine.processAtCscs(atString, type, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, -1);
    }

    @Test
    public void testProcessAtXapl() {
        Object[] args = new Object[2];
        args[0] = "1-12-3";
        args[1] = 1;

        mHeadsetStateMachine.processAtXapl(args, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, "+XAPL=iPhone," + String.valueOf(2));
    }

    @Test
    public void testProcessSendVendorSpecificResultCode() {
        HeadsetVendorSpecificResultCode resultCode =
                new HeadsetVendorSpecificResultCode(mDevice, "command", "arg");

        mHeadsetStateMachine.processSendVendorSpecificResultCode(resultCode);

        verify(mNativeInterface).atResponseString(mDevice, "command" + ": " + "arg");
    }

    @Test
    public void testProcessSubscriberNumberRequest_withSubscriberNumberNull() {
        when(mSystemInterface.getSubscriberNumber()).thenReturn(null);

        mHeadsetStateMachine.processSubscriberNumberRequest(mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessSubscriberNumberRequest_withSubscriberNumberNotNull() {
        String number = "1111";
        when(mSystemInterface.getSubscriberNumber()).thenReturn(number);

        mHeadsetStateMachine.processSubscriberNumberRequest(mDevice);

        verify(mNativeInterface)
                .atResponseString(
                        mDevice,
                        "+CNUM: ,\""
                                + number
                                + "\","
                                + PhoneNumberUtils.toaFromString(number)
                                + ",,4");
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessUnknownAt() {
        String atString = "+CSCS=invalid";
        mHeadsetStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.OPERATION_NOT_SUPPORTED);
        Mockito.clearInvocations(mNativeInterface);

        atString = "+CPBS=";
        mHeadsetStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.OPERATION_NOT_SUPPORTED);

        atString = "+CPBR=ERR";
        mHeadsetStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.TEXT_HAS_INVALID_CHARS);

        atString = "inval=";
        mHeadsetStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withNonExceptedNoEqualSignCommand() {
        String atString = "invalid_command";

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withUnsupportedCommand() {
        String atString = "invalid_command=";

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withQuestionMarkArg() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT + "=?arg";

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withValidCommandAndArg() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XAPL + "=1-12-3,1";

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, "+XAPL=iPhone," + "2");
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMI() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMI;

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.MANUFACTURER);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMM() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMM;

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.MODEL);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMR() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMR;

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface)
                .atResponseString(
                        mDevice,
                        String.format("%s (%s)", Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL));
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGSN() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGSN;

        mHeadsetStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.getSerial());
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVolumeEvent_withVolumeTypeMic() {
        when(mHeadsetService.getActiveDevice()).thenReturn(mDevice);

        mHeadsetStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, 1);

        assertThat(mHeadsetStateMachine.mMicVolume).isEqualTo(1);
    }

    @RequiresFlagsDisabled(FLAG_DEPRECATE_STREAM_BT_SCO)
    @Test
    public void testProcessVolumeEvent_withVolumeTypeSpk() {
        when(mHeadsetService.getActiveDevice()).thenReturn(mDevice);
        AudioManager mockAudioManager = mock(AudioManager.class);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO)).thenReturn(1);
        when(mSystemInterface.getAudioManager()).thenReturn(mockAudioManager);

        mHeadsetStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        assertThat(mHeadsetStateMachine.mSpeakerVolume).isEqualTo(2);
        verify(mockAudioManager).setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, 2, 0);
    }

    @RequiresFlagsEnabled(FLAG_DEPRECATE_STREAM_BT_SCO)
    @Test
    public void testProcessVolumeEvent_withVolumeTypeSpkAndStreamVoiceCall() {
        when(mHeadsetService.getActiveDevice()).thenReturn(mDevice);
        AudioManager mockAudioManager = mock(AudioManager.class);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)).thenReturn(1);
        when(mSystemInterface.getAudioManager()).thenReturn(mockAudioManager);

        mHeadsetStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        assertThat(mHeadsetStateMachine.mSpeakerVolume).isEqualTo(2);
        verify(mockAudioManager).setStreamVolume(AudioManager.STREAM_VOICE_CALL, 2, 0);
    }

    @Test
    public void testVolumeChangeEvent_fromIntentWhenAudioOn() {
        setUpAudioOnState();
        int originalVolume = mHeadsetStateMachine.mSpeakerVolume;
        mHeadsetStateMachine.mSpeakerVolume = 0;
        int vol = 10;

        // Send INTENT_SCO_VOLUME_CHANGED message
        Intent volumeChange = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        volumeChange.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, vol);

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.INTENT_SCO_VOLUME_CHANGED, volumeChange);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        // verify volume processed
        verify(mNativeInterface).setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_SPK, vol);

        mHeadsetStateMachine.mSpeakerVolume = originalVolume;
    }

    @Test
    public void testDump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();

        mHeadsetStateMachine.dump(sb);
    }

    /** A test to validate received Android AT commands and processing */
    @Test
    public void testCheckAndProcessAndroidAt() {
        // Commands that will be handled
        int counter_ok = 0;
        int counter_error = 0;
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=?", mDevice)).isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_ok))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,1,1,1", mDevice))
                .isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_ok))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,100,100,100", mDevice))
                .isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_ok))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,1,2,3,4,5", mDevice))
                .isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=1", mDevice)).isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2", mDevice)).isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2,3", mDevice))
                .isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2,3,4,5,6,7", mDevice))
                .isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(++counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);

        // Commands with correct format but will not be handled
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=", mDevice)).isFalse();
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID: PROBE,1,\"`AB\"", mDevice))
                .isFalse();
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID= PROBE,1,\"`AB\"", mDevice))
                .isFalse();
        assertThat(
                        mHeadsetStateMachine.checkAndProcessAndroidAt(
                                "AT+ANDROID=PROBE,1,1,\"PQGHRSBCTU__\"", mDevice))
                .isFalse();

        // Incorrect format AT command
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("RANDOM FORMAT", mDevice))
                .isFalse();

        // Check no any AT result was sent for the failed ones
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(counter_ok))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(counter_error))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testCheckAndProcessAndroidAt_handleConnectingTimePolicyNotAllowed() {
        when(mHeadsetService.getActiveDevice()).thenReturn(mDevice);
        mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=SINKAUDIOPOLICY,0,2,2", mDevice);
        verify(mHeadsetService).setActiveDevice(null);
    }

    @Test
    public void testCheckAndProcessAndroidAt_replyAndroidAtFeatureRequest() {
        // Commands that will be handled
        assertThat(mHeadsetStateMachine.checkAndProcessAndroidAt("+ANDROID=?", mDevice)).isTrue();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseString(mDevice, "+ANDROID: (SINKAUDIOPOLICY)");
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    /** A end to end test to validate received Android AT commands and processing */
    @Test
    public void testCheckAndProcessAndroidAtFromStateMachine() {
        // setAudioPolicyMetadata is invoked in HeadsetStateMachine.init() so start from 1
        int expectCallTimes = 1;

        // setup Audio Policy Feature
        setUpConnectedState();

        setUpAudioPolicy();
        // receive and set android policy
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT,
                        "+ANDROID=SINKAUDIOPOLICY,1,1,1",
                        mDevice));
        expectCallTimes++;
        verify(mDatabaseManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(expectCallTimes))
                .setAudioPolicyMetadata(anyObject(), anyObject());

        // receive and not set android policy
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT,
                        "AT+ANDROID=PROBE,1,1,\"PQGHRSBCTU__\"",
                        mDevice));
        verify(mDatabaseManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(expectCallTimes))
                .setAudioPolicyMetadata(anyObject(), anyObject());
    }

    /** A test to verify whether the sink audio policy command is valid */
    @Test
    public void testProcessAndroidAtSinkAudioPolicy() {
        // expected format
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,0,0", mDevice)).isTrue();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,0,1", mDevice)).isTrue();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,1,0", mDevice)).isTrue();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,1,0,0", mDevice)).isTrue();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,1,1,1", mDevice)).isTrue();

        // invalid format
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0", mDevice)).isFalse();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,0", mDevice)).isFalse();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,0,0,0", mDevice)).isFalse();
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,NOT,INT,TYPE", mDevice)).isFalse();
        assertThat(setSinkAudioPolicyArgs("RANDOM,VALUE-#$%,*(&^", mDevice)).isFalse();

        // wrong device
        BluetoothDevice device = getTestDevice(33);
        assertThat(setSinkAudioPolicyArgs("SINKAUDIOPOLICY,0,0,0", device)).isFalse();
    }

    /** Test setting audio parameters according to received SWB event. SWB AptX is enabled. */
    @Test
    public void testSetAudioParameters_SwbAptxEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        setUpConnectedState();
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verifyAudioSystemSetParametersInvocation(false, true);
        configureHeadsetServiceForAptxVoice(false);
    }

    /** Test setting audio parameters according to received SWB event. SWB LC3 is enabled. */
    @Test
    public void testSetAudioParameters_SwbLc3Enabled() {
        configureHeadsetServiceForAptxVoice(true);
        setUpConnectedState();
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verifyAudioSystemSetParametersInvocation(true, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    /** Test setting audio parameters according to received SWB event. All SWB disabled. */
    @Test
    public void testSetAudioParameters_SwbDisabled() {
        configureHeadsetServiceForAptxVoice(true);
        setUpConnectedState();
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_NO,
                        mDevice));

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verifyAudioSystemSetParametersInvocation(false, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    @Test
    public void testSetAudioParameters_isScoManagedByAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        setUpConnectedState();
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));

        verify(mAudioManager, times(0)).setParameters(any());
        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * verify parameters given to audio system
     *
     * @param lc3Enabled if true check if SWB LC3 was enabled
     * @param aptxEnabled if true check if SWB AptX was enabled
     */
    private void verifyAudioSystemSetParametersInvocation(boolean lc3Enabled, boolean aptxEnabled) {
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .setParameters(lc3Enabled ? "bt_lc3_swb=on" : "bt_lc3_swb=off");

        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .setParameters(aptxEnabled ? "bt_swb=0" : "bt_swb=65535");
    }

    /**
     * set sink audio policy
     *
     * @param arg body of the AT command
     * @return the result from processAndroidAtSinkAudioPolicy
     */
    private boolean setSinkAudioPolicyArgs(String arg, BluetoothDevice device) {
        Object[] args = HeadsetStateMachine.generateArgs(arg);
        return mHeadsetStateMachine.processAndroidAtSinkAudioPolicy(args, device);
    }

    /**
     * Setup Connecting State
     *
     * @return number of times mHeadsetService.sendBroadcastAsUser() has been invoked
     */
    private int setUpConnectingState() {
        // Put test state machine in connecting state
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.CONNECT, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTING, STATE_DISCONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);
        return 1;
    }

    /**
     * Setup Connected State
     *
     * @return number of times mHeadsetService.sendBroadcastAsUser() has been invoked
     */
    private int setUpConnectedState() {
        // Put test state machine into connected state
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTING, STATE_DISCONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_CONNECTED, STATE_CONNECTING, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
        return 2;
    }

    private int setUpAudioConnectingState() {
        int numBroadcastsSent = setUpConnectedState();
        // Send CONNECT_AUDIO
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
        return numBroadcastsSent;
    }

    private int setUpAudioOnState() {
        int numBroadcastsSent = setUpAudioConnectingState();
        // Send StackEvent.AUDIO_DISCONNECTED message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyAudioStateBroadcast(
                mDevice,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioOn.class);
        return numBroadcastsSent;
    }

    private int setUpAudioDisconnectingState() {
        int numBroadcastsSent = setUpAudioOnState();
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, mDevice);
        // No new broadcast due to lack of AUDIO_DISCONNECTING intent variable
        verify(mHeadsetService, after(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        any(Intent.class),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
        return numBroadcastsSent;
    }

    private int setUpDisconnectingState() {
        int numBroadcastsSent = setUpConnectedState();
        // Send DISCONNECT message
        numBroadcastsSent++;
        mHeadsetStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT, mDevice);
        verify(mHeadsetService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent))
                .sendBroadcastAsUser(
                        mIntentArgument.capture(),
                        eq(UserHandle.ALL),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
        HeadsetTestUtils.verifyConnectionStateBroadcast(
                mDevice, STATE_DISCONNECTING, STATE_CONNECTED, mIntentArgument.getValue());
        assertThat(mHeadsetStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnecting.class);
        return numBroadcastsSent;
    }

    private void setUpAudioPolicy() {
        mHeadsetStateMachine.sendMessage(
                HeadsetStateMachine.STACK_EVENT,
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT, "+ANDROID=?", mDevice));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseString(anyObject(), anyString());
    }

    private void configureHeadsetServiceForAptxVoice(boolean enable) {
        if (enable) {
            when(mHeadsetService.isAptXSwbEnabled()).thenReturn(true);
        }
    }
}
