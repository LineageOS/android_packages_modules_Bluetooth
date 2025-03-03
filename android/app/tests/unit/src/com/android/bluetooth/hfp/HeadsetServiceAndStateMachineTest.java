/*
 * Copyright 2018 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasData;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.PhoneAccount;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.util.SystemProperties;

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
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetServiceAndStateMachineTest {
    private static final String TAG = HeadsetServiceAndStateMachineTest.class.getSimpleName();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Spy private HeadsetObjectsFactory mObjectsFactory = HeadsetObjectsFactory.getInstance();

    @Mock private HeadsetNativeInterface mNativeInterface;
    @Mock private LeAudioService mLeAudioService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HeadsetSystemInterface mSystemInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private HeadsetPhoneState mPhoneState;
    @Mock private RemoteDevices mRemoteDevices;
    @Mock private SystemProperties.MockableSystemProperties mProperties;

    private static final int MAX_HEADSET_CONNECTIONS = 5;
    private static final ParcelUuid[] FAKE_HEADSET_UUID = {BluetoothUuid.HFP};
    private static final String TEST_PHONE_NUMBER = "1234567890";
    private static final String TEST_CALLER_ID = "Test Name";

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Set<BluetoothDevice> mBondedDevices = new HashSet<>();

    private PowerManager.WakeLock mVoiceRecognitionWakeLock;
    private HeadsetService mHeadsetService;
    private InOrder mInOrder;
    private TestLooper mTestLooper;

    @Before
    public void setUp() {
        mInOrder = inOrder(mAdapterService);
        doReturn(mTargetContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mTargetContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mTargetContext.getResources()).when(mAdapterService).getResources();
        doReturn(mTargetContext.getContentResolver()).when(mAdapterService).getContentResolver();

        PowerManager powerManager = mTargetContext.getSystemService(PowerManager.class);
        mVoiceRecognitionWakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceRecognitionTest");
        doReturn(MAX_HEADSET_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(new ParcelUuid[] {BluetoothUuid.HFP})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        HeadsetObjectsFactory.setInstanceForTesting(mObjectsFactory);
        // Mock methods in AdapterService
        doReturn(FAKE_HEADSET_UUID)
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[] {}))
                .when(mAdapterService)
                .getBondedDevices();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(mAdapterService)
                .getRequestedAudioPolicyAsSink(any(BluetoothDevice.class));
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        // Mock system interface
        doNothing().when(mSystemInterface).stop();
        doReturn(mPhoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(mAudioManager).when(mSystemInterface).getAudioManager();
        doReturn(true).when(mSystemInterface).activateVoiceRecognition();
        doReturn(true).when(mSystemInterface).deactivateVoiceRecognition();
        doReturn(mVoiceRecognitionWakeLock).when(mSystemInterface).getVoiceRecognitionWakeLock();
        doReturn(true).when(mSystemInterface).isCallIdle();
        // Mock methods in HeadsetNativeInterface
        doReturn(true).when(mNativeInterface).connectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).connectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).sendBsir(any(BluetoothDevice.class), anyBoolean());
        doReturn(true).when(mNativeInterface).startVoiceRecognition(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).stopVoiceRecognition(any(BluetoothDevice.class));
        doReturn(true)
                .when(mNativeInterface)
                .atResponseCode(any(BluetoothDevice.class), anyInt(), anyInt());
        // Use real state machines here
        doCallRealMethod()
                .when(mObjectsFactory)
                .makeStateMachine(any(), any(), any(), any(), any(), any());
        // Mock methods in HeadsetObjectsFactory
        doReturn(mSystemInterface).when(mObjectsFactory).makeSystemInterface(any());

        mTestLooper = new TestLooper();

        mHeadsetService =
                new HeadsetService(mAdapterService, mNativeInterface, mTestLooper.getLooper());
        mHeadsetService.setAvailable(true);

        verify(mObjectsFactory).makeSystemInterface(mHeadsetService);
        verify(mNativeInterface).init(MAX_HEADSET_CONNECTIONS + 1, true /* inband ringtone */);

        // Set up the Connection State Changed receiver
        verify(mNativeInterface)
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        eq(
                                SystemProperties.getBoolean(
                                        "bluetooth.hfp.codec_aptx_voice.enabled", false)),
                        eq(mHeadsetService.getActiveDevice()));
    }

    @After
    public void tearDown() {
        SystemProperties.mProperties = null;
        mTestLooper.dispatchAll();
        mHeadsetService.cleanup();
        mHeadsetService = HeadsetService.getHeadsetService();
        assertThat(mHeadsetService).isNull();
        // Clear classes that is spied on and has static life time
        HeadsetObjectsFactory.setInstanceForTesting(null);
        mBondedDevices.clear();
    }

    /** Test to verify that HeadsetService can be successfully started */
    @Test
    public void testGetHeadsetService() {
        assertThat(HeadsetService.getHeadsetService()).isEqualTo(mHeadsetService);
        // Verify default connection and audio states
        BluetoothDevice device = getTestDevice(0);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetService.getAudioState(device))
                .isEqualTo(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Test to verify that {@link HeadsetService#connect(BluetoothDevice)} actually result in a call
     * to native interface to create HFP
     */
    @Test
    public void testConnectFromApi() {
        BluetoothDevice device = getTestDevice(0);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        mBondedDevices.add(device);
        assertThat(mHeadsetService.connect(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mObjectsFactory)
                .makeStateMachine(
                        device,
                        mTestLooper.getLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mNativeInterface,
                        mSystemInterface);
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        verify(mNativeInterface).connectHfp(device);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);
        assertThat(mHeadsetService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTING}))
                .containsExactly(device);
        // Get feedback from native to put device into connected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        device);
        mHeadsetService.messageFromNative(connectedEvent);
        mTestLooper.dispatchAll();
        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .containsExactly(device);
    }

    /**
     * Test to verify that {@link BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with {@link
     * BluetoothDevice#EXTRA_BOND_STATE} as {@link BluetoothDevice#BOND_NONE} will cause a
     * disconnected device to be removed from state machine map
     */
    @Test
    public void testUnbondDevice_disconnectBeforeUnbond() {
        BluetoothDevice device = getTestDevice(0);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        mBondedDevices.add(device);
        assertThat(mHeadsetService.connect(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mObjectsFactory)
                .makeStateMachine(
                        device,
                        mTestLooper.getLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mNativeInterface,
                        mSystemInterface);
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        verify(mNativeInterface).connectHfp(device);
        // Get feedback from native layer to go back to disconnected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        device);
        mHeadsetService.messageFromNative(connectedEvent);
        mTestLooper.dispatchAll();
        verifyConnectionStateIntent(device, STATE_DISCONNECTED, STATE_CONNECTING);

        mHeadsetService.handleBondStateChanged(
                device, BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE);
        mTestLooper.dispatchAll();
        // Check that the state machine is actually destroyed
        ArgumentCaptor<HeadsetStateMachine> stateMachineArgument =
                ArgumentCaptor.forClass(HeadsetStateMachine.class);
        verify(mObjectsFactory).destroyStateMachine(stateMachineArgument.capture());
        assertThat(stateMachineArgument.getValue().getDevice()).isEqualTo(device);
    }

    /**
     * Test to verify that if a device can be property disconnected after {@link
     * BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with {@link
     * BluetoothDevice#EXTRA_BOND_STATE} as {@link BluetoothDevice#BOND_NONE} is received.
     */
    @Test
    public void testUnbondDevice_disconnectAfterUnbond() {
        BluetoothDevice device = getTestDevice(0);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        mBondedDevices.add(device);
        assertThat(mHeadsetService.connect(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mObjectsFactory)
                .makeStateMachine(
                        device,
                        mTestLooper.getLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mNativeInterface,
                        mSystemInterface);
        verify(mNativeInterface).connectHfp(device);
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        // Get feedback from native layer to go to connected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        device);
        mHeadsetService.messageFromNative(connectedEvent);
        mTestLooper.dispatchAll();
        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).containsExactly(device);

        // Check that the state machine is not destroyed
        verify(mObjectsFactory, never()).destroyStateMachine(any());

        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(eq(device));
        // Now disconnect the device
        HeadsetStackEvent connectingEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        device);
        mHeadsetService.messageFromNative(connectingEvent);
        mTestLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that the state machine is destroyed after another async call
        ArgumentCaptor<HeadsetStateMachine> stateMachineArgument =
                ArgumentCaptor.forClass(HeadsetStateMachine.class);
        verify(mObjectsFactory).destroyStateMachine(stateMachineArgument.capture());
        assertThat(stateMachineArgument.getValue().getDevice()).isEqualTo(device);
    }

    /**
     * Test the functionality of {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and {@link
     * BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * <p>Normal start and stop
     */
    @Test
    public void testVirtualCall_normalStartStop() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        assertThat(mHeadsetService.setActiveDevice(activeDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verifyActiveDeviceChangedIntent(activeDevice);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(activeDevice);
        // Start virtual call
        assertThat(mHeadsetService.startScoUsingVirtualVoiceCall()).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isTrue();
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // End virtual call
        assertThat(mHeadsetService.stopScoUsingVirtualVoiceCall()).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
    }

    /**
     * Test the functionality of {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and {@link
     * BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * <p>Virtual call should be preempted by telecom call
     */
    @Test
    public void testVirtualCall_preemptedByTelecomCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        assertThat(mHeadsetService.setActiveDevice(activeDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verify(mAdapterService).handleActiveDeviceChange(BluetoothProfile.HEADSET, activeDevice);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(activeDevice);
        // Start virtual call
        assertThat(mHeadsetService.startScoUsingVirtualVoiceCall()).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isTrue();
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // Virtual call should be preempted by telecom call
        mHeadsetService.phoneStateChanged(
                0, 0, HeadsetHalConstants.CALL_STATE_INCOMING, TEST_PHONE_NUMBER, 128, "", false);
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_INCOMING, TEST_PHONE_NUMBER, 128, ""),
                connectedDevices);
    }

    /**
     * Test the functionality of {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and {@link
     * BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * <p>Virtual call should be rejected when there is a telecom call
     */
    @Test
    public void testVirtualCall_rejectedWhenThereIsTelecomCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        assertThat(mHeadsetService.setActiveDevice(activeDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verifyActiveDeviceChangedIntent(activeDevice);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(activeDevice);
        // Reject virtual call setup if call state is not idle
        doReturn(false).when(mSystemInterface).isCallIdle();
        assertThat(mHeadsetService.startScoUsingVirtualVoiceCall()).isFalse();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
    }

    /** Test the value of isInbandRingingEnabled will be changed with the change of active device */
    @Test
    public void testIsInbandRingingEnabled_SwitchActiveDevice() {
        mSetFlagsRule.enableFlags(Flags.FLAG_UPDATE_ACTIVE_DEVICE_IN_BAND_RINGTONE);
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);

        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isInbandRingingEnabled()).isTrue();

        assertThat(mHeadsetService.setActiveDevice(null)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, atLeastOnce()).sendBsir(eq(device), eq(false));
        assertThat(mHeadsetService.isInbandRingingEnabled()).isFalse();

        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, atLeastOnce()).sendBsir(eq(device), eq(true));
        assertThat(mHeadsetService.isInbandRingingEnabled()).isTrue();
    }

    /** Test the behavior when dialing outgoing call from the headset */
    @Test
    public void testDialingOutCall_NormalDialingOut() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        BluetoothDevice activeDevice = connectedDevices.get(0);
        assertThat(mHeadsetService.setActiveDevice(activeDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verifyActiveDeviceChangedIntent(activeDevice);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(activeDevice);
        // Try dialing out from the a non active Headset
        BluetoothDevice dialingOutDevice = connectedDevices.get(1);
        HeadsetStackEvent dialingOutEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_DIAL_CALL,
                        TEST_PHONE_NUMBER,
                        dialingOutDevice);
        Uri dialOutUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, TEST_PHONE_NUMBER, null);
        mHeadsetService.messageFromNative(dialingOutEvent);
        mTestLooper.dispatchAll();
        verifyActiveDeviceChangedIntent(dialingOutDevice);
        assertThat(mHeadsetService.hasDeviceInitiatedDialingOut()).isTrue();
        // Make sure the correct intent is fired
        mInOrder.verify(mAdapterService)
                .startActivity(
                        MockitoHamcrest.argThat(
                                AllOf.allOf(
                                        hasAction(Intent.ACTION_CALL_PRIVILEGED),
                                        hasData(dialOutUri))));

        // Further dial out attempt from same device will fail
        mHeadsetService.messageFromNative(dialingOutEvent);
        mTestLooper.dispatchAll();
        verify(mNativeInterface)
                .atResponseCode(dialingOutDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);

        // Further dial out attempt from other device will fail
        HeadsetStackEvent dialingOutEventOtherDevice =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_DIAL_CALL, TEST_PHONE_NUMBER, activeDevice);
        mHeadsetService.messageFromNative(dialingOutEventOtherDevice);
        mTestLooper.dispatchAll();
        verify(mNativeInterface)
                .atResponseCode(activeDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        mInOrder.verify(mAdapterService, times(0))
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(
                                hasAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)),
                        any(),
                        any(),
                        any());
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(dialingOutDevice);

        // Make sure only one intent is fired
        mInOrder.verify(mAdapterService, never())
                .startActivity(
                        MockitoHamcrest.argThat(
                                AllOf.allOf(
                                        hasAction(Intent.ACTION_CALL_PRIVILEGED),
                                        hasData(dialOutUri))));

        // Verify that phone state update confirms the dial out event
        mHeadsetService.phoneStateChanged(
                0, 0, HeadsetHalConstants.CALL_STATE_DIALING, TEST_PHONE_NUMBER, 128, "", false);
        mTestLooper.dispatchAll();
        HeadsetCallState dialingCallState =
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_DIALING, TEST_PHONE_NUMBER, 128, "");
        verifyCallStateToNativeInvocation(dialingCallState, connectedDevices);
        verify(mNativeInterface)
                .atResponseCode(dialingOutDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        // Verify that IDLE phone state clears the dialing out flag
        mHeadsetService.phoneStateChanged(
                1, 0, HeadsetHalConstants.CALL_STATE_IDLE, TEST_PHONE_NUMBER, 128, "", false);
        mTestLooper.dispatchAll();
        HeadsetCallState activeCallState =
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_DIALING, TEST_PHONE_NUMBER, 128, "");
        verifyCallStateToNativeInvocation(activeCallState, connectedDevices);
        assertThat(mHeadsetService.hasDeviceInitiatedDialingOut()).isFalse();
    }

    /** Test the behavior when dialing outgoing call from the headset */
    @Test
    public void testDialingOutCall_DialingOutPreemptVirtualCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        BluetoothDevice activeDevice = connectedDevices.get(0);
        assertThat(mHeadsetService.setActiveDevice(activeDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verifyActiveDeviceChangedIntent(activeDevice);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(activeDevice);
        // Start virtual call
        assertThat(mHeadsetService.startScoUsingVirtualVoiceCall()).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.isVirtualCallStarted()).isTrue();
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // Try dialing out from the a non active Headset
        BluetoothDevice dialingOutDevice = connectedDevices.get(1);
        HeadsetStackEvent dialingOutEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_DIAL_CALL,
                        TEST_PHONE_NUMBER,
                        dialingOutDevice);
        Uri dialOutUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, TEST_PHONE_NUMBER, null);
        mHeadsetService.messageFromNative(dialingOutEvent);
        mTestLooper.dispatchAll();
        verifyActiveDeviceChangedIntent(dialingOutDevice);
        assertThat(mHeadsetService.hasDeviceInitiatedDialingOut()).isTrue();

        mInOrder.verify(mAdapterService)
                .startActivity(
                        MockitoHamcrest.argThat(
                                AllOf.allOf(
                                        hasAction(Intent.ACTION_CALL_PRIVILEGED),
                                        hasData(dialOutUri))));
        // Virtual call should be preempted by dialing out call
        assertThat(mHeadsetService.isVirtualCallStarted()).isFalse();
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
    }

    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition in the
     * successful scenario 1. HF device sends AT+BVRA=1 2. HeadsetStateMachine sends out {@link
     * Intent#ACTION_VOICE_COMMAND} 3. AG call {@link
     * BluetoothHeadset#stopVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has stopped 4. AG sends OK to HF
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedSuccess() {
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
    }

    /**
     * Same process as {@link
     * HeadsetServiceAndStateMachineTest#testVoiceRecognition_SingleHfInitiatedSuccess()} except the
     * SCO connection is handled by the Audio Framework
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedSuccess_ScoManagedByAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf_ScoManagedByAudio(device);

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * Test to verify the following behavior regarding active HF stop voice recognition in the
     * successful scenario 1. HF device sends AT+BVRA=0 2. Let voice recognition app to stop 3. AG
     * respond with OK 4. Disconnect audio
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfStopSuccess() {
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Stop voice recognition
        HeadsetStackEvent stopVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STOPPED,
                        device);
        mHeadsetService.messageFromNative(stopVrEvent);
        mTestLooper.dispatchAll();
        mTestLooper.dispatchAll();
        verify(mSystemInterface).deactivateVoiceRecognition();
        verify(mNativeInterface, times(2))
                .atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mNativeInterface).disconnectAudio(device);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition in the
     * failed to activate scenario 1. HF device sends AT+BVRA=1 2. HeadsetStateMachine sends out
     * {@link Intent#ACTION_VOICE_COMMAND} 3. Failed to activate voice recognition through intent 4.
     * AG sends ERROR to HF
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedFailedToActivate() {
        doReturn(false).when(mSystemInterface).activateVoiceRecognition();
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        device);
        mHeadsetService.messageFromNative(startVrEvent);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();
        verify(mNativeInterface).atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
        verifyZeroInteractions(mAudioManager);
    }

    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition in the
     * timeout scenario 1. HF device sends AT+BVRA=1 2. HeadsetStateMachine sends out {@link
     * Intent#ACTION_VOICE_COMMAND} 3. AG failed to get back to us on time 4. AG sends ERROR to HF
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedTimeout() {
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        device);
        mHeadsetService.messageFromNative(startVrEvent);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();

        mTestLooper.moveTimeForward(mHeadsetService.sStartVrTimeoutMs); // Trigger timeout
        mTestLooper.dispatchAll();
        verify(mNativeInterface).atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);

        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
        verifyZeroInteractions(mAudioManager);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition in the
     * successful scenario 1. AG starts voice recognition and notify the Bluetooth stack via {@link
     * BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has started 2. AG send +BVRA:1 to HF 3. AG start SCO connection if SCO has not been started
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedSuccess() {
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg();
    }

    /**
     * Same process as {@link
     * HeadsetServiceAndStateMachineTest#testVoiceRecognition_SingleAgInitiatedSuccess()} except the
     * SCO connection is handled by the Audio Framework
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedSuccess_ScoManagedByAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg_ScoManagedByAudio();

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * Test to verify the following behavior regarding AG stops voice recognition in the successful
     * scenario 1. AG stops voice recognition and notify the Bluetooth stack via {@link
     * BluetoothHeadset#stopVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has stopped 2. AG send +BVRA:0 to HF 3. AG stop SCO connection
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgStopSuccess() {
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg();
        // Stop voice recognition
        assertThat(mHeadsetService.stopVoiceRecognition(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).stopVoiceRecognition(device);
        verify(mNativeInterface).disconnectAudio(device);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition in the device
     * not connected failure scenario 1. AG starts voice recognition and notify the Bluetooth stack
     * via {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     * recognition has started 2. Device is not connected, return false
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedDeviceNotConnected() {
        // Start voice recognition
        BluetoothDevice disconnectedDevice = getTestDevice(0);
        assertThat(mHeadsetService.startVoiceRecognition(disconnectedDevice)).isFalse();
        mTestLooper.dispatchAll();
        verifyNoMoreInteractions(mNativeInterface);
        verifyZeroInteractions(mAudioManager);
    }

    /**
     * Test to verify the following behavior regarding non active HF initiated voice recognition in
     * the successful scenario 1. HF device sends AT+BVRA=1 2. HeadsetStateMachine sends out {@link
     * Intent#ACTION_VOICE_COMMAND} 3. AG call {@link
     * BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has started 4. AG sends OK to HF 5. Suspend A2DP 6. Start SCO if SCO hasn't been started
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiHfInitiatedSwitchActiveDeviceSuccess() {
        // Connect two devices
        BluetoothDevice deviceA = getTestDevice(0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = getTestDevice(1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        if (!Flags.updateActiveDeviceInBandRingtone()) {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(true));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
        } else {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
        }
        // Set active device to device B
        assertThat(mHeadsetService.setActiveDevice(deviceB)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(deviceB);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceB);
        // Start voice recognition from non active device A
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();
        // Active device should have been swapped to device A
        verify(mNativeInterface).setActiveDevice(deviceA);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceA);
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventB =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceB);
        mHeadsetService.messageFromNative(startVrEventB);
        mTestLooper.dispatchAll();
        verify(mNativeInterface).atResponseCode(deviceB, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        // Reply to continue voice recognition
        mHeadsetService.startVoiceRecognition(deviceA);
        mTestLooper.dispatchAll();
        verify(mNativeInterface).atResponseCode(deviceA, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        verify(mNativeInterface).connectAudio(deviceA);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(deviceA));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    /**
     * Test to verify the following behavior regarding non active HF initiated voice recognition in
     * the successful scenario 1. HF device sends AT+BVRA=1 2. HeadsetStateMachine sends out {@link
     * Intent#ACTION_VOICE_COMMAND} 3. AG call {@link
     * BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has started, but on a wrong HF 4. Headset service instead keep using the initiating HF 5. AG
     * sends OK to HF 6. Suspend A2DP 7. Start SCO if SCO hasn't been started
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiHfInitiatedSwitchActiveDeviceReplyWrongHfSuccess() {
        // Connect two devices
        InOrder inOrder = inOrder(mNativeInterface);
        BluetoothDevice deviceA = getTestDevice(0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = getTestDevice(1);
        connectTestDevice(deviceB);
        if (!Flags.updateActiveDeviceInBandRingtone()) {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(true));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
        } else {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
        }
        // Set active device to device B
        assertThat(mHeadsetService.setActiveDevice(deviceB)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(deviceB);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceB);
        // Start voice recognition from non active device A
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();
        // Active device should have been swapped to device A
        verify(mNativeInterface).setActiveDevice(deviceA);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceA);
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventB =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceB);
        mHeadsetService.messageFromNative(startVrEventB);
        mTestLooper.dispatchAll();
        verify(mNativeInterface).atResponseCode(deviceB, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        // Reply to continue voice recognition on a wrong device
        mHeadsetService.startVoiceRecognition(deviceB);
        mTestLooper.dispatchAll();
        // We still continue on the initiating HF
        verify(mNativeInterface).atResponseCode(deviceA, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        verify(mNativeInterface).connectAudio(deviceA);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(deviceA));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition in the
     * successful scenario 1. AG starts voice recognition and notify the Bluetooth stack via {@link
     * BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice recognition
     * has started 2. Suspend A2DP 3. AG send +BVRA:1 to HF 4. AG start SCO connection if SCO has
     * not been started
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiAgInitiatedSuccess() {
        // Connect two devices
        BluetoothDevice deviceA = getTestDevice(0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = getTestDevice(1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        if (!Flags.updateActiveDeviceInBandRingtone()) {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(true));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
        } else {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
        }
        // Set active device to device B
        assertThat(mHeadsetService.setActiveDevice(deviceB)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(deviceB);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceB);
        // Start voice recognition
        startVoiceRecognitionFromAg();
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        mTestLooper.dispatchAll();
        verify(mNativeInterface).stopVoiceRecognition(deviceB);
        verify(mNativeInterface).disconnectAudio(deviceB);
        // This request should still fail
        verify(mNativeInterface).atResponseCode(deviceA, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(deviceB));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition in the device
     * not active failure scenario 1. AG starts voice recognition and notify the Bluetooth stack via
     * {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     * recognition has started 2. Device is not active, should do voice recognition on active device
     * only
     *
     * <p>Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiAgInitiatedDeviceNotActive() {
        // Connect two devices
        BluetoothDevice deviceA = getTestDevice(0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = getTestDevice(1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        if (!Flags.updateActiveDeviceInBandRingtone()) {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(true));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
        } else {
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceA), eq(false));
            inOrder.verify(mNativeInterface).sendBsir(eq(deviceB), eq(false));
        }
        // Set active device to device B
        assertThat(mHeadsetService.setActiveDevice(deviceB)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(deviceB);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceB);
        // Start voice recognition should succeed
        assertThat(mHeadsetService.startVoiceRecognition(deviceA)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(deviceA);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(deviceA);
        verify(mNativeInterface).startVoiceRecognition(deviceA);
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        verify(mNativeInterface).connectAudio(deviceA);
        verifyAudioStateIntent(
                deviceA,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        deviceA));
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                deviceA,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(deviceA));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    /**
     * Test to verify the call state and caller information are correctly delivered {@link
     * BluetoothHeadset#phoneStateChanged(int, int, int, String, int, String, boolean)}
     */
    @Test
    public void testPhoneStateChangedWithIncomingCallState() throws RemoteException {
        // Connect HF
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = getTestDevice(i);
            connectTestDevice(device);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(mBondedDevices);
            assertThat(
                            mHeadsetService.getDevicesMatchingConnectionStates(
                                    new int[] {STATE_CONNECTED}))
                    .containsExactlyElementsIn(mBondedDevices);
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        assertThat(connectedDevices).containsExactlyElementsIn(mBondedDevices);
        // Incoming call update by telecom
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        mTestLooper.dispatchAll();
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
    }

    /**
     * Test to verify if AptX Voice codec is set properly within incoming call. AptX SWB and AptX
     * SWB PM are enabled, LC3 SWB is disabled. Voice call is non-HD and non Voip. Expected result:
     * AptX SWB codec disabled.
     */
    @Test
    public void testIncomingCall_NonHdNonVoipCall_AptXDisabled() {
        configureHeadsetServiceForAptxVoice(true);

        BluetoothDevice device = getTestDevice(0);

        doReturn(true)
                .when(mNativeInterface)
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        doReturn(false).when(mSystemInterface).isHighDefCallInProgress();

        // Connect HF
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        // Simulate AptX SWB enabled, LC3 SWB disabled
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Simulate incoming call
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        mTestLooper.dispatchAll();
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
        doReturn(true).when(mSystemInterface).isRinging();
        // Connect Audio
        assertThat(mHeadsetService.connectAudio()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);

        // Check that AptX SWB disabled, LC3 SWB disabled
        verifySetParametersToAudioSystemInvocation(false, true, false);
        verify(mNativeInterface).connectAudio(eq(device));
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(false), eq(device));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if AptX Voice codec is set properly within incoming call. AptX SWB and AptX
     * SWB PM are enabled, LC3 SWB is disabled. Voice call is HD and non Voip. Expected result: AptX
     * SWB codec enabled.
     */
    @Test
    public void testIncomingCall_HdNonVoipCall_AptXEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        BluetoothDevice device = getTestDevice(0);

        doReturn(true)
                .when(mNativeInterface)
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        doReturn(true).when(mSystemInterface).isHighDefCallInProgress();

        // Connect HF
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        // Simulate AptX SWB enabled, LC3 SWB disabled
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Simulate incoming call
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        mTestLooper.dispatchAll();
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
        // TestUtils.waitForLooperToFinishScheduledTask(mTestLooper.getLooper());
        doReturn(true).when(mSystemInterface).isRinging();
        // Connect Audio
        assertThat(mHeadsetService.connectAudio()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);

        // Check that AptX SWB enabled, LC3 SWB disabled
        verifySetParametersToAudioSystemInvocation(false, true, true);
        verify(mNativeInterface).connectAudio(eq(device));
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(true), eq(device));
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * LC3 SWB enabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_Lc3SwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_LC3;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(true, true, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature not present.
     * Test LC3 SWB enabled
     */
    @Test
    public void testSetAudioParametersWithoutAptxVoice_Lc3SwbEnabled() {
        configureHeadsetServiceForAptxVoice(false);
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_LC3;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(true, false, false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * aptX SWB enabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_AptXSwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, true, true);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * SWB disabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_SwbDisabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int codec = HeadsetHalConstants.BTHF_SWB_NO;
        HeadsetStackEvent event =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SWB, codec, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, true, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature not present.
     * Test SWB disabled
     */
    @Test
    public void testSetAudioParametersWithoutAptxVoice_SwbDisabled() {
        configureHeadsetServiceForAptxVoice(false);
        // Connect HF
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int codec = HeadsetHalConstants.BTHF_SWB_NO;
        HeadsetStackEvent event =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SWB, codec, device);
        mHeadsetService.messageFromNative(event);
        mTestLooper.dispatchAll();
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, false, false);
    }

    /**
     * Test the functionality of {@link HeadsetService#enableSwbCodec()}
     *
     * <p>AptX SWB and AptX SWB PM enabled
     */
    @Test
    public void testVoiceRecognition_AptXSwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        BluetoothDevice device = getTestDevice(0);

        // Connect HF
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));
        // Start voice recognition to connect audio
        startVoiceRecognitionFromHf(device);

        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(true), eq(device));
        configureHeadsetServiceForAptxVoice(false);
    }

    @Test
    public void testHfpOnlyHandoverToLeAudioAfterScoDisconnect() {
        BluetoothDevice device = getTestDevice(0);

        assertThat(mHeadsetService.mFactory).isNotNull();
        mHeadsetService.mFactory = mServiceFactory;

        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();
        doReturn(List.of(device)).when(mLeAudioService).getConnectedDevices();
        List<BluetoothDevice> activeDeviceList = new ArrayList<>();
        activeDeviceList.add(null);
        doReturn(activeDeviceList).when(mLeAudioService).getActiveDevices();

        // Connect HF
        connectTestDevice(device);
        // Make device active
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).setActiveDevice(device);
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
        verify(mNativeInterface).sendBsir(eq(device), eq(true));

        // this device is a HFP only device
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.A2DP);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEARING_AID);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO);

        doReturn(true).when(mSystemInterface).isInCall();

        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTING,
                        device));
        mTestLooper.dispatchAll();

        // simulate controller cannot handle SCO and CIS coexistence,
        // and SCO is failed to connect initially,
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                        device));
        mTestLooper.dispatchAll();
        // at this moment, should not resume LE Audio active device
        verify(mLeAudioService, never()).setActiveAfterHfpHandover();

        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTING,
                        device));
        mTestLooper.dispatchAll();

        // then SCO is connected
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));

        doReturn(false).when(mSystemInterface).isInCall();
        doReturn(true).when(mSystemInterface).isCallIdle();
        // Audio disconnected
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_DISCONNECTED,
                        device));
        mTestLooper.dispatchAll();
        verify(mLeAudioService, atLeastOnce()).setActiveAfterHfpHandover();
    }

    private void startVoiceRecognitionFromHf(BluetoothDevice device) {
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        device);
        mHeadsetService.messageFromNative(startVrEvent);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        verify(mNativeInterface).connectAudio(device);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    private void startVoiceRecognitionFromHf_ScoManagedByAudio(BluetoothDevice device) {
        if (!Flags.isScoManagedByAudio()) {
            Log.i(TAG, "isScoManagedByAudio is disabled");
            return;
        }
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        device);
        mHeadsetService.messageFromNative(startVrEvent);
        mTestLooper.dispatchAll();
        verify(mSystemInterface).activateVoiceRecognition();
        // has not add verification AudioDeviceInfo because it is final, unless add a wrapper
        mHeadsetService.startVoiceRecognition(device);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setA2dpSuspended(true);
        verify(mAudioManager, times(0)).setLeAudioSuspended(true);
        verify(mNativeInterface, times(0)).connectAudio(device);
    }

    private void startVoiceRecognitionFromAg() {
        BluetoothDevice device = mHeadsetService.getActiveDevice();
        assertThat(device).isNotNull();
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface).startVoiceRecognition(device);
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        verify(mNativeInterface).connectAudio(device);
        verify(mNativeInterface, atLeast(1))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device));
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        mTestLooper.dispatchAll();
        verifyAudioStateIntent(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);
        verifyNoMoreInteractions(ignoreStubs(mNativeInterface));
    }

    private void startVoiceRecognitionFromAg_ScoManagedByAudio() {
        BluetoothDevice device = mHeadsetService.getActiveDevice();
        assertThat(device).isNotNull();
        mHeadsetService.startVoiceRecognition(device);
        mTestLooper.dispatchAll();
        // has not add verification AudioDeviceInfo because it is final, unless add a wrapper
        verify(mNativeInterface).startVoiceRecognition(device);
        verify(mAudioManager, times(0)).setA2dpSuspended(true);
        verify(mAudioManager, times(0)).setLeAudioSuspended(true);
        verify(mNativeInterface, times(0)).connectAudio(device);
    }

    /**
     * Test to verify the following behavior regarding phoneStateChanged when the SCO is managed by
     * the Audio: When phoneStateChange returns, HeadsetStateMachine completes processing
     * mActiveDevice's CALL_STATE_CHANGED message
     */
    @Test
    public void testPhoneStateChange_SynchronousCallStateChanged() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        BluetoothDevice device = getTestDevice(0);
        assertThat(device).isNotNull();
        connectTestDevice(device);

        BluetoothDevice device2 = getTestDevice(1);
        assertThat(device2).isNotNull();
        connectTestDevice(device2);

        BluetoothDevice device3 = getTestDevice(2);
        assertThat(device3).isNotNull();
        connectTestDevice(device3);

        mHeadsetService.setActiveDevice(device);
        mTestLooper.dispatchAll();
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        mTestLooper.dispatchAll();

        HeadsetCallState headsetCallState =
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_INCOMING, TEST_PHONE_NUMBER, 128, "");
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        mTestLooper.dispatchAll();
        // HeadsetStateMachine completes processing CALL_STATE_CHANGED message
        verify(mNativeInterface).phoneStateChange(device, headsetCallState);

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    private void connectTestDevice(BluetoothDevice device) {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(eq(device));
        // Make device bonded
        mBondedDevices.add(device);
        // Use connecting event to indicate that device is connecting
        HeadsetStackEvent rfcommConnectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                        device);
        mHeadsetService.messageFromNative(rfcommConnectedEvent);
        mTestLooper.dispatchAll();
        verify(mObjectsFactory)
                .makeStateMachine(
                        device,
                        mTestLooper.getLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mNativeInterface,
                        mSystemInterface);
        verify(mActiveDeviceManager)
                .profileConnectionStateChanged(
                        BluetoothProfile.HEADSET, device, STATE_DISCONNECTED, STATE_CONNECTING);
        verify(mSilenceDeviceManager)
                .hfpConnectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);
        assertThat(mHeadsetService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTING}))
                .containsExactly(device);
        // Get feedback from native to put device into connected state
        HeadsetStackEvent slcConnectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED,
                        device);
        mHeadsetService.messageFromNative(slcConnectedEvent);
        mTestLooper.dispatchAll();
        verify(mActiveDeviceManager)
                .profileConnectionStateChanged(
                        BluetoothProfile.HEADSET, device, STATE_CONNECTING, STATE_CONNECTED);
        verify(mSilenceDeviceManager)
                .hfpConnectionStateChanged(device, STATE_CONNECTING, STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any(), any());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState));
    }

    private void verifyActiveDeviceChangedIntent(BluetoothDevice device) {
        verifyIntentSent(
                hasAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device));
    }

    private void verifyAudioStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState));
    }

    /**
     * Verify the series of invocations after {@link
     * BluetoothHeadset#startScoUsingVirtualVoiceCall()}
     *
     * @param connectedDevices must be in the same sequence as {@link
     *     BluetoothHeadset#getConnectedDevices()}
     */
    private void verifyVirtualCallStartSequenceInvocations(List<BluetoothDevice> connectedDevices) {
        // Do not verify HeadsetPhoneState changes as it is verified in HeadsetServiceTest
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_DIALING, "", 0, ""),
                connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_ALERTING, "", 0, ""),
                connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(1, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, ""),
                connectedDevices);
    }

    private void verifyVirtualCallStopSequenceInvocations(List<BluetoothDevice> connectedDevices) {
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, ""),
                connectedDevices);
    }

    private void verifyCallStateToNativeInvocation(
            HeadsetCallState headsetCallState, List<BluetoothDevice> connectedDevices) {
        for (BluetoothDevice device : connectedDevices) {
            verify(mNativeInterface).phoneStateChange(device, headsetCallState);
        }
    }

    private void verifySetParametersToAudioSystemInvocation(
            boolean lc3Enabled, boolean aptxSupported, boolean aptxEnabled) {
        verify(mAudioManager).setParameters(lc3Enabled ? "bt_lc3_swb=on" : "bt_lc3_swb=off");
        if (aptxSupported) {
            verify(mAudioManager).setParameters(aptxEnabled ? "bt_swb=0" : "bt_swb=65535");
        }
    }

    private void configureHeadsetServiceForAptxVoice(boolean enable) {
        doReturn(enable)
                .when(mProperties)
                .getBoolean(eq("bluetooth.hfp.codec_aptx_voice.enabled"), anyBoolean());
        doReturn(enable)
                .when(mProperties)
                .getBoolean(eq("bluetooth.hfp.swb.aptx.power_management.enabled"), anyBoolean());
        SystemProperties.mProperties = mProperties;
        mHeadsetService.mIsAptXSwbEnabled = enable;
        assertThat(mHeadsetService.isAptXSwbEnabled()).isEqualTo(enable);
        mHeadsetService.mIsAptXSwbPmEnabled = enable;
        assertThat(mHeadsetService.isAptXSwbPmEnabled()).isEqualTo(enable);
    }
}
