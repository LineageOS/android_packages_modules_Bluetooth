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

package com.android.bluetooth.hfp;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.platform.test.flag.junit.DeviceFlagsValueProvider.createCheckFlagsRule;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.VolumeInfo;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Test cases for {@link HeadsetService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetServiceTest {
    @Rule public final CheckFlagsRule mCheckFlagsRule = createCheckFlagsRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;
    @Mock private BluetoothStorageManager mStorage;
    @Mock private HeadsetSystemInterface mSystemInterface;
    @Mock private HeadsetNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private AudioDeviceVolumeManager mAudioDeviceVolumeManager;
    @Mock private HeadsetPhoneState mPhoneState;
    @Mock private RemoteDevices mRemoteDevices;

    @Spy private HeadsetObjectsFactory mObjectsFactory = HeadsetObjectsFactory.getInstance();

    private static final int MAX_HEADSET_CONNECTIONS = 5;
    private static final ParcelUuid[] FAKE_HEADSET_UUID = {BluetoothUuid.HFP};
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 250;
    private static final String TEST_PHONE_NUMBER = "1234567890";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final HashMap<BluetoothDevice, HeadsetStateMachine> mStateMachines = new HashMap<>();

    private HeadsetService mHeadsetService;
    private BluetoothDevice mCurrentDevice;

    @Before
    public void setUp() throws Exception {
        doReturn(mContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getResources()).when(mAdapterService).getResources();

        HeadsetObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(MAX_HEADSET_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(new ParcelUuid[] {BluetoothUuid.HFP})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        // This line must be called to make sure relevant objects are initialized properly
        // Mock methods in AdapterService
        doReturn(FAKE_HEADSET_UUID)
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        doAnswer(invocation -> mStateMachines.keySet()).when(mAdapterService).getBondedDevices();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(mAdapterService)
                .getRequestedAudioPolicyAsSink(any(BluetoothDevice.class));
        // Mock system interface
        doNothing().when(mSystemInterface).stop();
        doReturn(mPhoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(mAudioManager).when(mSystemInterface).getAudioManager();
        doReturn(mAudioDeviceVolumeManager).when(mSystemInterface).getAudioDeviceVolumeManager();
        doReturn(true, false, true, false).when(mSystemInterface).isCallIdle();
        // Mock methods in HeadsetNativeInterface
        doNothing().when(mNativeInterface).init(anyInt(), anyBoolean());
        doNothing().when(mNativeInterface).cleanup();
        doReturn(true).when(mNativeInterface).connectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).connectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).sendBsir(any(BluetoothDevice.class), anyBoolean());
        // Mock methods in HeadsetObjectsFactory
        doAnswer(
                        invocation -> {
                            BluetoothDevice device = invocation.getArgument(0);
                            assertThat(device).isNotNull();
                            final HeadsetStateMachine stateMachine =
                                    mock(HeadsetStateMachine.class);
                            doReturn(STATE_DISCONNECTED).when(stateMachine).getConnectionState();
                            doReturn(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
                                    .when(stateMachine)
                                    .getAudioState();
                            mStateMachines.put(device, stateMachine);
                            return stateMachine;
                        })
                .when(mObjectsFactory)
                .makeStateMachine(any(), any(), any(), any(), any(), any(), any());
        mHeadsetService =
                new HeadsetService(
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface,
                        mActiveDeviceManager);
        mHeadsetService.setAvailable(true);
    }

    @After
    public void tearDown() throws Exception {
        mHeadsetService.cleanup();
        mStateMachines.clear();
        mCurrentDevice = null;
        HeadsetObjectsFactory.setInstanceForTesting(null);
    }

    /** Test to verify that HeadsetService can be successfully started */
    @Test
    public void testGetHeadsetService() {
        // Verify default connection and audio states
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                .isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetService.getAudioState(mCurrentDevice))
                .isEqualTo(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
    }

    /** Test okToAcceptConnection method using various test cases */
    @Test
    public void testOkToAcceptConnection() {
        mCurrentDevice = getTestDevice(0);
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_UNKNOWN, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_ALLOWED, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_UNKNOWN, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_ALLOWED, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_UNKNOWN, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_ALLOWED, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToAcceptConnectionCase(mCurrentDevice, badBondState, CONNECTION_POLICY_UNKNOWN, true);
        testOkToAcceptConnectionCase(
                mCurrentDevice, badBondState, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToAcceptConnectionCase(mCurrentDevice, badBondState, CONNECTION_POLICY_ALLOWED, true);
        testOkToAcceptConnectionCase(mCurrentDevice, badBondState, badPriorityValue, false);
    }

    /**
     * Test to verify that {@link HeadsetService#connect(BluetoothDevice)} returns true when the
     * device was not connected and number of connected devices is less than {@link
     * #MAX_HEADSET_CONNECTIONS}
     */
    @Test
    public void testConnectDevice_connectDeviceBelowLimit() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTING).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTING);
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).isEqualTo(List.of(mCurrentDevice));
        // 2nd connection attempt will fail
        assertThat(mHeadsetService.connect(mCurrentDevice)).isFalse();
        // Verify makeStateMachine is only called once
        verify(mObjectsFactory).makeStateMachine(any(), any(), any(), any(), any(), any(), any());
        // Verify CONNECT is only sent once
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.CONNECT), any());
    }

    /**
     * Test that {@link HeadsetService#messageFromNative(HeadsetStackEvent)} will send correct
     * message to the underlying state machine
     */
    @Test
    public void testMessageFromNative_deviceConnected() {
        mCurrentDevice = getTestDevice(0);
        // Test connect from native
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED,
                        mCurrentDevice);
        mHeadsetService.messageFromNative(connectedEvent);
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.STACK_EVENT, connectedEvent);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).isEqualTo(List.of(mCurrentDevice));
        // Test disconnect from native
        HeadsetStackEvent disconnectEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mCurrentDevice);
        mHeadsetService.messageFromNative(disconnectEvent);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.STACK_EVENT, disconnectEvent);
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                .isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).isEmpty();
    }

    /**
     * Stack connection event to {@link HeadsetHalConstants#CONNECTION_STATE_CONNECTING} should
     * create new state machine
     */
    @Test
    public void testMessageFromNative_deviceConnectingUnknown() {
        mCurrentDevice = getTestDevice(0);
        HeadsetStackEvent connectingEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTING,
                        mCurrentDevice);
        mHeadsetService.messageFromNative(connectingEvent);
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.STACK_EVENT, connectingEvent);
    }

    /**
     * Stack connection event to {@link HeadsetHalConstants#CONNECTION_STATE_DISCONNECTED} should
     * crash by throwing {@link IllegalStateException} if the device is unknown
     */
    @Test
    public void testMessageFromNative_deviceDisconnectedUnknown() {
        mCurrentDevice = getTestDevice(0);
        HeadsetStackEvent connectingEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                        mCurrentDevice);
        assertThrows(
                IllegalStateException.class,
                () -> mHeadsetService.messageFromNative(connectingEvent));
        verifyNoMoreInteractions(mObjectsFactory);
    }

    /**
     * Test to verify that {@link HeadsetService#connect(BluetoothDevice)} fails after {@link
     * #MAX_HEADSET_CONNECTIONS} connection requests
     */
    @Test
    public void testConnectDevice_connectDeviceAboveLimit() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            verify(mObjectsFactory, times(i + 1))
                    .makeStateMachine(
                            any(BluetoothDevice.class),
                            eq(mHeadsetService.getStateMachinesThreadLooper()),
                            eq(mHeadsetService),
                            eq(mAdapterService),
                            eq(mStorage),
                            eq(mNativeInterface),
                            eq(mSystemInterface));
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT), any(BluetoothDevice.class));
            // Put device to connecting
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Put device to connected
            connectedDevices.add(mCurrentDevice);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                    .isEqualTo(STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
        }
        // Connect the next device will fail
        mCurrentDevice = getTestDevice(MAX_HEADSET_CONNECTIONS);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isFalse();
        // Though connection failed, a new state machine is still lazily created for the device
        verify(mObjectsFactory, times(MAX_HEADSET_CONNECTIONS + 1))
                .makeStateMachine(
                        any(BluetoothDevice.class),
                        eq(mHeadsetService.getStateMachinesThreadLooper()),
                        eq(mHeadsetService),
                        eq(mAdapterService),
                        eq(mStorage),
                        eq(mNativeInterface),
                        eq(mSystemInterface));
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                .isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetService.getConnectedDevices())
                .containsExactlyElementsIn(connectedDevices);
    }

    /**
     * Test to verify that {@link HeadsetService#connectAudio(BluetoothDevice)} return true when the
     * device is connected and audio is not connected and returns false when audio is already
     * connecting
     */
    @Test
    public void testConnectAudio_withOneDevice() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(mStateMachines.get(mCurrentDevice))
                .getHfpCallAudioPolicy();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).isEqualTo(List.of(mCurrentDevice));
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        // Test connect audio - set the device first as the active device, fake a call
        doReturn(true).when(mSystemInterface).isInCall();
        assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mCurrentDevice);
        doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTING)
                .when(mStateMachines.get(mCurrentDevice))
                .getAudioState();
        // 2nd connection attempt for the same device will succeed as well
        assertThat(mHeadsetService.connectAudio(mCurrentDevice))
                .isEqualTo(BluetoothStatusCodes.SUCCESS);
        // Verify CONNECT_AUDIO is only sent once
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.CONNECT_AUDIO), any());
        // Test disconnect audio
        assertThat(mHeadsetService.disconnectAudio(mCurrentDevice))
                .isEqualTo(BluetoothStatusCodes.SUCCESS);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, mCurrentDevice);
        doReturn(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
                .when(mStateMachines.get(mCurrentDevice))
                .getAudioState();
        // Further disconnection requests will fail
        assertThat(mHeadsetService.disconnectAudio(mCurrentDevice))
                .isEqualTo(BluetoothStatusCodes.ERROR_AUDIO_DEVICE_ALREADY_DISCONNECTED);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.DISCONNECT_AUDIO), any(BluetoothDevice.class));
    }

    /**
     * Test to verify that HFP audio connection can be initiated when multiple devices are connected
     * and can be canceled or disconnected as well
     */
    @Test
    public void testConnectAudio_withMultipleDevices() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            verify(mObjectsFactory, times(i + 1))
                    .makeStateMachine(
                            any(BluetoothDevice.class),
                            eq(mHeadsetService.getStateMachinesThreadLooper()),
                            eq(mHeadsetService),
                            eq(mAdapterService),
                            eq(mStorage),
                            eq(mNativeInterface),
                            eq(mSystemInterface));
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT), any(BluetoothDevice.class));
            // Put device to connecting
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Put device to connected
            connectedDevices.add(mCurrentDevice);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getHfpCallAudioPolicy();
            assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                    .isEqualTo(STATE_CONNECTED);
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_CONNECTING, STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Try to connect audio
            // Should fail
            assertThat(mHeadsetService.connectAudio(mCurrentDevice))
                    .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
            // Should succeed after setActiveDevice(), fake active call
            doReturn(true).when(mSystemInterface).isInCall();
            assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mCurrentDevice);
            // Put device to audio connecting state
            doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getAudioState();
            // 2nd connection attempt will also succeed
            assertThat(mHeadsetService.connectAudio(mCurrentDevice))
                    .isEqualTo(BluetoothStatusCodes.SUCCESS);
            // Verify CONNECT_AUDIO is only sent once
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT_AUDIO), any());
            // Put device to audio connected state
            doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getAudioState();
            // Disconnect audio
            assertThat(mHeadsetService.disconnectAudio(mCurrentDevice))
                    .isEqualTo(BluetoothStatusCodes.SUCCESS);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, mCurrentDevice);
            doReturn(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getAudioState();
            // Further disconnection requests will fail
            assertThat(mHeadsetService.disconnectAudio(mCurrentDevice))
                    .isEqualTo(BluetoothStatusCodes.ERROR_AUDIO_DEVICE_ALREADY_DISCONNECTED);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(
                            eq(HeadsetStateMachine.DISCONNECT_AUDIO), any(BluetoothDevice.class));
        }
    }

    /**
     * Verify that only one device can be in audio connecting or audio connected state, further
     * attempt to call {@link HeadsetService#connectAudio(BluetoothDevice)} should fail by returning
     * false
     */
    @Test
    public void testConnectAudio_connectTwoAudioChannelsShouldFail() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            verify(mObjectsFactory, times(i + 1))
                    .makeStateMachine(
                            any(BluetoothDevice.class),
                            eq(mHeadsetService.getStateMachinesThreadLooper()),
                            eq(mHeadsetService),
                            eq(mAdapterService),
                            eq(mStorage),
                            eq(mNativeInterface),
                            eq(mSystemInterface));
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT), any(BluetoothDevice.class));
            // Put device to connecting
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Put device to connected
            connectedDevices.add(mCurrentDevice);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_CONNECTING, STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                    .isEqualTo(STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
        }
        if (MAX_HEADSET_CONNECTIONS >= 2) {
            // Try to connect audio
            BluetoothDevice firstDevice = connectedDevices.get(0);
            BluetoothDevice secondDevice = connectedDevices.get(1);
            // Set the first device as the active device, fake a call
            doReturn(true).when(mSystemInterface).isInCall();
            assertThat(mHeadsetService.setActiveDevice(firstDevice)).isTrue();
            verify(mStateMachines.get(firstDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, firstDevice);
            // Put device to audio connecting state
            doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTING)
                    .when(mStateMachines.get(firstDevice))
                    .getAudioState();
            // 2nd connection attempt will succeed for the same device
            assertThat(mHeadsetService.connectAudio(firstDevice))
                    .isEqualTo(BluetoothStatusCodes.SUCCESS);
            // Connect to 2nd device will fail
            assertThat(mHeadsetService.connectAudio(secondDevice))
                    .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
            verify(mStateMachines.get(secondDevice), never())
                    .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, secondDevice);
            // Put device to audio connected state
            doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED)
                    .when(mStateMachines.get(firstDevice))
                    .getAudioState();
            // Connect to 2nd device will fail
            assertThat(mHeadsetService.connectAudio(secondDevice))
                    .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
            verify(mStateMachines.get(secondDevice), never())
                    .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, secondDevice);
        }
    }

    /**
     * Verify that {@link HeadsetService#connectAudio()} will connect to first connected/connecting
     * device
     */
    @Test
    public void testConnectAudio_firstConnectedAudioDevice() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        doAnswer(invocation -> Set.copyOf(connectedDevices))
                .when(mAdapterService)
                .getBondedDevices();
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            verify(mObjectsFactory, times(i + 1))
                    .makeStateMachine(
                            any(BluetoothDevice.class),
                            eq(mHeadsetService.getStateMachinesThreadLooper()),
                            eq(mHeadsetService),
                            eq(mAdapterService),
                            eq(mStorage),
                            eq(mNativeInterface),
                            eq(mSystemInterface));
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT), any(BluetoothDevice.class));
            // Put device to connecting
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Put device to connected
            connectedDevices.add(mCurrentDevice);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                    .isEqualTo(STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_CONNECTING, STATE_CONNECTED);
        }
        // Try to connect audio
        BluetoothDevice firstDevice = connectedDevices.get(0);
        doReturn(true).when(mSystemInterface).isInCall();
        assertThat(mHeadsetService.setActiveDevice(firstDevice)).isTrue();
        verify(mStateMachines.get(firstDevice))
                .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, firstDevice);
    }

    /**
     * Test to verify that {@link HeadsetService#connectAudio(BluetoothDevice)} fails if device was
     * never connected
     */
    @Test
    public void testConnectAudio_deviceNeverConnected() {
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connectAudio(mCurrentDevice))
                .isEqualTo(BluetoothStatusCodes.ERROR_PROFILE_NOT_CONNECTED);
    }

    /**
     * Test to verify that {@link HeadsetService#connectAudio(BluetoothDevice)} fails if device is
     * disconnected
     */
    @Test
    public void testConnectAudio_deviceDisconnected() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        // Put device in disconnected state
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                .isEqualTo(STATE_DISCONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).isEmpty();
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        // connectAudio should fail
        assertThat(mHeadsetService.connectAudio(mCurrentDevice))
                .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
        verify(mStateMachines.get(mCurrentDevice), never())
                .sendMessage(eq(HeadsetStateMachine.CONNECT_AUDIO), any());
    }

    /**
     * Verifies that phone state change will trigger a system-wide saving of call state even when no
     * device is connected
     *
     * @throws RemoteException if binder call fails
     */
    @Test
    public void testPhoneStateChange_noDeviceSaveState() throws RemoteException {
        HeadsetCallState headsetCallState =
                new HeadsetCallState(
                        1, 0, HeadsetHalConstants.CALL_STATE_ALERTING, TEST_PHONE_NUMBER, 128, "");
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        verify(mAudioManager, never()).setA2dpSuspended(true);
        verify(mAudioManager, never()).setLeAudioSuspended(true);
        verifyPhoneStateChangeSetters(mPhoneState, headsetCallState, ASYNC_CALL_TIMEOUT_MILLIS);
    }

    /**
     * Verifies that phone state change will trigger a system-wide saving of call state and send
     * state change to connected devices
     *
     * @throws RemoteException if binder call fails
     */
    @Test
    public void testPhoneStateChange_oneDeviceSaveState() throws RemoteException {
        HeadsetCallState headsetCallState =
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_IDLE, TEST_PHONE_NUMBER, 128, "");
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        final ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        // Connect one device
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        // Put device to connecting
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        doReturn(STATE_CONNECTING).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mHeadsetService.getConnectedDevices())
                .containsExactlyElementsIn(connectedDevices);
        // Put device to connected
        connectedDevices.add(mCurrentDevice);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices())
                .containsExactlyElementsIn(connectedDevices);
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_CONNECTING, STATE_CONNECTED);
        // Change phone state
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());

        // Should not ask Audio HAL to suspend A2DP or LE Audio without active device
        verify(mAudioManager, never()).setA2dpSuspended(true);
        verify(mAudioManager, never()).setLeAudioSuspended(true);
        // Make sure we notify device about this change
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CALL_STATE_CHANGED, headsetCallState);
        // Make sure state is updated once in phone state holder
        verifyPhoneStateChangeSetters(mPhoneState, headsetCallState, ASYNC_CALL_TIMEOUT_MILLIS);

        // Set the device first as the active device
        assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
        // Change phone state
        headsetCallState.mCallState = HeadsetHalConstants.CALL_STATE_ALERTING;
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        // Ask Audio HAL to suspend A2DP and LE Audio
        verify(mAudioManager).setA2dpSuspended(true);
        verify(mAudioManager).setLeAudioSuspended(true);
        // Make sure state is updated
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CALL_STATE_CHANGED, headsetCallState);
        verify(mPhoneState).setCallState(eq(headsetCallState.mCallState));
    }

    /**
     * Verifies that phone state change will trigger a system-wide saving of call state and send
     * state change to connected devices
     *
     * @throws RemoteException if binder call fails
     */
    @Test
    public void testPhoneStateChange_multipleDevicesSaveState() throws RemoteException {
        HeadsetCallState headsetCallState =
                new HeadsetCallState(
                        1, 0, HeadsetHalConstants.CALL_STATE_ALERTING, TEST_PHONE_NUMBER, 128, "");
        final ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            verify(mObjectsFactory, times(i + 1))
                    .makeStateMachine(
                            any(BluetoothDevice.class),
                            eq(mHeadsetService.getStateMachinesThreadLooper()),
                            eq(mHeadsetService),
                            eq(mAdapterService),
                            eq(mStorage),
                            eq(mNativeInterface),
                            eq(mSystemInterface));
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(HeadsetStateMachine.CONNECT, mCurrentDevice);
            verify(mStateMachines.get(mCurrentDevice))
                    .sendMessage(eq(HeadsetStateMachine.CONNECT), any(BluetoothDevice.class));
            // Put device to connecting
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            // Put device to connected
            connectedDevices.add(mCurrentDevice);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            doReturn(SystemClock.uptimeMillis())
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectingTimestampMs();
            assertThat(mHeadsetService.getConnectionState(mCurrentDevice))
                    .isEqualTo(STATE_CONNECTED);
            assertThat(mHeadsetService.getConnectedDevices())
                    .containsExactlyElementsIn(connectedDevices);
            mHeadsetService.onConnectionStateChangedFromStateMachine(
                    mCurrentDevice, STATE_CONNECTING, STATE_CONNECTED);
            assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
        }
        // Change phone state
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        // Ask Audio HAL to suspend A2DP and LE Audio
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        // Make sure we notify devices about this change
        for (BluetoothDevice device : connectedDevices) {
            verify(mStateMachines.get(device))
                    .sendMessage(HeadsetStateMachine.CALL_STATE_CHANGED, headsetCallState);
        }
        // Make sure state is updated once in phone state holder
        verifyPhoneStateChangeSetters(mPhoneState, headsetCallState, ASYNC_CALL_TIMEOUT_MILLIS);
    }

    /** Verifies that all CLCC responses are sent to the connected device. */
    @Test
    public void testClccResponse_withOneDeviceConnected() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTED);
        mHeadsetService.clccResponse(1, 0, 0, 0, false, "8225319000", 0);
        // index 0 is the end mark of CLCC response.
        mHeadsetService.clccResponse(0, 0, 0, 0, false, "8225319000", 0);
        verify(mStateMachines.get(mCurrentDevice), times(2))
                .sendMessage(
                        eq(HeadsetStateMachine.SEND_CLCC_RESPONSE), any(HeadsetClccResponse.class));
    }

    /** Verifies that all CLCC responses are sent to the connecting device. */
    @Test
    public void testClccResponse_withOneDeviceConnecting() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        mCurrentDevice,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTING).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(mCurrentDevice)).isEqualTo(STATE_CONNECTING);
        mHeadsetService.clccResponse(1, 0, 0, 0, false, "8225319000", 0);
        // index 0 is the end mark of CLCC response.
        mHeadsetService.clccResponse(0, 0, 0, 0, false, "8225319000", 0);
        verify(mStateMachines.get(mCurrentDevice), times(2))
                .sendMessage(
                        eq(HeadsetStateMachine.SEND_CLCC_RESPONSE), any(HeadsetClccResponse.class));
    }

    /**
     * Verifies that all CLCC responses are sent to the connected devices even it is connected in
     * the middle of generating CLCC responses.
     */
    @Test
    public void testClccResponse_withMultipleDevicesConnected() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 2; i >= 0; i--) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
            connectedDevices.add(mCurrentDevice);
            // index 0 is the end mark of CLCC response.
            mHeadsetService.clccResponse(i, 0, 0, 0, false, "8225319000", 0);
        }
        for (int i = 2; i >= 0; i--) {
            verify(mStateMachines.get(connectedDevices.get(i)), times(3))
                    .sendMessage(
                            eq(HeadsetStateMachine.SEND_CLCC_RESPONSE),
                            any(HeadsetClccResponse.class));
        }
    }

    /**
     * Verifies that all CLCC responses are sent to the connected devices even it is connected in
     * the middle of generating CLCC responses.
     */
    @Test
    public void testClccResponse_withMultipleDevicesConnecting() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 2; i >= 0; i--) {
            mCurrentDevice = getTestDevice(i);
            assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
            verify(mObjectsFactory)
                    .makeStateMachine(
                            mCurrentDevice,
                            mHeadsetService.getStateMachinesThreadLooper(),
                            mHeadsetService,
                            mAdapterService,
                            mStorage,
                            mNativeInterface,
                            mSystemInterface);
            doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
            doReturn(STATE_CONNECTING)
                    .when(mStateMachines.get(mCurrentDevice))
                    .getConnectionState();
            connectedDevices.add(mCurrentDevice);
            // index 0 is the end mark of CLCC response.
            mHeadsetService.clccResponse(i, 0, 0, 0, false, "8225319000", 0);
        }
        for (int i = 2; i >= 0; i--) {
            verify(mStateMachines.get(connectedDevices.get(i)), times(3))
                    .sendMessage(
                            eq(HeadsetStateMachine.SEND_CLCC_RESPONSE),
                            any(HeadsetClccResponse.class));
        }
    }

    /** Test that whether active device been removed after enable silence mode */
    @Test
    public void testSetSilenceMode() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        BluetoothDevice otherDevice = getTestDevice(1);
        mockGetRemoteDevice(mAdapterService, mCurrentDevice, otherDevice);
        for (BluetoothDevice device : List.of(mCurrentDevice, otherDevice)) {
            assertThat(mHeadsetService.connect(device)).isTrue();
            doReturn(device).when(mStateMachines.get(device)).getDevice();
            doReturn(STATE_CONNECTED).when(mStateMachines.get(device)).getConnectionState();
            doReturn(true).when(mStateMachines.get(device)).setSilenceDevice(anyBoolean());
        }

        // Test whether active device been removed after enable silence mode.
        assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(mCurrentDevice);
        assertThat(mHeadsetService.setSilenceMode(mCurrentDevice, true)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isNull();

        // Test whether active device been resumed after disable silence mode.
        assertThat(mHeadsetService.setSilenceMode(mCurrentDevice, false)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(mCurrentDevice);

        // Test that active device should not be changed when silence a non-active device
        assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(mCurrentDevice);
        assertThat(mHeadsetService.setSilenceMode(otherDevice, true)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(mCurrentDevice);

        // Test that active device should not be changed when another device exits silence mode
        assertThat(mHeadsetService.setSilenceMode(otherDevice, false)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(mCurrentDevice);
    }

    /** Test that whether active device been removed after enable silence mode */
    @Test
    public void testSetActiveDevice_AudioNotAllowed() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);

        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();

        assertThat(mHeadsetService.setActiveDevice(null)).isTrue();
        doReturn(true).when(mSystemInterface).isInCall();
        mHeadsetService.setAudioRouteAllowed(false);

        // Test that active device should not be changed if audio is not allowed
        assertThat(mHeadsetService.setActiveDevice(mCurrentDevice)).isFalse();
        assertThat(mHeadsetService.getActiveDevice()).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();

        mHeadsetService.dump(sb);
    }

    @Test
    public void testGetFallbackCandidates() {
        BluetoothDevice deviceA = getTestDevice(0);
        BluetoothDevice deviceB = getTestDevice(1);

        // No connected device
        assertThat(mHeadsetService.getFallbackCandidates()).isEmpty();

        // One connected device
        addConnectedDeviceHelper(deviceA);
        assertThat(mHeadsetService.getFallbackCandidates()).containsExactly(deviceA);

        // Two connected devices
        addConnectedDeviceHelper(deviceB);
        assertThat(mHeadsetService.getFallbackCandidates()).containsExactly(deviceA, deviceB);
    }

    @Test
    public void testGetFallbackCandidates_HasWatchDevice() {
        BluetoothDevice deviceWatch = getTestDevice(0);
        BluetoothDevice deviceRegular = getTestDevice(1);

        // Make deviceWatch a watch
        doReturn(BluetoothDevice.DEVICE_TYPE_WATCH.getBytes())
                .when(mAdapterService)
                .getMetadata(deviceWatch, BluetoothDevice.METADATA_DEVICE_TYPE);
        doReturn(null)
                .when(mAdapterService)
                .getMetadata(deviceRegular, BluetoothDevice.METADATA_DEVICE_TYPE);

        // Has a connected watch device
        addConnectedDeviceHelper(deviceWatch);
        assertThat(mHeadsetService.getFallbackCandidates()).isEmpty();

        // Two connected devices with one watch
        addConnectedDeviceHelper(deviceRegular);
        assertThat(mHeadsetService.getFallbackCandidates()).containsExactly(deviceRegular);
    }

    @Test
    public void testGetFallbackCandidates_HasWatchDeviceWithCod() {
        BluetoothDevice deviceWatch = getTestDevice(0);
        BluetoothDevice deviceRegular = getTestDevice(1);

        // Make deviceWatch as watch with COD
        doReturn(BluetoothClass.Device.WEARABLE_WRIST_WATCH)
                .when(mAdapterService)
                .getRemoteClass(deviceWatch);
        doReturn(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
                .when(mAdapterService)
                .getRemoteClass(deviceRegular);

        // Has a connected watch device
        addConnectedDeviceHelper(deviceWatch);
        assertThat(mHeadsetService.getFallbackCandidates()).isEmpty();

        // Two connected devices with one watch
        addConnectedDeviceHelper(deviceRegular);
        assertThat(mHeadsetService.getFallbackCandidates()).containsExactly(deviceRegular);
    }

    @Test
    public void testConnectDeviceNotAllowedInbandRingPolicy_InbandRingStatus() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        assertThat(mHeadsetService.getConnectedDevices()).isEqualTo(List.of(mCurrentDevice));
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        mHeadsetService.setActiveDevice(mCurrentDevice);

        doReturn(
                        new BluetoothSinkAudioPolicy.Builder()
                                .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setActiveDevicePolicyAfterConnection(
                                        BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .build())
                .when(mStateMachines.get(mCurrentDevice))
                .getHfpCallAudioPolicy();
        assertThat(mHeadsetService.isInbandRingingEnabled()).isTrue();

        doReturn(
                        new BluetoothSinkAudioPolicy.Builder()
                                .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setActiveDevicePolicyAfterConnection(
                                        BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setInBandRingtonePolicy(
                                        BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                                .build())
                .when(mStateMachines.get(mCurrentDevice))
                .getHfpCallAudioPolicy();
        assertThat(mHeadsetService.isInbandRingingEnabled()).isFalse();
    }

    @Test
    public void testIncomingCallDeviceConnect_InbandRingStatus() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        connectDeviceHelper(mCurrentDevice);

        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(mStateMachines.get(mCurrentDevice))
                .getHfpCallAudioPolicy();

        doReturn(true).when(mSystemInterface).isRinging();
        mHeadsetService.setActiveDevice(mCurrentDevice);

        verify(mNativeInterface).setActiveDevice(mCurrentDevice);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(HeadsetStateMachine.CONNECT_AUDIO, mCurrentDevice);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.SEND_BSIR), eq(1));
    }

    @Test
    public void testIncomingCallWithDeviceAudioConnected() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        for (int i = 2; i >= 0; i--) {
            mCurrentDevice = getTestDevice(i);
            connectDeviceHelper(mCurrentDevice);
            connectedDevices.add(mCurrentDevice);
        }

        mHeadsetService.setActiveDevice(connectedDevices.get(1));
        doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED)
                .when(mStateMachines.get(connectedDevices.get(1)))
                .getAudioState();

        doReturn(true).when(mSystemInterface).isRinging();
        mHeadsetService.setActiveDevice(connectedDevices.get(2));

        verify(mNativeInterface).setActiveDevice(connectedDevices.get(2));
        verify(mStateMachines.get(connectedDevices.get(2)), atLeast(1))
                .sendMessage(eq(HeadsetStateMachine.SEND_BSIR), eq(0));
    }

    @Test
    public void isScoAcceptable_notActiveDevice_returnsError() {
        BluetoothDevice device = getTestDevice(0);
        connectTestDevice(device);
        // Don't set active device
        assertThat(mHeadsetService.getActiveDevice()).isNull();

        assertThat(mHeadsetService.isScoAcceptable(device))
                .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
    }

    @Test
    public void isScoAcceptable_nullDevice_returnsError() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);

        assertThat(mHeadsetService.isScoAcceptable(null))
                .isEqualTo(BluetoothStatusCodes.ERROR_NOT_ACTIVE_DEVICE);
    }

    @Test
    public void isScoAcceptable_audioRouteNotAllowed_returnsError() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        mHeadsetService.setAudioRouteAllowed(false);

        assertThat(mHeadsetService.isScoAcceptable(device))
                .isEqualTo(BluetoothStatusCodes.ERROR_AUDIO_ROUTE_BLOCKED);
    }

    @Test
    public void isScoAcceptable_idle_returnsError() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isCallIdle();
        doReturn(false).when(mSystemInterface).isInCall();
        doReturn(false).when(mSystemInterface).isRinging();

        assertThat(mHeadsetService.isScoAcceptable(device))
                .isEqualTo(BluetoothStatusCodes.ERROR_CALL_ACTIVE);
    }

    @Test
    public void isScoAcceptable_inCall_returnsSuccess() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isInCall();

        assertThat(mHeadsetService.isScoAcceptable(device)).isEqualTo(BluetoothStatusCodes.SUCCESS);
    }

    @Test
    public void isScoAcceptable_ringingWithInbandEnabled_returnsSuccess() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isRinging();
        // isInbandRingingEnabled() is true by default in test setup

        assertThat(mHeadsetService.isScoAcceptable(device)).isEqualTo(BluetoothStatusCodes.SUCCESS);
    }

    @Test
    public void isScoAcceptable_ringingWithInbandDisabled_returnsError() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isRinging();
        // Disable inband ringing
        doReturn(
                        new BluetoothSinkAudioPolicy.Builder()
                                .setInBandRingtonePolicy(
                                        BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                                .build())
                .when(mStateMachines.get(device))
                .getHfpCallAudioPolicy();
        assertThat(mHeadsetService.isInbandRingingEnabled()).isFalse();

        assertThat(mHeadsetService.isScoAcceptable(device))
                .isEqualTo(BluetoothStatusCodes.ERROR_CALL_ACTIVE);
    }

    @Test
    public void isScoAcceptable_voiceRecognitionStarted_returnsSuccess() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mNativeInterface).isVoiceRecognitionSupported(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();

        assertThat(mHeadsetService.isScoAcceptable(device)).isEqualTo(BluetoothStatusCodes.SUCCESS);
    }

    @Test
    public void isScoAcceptable_virtualCallStarted_returnsSuccess() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        assertThat(mHeadsetService.startScoUsingVirtualVoiceCall()).isTrue();

        assertThat(mHeadsetService.isScoAcceptable(device)).isEqualTo(BluetoothStatusCodes.SUCCESS);
    }

    @Test
    @RequiresFlagsEnabled({
        android.media.audio.Flags.FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT,
        android.media.audio.Flags.FLAG_DEPRECATE_STREAM_BT_SCO
    })
    public void testVolumeChange_sendsMessageToStateMachine() {
        int volumeIndex = 7; // sample value used for testing volume change
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        assertThat(mHeadsetService.getConnectedDevices()).containsExactly(mCurrentDevice);
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        mHeadsetService.setActiveDevice(mCurrentDevice);

        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(
                        AudioDeviceAttributes.ROLE_OUTPUT,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        mCurrentDevice.getAddress());
        VolumeInfo volumeInfo =
                new VolumeInfo.Builder(AudioManager.STREAM_VOICE_CALL)
                        .setVolumeIndex(volumeIndex)
                        .build();
        ArgumentCaptor<AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener> callback =
                ArgumentCaptor.forClass(
                        AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener.class);
        verify(mAudioDeviceVolumeManager)
                .setDeviceAbsoluteMultiVolumeBehavior(any(), any(), any(), callback.capture());

        callback.getValue().onAudioDeviceVolumeChanged(attributes, volumeInfo);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.SCO_VOLUME_CHANGED), eq(volumeIndex));
    }

    @Test
    @RequiresFlagsEnabled({
        android.media.audio.Flags.FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT,
    })
    public void testVolumeChangeAssistant_sendsMessageToStateMachine() {
        int volumeIndex = 7; // sample value used for testing volume change
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        mCurrentDevice = getTestDevice(0);
        assertThat(mHeadsetService.connect(mCurrentDevice)).isTrue();
        doReturn(mCurrentDevice).when(mStateMachines.get(mCurrentDevice)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(SystemClock.uptimeMillis())
                .when(mStateMachines.get(mCurrentDevice))
                .getConnectingTimestampMs();
        assertThat(mHeadsetService.getConnectedDevices()).containsExactly(mCurrentDevice);
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        mHeadsetService.setActiveDevice(mCurrentDevice);

        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(
                        AudioDeviceAttributes.ROLE_OUTPUT,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        mCurrentDevice.getAddress());
        VolumeInfo volumeInfo =
                new VolumeInfo.Builder(AudioManager.STREAM_ASSISTANT)
                        .setVolumeIndex(volumeIndex)
                        .build();
        ArgumentCaptor<AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener> callback =
                ArgumentCaptor.forClass(
                        AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener.class);
        verify(mAudioDeviceVolumeManager)
                .setDeviceAbsoluteMultiVolumeBehavior(any(), any(), any(), callback.capture());

        callback.getValue().onAudioDeviceVolumeChanged(attributes, volumeInfo);
        verify(mStateMachines.get(mCurrentDevice))
                .sendMessage(eq(HeadsetStateMachine.SCO_VOLUME_CHANGED), eq(volumeIndex));
    }

    private static void verifyPhoneStateChangeSetters(
            HeadsetPhoneState headsetPhoneState, HeadsetCallState headsetCallState, int timeoutMs) {
        verify(headsetPhoneState, timeout(timeoutMs)).setNumActiveCall(headsetCallState.mNumActive);
        verify(headsetPhoneState, timeout(timeoutMs)).setNumHeldCall(headsetCallState.mNumHeld);
        verify(headsetPhoneState, timeout(timeoutMs)).setCallState(headsetCallState.mCallState);
    }

    private void addConnectedDeviceHelper(BluetoothDevice device) {
        mCurrentDevice = device;
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), eq(BluetoothProfile.HEADSET));
        assertThat(mHeadsetService.connect(device)).isTrue();
        doReturn(device).when(mStateMachines.get(device)).getDevice();
        doReturn(STATE_CONNECTING).when(mStateMachines.get(device)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);
        doReturn(STATE_CONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        assertThat(mHeadsetService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);
        assertThat(mHeadsetService.getConnectedDevices()).contains(device);
    }

    /**
     * Helper function to test okToAcceptConnection() method
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, could be invalid
     * @param expected expected result from okToAcceptConnection()
     */
    private void testOkToAcceptConnectionCase(
            BluetoothDevice device, int bondState, int priority, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        doReturn(priority)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);
        assertThat(mHeadsetService.okToAcceptConnection(device, false)).isEqualTo(expected);
    }

    private void connectDeviceHelper(BluetoothDevice device) {
        assertThat(mHeadsetService.connect(device)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        device,
                        mHeadsetService.getStateMachinesThreadLooper(),
                        mHeadsetService,
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mSystemInterface);
        doReturn(device).when(mStateMachines.get(device)).getDevice();
        doReturn(STATE_CONNECTED).when(mStateMachines.get(device)).getConnectionState();
    }

    private void connectTestDevice(BluetoothDevice device) {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEADSET));
        assertThat(mHeadsetService.connect(device)).isTrue();
        HeadsetStateMachine stateMachine = mStateMachines.get(device);
        doReturn(device).when(stateMachine).getDevice();
        doReturn(STATE_CONNECTED).when(stateMachine).getConnectionState();
        doReturn(SystemClock.uptimeMillis()).when(stateMachine).getConnectingTimestampMs();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(stateMachine)
                .getHfpCallAudioPolicy();
        mHeadsetService.onConnectionStateChangedFromStateMachine(
                device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    private void connectAndSetActiveDevice(BluetoothDevice device) {
        connectTestDevice(device);
        assertThat(mHeadsetService.setActiveDevice(device)).isTrue();
        assertThat(mHeadsetService.getActiveDevice()).isEqualTo(device);
    }

    @Test
    public void onAudioStateChanged_scoNotManagedByAudio_cleansUpVr() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(false).when(mSystemInterface).isScoManagedByAudioEnabled();
        doReturn(true).when(mNativeInterface).isVoiceRecognitionSupported(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        doReturn(true).when(mSystemInterface).deactivateVoiceRecognition(device);

        // Start voice recognition
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();

        // Mock that audio is connected
        doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED)
                .when(mStateMachines.get(device))
                .getAudioState();
        assertThat(mHeadsetService.isAudioOn()).isTrue();

        // Disconnect audio
        mHeadsetService.onAudioStateChangedFromStateMachine(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);

        // Verify that cleanup is called
        verify(mSystemInterface).deactivateVoiceRecognition(device);
    }

    @Test
    public void onAudioStateChanged_scoManagedByAudio_doesNotCleanupVr() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mSystemInterface).isScoManagedByAudioEnabled();
        doReturn(true).when(mNativeInterface).isVoiceRecognitionSupported(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        doReturn(true).when(mSystemInterface).requestBluetoothAudio(device);

        // Start voice recognition
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();

        // Mock that audio is connected
        doReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED)
                .when(mStateMachines.get(device))
                .getAudioState();
        assertThat(mHeadsetService.isAudioOn()).isTrue();

        // Disconnect audio
        mHeadsetService.onAudioStateChangedFromStateMachine(
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);

        // Verify that cleanup is NOT called due to the early return
        verify(mSystemInterface, never()).deactivateVoiceRecognition(any());
    }

    @Test
    public void cleanUpAfterScoDisconnection_vrStarted_stopSucceeds() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mNativeInterface).isVoiceRecognitionSupported(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        // Make stopVoiceRecognitionByHeadset succeed
        doReturn(true).when(mSystemInterface).deactivateVoiceRecognition(device);

        // Start voice recognition to set mVoiceRecognitionStarted = true
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();

        // Execute the method under test
        mHeadsetService.cleanUpAfterScoDisconnection(device);

        // Verify stop was attempted and native interface was called with OK
        verify(mSystemInterface).deactivateVoiceRecognition(device);
        verify(mNativeInterface).atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mNativeInterface, never())
                .atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    @Test
    public void cleanUpAfterScoDisconnection_vrStarted_stopFails() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        doReturn(true).when(mNativeInterface).isVoiceRecognitionSupported(device);
        doReturn(true).when(mSystemInterface).isCallIdle(); // for isAudioModeIdle check
        // Make stopVoiceRecognitionByHeadset fail
        doReturn(false).when(mSystemInterface).deactivateVoiceRecognition(device);

        // Start voice recognition to set mVoiceRecognitionStarted = true
        assertThat(mHeadsetService.startVoiceRecognition(device)).isTrue();

        // Execute the method under test
        mHeadsetService.cleanUpAfterScoDisconnection(device);

        // Verify stop was attempted and native interface was called with ERROR
        verify(mSystemInterface).deactivateVoiceRecognition(device);
        verify(mNativeInterface).atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        verify(mNativeInterface, never())
                .atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    @Test
    public void cleanUpAfterScoDisconnection_vrNotStarted() {
        BluetoothDevice device = getTestDevice(0);
        connectAndSetActiveDevice(device);
        // Ensure mVoiceRecognitionStarted is false (default)

        // Execute the method under test
        mHeadsetService.cleanUpAfterScoDisconnection(device);

        // Verify nothing happens
        verify(mSystemInterface, never()).deactivateVoiceRecognition(device);
        verify(mNativeInterface, never()).atResponseCode(any(), anyInt(), anyInt());
    }
}
