/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED;
import static android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED;
import static android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTING;
import static android.bluetooth.BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothStatusCodes.SUCCESS;
import static android.platform.test.flag.junit.DeviceFlagsValueProvider.createCheckFlagsRule;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.hfp.HeadsetStateMachine.FLAG_ABSOLUTE_VOLUME;
import static com.android.bluetooth.hfp.HeadsetStateMachine.HFP_VOLUME_CONTROL_ENABLED;
import static com.android.bluetooth.hfp.HeadsetStateMachine.sConnectTimeoutMs;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemProperties;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
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

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link HeadsetStateMachine}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class HeadsetStateMachineTest {
    @Rule public final CheckFlagsRule mCheckFlagsRule = createCheckFlagsRule();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Mock private BluetoothSinkAudioPolicy sinkAudioPolicy;
    @Mock private AdapterService mAdapterService;
    @Mock private AudioManager mAudioManager;
    @Mock private BluetoothStorageManager mStorage;
    @Mock private HeadsetNativeInterface mNativeInterface;
    @Mock private HeadsetPhoneState mPhoneState;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HeadsetSystemInterface mSystemInterface;
    @Mock private RemoteDevices mRemoteDevices;
    @Mock private Resources mResources;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;

    private static final String TEST_PHONE_NUMBER = "1234567890";
    private static final int MAX_RETRY_DISCONNECT_AUDIO = 3;
    private static final int MIC_MUTE = 0;
    private static final int MIC_UNMUTE = 15;

    private final BluetoothDevice mDevice = getTestDevice(87);

    private MockContentResolver mMockContentResolver;
    private InOrder mInOrder;
    private TestLooper mLooper;
    private HeadsetStateMachine mStateMachine;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(android.media.audio.Flags.FLAG_SCO_MANAGED_BY_AUDIO);
    }

    public HeadsetStateMachineTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(READ_PRIVILEGED_PHONE_STATE);

        doReturn(mPhoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(mAudioManager).when(mSystemInterface).getAudioManager();

        doReturn(sinkAudioPolicy).when(mStorage).getAudioPolicyMetadata(any());

        doReturn(true).when(mNativeInterface).connectHfp(mDevice);
        doReturn(true).when(mNativeInterface).disconnectHfp(mDevice);
        doReturn(true).when(mNativeInterface).connectAudio(mDevice);
        doReturn(true).when(mNativeInterface).disconnectAudio(mDevice);

        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        mMockContentResolver = new MockContentResolver();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(mResources).when(mAdapterService).getResources();

        doReturn("").when(mResources).getString(anyInt());

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(true).when(mHeadsetService).okToAcceptConnection(any(), anyBoolean());
        doReturn(SUCCESS).when(mHeadsetService).isScoAcceptable(any());

        mInOrder = inOrder(mHeadsetService, mNativeInterface, mStorage);

        mLooper = new TestLooper();

        mStateMachine =
                new HeadsetStateMachine(
                        mDevice,
                        mLooper.getLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Disconnected.class);
    }

    /** Test that state is Connected after calling setUpConnectedState() */
    @Test
    public void testSetupConnectedState() {
        setUpConnectedState();
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /** Test state transition from Disconnected to Connecting state via CONNECT message */
    @Test
    public void testStateTransition_DisconnectedToConnecting_Connect() {
        sendAndDispatchMessage(HeadsetStateMachine.CONNECT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
    }

    /**
     * Test state transition from Disconnected to Connecting state via StackEvent.CONNECTED message
     */
    @Test
    public void testStateTransition_DisconnectedToConnecting_StackConnected() {
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                STATE_DISCONNECTED,
                STATE_CONNECTING);
    }

    /**
     * Test state transition from Disconnected to Connecting state via StackEvent.CONNECTING message
     */
    @Test
    public void testStateTransition_DisconnectedToConnecting_StackConnecting() {
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_CONNECTING,
                STATE_DISCONNECTED,
                STATE_CONNECTING);
    }

    /**
     * Test state transition from Connecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_ConnectingToDisconnected_StackDisconnected() {
        setUpConnectingState();
        // Indicate disconnecting to test state machine that should do nothing
        generateUnexpectedConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING);

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connecting.class);

        // Indicate connection failed to test state machine
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                STATE_CONNECTING,
                STATE_DISCONNECTED);
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        setUpConnectingState();

        mLooper.moveTimeForward(sConnectTimeoutMs);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
    }

    /**
     * Test state transition from Connecting to Connected state via StackEvent.SLC_CONNECTED message
     */
    @Test
    public void testStateTransition_ConnectingToConnected_StackSlcConnected() {
        setUpConnectingState();
        // Indicate connecting to test state machine that should do nothing
        generateUnexpectedConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_CONNECTING);

        // Indicate RFCOMM connection is successful to test state machine that should do nothing
        generateUnexpectedConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_CONNECTED);

        // Indicate SLC connection is successful to test state machine
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                STATE_CONNECTING,
                STATE_CONNECTED);
    }

    /**
     * Test state transition from Disconnecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_DisconnectingToDisconnected_StackDisconnected() {
        setUpDisconnectingState();

        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                STATE_DISCONNECTING,
                STATE_DISCONNECTED);
    }

    /**
     * Test state transition from Disconnecting to Disconnected state via CONNECT_TIMEOUT message
     */
    @Test
    public void testStateTransition_DisconnectingToDisconnected_Timeout() {
        setUpDisconnectingState();

        mLooper.moveTimeForward(sConnectTimeoutMs);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTING, STATE_DISCONNECTED);
    }

    /**
     * Test state transition from Disconnecting to Connected state via StackEvent.SLC_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_DisconnectingToConnected_StackSlcConnected() {
        setUpDisconnectingState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                STATE_DISCONNECTING,
                STATE_CONNECTED);
    }

    @Test
    public void testStateTransition_ConnectedToDisconnecting_Disconnect() {
        setUpConnectedState();
        sendAndDispatchMessage(HeadsetStateMachine.DISCONNECT);
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTING);
    }

    /**
     * Test state transition from Connected to Disconnecting state via StackEvent.DISCONNECTING
     * message
     */
    @Test
    public void testStateTransition_ConnectedToDisconnecting_StackDisconnecting() {
        setUpConnectedState();

        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                STATE_CONNECTED,
                STATE_DISCONNECTING);
    }

    /**
     * Test state transition from Connected to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_ConnectedToDisconnected_StackDisconnected() {
        setUpConnectedState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                STATE_CONNECTED,
                STATE_DISCONNECTED);
    }

    /** Test state transition from Connected to AudioConnecting state via CONNECT_AUDIO message */
    @Test
    public void testStateTransition_ConnectedToAudioConnecting_ConnectAudio() {
        setUpConnectedState();
        sendAndDispatchMessage(HeadsetStateMachine.CONNECT_AUDIO);
        verifyAudioStateIntent(STATE_AUDIO_DISCONNECTED, STATE_AUDIO_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    /**
     * Test state transition from Connected to AudioConnecting state via CONNECT_AUDIO message when
     * ScoManagedByAudioEnabled
     */
    @Test
    @EnableFlags(android.media.audio.Flags.FLAG_SCO_MANAGED_BY_AUDIO)
    public void testStateTransition_ConnectedToAudioConnecting_ConnectAudio_ScoManagedByAudio() {
        doReturn(true).when(mSystemInterface).isScoManagedByAudioEnabled();

        setUpConnectedState();
        sendAndDispatchMessage(HeadsetStateMachine.CONNECT_AUDIO);
        // verify no native connect audio
        verify(mNativeInterface, never()).connectAudio(mDevice);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    /**
     * Test state transition from Connected to AudioConnecting state via StackEvent.AUDIO_CONNECTING
     * message
     */
    @Test
    public void testStateTransition_ConnectedToAudioConnecting_StackAudioConnecting() {
        setUpConnectedState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_CONNECTING,
                STATE_AUDIO_DISCONNECTED,
                STATE_AUDIO_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    /**
     * Test state transition from Connected to AudioOn state via StackEvent.AUDIO_CONNECTED message
     */
    @Test
    public void testStateTransition_ConnectedToAudioOn_StackAudioConnected() {
        setUpConnectedState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                STATE_AUDIO_DISCONNECTED,
                STATE_AUDIO_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /** Test state transition from AudioConnecting to Connected state via CONNECT_TIMEOUT message */
    @Test
    public void testStateTransition_AudioConnectingToConnected_Timeout() {
        setUpAudioConnectingState();

        mLooper.moveTimeForward(sConnectTimeoutMs);
        mLooper.dispatchAll();

        verifyAudioStateIntent(STATE_AUDIO_CONNECTING, STATE_AUDIO_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioConnecting to Connected state via
     * StackEvent.AUDIO_DISCONNECTED message
     */
    @Test
    public void testStateTransition_AudioConnectingToConnected_StackAudioDisconnected() {
        setUpAudioConnectingState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTING,
                STATE_AUDIO_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioConnecting to Disconnected state via StackEvent.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioConnectingToDisconnected_StackDisconnected() {
        setUpAudioConnectingState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                STATE_CONNECTED,
                STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTING,
                STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Test state transition from AudioConnecting to Disconnecting state via
     * StackEvent.DISCONNECTING message
     */
    @Test
    public void testStateTransition_AudioConnectingToDisconnecting_StackDisconnecting() {
        setUpAudioConnectingState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                STATE_CONNECTED,
                STATE_DISCONNECTING,
                STATE_AUDIO_CONNECTING,
                STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Test state transition from AudioConnecting to AudioOn state via StackEvent.AUDIO_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioConnectingToAudioOn_StackAudioConnected() {
        setUpAudioConnectingState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                STATE_AUDIO_CONNECTING,
                STATE_AUDIO_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via
     * StackEvent.AUDIO_DISCONNECTING message
     */
    @Test
    public void testStateTransition_AudioOnToAudioDisconnecting_StackAudioDisconnecting() {
        setUpAudioOnState();
        // Should not sent any broadcast due to lack of AUDIO_DISCONNECTING intent value
        generateUnexpectedAudioMessageFromNative(HeadsetHalConstants.AUDIO_STATE_DISCONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via DISCONNECT_AUDIO message
     */
    @Test
    public void testStateTransition_AudioOnToAudioDisconnecting_DisconnectAudio() {
        setUpAudioOnState();
        sendAndDispatchMessage(HeadsetStateMachine.DISCONNECT_AUDIO);
        // Should not sent any broadcast due to lack of AUDIO_DISCONNECTING intent value
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
    }

    /**
     * Test state transition from AudioOn to AudioDisconnecting state via Stack.AUDIO_DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioOnToConnected_StackAudioDisconnected() {
        setUpAudioOnState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /** Test state transition from AudioOn to Disconnected state via Stack.DISCONNECTED message */
    @Test
    public void testStateTransition_AudioOnToDisconnected_StackDisconnected() {
        setUpAudioOnState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                STATE_CONNECTED,
                STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
    }

    /** Test state transition from AudioOn to Disconnecting state via Stack.DISCONNECTING message */
    @Test
    public void testStateTransition_AudioOnToDisconnecting_StackDisconnecting() {
        setUpAudioOnState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                STATE_CONNECTED,
                STATE_DISCONNECTING,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Test state transition from AudioDisconnecting to AudioOn state via CONNECT_TIMEOUT message
     * until retry count is reached, then test transition to Disconnecting state.
     */
    @Test
    public void testStateTransition_AudioDisconnectingToAudioOnAndDisconnecting_Timeout() {
        setUpAudioDisconnectingState();
        // Wait for connection to timeout
        for (int i = 0; i <= MAX_RETRY_DISCONNECT_AUDIO; i++) {
            if (i > 0) { // Skip first AUDIO_DISCONNECTING init as it was setup before the loop
                sendAndDispatchMessage(HeadsetStateMachine.DISCONNECT_AUDIO);
                // No new broadcast due to lack of AUDIO_DISCONNECTING intent variable
                verifyNoIntentSent();
                assertThat(mStateMachine.getCurrentState())
                        .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
            }

            mLooper.moveTimeForward(sConnectTimeoutMs);
            mLooper.dispatchAll();

            if (i < MAX_RETRY_DISCONNECT_AUDIO) { // Test if state is AudioOn before max retry
                verifyAudioStateIntent(STATE_AUDIO_CONNECTED, STATE_AUDIO_CONNECTED);
                assertThat(mStateMachine.getCurrentState())
                        .isInstanceOf(HeadsetStateMachine.AudioOn.class);
            } else { // Max retry count reached, test Disconnecting state
                verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTING);
            }
        }
    }

    /**
     * Test state transition from AudioDisconnecting to Connected state via Stack.AUDIO_DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToConnected_StackAudioDisconnected() {
        setUpAudioDisconnectingState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.Connected.class);
    }

    /**
     * Test state transition from AudioDisconnecting to AudioOn state via Stack.AUDIO_CONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToAudioOn_StackAudioConnected() {
        setUpAudioDisconnectingState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    /**
     * Test state transition from AudioDisconnecting to Disconnecting state via Stack.DISCONNECTING
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToDisconnecting_StackDisconnecting() {
        setUpAudioDisconnectingState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING,
                STATE_CONNECTED,
                STATE_DISCONNECTING,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Test state transition from AudioDisconnecting to Disconnecting state via Stack.DISCONNECTED
     * message
     */
    @Test
    public void testStateTransition_AudioDisconnectingToDisconnected_StackDisconnected() {
        setUpAudioDisconnectingState();
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                STATE_CONNECTED,
                STATE_DISCONNECTED,
                STATE_AUDIO_CONNECTED,
                STATE_AUDIO_DISCONNECTED);
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

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(true, true, false, false),
                        mDevice));
        verify(mPhoneState).listenForPhoneState(mDevice, PhoneStateListener.LISTEN_SERVICE_STATE);

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(false, false, true, false),
                        mDevice));
        verify(mPhoneState)
                .listenForPhoneState(mDevice, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(false, true, true, false),
                        mDevice));
        verify(mPhoneState).listenForPhoneState(mDevice, PhoneStateListener.LISTEN_SERVICE_STATE);

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_BIA,
                        new HeadsetAgIndicatorEnableState(false, false, false, false),
                        mDevice));
        verify(mPhoneState).listenForPhoneState(mDevice, PhoneStateListener.LISTEN_NONE);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventWhenIdleAndAudioOff_dialCall() {
        setUpConnectedState();
        Cursor cursor = mock(Cursor.class);
        doReturn(1).when(cursor).getCount();
        doReturn(true).when(cursor).moveToNext();
        int magicNumber = 42;
        doReturn(magicNumber).when(cursor).getColumnIndexOrThrow(CallLog.Calls.NUMBER);
        doReturn(TEST_PHONE_NUMBER).when(cursor).getString(magicNumber);
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
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mHeadsetService).dialOutgoingCall(mDevice, TEST_PHONE_NUMBER);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventDuringRinging_answerCall() {
        setUpConnectedState();
        doReturn(true).when(mSystemInterface).isRinging();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mSystemInterface).answerCall(mDevice);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventInCallButAudioOff_setActiveDevice() {
        setUpConnectedState();
        doReturn(true).when(mSystemInterface).isInCall();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mHeadsetService).setActiveDevice(mDevice);
    }

    /** A test to verify that we correctly handles key pressed event from a HSP headset */
    @Test
    public void testKeyPressedEventInCallAndAudioOn_hangupCall() {
        setUpAudioOnState();
        doReturn(true).when(mSystemInterface).isInCall();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mSystemInterface).hangupCall(mDevice);
    }

    /** A test to verify that we correctly send CIND response when a call is in progress */
    @Test
    public void testCindEventWhenCallIsInProgress() {
        doReturn(HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE)
                .when(mPhoneState)
                .getCindService();
        doReturn(false).when(mHeadsetService).isVirtualCallStarted();
        doReturn(1).when(mPhoneState).getNumActiveCall();

        setUpAudioOnState();

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_CIND, mDevice));
        // wait state machine to process the message
        verify(mNativeInterface)
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
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, mDevice));
        verify(mNativeInterface).disconnectAudio(mDevice);
    }

    /** A test to verify that we correctly handles AT+BIND event with driver safety case from HF */
    @Test
    public void testAtBindWithDriverSafetyEventWhenConnecting() {
        setUpConnectingState();

        String atString = "1";
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        verifyHfIndicatorIntent(HeadsetHalConstants.HF_INDICATOR_ENHANCED_DRIVER_SAFETY, -1);
    }

    /** A test to verify that we correctly handles AT+BIND event with battery level case from HF */
    @Test
    public void testAtBindEventWithBatteryLevelEventWhenConnecting() {
        setUpConnectingState();

        String atString = "2";
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        verifyHfIndicatorIntent(HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS, -1);
    }

    /** A test to verify that we correctly handles AT+BIND event with error case from HF */
    @Test
    public void testAtBindEventWithErrorEventWhenConnecting() {
        setUpConnectingState();

        String atString = "err,A,123,,1";
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, mDevice));
        verifyHfIndicatorIntent(HeadsetHalConstants.HF_INDICATOR_ENHANCED_DRIVER_SAFETY, -1);
    }

    /** A test to verify that we correctly set AG indicator mask when enter/exit silence mode */
    @Test
    public void testSetSilenceDevice() {
        doNothing().when(mPhoneState).listenForPhoneState(any(BluetoothDevice.class), anyInt());
        mStateMachine.setSilenceDevice(true);
        mStateMachine.setSilenceDevice(false);
        verify(mPhoneState, times(2)).listenForPhoneState(mDevice, PhoneStateListener.LISTEN_NONE);
    }

    @Test
    public void testBroadcastVendorSpecificEventIntent() {
        mStateMachine.broadcastVendorSpecificEventIntent("command", 1, 1, null, mDevice);
        verifyIntentSent(hasAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT));
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
        assertThat(mStateMachine.getAtCommandType(atCommand)).isEqualTo(AtPhonebook.TYPE_READ);

        atCommand = "start=?";
        assertThat(mStateMachine.getAtCommandType(atCommand)).isEqualTo(AtPhonebook.TYPE_TEST);

        atCommand = "start=comm";
        assertThat(mStateMachine.getAtCommandType(atCommand)).isEqualTo(AtPhonebook.TYPE_SET);

        atCommand = "start!";
        assertThat(mStateMachine.getAtCommandType(atCommand)).isEqualTo(AtPhonebook.TYPE_UNKNOWN);
    }

    @Test
    public void testParseUnknownAt() {
        String atString = "\"command\"";

        assertThat(mStateMachine.parseUnknownAt(atString)).isEqualTo("\"command\"");
    }

    @Test
    public void testParseUnknownAt_withUnmatchingQuotes() {
        String atString = "\"command";

        assertThat(mStateMachine.parseUnknownAt(atString)).isEqualTo("\"command\"");
    }

    @Test
    public void testParseUnknownAt_withCharOutsideQuotes() {
        String atString = "a\"command\"";

        assertThat(mStateMachine.parseUnknownAt(atString)).isEqualTo("A\"command\"");
    }

    @Test
    public void testHandleAccessPermissionResult_withNoChangeInAtCommandResult() {
        var intent =
                new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)
                        .putExtra(
                                BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                BluetoothDevice.CONNECTION_ACCESS_NO)
                        .putExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)
                        .putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mStateMachine.mPhonebook.setCheckingAccessPermission(true);

        mStateMachine.handleAccessPermissionResult(intent);
        verify(mNativeInterface).atResponseCode(mDevice, 0, 0);
    }

    @Test
    public void testProcessAtBievCommand() {
        mStateMachine.processAtBiev(1, 1, mDevice);

        verifyHfIndicatorIntent(1, 1);
    }

    @Test
    public void testProcessAtChld_withProcessChldTrue() {
        int chld = 1;
        doReturn(true).when(mSystemInterface).processChld(mHeadsetService, chld);

        mStateMachine.processAtChld(chld, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessAtChld_withProcessChldFalse() {
        int chld = 1;
        doReturn(false).when(mSystemInterface).processChld(mHeadsetService, chld);

        mStateMachine.processAtChld(chld, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessAtClcc_withVirtualCallStarted() {
        doReturn(true).when(mHeadsetService).isVirtualCallStarted();

        mStateMachine.processAtClcc(mDevice);

        verify(mNativeInterface).clccResponse(mDevice, 0, 0, 0, 0, false, "", 0);
    }

    @Test
    public void testProcessAtClcc_withVirtualCallNotStarted() {
        doReturn(false).when(mHeadsetService).isVirtualCallStarted();
        doReturn(false).when(mSystemInterface).listCurrentCalls(any());

        mStateMachine.processAtClcc(mDevice);

        verify(mNativeInterface).clccResponse(mDevice, 0, 0, 0, 0, false, "", 0);
    }

    @Test
    public void testProcessAtCops() {
        ServiceState serviceState = mock(ServiceState.class);
        doReturn("").when(serviceState).getOperatorAlphaLong();
        doReturn("").when(serviceState).getOperatorAlphaShort();
        HeadsetPhoneState phoneState = mock(HeadsetPhoneState.class);
        doReturn(serviceState).when(phoneState).getServiceState();
        doReturn(phoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(true).when(mSystemInterface).isInCall();
        doReturn(null).when(mSystemInterface).getNetworkOperator();

        mStateMachine.processAtCops(mDevice);

        verify(mNativeInterface).copsResponse(mDevice, "");
    }

    @Test
    public void testProcessAtCpbr() {
        String atString = "command=ERR";
        int type = AtPhonebook.TYPE_SET;

        mStateMachine.processAtCpbr(atString, type, mDevice);

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

        mStateMachine.processAtCpbs(atString, type, mDevice);

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

        mStateMachine.processAtCscs(atString, type, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, -1);
    }

    @Test
    public void testProcessAtXapl() {
        Object[] args = new Object[2];
        args[0] = "1-12-3";
        args[1] = 1;

        mStateMachine.processAtXapl(args, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, "+XAPL=iPhone," + String.valueOf(2));
    }

    @Test
    public void testProcessSendVendorSpecificResultCode() {
        HeadsetVendorSpecificResultCode resultCode =
                new HeadsetVendorSpecificResultCode(mDevice, "command", "arg");

        mStateMachine.processSendVendorSpecificResultCode(resultCode);

        verify(mNativeInterface).atResponseString(mDevice, "command" + ": " + "arg");
    }

    @Test
    public void testProcessSubscriberNumberRequest_withSubscriberNumberNull() {
        mStateMachine.processSubscriberNumberRequest(mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessSubscriberNumberRequest_withSubscriberNumberNotNull() {
        String number = "1111";
        doReturn(number).when(mSystemInterface).getSubscriberNumber();

        mStateMachine.processSubscriberNumberRequest(mDevice);

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
        mStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.OPERATION_NOT_SUPPORTED);
        Mockito.clearInvocations(mNativeInterface);

        atString = "+CPBS=";
        mStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.OPERATION_NOT_SUPPORTED);

        atString = "+CPBR=ERR";
        mStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface)
                .atResponseCode(
                        mDevice,
                        HeadsetHalConstants.AT_RESPONSE_ERROR,
                        BluetoothCmeError.TEXT_HAS_INVALID_CHARS);

        atString = "inval=";
        mStateMachine.processUnknownAt(atString, mDevice);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withNonExceptedNoEqualSignCommand() {
        String atString = "invalid_command";

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withUnsupportedCommand() {
        String atString = "invalid_command=";

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withQuestionMarkArg() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT + "=?arg";

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withValidCommandAndArg() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XAPL + "=1-12-3,1";

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, "+XAPL=iPhone," + "2");
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMI() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMI;

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.MANUFACTURER);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMM() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMM;

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.MODEL);
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGMR() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGMR;

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface)
                .atResponseString(
                        mDevice,
                        String.format("%s (%s)", Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL));
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testProcessVendorSpecificAt_withExceptedNoEqualSignCommandCGSN() {
        String atString = BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_CGSN;

        mStateMachine.processVendorSpecificAt(atString, mDevice);

        verify(mNativeInterface).atResponseString(mDevice, Build.getSerial());
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void testMicMuteStatusChange_WhenAudioOn() {
        setUpAudioOnState();

        Intent micMuteChange = new Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);

        doReturn(true).when(mAudioManager).isMicrophoneMute();

        sendAndDispatchMessage(HeadsetStateMachine.MICROPHONE_VOL_MUTE_CHANGED, micMuteChange);

        // verify volume processed
        verify(mNativeInterface).setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_MUTE);

        doReturn(false).when(mAudioManager).isMicrophoneMute();

        sendAndDispatchMessage(HeadsetStateMachine.MICROPHONE_VOL_MUTE_CHANGED, micMuteChange);

        // verify volume processed
        verify(mNativeInterface)
                .setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_UNMUTE);
    }

    @Test
    @DisableFlags(Flags.FLAG_MICROPHONE_MUTE_GAIN_RETAIN)
    public void testProcessVolumeEvent_withVolumeTypeMic_old() {
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_UNMUTE);

        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
        verify(mockAudioManager).setMicrophoneMute(false);

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_MUTE);

        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_MUTE);
        verify(mockAudioManager).setMicrophoneMute(true);
    }

    @Test
    @EnableFlags(Flags.FLAG_MICROPHONE_MUTE_GAIN_RETAIN)
    public void testProcessVolumeEvent_withVolumeTypeMic() {
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_UNMUTE);

        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
        verify(mockAudioManager).setMicrophoneMute(false);

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_MUTE);

        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
        verify(mockAudioManager).setMicrophoneMute(true);
    }

    @Test
    @EnableFlags(Flags.FLAG_MICROPHONE_MUTE_GAIN_RETAIN)
    public void testProcessVolumeEvent_and_MicMuteStatusChange() {
        setUpAudioOnState();
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_UNMUTE);

        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
        verify(mockAudioManager).setMicrophoneMute(false);
        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_MUTE);

        Intent micMuteChange = new Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);

        doReturn(true).when(mAudioManager).isMicrophoneMute();

        sendAndDispatchMessage(HeadsetStateMachine.MICROPHONE_VOL_MUTE_CHANGED, micMuteChange);

        // verify volume processed
        verify(mNativeInterface)
                .setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_UNMUTE);
        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_MIC, MIC_MUTE);
        verify(mockAudioManager, times(2)).setMicrophoneMute(true);
        assertThat(mStateMachine.mMicVolume).isEqualTo(MIC_UNMUTE);
    }

    @Test
    @RequiresFlagsDisabled(android.media.audio.Flags.FLAG_DEPRECATE_STREAM_BT_SCO)
    public void testProcessVolumeEvent_withVolumeTypeSpk() {
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(1).when(mockAudioManager).getStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO);
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        assertThat(mStateMachine.mSpeakerVolume).isEqualTo(2);
        verify(mockAudioManager)
                .setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, 2, FLAG_ABSOLUTE_VOLUME);
    }

    @Test
    @RequiresFlagsEnabled(android.media.audio.Flags.FLAG_DEPRECATE_STREAM_BT_SCO)
    public void testProcessVolumeEvent_withVolumeTypeSpkAndStreamVoiceCall() {
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(1).when(mockAudioManager).getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        assertThat(mStateMachine.mSpeakerVolume).isEqualTo(2);
        verify(mockAudioManager)
                .setStreamVolume(AudioManager.STREAM_VOICE_CALL, 2, FLAG_ABSOLUTE_VOLUME);
    }

    @Test
    public void testProcessVolumeEventAudioConnected_withVolumeControlEnabled_ShowUiFlagEnabled() {
        mockSystemPropertyGet(HFP_VOLUME_CONTROL_ENABLED, true);

        setUpAudioOnState();

        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(1).when(mockAudioManager).getStreamVolume(anyInt());
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        var flagsCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mockAudioManager).setStreamVolume(anyInt(), anyInt(), flagsCaptor.capture());
        assertThat(flagsCaptor.getValue() & AudioManager.FLAG_SHOW_UI)
                .isEqualTo(AudioManager.FLAG_SHOW_UI);
    }

    @Test
    public void testProcessVolumeEventAudioConnected_withVolumeControlEnabled_ShowUiFlagDisabled() {
        mockSystemPropertyGet(HFP_VOLUME_CONTROL_ENABLED, false);

        setUpAudioOnState();

        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(1).when(mockAudioManager).getStreamVolume(anyInt());
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        var flagsCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mockAudioManager).setStreamVolume(anyInt(), anyInt(), flagsCaptor.capture());
        assertThat(flagsCaptor.getValue() & AudioManager.FLAG_SHOW_UI).isEqualTo(0);
    }

    @Test
    public void testProcessVolumeEventAudioConnected_withVolumeControlEnabled_SetAbsVolFlag() {
        setUpAudioOnState();

        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        AudioManager mockAudioManager = mock(AudioManager.class);
        doReturn(1).when(mockAudioManager).getStreamVolume(anyInt());
        doReturn(mockAudioManager).when(mSystemInterface).getAudioManager();

        mStateMachine.processVolumeEvent(HeadsetHalConstants.VOLUME_TYPE_SPK, 2);

        var flagsCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mockAudioManager).setStreamVolume(anyInt(), anyInt(), flagsCaptor.capture());
        assertThat(flagsCaptor.getValue() & FLAG_ABSOLUTE_VOLUME).isEqualTo(FLAG_ABSOLUTE_VOLUME);
    }

    @Test
    public void testVolumeChangeEvent_fromIntentWhenAudioOn() {
        setUpAudioOnState();
        int originalVolume = mStateMachine.mSpeakerVolume;
        mStateMachine.mSpeakerVolume = 0;
        int vol = 10;

        // Send INTENT_SCO_VOLUME_CHANGED message
        Intent volumeChange = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        volumeChange.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, vol);

        sendAndDispatchMessage(HeadsetStateMachine.INTENT_SCO_VOLUME_CHANGED, volumeChange);

        // verify volume processed
        verify(mNativeInterface).setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_SPK, vol);

        mStateMachine.mSpeakerVolume = originalVolume;
    }

    @Test
    @EnableFlags(android.media.audio.Flags.FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void testVolumeChangeEvent_fromVolumeIndexWhenAudioOn() {
        setUpAudioOnState();
        int originalVolume = mStateMachine.mSpeakerVolume;
        mStateMachine.mSpeakerVolume = 0;
        int vol = 10;

        sendAndDispatchMessage(HeadsetStateMachine.SCO_VOLUME_CHANGED, vol);

        // verify volume processed
        verify(mNativeInterface).setVolume(mDevice, HeadsetHalConstants.VOLUME_TYPE_SPK, vol);

        mStateMachine.mSpeakerVolume = originalVolume;
    }

    @Test
    public void testDump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();

        mStateMachine.dump(sb);
    }

    /**
     * Test that an unexpected ANSWER_CALL event is handled in Connecting state for compatibility.
     */
    @Test
    public void testUnexpectedAnswerCallEventInConnectingState() {
        setUpConnectingState();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_ANSWER_CALL, mDevice));
        verify(mSystemInterface).answerCall(mDevice);
    }

    /**
     * Test that an unexpected HANGUP_CALL event is handled in Connecting state for compatibility.
     */
    @Test
    public void testUnexpectedHangupCallEventInConnectingState() {
        setUpConnectingState();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_HANGUP_CALL, mDevice));
        verify(mSystemInterface).hangupCall(mDevice);
    }

    /**
     * Test that an unexpected VOLUME_CHANGED event is handled in Connecting state for
     * compatibility.
     */
    @Test
    public void testUnexpectedVolumeChangedEventInConnectingState() {
        setUpConnectingState();
        // The device is not active in Connecting state, so volume change should be ignored.
        doReturn(null).when(mHeadsetService).getActiveDevice();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VOLUME_CHANGED,
                        HeadsetHalConstants.VOLUME_TYPE_SPK,
                        10,
                        mDevice));
        // Verify that AudioManager is not called to set volume
        verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
    }

    /** A test to validate received Android AT commands and processing */
    @Test
    public void testCheckAndProcessAndroidAt() {
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=?", mDevice)).isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,1,1,1", mDevice))
                .isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,100,100,100", mDevice))
                .isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        assertThat(
                        mStateMachine.checkAndProcessAndroidAt(
                                "+ANDROID=SINKAUDIOPOLICY,1,2,3,4,5", mDevice))
                .isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=1", mDevice)).isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2", mDevice)).isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2,3", mDevice)).isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=1,2,3,4,5,6,7", mDevice))
                .isTrue();
        mInOrder.verify(mNativeInterface)
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);

        // Commands with correct format but will not be handled
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=", mDevice)).isFalse();
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID: PROBE,1,\"`AB\"", mDevice))
                .isFalse();
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID= PROBE,1,\"`AB\"", mDevice))
                .isFalse();
        assertThat(
                        mStateMachine.checkAndProcessAndroidAt(
                                "AT+ANDROID=PROBE,1,1,\"PQGHRSBCTU__\"", mDevice))
                .isFalse();

        // Incorrect format AT command
        assertThat(mStateMachine.checkAndProcessAndroidAt("RANDOM FORMAT", mDevice)).isFalse();

        // Check no any AT result was sent for the failed ones
        mInOrder.verify(mNativeInterface, never())
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        mInOrder.verify(mNativeInterface, never())
                .atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void testCheckAndProcessAndroidAt_handleConnectingTimePolicyNotAllowed() {
        doReturn(mDevice).when(mHeadsetService).getActiveDevice();
        mStateMachine.checkAndProcessAndroidAt("+ANDROID=SINKAUDIOPOLICY,0,2,2", mDevice);
        verify(mHeadsetService).setActiveDevice(null);
    }

    @Test
    public void testCheckAndProcessAndroidAt_replyAndroidAtFeatureRequest() {
        // Commands that will be handled
        assertThat(mStateMachine.checkAndProcessAndroidAt("+ANDROID=?", mDevice)).isTrue();
        verify(mNativeInterface).atResponseString(mDevice, "+ANDROID: (SINKAUDIOPOLICY)");
        verify(mNativeInterface).atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    /** A end to end test to validate received Android AT commands and processing */
    @Test
    public void testCheckAndProcessAndroidAtFromStateMachine() {
        mInOrder.verify(mStorage).getAudioPolicyMetadata(any());
        setUpConnectedState();

        setUpAudioPolicy();
        // receive and set android policy
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT,
                        "+ANDROID=SINKAUDIOPOLICY,1,1,1",
                        mDevice));
        mInOrder.verify(mStorage).setAudioPolicyMetadata(any(), any());

        // receive and not set android policy
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT,
                        "AT+ANDROID=PROBE,1,1,\"PQGHRSBCTU__\"",
                        mDevice));
        mInOrder.verify(mStorage, never()).setAudioPolicyMetadata(any(), any());
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
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        sendAndDispatchStackEvent(
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
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        sendAndDispatchStackEvent(
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
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_NO,
                        mDevice));

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));
        verifyAudioSystemSetParametersInvocation(false, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    @Test
    @EnableFlags(android.media.audio.Flags.FLAG_SCO_MANAGED_BY_AUDIO)
    public void testSetAudioParameters_isScoManagedByAudio() {
        doReturn(true).when(mSystemInterface).isScoManagedByAudioEnabled();

        setUpConnectedState();
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB,
                        HeadsetHalConstants.BTHF_SWB_CODEC_LC3,
                        HeadsetHalConstants.BTHF_SWB_YES,
                        mDevice));

        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        mDevice));

        //Should set nrec and wbs properties when AMSCO enabled
        verify(mAudioManager, times(1)).setParameters(any());
    }

    /**
     * verify parameters given to audio system
     *
     * @param lc3Enabled if true check if SWB LC3 was enabled
     * @param aptxEnabled if true check if SWB AptX was enabled
     */
    private void verifyAudioSystemSetParametersInvocation(boolean lc3Enabled, boolean aptxEnabled) {
        verify(mAudioManager).setParameters(lc3Enabled ? "bt_lc3_swb=on" : "bt_lc3_swb=off");

        verify(mAudioManager).setParameters(aptxEnabled ? "bt_swb=0" : "bt_swb=65535");
    }

    /**
     * set sink audio policy
     *
     * @param arg body of the AT command
     * @return the result from processAndroidAtSinkAudioPolicy
     */
    private boolean setSinkAudioPolicyArgs(String arg, BluetoothDevice device) {
        Object[] args = HeadsetStateMachine.generateArgs(arg);
        return mStateMachine.processAndroidAtSinkAudioPolicy(args, device);
    }

    /** Put test state machine in connecting state */
    private void setUpConnectingState() {
        sendAndDispatchMessage(HeadsetStateMachine.CONNECT);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
    }

    /** Put test state machine into connected state */
    private void setUpConnectedState() {
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                STATE_DISCONNECTED,
                STATE_CONNECTING);
        generateConnectionMessageFromNative(
                HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                STATE_CONNECTING,
                STATE_CONNECTED);
    }

    private void setUpAudioConnectingState() {
        setUpConnectedState();
        sendAndDispatchMessage(HeadsetStateMachine.CONNECT_AUDIO);
        verifyAudioStateIntent(STATE_AUDIO_DISCONNECTED, STATE_AUDIO_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioConnecting.class);
    }

    private void setUpAudioOnState() {
        setUpAudioConnectingState();
        generateAudioMessageFromNative(
                HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                STATE_AUDIO_CONNECTING,
                STATE_AUDIO_CONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(HeadsetStateMachine.AudioOn.class);
    }

    private void setUpAudioDisconnectingState() {
        setUpAudioOnState();
        sendAndDispatchMessage(HeadsetStateMachine.DISCONNECT_AUDIO);
        // No new broadcast due to lack of AUDIO_DISCONNECTING intent variable
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(HeadsetStateMachine.AudioDisconnecting.class);
    }

    private void setUpDisconnectingState() {
        setUpConnectedState();
        sendAndDispatchMessage(HeadsetStateMachine.DISCONNECT);
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTING);
    }

    private void setUpAudioPolicy() {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT, "+ANDROID=?", mDevice));
        verify(mNativeInterface).atResponseString(any(), anyString());
    }

    private void configureHeadsetServiceForAptxVoice(boolean enable) {
        if (enable) {
            doReturn(true).when(mHeadsetService).isAptXSwbEnabled();
        }
    }

    private void sendAndDispatchMessage(int what, int arg) {
        mStateMachine.sendMessage(what, arg);
        mLooper.dispatchAll();
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    private void sendAndDispatchStackEvent(HeadsetStackEvent event) {
        sendAndDispatchMessage(HeadsetStateMachine.STACK_EVENT, event);
    }

    private void sendAndDispatchMessage(int what) {
        sendAndDispatchMessage(what, mDevice);
    }

    @SafeVarargs
    private void verifyIntentSentRegular(Matcher<Intent>... matchers) {
        mInOrder.verify(mHeadsetService)
                .sendBroadcast(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(BLUETOOTH_CONNECT),
                        any());
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mHeadsetService)
                .sendBroadcast(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(BLUETOOTH_CONNECT),
                        any());
    }

    private void verifyNoIntentSent() {
        mInOrder.verify(mHeadsetService, never()).sendBroadcast(any(), any(), any());
    }

    private void verifyConnectionStateIntent(int oldState, int newState) {
        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
        assertThat(mStateMachine.getConnectionState()).isEqualTo(newState);
    }

    private void verifyAudioStateIntent(int oldState, int newState) {
        verifyIntentSent(
                hasAction(ACTION_AUDIO_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
    }

    private void verifyHfIndicatorIntent(int id, int indValue) {
        verifyIntentSentRegular(
                hasAction(BluetoothHeadset.ACTION_HF_INDICATORS_VALUE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, id),
                hasExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, indValue));
    }

    private void generateConnectionMessageFromNative(
            int halState, int oldState, int newState, int oldAudioState, int newAudioState) {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED, halState, mDevice));
        verifyAudioStateIntent(oldAudioState, newAudioState);
        verifyConnectionStateIntent(oldState, newState);
    }

    private void generateConnectionMessageFromNative(int halState, int oldState, int newState) {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED, halState, mDevice));
        verifyConnectionStateIntent(oldState, newState);
    }

    private void generateAudioMessageFromNative(int halState, int oldState, int newState) {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED, halState, mDevice));
        verifyAudioStateIntent(oldState, newState);
    }

    private void generateUnexpectedAudioMessageFromNative(int halState) {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED, halState, mDevice));
        verifyNoIntentSent();
    }

    private void generateUnexpectedConnectionMessageFromNative(int halState) {
        sendAndDispatchStackEvent(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED, halState, mDevice));
        verifyNoIntentSent();
    }
}
