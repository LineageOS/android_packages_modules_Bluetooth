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

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.IBluetoothCsipSetCoordinator.CSIS_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.ParcelUuid;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;

import com.google.common.truth.Expect;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Test cases for {@link VolumeControlService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BassClientService mBassClientService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private VolumeControlNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private CsipSetCoordinatorService mCsipService;

    private static final int BT_LE_AUDIO_MAX_VOL = 255;
    private static final int MEDIA_MIN_VOL = 0;
    private static final int MEDIA_MAX_VOL = 25;
    private static final int CALL_MIN_VOL = 1;
    private static final int CALL_MAX_VOL = 8;
    private static final int GROUP_ID = 1;
    private static final int GROUP_ID_2 = 2;
    private static final int GROUP_ID_INVALID = -1;

    private final BluetoothAdapter mAdapter =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter();
    private final BluetoothDevice mDevice = getTestDevice(134);
    private final BluetoothDevice mDeviceTwo = getTestDevice(231);

    private AttributionSource mAttributionSource;
    private VolumeControlService mService;
    private VolumeControlServiceBinder mBinder;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() {
        doReturn(true).when(mNativeInterface).connectVolumeControl(any());
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any());

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.VOLUME_CONTROL})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();
        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();
        doReturn(mBassClientService).when(mServiceFactory).getBassClientService();

        doReturn(MEDIA_MIN_VOL)
                .when(mAudioManager)
                .getStreamMinVolume(eq(AudioManager.STREAM_MUSIC));
        doReturn(MEDIA_MAX_VOL)
                .when(mAudioManager)
                .getStreamMaxVolume(eq(AudioManager.STREAM_MUSIC));
        doReturn(CALL_MIN_VOL)
                .when(mAudioManager)
                .getStreamMinVolume(eq(AudioManager.STREAM_VOICE_CALL));
        doReturn(CALL_MAX_VOL)
                .when(mAudioManager)
                .getStreamMaxVolume(eq(AudioManager.STREAM_VOICE_CALL));
        TestUtils.mockGetSystemService(
                mAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mAudioManager);

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mAttributionSource = mAdapter.getAttributionSource();
        mService = new VolumeControlService(mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);

        mService.mFactory = mServiceFactory;
        mBinder = (VolumeControlServiceBinder) mService.initBinder();
    }

    @After
    public void tearDown() {
        assertThat(mLooper.nextMessage()).isNull();
        mService.cleanup();
        mLooper.dispatchAll();
        assertThat(VolumeControlService.getVolumeControlService()).isNull();
    }

    @Test
    public void getVolumeControlService() {
        assertThat(VolumeControlService.getVolumeControlService()).isEqualTo(mService);
    }

    @Test
    public void getConnectionPolicy() {
        for (int policy :
                List.of(
                        CONNECTION_POLICY_UNKNOWN,
                        CONNECTION_POLICY_FORBIDDEN,
                        CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.getConnectionPolicy(mDevice)).isEqualTo(policy);
        }
    }

    @Test
    public void canConnect_whenNotBonded_returnFalse() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        for (int bondState : List.of(BOND_NONE, BOND_BONDING, badBondState)) {
            for (int policy :
                    List.of(
                            CONNECTION_POLICY_UNKNOWN,
                            CONNECTION_POLICY_FORBIDDEN,
                            CONNECTION_POLICY_ALLOWED,
                            badPolicyValue)) {
                doReturn(bondState).when(mAdapterService).getBondState(any());
                doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
                assertThat(mService.okToConnect(mDevice)).isEqualTo(false);
            }
        }
    }

    @Test
    public void canConnect_whenBonded() {
        int badPolicyValue = 1024;
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());

        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice)).isEqualTo(false);
        }
        for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice)).isEqualTo(true);
        }
    }

    @Test
    public void connectToDevice_whenUuidIsMissing_returnFalse() {
        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mService.connect(mDevice)).isFalse();
    }

    @Test
    public void disconnect_whenConnecting_isDisconnectedWithBroadcast() {
        assertThat(mService.connect(mDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        assertThat(mService.disconnect(mDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);

        assertThat(mService.connect(mDevice)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(mService.connect(mDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(VolumeControlStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void incomingConnecting_whenNoDevice_createStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void incomingDisconnect_whenConnectingDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void incomingConnect_whenNoDevice_createStateMachine() {
        // Theoretically impossible case
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void incomingDisconnect_whenConnectedDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void incomingDisconnecting_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mDevice, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice);
    }

    @Test
    public void incomingDisconnect_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mDevice, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice);
    }

    @Test
    public void unbondDevice_whenConnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.bondStateChanged(mDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenConnected_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.bondStateChanged(mDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenDisconnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.bondStateChanged(mDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenDisconnected_removeStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.bondStateChanged(mDevice, BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices()).doesNotContain(mDevice);
    }

    @Test
    public void unbondDevice_whenDisconnected_removeDeviceData() {
        int groupVolume = 6;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        // Connect and disconnect first device
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Connect and disconnect second device
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);

        // Set group volume, check devices volume and group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond first device, group and second device volume should remain
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice);
        mService.bondStateChanged(mDevice, BOND_NONE);
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP))
                    .thenReturn(CSIS_GROUP_ID_INVALID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDeviceTwo));
        }
        expect.that(mService.getDevices()).doesNotContain(mDevice);
        expect.that(mService.getDevices()).contains(mDeviceTwo);
        expect.that(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond second device, both devices and group data should be removed
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDeviceTwo);
        mService.bondStateChanged(mDeviceTwo, BOND_NONE);
        mLooper.dispatchAll();
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP))
                    .thenReturn(CSIS_GROUP_ID_INVALID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID)).thenReturn(Arrays.asList());
        }
        expect.that(mService.getDevices()).doesNotContain(mDevice);
        expect.that(mService.getDevices()).doesNotContain(mDeviceTwo);
        expect.that(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    @Test
    public void disconnect_whenBonded_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void disconnect_whenUnbonded_removeStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        doReturn(BOND_NONE).when(mAdapterService).getBondState(any());
        mService.bondStateChanged(mDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);

        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);

        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice);
    }

    @Test
    public void disconnect_whenUnbonded_removeDeviceData() {
        int groupVolume = 6;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        // Connect and go to disconnecting on first device
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);

        // Connect and go to disconnecting on second device
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_DISCONNECTING, STATE_CONNECTED);

        // Set group volume, check devices volume and group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond both devices, data should remain
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice);
        mService.bondStateChanged(mDevice, BOND_NONE);
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDeviceTwo);
        mService.bondStateChanged(mDeviceTwo, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Disconnect first device, group and second device volume should remain
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        expect.that(mService.getDevices()).doesNotContain(mDevice);
        expect.that(mService.getDevices()).contains(mDeviceTwo);
        expect.that(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Disconnect second device, both devices and group data should be removed
        generateConnectionMessageFromNative(mDeviceTwo, STATE_DISCONNECTED, STATE_DISCONNECTING);
        expect.that(mService.getDevices()).doesNotContain(mDevice);
        expect.that(mService.getDevices()).doesNotContain(mDeviceTwo);
        expect.that(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    int getLeAudioVolume(int index, int minIndex, int maxIndex, int streamType) {
        // Note: This has to be the same as mBtHelper.setLeAudioVolume()
        return (int) Math.round((double) index * BT_LE_AUDIO_MAX_VOL / maxIndex);
    }

    void testVolumeCalculations(int streamType, int minIdx, int maxIdx) {
        // Send a message to trigger volume state changed broadcast
        final VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        stackEvent.device = null;
        stackEvent.valueInt1 = GROUP_ID; // groupId
        stackEvent.valueBool1 = false; // isMuted
        stackEvent.valueBool2 = true; // isAutonomous

        IntStream.range(minIdx, maxIdx)
                .forEach(
                        idx -> {
                            // Given the reference volume index, set the LeAudio Volume
                            stackEvent.valueInt2 =
                                    getLeAudioVolume(idx, minIdx, maxIdx, streamType);
                            mService.messageFromNative(stackEvent);

                            // Verify that setting LeAudio Volume, sets the original volume index to
                            // Audio FW
                            verify(mAudioManager)
                                    .setStreamVolume(eq(streamType), eq(idx), anyInt());
                        });
    }

    @Test
    public void incomingAutonomousVolumeStateChange_isApplied() {
        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID);

        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();
        testVolumeCalculations(AudioManager.STREAM_VOICE_CALL, CALL_MIN_VOL, CALL_MAX_VOL);

        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        testVolumeCalculations(AudioManager.STREAM_MUSIC, MEDIA_MIN_VOL, MEDIA_MAX_VOL);
    }

    @Test
    public void incomingAutonomousMuteUnmute_isApplied() {
        int streamType = AudioManager.STREAM_MUSIC;
        int streamVol = getLeAudioVolume(19, MEDIA_MIN_VOL, MEDIA_MAX_VOL, streamType);

        doReturn(false).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));

        // Verify that muting LeAudio device, sets the mute state on the audio device
        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID);

        generateVolumeStateChanged(null, GROUP_ID, streamVol, 0, true, true);
        verify(mAudioManager)
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_MUTE), anyInt());

        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));

        // Verify that unmuting LeAudio device, unsets the mute state on the audio device
        generateVolumeStateChanged(null, GROUP_ID, streamVol, 0, false, true);
        verify(mAudioManager)
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    @Test
    public void volumeCache() {
        int groupVolume = 6;
        int devOneVolume = 20;
        int devTwoVolume = 30;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);

        // Send autonomous volume change.
        int autonomousVolume = 10;
        generateVolumeStateChanged(null, GROUP_ID, autonomousVolume, 0, false, true);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(autonomousVolume);

        // Set first device volume
        mService.setDeviceVolume(mDevice, devOneVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(autonomousVolume);

        // Set second device volume
        mService.setDeviceVolume(mDeviceTwo, devTwoVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(devTwoVolume);

        // Set group volume again
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
    }

    @Test
    public void activeGroupChange() {
        int volumeGroup_1 = 6;
        int volumeGroup_2 = 20;

        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        mService.setGroupVolume(GROUP_ID, volumeGroup_1);
        mService.setGroupVolume(GROUP_ID_2, volumeGroup_2);

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID);
        mService.setGroupActive(GROUP_ID, true);

        // Expected index for STREAM_MUSIC
        int expectedVol =
                (int) Math.round((double) (volumeGroup_1 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID_2);
        mService.setGroupActive(GROUP_ID_2, true);

        expectedVol =
                (int) Math.round((double) (volumeGroup_2 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());
    }

    @Test
    public void muteCache() {
        int groupVolume = 6;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Send autonomous volume change
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);

        // Mute
        mService.muteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isTrue();

        // Make sure the volume is kept even when muted
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);

        // Send autonomous unmute
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Mute first device
        mService.mute(mDevice);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Mute second device
        mService.mute(mDeviceTwo);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isTrue();

        // Unmute group should unmute devices even if group is unmuted
        mService.unmuteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();
    }

    /** Test Volume Control with muted stream. */
    @Test
    public void volumeChangeWhileMuted() {
        int volume = 6;

        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();

        generateVolumeStateChanged(null, GROUP_ID, volume, 0, false, true);

        // Mute
        mService.muteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).muteGroup(eq(GROUP_ID));

        // Make sure the volume is kept even when muted
        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(volume);

        // Lower the volume and keep it mute
        mService.setGroupVolume(GROUP_ID, --volume);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());

        // Don't unmute on consecutive calls either
        mService.setGroupVolume(GROUP_ID, --volume);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());

        // Raise the volume and unmute
        volume += 10; // avoid previous volume levels and simplify mock verification
        doReturn(false).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        mService.setGroupVolume(GROUP_ID, ++volume);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volume));
        inOrderNative.verify(mNativeInterface).unmuteGroup(eq(GROUP_ID));
        // Verify the number of unmute calls after the second volume change
        mService.setGroupVolume(GROUP_ID, ++volume);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volume));
        // Make sure we unmuted only once
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());
    }

    /** Test if phone will set volume which is read from the buds */
    @Test
    public void connectedDeviceWithUserPersistFlagSet() {
        int volumeDevice = 56;
        int volumeDeviceTwo = 100;
        int flags = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        }

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());
        // Group is not active unicast and not active primary broadcast, AF will not be notified
        generateVolumeStateChanged(
                mDevice,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDevice,
                flags,
                initialMuteState,
                initialAutonomousFlag);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());

        InOrder inOrderNative = inOrder(mNativeInterface);
        // AF always call setVolume via LeAudioService at first connected remote from group
        mService.setGroupVolume(GROUP_ID, 123);
        // It should be ignored and not set to native
        inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID);
        mService.setGroupActive(GROUP_ID, true);
        int expectedAfVol =
                (int) Math.round((double) (volumeDevice * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedAfVol), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(
                mDeviceTwo,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDeviceTwo,
                flags,
                initialMuteState,
                initialAutonomousFlag);

        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volumeDevice));
    }

    @Test
    public void testClearingSetVolumeFromAF() {
        int volumeDevice = 56;
        int streamVolume = 30;
        int streamMaxVolume = 100;
        int persistFlag = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        int resetFlag = 0;
        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Set group for device
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID)).thenReturn(Arrays.asList(mDevice));
        }

        // Connect device, first group
        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());

        // Device volume updated with persisted flag, mIgnoreSetVolumeFromAF is set
        generateVolumeStateChanged(
                mDevice,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDevice,
                persistFlag,
                initialMuteState,
                initialAutonomousFlag);

        // AF not set volume before device disconnected
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Set group for second device
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID_2);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID_2))
                    .thenReturn(Arrays.asList(mDeviceTwo));
        }

        // Connected second device, second group
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID_2, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);

        // Device volume updated with reset flag and no cache, mIgnoreSetVolumeFromAF is cleared
        generateVolumeStateChanged(
                mDeviceTwo,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDevice,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);

        // AF always call setVolume via LeAudioService at first connected remote from group
        int expectedAfVol =
                (int) Math.round((double) streamVolume * BT_LE_AUDIO_MAX_VOL / streamMaxVolume);
        mService.setGroupVolume(GROUP_ID_2, expectedAfVol);
        verify(mNativeInterface).setGroupVolume(eq(GROUP_ID_2), eq(expectedAfVol));
    }

    private void testConnectedDeviceWithResetFlag(
            int resetVolumeDeviceOne, int resetVolumeDeviceTwo) {
        int streamVolume = 30;
        int streamMaxVolume = 100;
        int resetFlag = 0;

        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        }

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(streamVolume);
        when(mAudioManager.getStreamMaxVolume(anyInt())).thenReturn(streamMaxVolume);

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        int expectedAfVol =
                (int) Math.round((double) streamVolume * BT_LE_AUDIO_MAX_VOL / streamMaxVolume);

        // Group is not active, AF will not be notified. Device volume updated to system volume.
        generateVolumeStateChanged(
                mDevice,
                LE_AUDIO_GROUP_ID_INVALID,
                resetVolumeDeviceOne,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);

        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        InOrder inOrderNative = inOrder(mNativeInterface);
        // AF always call setVolume via LeAudioService at first connected remote from group
        mService.setGroupVolume(GROUP_ID, expectedAfVol);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(expectedAfVol));

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(GROUP_ID);
        mService.setGroupActive(GROUP_ID, true);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(streamVolume), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(
                mDeviceTwo,
                LE_AUDIO_GROUP_ID_INVALID,
                resetVolumeDeviceTwo,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);

        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(expectedAfVol));
    }

    /** Test if phone will set volume which is read from the buds */
    @Test
    public void connectedDeviceWithResetFlagSetWithNonZeroVolume() {
        testConnectedDeviceWithResetFlag(56, 100);
    }

    /** Test if phone will set volume to buds which has no volume */
    @Test
    public void connectedDeviceWithResetFlagSetWithZeroVolume() {
        testConnectedDeviceWithResetFlag(0, 0);
    }

    /**
     * Test setting volume for a group member who connects after the volume level for a group was
     * already changed and cached.
     */
    @Test
    public void lateConnectingDevice() {
        int groupVolume = 56;
        int volume_2 = 20;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.setGroupVolume(GROUP_ID, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
    }

    /**
     * Test setting volume for a new group member who is discovered after the volume level for a
     * group was already changed and cached.
     */
    @Test
    public void lateDiscoveredGroupMember() {
        int groupVolume = 56;

        // For now only one device is in the group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP))
                    .thenReturn(CSIS_GROUP_ID_INVALID);
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        }

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // But gets the volume when it becomes the group member
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        } else {
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }
        mService.handleGroupNodeAdded(GROUP_ID, mDeviceTwo);
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
    }

    /**
     * Test setting volume to 0 for a group member who connects after the volume level for a group
     * was already changed and cached. LeAudio has no knowledge of mute for anything else than
     * telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void muteLateConnectingDevice() {
        int volume = 100;
        int volume_2 = 20;

        // Both devices are in the same group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(GROUP_ID, volume);

        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        // Check if it was muted
        inOrderNative.verify(mNativeInterface).muteGroup(eq(GROUP_ID));
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        // Check if new device was muted
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volume));
        inOrderNative.verify(mNativeInterface).mute(eq(mDeviceTwo));
    }

    /**
     * Test setting volume to 0 for a new group member who is discovered after the volume level for
     * a group was already changed and cached. LeAudio has no knowledge of mute for anything else
     * than telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void muteLateDiscoveredGroupMember() {
        int volume = 100;

        // For now only one device is in the group
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP))
                    .thenReturn(CSIS_GROUP_ID_INVALID);
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        }

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(GROUP_ID, volume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, volume, 0, false, true);

        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        // Check if it was not muted
        inOrderNative.verify(mNativeInterface, never()).mute(any());

        // But gets the volume when it becomes the group member
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        } else {
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }
        mService.handleGroupNodeAdded(GROUP_ID, mDeviceTwo);
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volume));
        inOrderNative.verify(mNativeInterface).mute(eq(mDeviceTwo));
    }

    @Test
    public void serviceBinderGetDevicesMatchingConnectionStates() {
        assertThat(mBinder.getDevicesMatchingConnectionStates(null, mAttributionSource)).isEmpty();
    }

    @Test
    public void serviceBinderSetConnectionPolicy() {
        assertThat(
                        mBinder.setConnectionPolicy(
                                mDevice, CONNECTION_POLICY_UNKNOWN, mAttributionSource))
                .isTrue();
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.VOLUME_CONTROL, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void serviceBinderVolumeOffsetMethods() {
        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 2, 1);

        assertThat(mBinder.isVolumeOffsetAvailable(mDevice, mAttributionSource)).isTrue();

        int numberOfInstances =
                mBinder.getNumberOfVolumeOffsetInstances(mDevice, mAttributionSource);
        assertThat(numberOfInstances).isEqualTo(2);

        int id = 1;
        int volumeOffset = 100;
        mBinder.setVolumeOffset(mDevice, id, volumeOffset, mAttributionSource);
        verify(mNativeInterface).setExtAudioOutVolumeOffset(mDevice, id, volumeOffset);
    }

    @Test
    public void getGroupId() {
        int groupVolume = 56;

        if (!Flags.vcpHandleGroupIdInternally()) {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
            assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
            assertThat(mService.getDevices()).contains(mDevice);

            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP))
                    .thenReturn(LE_AUDIO_GROUP_ID_INVALID);
            when(mLeAudioService.getGroupId(mDevice)).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
            mService.setDeviceVolume(mDevice, groupVolume, true);
            verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

            when(mLeAudioService.getGroupId(mDevice)).thenReturn(GROUP_ID);
            mService.setDeviceVolume(mDevice, groupVolume, true);
            verify(mNativeInterface).setGroupVolume(GROUP_ID, groupVolume);

            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID_2);
            mService.setDeviceVolume(mDevice, groupVolume, true);
            verify(mNativeInterface).setGroupVolume(GROUP_ID_2, groupVolume);
        } else {
            generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
            assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
            assertThat(mService.getDevices()).contains(mDevice);

            mService.setDeviceVolume(mDevice, groupVolume, true);
            verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
            mService.setDeviceVolume(mDevice, groupVolume, true);
            verify(mNativeInterface).setGroupVolume(GROUP_ID, groupVolume);
        }
    }

    @Test
    public void getGroupDevices() throws Exception {
        int groupVolume = 56;

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        InOrder inOrderCallback = inOrder(callback);

        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback, never()).onDeviceVolumeChanged(any(), anyInt());

        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mLeAudioService.getGroupDevices(GROUP_ID)).thenReturn(Arrays.asList(mDevice));
        } else {
            generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        }
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));

        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDeviceTwo));
            generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
            inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));

            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDeviceTwo, mDevice));
        } else {
            generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        }
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));
    }

    @Test
    public void serviceBinderSetDeviceVolumeMethods() {
        int groupVolume = 56;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        InOrder inOrderNative = inOrder(mNativeInterface);

        mBinder.setDeviceVolume(mDevice, groupVolume, true, mAttributionSource);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        inOrderNative.verify(mNativeInterface).setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        mBinder.setDeviceVolume(mDevice, deviceOneVolume, false, mAttributionSource);
        inOrderNative.verify(mNativeInterface).setVolume(mDevice, deviceOneVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(deviceOneVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isNotEqualTo(deviceOneVolume);
        inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        mBinder.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false, mAttributionSource);
        inOrderNative.verify(mNativeInterface).setVolume(mDeviceTwo, deviceTwoVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(deviceTwoVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isNotEqualTo(deviceTwoVolume);
        inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES)
    public void testServiceBinderSetDeviceVolumeNoGroupId() throws Exception {
        int deviceVolume = 42;
        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP))
                    .thenReturn(LE_AUDIO_GROUP_ID_INVALID);
            when(mLeAudioService.getGroupId(mDevice)).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        }

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID_INVALID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mBinder.setDeviceVolume(mDevice, deviceVolume, true, mAttributionSource);
        verify(mNativeInterface, never()).setVolume(any(), anyInt());
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        mBinder.setDeviceVolume(mDevice, deviceVolume, false, mAttributionSource);
        verify(mNativeInterface).setVolume(mDevice, deviceVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(deviceVolume);
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());
    }

    @Test
    public void testServiceBinderRegisterUnregisterCallback() throws Exception {
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);

            mService.unregisterCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size);
        }
    }

    @Test
    public void serviceBinderRegisterCallbackWhenDeviceAlreadyConnected() throws Exception {
        int groupVolume = 56;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 2, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.setGroupVolume(GROUP_ID, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(
                mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, groupVolume, 0, false, true);

        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));

        // Generate events for both devices
        generateDeviceOffsetChangedMessageFromNative(mDevice, 1, 100);
        generateDeviceLocationChangedMessageFromNative(mDevice, 1, 1);
        final String testDevice1Desc1 = "testDevice1Desc1";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 1, testDevice1Desc1);

        generateDeviceOffsetChangedMessageFromNative(mDevice, 2, 200);
        generateDeviceLocationChangedMessageFromNative(mDevice, 2, 2);
        final String testDevice1Desc2 = "testDevice1Desc2";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 2, testDevice1Desc2);

        generateDeviceOffsetChangedMessageFromNative(mDeviceTwo, 1, 250);
        generateDeviceLocationChangedMessageFromNative(mDeviceTwo, 1, 3);
        final String testDevice2Desc = "testDevice2Desc";
        generateDeviceDescriptionChangedMessageFromNative(mDeviceTwo, 1, testDevice2Desc);

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(1), eq(100));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(1), eq(1));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(1), eq(testDevice1Desc1));

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(2), eq(200));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(2), eq(2));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(2), eq(testDevice1Desc2));

        verify(callback).onVolumeOffsetChanged(eq(mDeviceTwo), eq(1), eq(250));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDeviceTwo), eq(1), eq(3));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDeviceTwo), eq(1), eq(testDevice2Desc));

        generateDeviceOffsetChangedMessageFromNative(mDevice, 1, 50);
        generateDeviceLocationChangedMessageFromNative(mDevice, 1, 0);
        final String testDevice1Desc3 = "testDevice1Desc3";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 1, testDevice1Desc3);

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(1), eq(50));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(1), eq(0));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(1), eq(testDevice1Desc3));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES)
    public void serviceBinderRegisterVolumeChangedCallbackWhenDeviceAlreadyConnected()
            throws Exception {
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));
    }

    @Test
    public void serviceBinderTestNotifyNewRegisteredCallback() throws Exception {
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        int size;
        synchronized (mService.mCallbacks) {
            size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        IBluetoothVolumeControlCallback callback_new_client =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder_new_client = Mockito.mock(Binder.class);
        when(callback_new_client.asBinder()).thenReturn(binder_new_client);

        mLooper.startAutoDispatch();
        mBinder.notifyNewRegisteredCallback(callback_new_client, mAttributionSource);
        mLooper.stopAutoDispatch();

        synchronized (mService.mCallbacks) {
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        // This shall be done only once after mService.registerCallback
        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));

        // This shall be done only once after mBinder.updateNewRegisteredCallback
        verify(callback_new_client).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback_new_client).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));
    }

    @Test
    public void serviceBinderMuteMethods() {
        mBinder.mute(mDevice, mAttributionSource);
        verify(mNativeInterface).mute(mDevice);

        mBinder.unmute(mDevice, mAttributionSource);
        verify(mNativeInterface).unmute(mDevice);

        mBinder.muteGroup(GROUP_ID, mAttributionSource);
        verify(mNativeInterface).muteGroup(GROUP_ID);

        mBinder.unmuteGroup(GROUP_ID, mAttributionSource);
        verify(mNativeInterface).unmuteGroup(GROUP_ID);
    }

    @Test
    public void dump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
    }

    @Test
    public void volumeControlChangedCallback() throws Exception {
        int groupVolume = 56;
        int deviceOneVolume = 46;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDeviceTwo, GROUP_ID, 1, 1);

        mService.setDeviceVolume(mDevice, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        if (!Flags.vcpHandleGroupIdInternally()) {
            when(mCsipService.getGroupDevicesOrdered(GROUP_ID))
                    .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        }

        // Send group volume change.
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);

        verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));

        // Send device volume change only for one device
        generateVolumeStateChanged(
                mDevice, LE_AUDIO_GROUP_ID_INVALID, deviceOneVolume, 0, false, false);

        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback, never()).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceOneVolume));
    }

    /** Test Volume Control changed for broadcast primary group. */
    @Test
    public void volumeControlChangedForBroadcastPrimaryGroup() {
        int groupVolume = 30;

        if (!Flags.vcpHandleGroupIdInternally()) {
            // Both devices are in the same group
            when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
            when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(GROUP_ID);
        }

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(groupVolume);

        generateDeviceAvailableMessageFromNative(mDevice, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Make active group as null and broadcast not active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());

        // Group is not broadcast primary group, AF will not be notified
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make active group as null and broadcast active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks())
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        when(mLeAudioService.isPrimaryGroup(GROUP_ID)).thenReturn(true);
        // Group is broadcast primary group, AF will be notified
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), anyInt(), anyInt());
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        mInOrder.verify(mAdapterService, never()).sendBroadcast(any(), any());
    }

    private void generateDeviceAvailableMessageFromNative(
            BluetoothDevice device,
            int groupId,
            int numberOfExtOffsets,
            int numberOfExternalInputs) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = device;
        event.valueInt1 = groupId;
        event.valueInt2 = numberOfExtOffsets; // number of external outputs
        event.valueInt3 = numberOfExternalInputs;
        mService.messageFromNative(event);
        mLooper.dispatchAll();
    }

    private void generateVolumeStateChanged(
            BluetoothDevice device,
            int group_id,
            int volume,
            int flags,
            boolean mute,
            boolean isAutonomous) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = group_id;
        stackEvent.valueInt2 = volume;
        stackEvent.valueInt3 = flags;
        stackEvent.valueBool1 = mute;
        stackEvent.valueBool2 = isAutonomous;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();
    }

    private void generateDeviceOffsetChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, int offset) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueInt2 = offset; // offset value
        mService.messageFromNative(event);
        mLooper.dispatchAll();
    }

    private void generateDeviceLocationChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, int location) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueInt2 = location; // location
        mService.messageFromNative(event);
        mLooper.dispatchAll();
    }

    private void generateDeviceDescriptionChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, String description) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueString1 = description; // description
        mService.messageFromNative(event);
        mLooper.dispatchAll();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothVolumeControl.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState));
        assertThat(mService.getConnectionState(device)).isEqualTo(newState);
    }
}
