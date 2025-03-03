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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
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
import android.platform.test.annotations.DisableFlags;
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

@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
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
    private static final int TEST_GROUP_ID = 1;

    private final BluetoothAdapter mAdapter =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter();
    private final BluetoothDevice mDevice = getTestDevice(134);
    private final BluetoothDevice mDeviceTwo = getTestDevice(231);

    private AttributionSource mAttributionSource;
    private VolumeControlService mService;
    private VolumeControlService.BluetoothVolumeControlBinder mBinder;
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
        mBinder = (VolumeControlService.BluetoothVolumeControlBinder) mService.initBinder();
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
    public void unBondDevice_whenConnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.bondStateChanged(mDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unBondDevice_whenConnected_keepStateMachine() {
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
    public void unBondDevice_whenDisconnecting_keepStateMachine() {
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
    public void unBondDevice_whenDisconnected_removeStateMachine() {
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
    public void disconnect_whenBonded_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
    }

    @Test
    public void disconnect_whenUnBonded_removeStateMachine() {
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
        stackEvent.valueInt1 = TEST_GROUP_ID; // groupId
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
        when(mLeAudioService.getActiveGroupId()).thenReturn(TEST_GROUP_ID);

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
        when(mLeAudioService.getActiveGroupId()).thenReturn(TEST_GROUP_ID);

        generateVolumeStateChanged(null, TEST_GROUP_ID, streamVol, 0, true, true);
        verify(mAudioManager)
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_MUTE), anyInt());

        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));

        // Verify that unmuting LeAudio device, unsets the mute state on the audio device
        generateVolumeStateChanged(null, TEST_GROUP_ID, streamVol, 0, false, true);
        verify(mAudioManager)
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    @Test
    @DisableFlags(Flags.FLAG_VCP_DEVICE_VOLUME_API_IMPROVEMENTS)
    public void volumeCacheDeprecated() {
        int groupId = 1;
        int volume = 6;

        assertThat(mService.getGroupVolume(groupId)).isEqualTo(-1);
        mService.setGroupVolume(groupId, volume);

        assertThat(mService.getGroupVolume(groupId)).isEqualTo(volume);

        volume = 10;
        // Send autonomous volume change.
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        assertThat(mService.getGroupVolume(groupId)).isEqualTo(volume);
    }

    @Test
    @EnableFlags(Flags.FLAG_VCP_DEVICE_VOLUME_API_IMPROVEMENTS)
    public void volumeCache() {
        int groupId = 1;
        int groupVolume = 6;
        int devOneVolume = 20;
        int devTwoVolume = 30;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        assertThat(mService.getGroupVolume(groupId)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group volume
        mService.setGroupVolume(groupId, groupVolume);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);

        // Send autonomous volume change.
        int autonomousVolume = 10;
        generateVolumeStateChanged(null, groupId, autonomousVolume, 0, false, true);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(autonomousVolume);

        // Set first device volume
        mService.setDeviceVolume(mDevice, devOneVolume, false);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(autonomousVolume);

        // Set second device volume
        mService.setDeviceVolume(mDeviceTwo, devTwoVolume, false);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(devTwoVolume);

        // Set group volume again
        mService.setGroupVolume(groupId, groupVolume);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);
    }

    @Test
    public void activeGroupChange() {
        int groupId_1 = 1;
        int volume_groupId_1 = 6;

        int groupId_2 = 2;
        int volume_groupId_2 = 20;

        assertThat(mService.getGroupVolume(groupId_1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(groupId_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        mService.setGroupVolume(groupId_1, volume_groupId_1);
        mService.setGroupVolume(groupId_2, volume_groupId_2);

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId_1);
        mService.setGroupActive(groupId_1, true);

        // Expected index for STREAM_MUSIC
        int expectedVol =
                (int) Math.round((double) (volume_groupId_1 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId_2);
        mService.setGroupActive(groupId_2, true);

        expectedVol =
                (int) Math.round((double) (volume_groupId_2 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());
    }

    @Test
    @DisableFlags(Flags.FLAG_VCP_DEVICE_VOLUME_API_IMPROVEMENTS)
    public void muteCacheDeprecated() {
        int groupId = 1;
        int volume = 6;

        assertThat(mService.getGroupMute(groupId)).isFalse();

        // Send autonomous volume change
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        // Mute
        mService.muteGroup(groupId);
        assertThat(mService.getGroupMute(groupId)).isTrue();

        // Make sure the volume is kept even when muted
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(volume);

        // Send autonomous unmute
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        assertThat(mService.getGroupMute(groupId)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_VCP_DEVICE_VOLUME_API_IMPROVEMENTS)
    public void muteCache() {
        int groupId = 1;
        int groupVolume = 6;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        assertThat(mService.getGroupMute(groupId)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Send autonomous volume change
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);

        // Mute
        mService.muteGroup(groupId);
        assertThat(mService.getGroupMute(groupId)).isTrue();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isTrue();

        // Make sure the volume is kept even when muted
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDeviceTwo)).isEqualTo(groupVolume);

        // Send autonomous unmute
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Mute first device
        mService.mute(mDevice);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();

        // Mute second device
        mService.mute(mDeviceTwo);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        assertThat(mService.getMute(mDevice)).isTrue();
        assertThat(mService.getMute(mDeviceTwo)).isTrue();

        // Unmute group should unmute devices even if group is unmuted
        mService.unmuteGroup(groupId);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        assertThat(mService.getMute(mDevice)).isFalse();
        assertThat(mService.getMute(mDeviceTwo)).isFalse();
    }

    /** Test Volume Control with muted stream. */
    @Test
    public void volumeChangeWhileMuted() {
        int groupId = 1;
        int volume = 6;

        assertThat(mService.getGroupMute(groupId)).isFalse();

        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        // Mute
        mService.muteGroup(groupId);
        assertThat(mService.getGroupMute(groupId)).isTrue();
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).muteGroup(eq(groupId));

        // Make sure the volume is kept even when muted
        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(volume);

        // Lower the volume and keep it mute
        mService.setGroupVolume(groupId, --volume);
        assertThat(mService.getGroupMute(groupId)).isTrue();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());

        // Don't unmute on consecutive calls either
        mService.setGroupVolume(groupId, --volume);
        assertThat(mService.getGroupMute(groupId)).isTrue();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());

        // Raise the volume and unmute
        volume += 10; // avoid previous volume levels and simplify mock verification
        doReturn(false).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        mService.setGroupVolume(groupId, ++volume);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        inOrderNative.verify(mNativeInterface).unmuteGroup(eq(groupId));
        // Verify the number of unmute calls after the second volume change
        mService.setGroupVolume(groupId, ++volume);
        assertThat(mService.getGroupMute(groupId)).isFalse();
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        // Make sure we unmuted only once
        inOrderNative.verify(mNativeInterface, never()).unmuteGroup(anyInt());
    }

    /** Test if phone will set volume which is read from the buds */
    @Test
    public void connectedDeviceWithUserPersistFlagSet() {
        int groupId = 1;
        int volumeDevice = 56;
        int volumeDeviceTwo = 100;
        int flags = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
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
        if (Flags.vcpDeviceVolumeApiImprovements()) {
            // AF always call setVolume via LeAudioService at first connected remote from group
            mService.setGroupVolume(groupId, 123);
            // It should be ignored and not set to native
            inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());
        }

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId);
        mService.setGroupActive(groupId, true);
        int expectedAfVol =
                (int) Math.round((double) (volumeDevice * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedAfVol), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
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
        if (Flags.vcpDeviceVolumeApiImprovements()) {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volumeDevice));
        } else {
            inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volumeDevice));
        }
    }

    private void testConnectedDeviceWithResetFlag(
            int resetVolumeDeviceOne, int resetVolumeDeviceTwo) {
        int groupId = 1;
        int streamVolume = 30;
        int streamMaxVolume = 100;
        int resetFlag = 0;

        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(streamVolume);
        when(mAudioManager.getStreamMaxVolume(anyInt())).thenReturn(streamMaxVolume);

        generateDeviceAvailableMessageFromNative(mDevice, 1);
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
        if (Flags.vcpDeviceVolumeApiImprovements()) {
            // AF always call setVolume via LeAudioService at first connected remote from group
            mService.setGroupVolume(groupId, expectedAfVol);
        }
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(expectedAfVol));

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId);
        mService.setGroupActive(groupId, true);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(streamVolume), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
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
        if (Flags.vcpDeviceVolumeApiImprovements()) {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(expectedAfVol));
        } else {
            inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(expectedAfVol));
        }
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
        int groupId = 1;
        int groupVolume = 56;
        int volume_2 = 20;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.setGroupVolume(groupId, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        if (Flags.vcpDeviceVolumeApiImprovements()) {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
        } else {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
            inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(groupVolume));
        }
    }

    /**
     * Test setting volume for a new group member who is discovered after the volume level for a
     * group was already changed and cached.
     */
    @Test
    public void lateDiscoveredGroupMember() {
        int groupId = 1;
        int groupVolume = 56;

        // For now only one device is in the group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP))
                .thenReturn(CSIS_GROUP_ID_INVALID);

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the group volume
        mService.setGroupVolume(groupId, groupVolume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // But gets the volume when it becomes the group member
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        mService.handleGroupNodeAdded(groupId, mDeviceTwo);
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
    }

    /**
     * Test setting volume to 0 for a group member who connects after the volume level for a group
     * was already changed and cached. LeAudio has no knowledge of mute for anything else than
     * telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void muteLateConnectingDevice() {
        int groupId = 1;
        int volume = 100;
        int volume_2 = 20;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(groupId, volume);

        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        // Check if it was muted
        inOrderNative.verify(mNativeInterface).muteGroup(eq(groupId));
        assertThat(mService.getGroupMute(groupId)).isTrue();

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        // Check if new device was muted
        if (Flags.vcpDeviceVolumeApiImprovements()) {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volume));
            inOrderNative.verify(mNativeInterface).mute(eq(mDeviceTwo));
        } else {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(volume));
            inOrderNative.verify(mNativeInterface).mute(eq(mDeviceTwo));
            inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(volume));
        }
    }

    /**
     * Test setting volume to 0 for a new group member who is discovered after the volume level for
     * a group was already changed and cached. LeAudio has no knowledge of mute for anything else
     * than telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void muteLateDiscoveredGroupMember() {
        int groupId = 1;
        int volume = 100;

        // For now only one device is in the group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP))
                .thenReturn(CSIS_GROUP_ID_INVALID);

        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(groupId, volume);

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
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        mService.handleGroupNodeAdded(groupId, mDeviceTwo);
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
        generateDeviceAvailableMessageFromNative(mDevice, 2);

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
        int groupId_1 = 1;
        int groupId_2 = 2;
        int groupVolume = 56;

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP))
                .thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        mService.setDeviceVolume(mDevice, groupVolume, true);
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId_1);
        mService.setDeviceVolume(mDevice, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(groupId_1, groupVolume);

        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId_2);
        mService.setDeviceVolume(mDevice, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(groupId_2, groupVolume);
    }

    @Test
    public void getGroupDevices() throws Exception {
        int groupId = 1;
        int groupVolume = 56;

        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, 2);

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

        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, false);
        inOrderCallback.verify(callback, never()).onDeviceVolumeChanged(any(), anyInt());

        when(mLeAudioService.getGroupDevices(groupId)).thenReturn(Arrays.asList(mDevice));
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));

        when(mCsipService.getGroupDevicesOrdered(groupId)).thenReturn(Arrays.asList(mDeviceTwo));
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));

        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));
    }

    @Test
    public void serviceBinderSetDeviceVolumeMethods() {
        int groupId = 1;
        int groupVolume = 56;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        InOrder inOrderNative = inOrder(mNativeInterface);

        mBinder.setDeviceVolume(mDevice, groupVolume, true, mAttributionSource);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        inOrderNative.verify(mNativeInterface).setGroupVolume(groupId, groupVolume);
        assertThat(mService.getGroupVolume(groupId)).isEqualTo(groupVolume);

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
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP))
                .thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(LE_AUDIO_GROUP_ID_INVALID);

        generateDeviceAvailableMessageFromNative(mDevice, 1);
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
        int groupId = 1;
        int groupVolume = 56;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        generateDeviceAvailableMessageFromNative(mDevice, 2);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        mService.setGroupVolume(groupId, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        generateVolumeStateChanged(
                mDeviceTwo, LE_AUDIO_GROUP_ID_INVALID, groupVolume, 0, false, true);

        if (Flags.vcpDeviceVolumeApiImprovements()) {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
        } else {
            inOrderNative.verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(groupVolume));
            inOrderNative.verify(mNativeInterface).setGroupVolume(eq(groupId), eq(groupVolume));
        }

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
        int groupId = 1;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

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
        int groupId = 1;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(mDeviceTwo, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDeviceTwo)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDeviceTwo);
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

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

        int groupId = 1;
        mBinder.muteGroup(groupId, mAttributionSource);
        verify(mNativeInterface).muteGroup(groupId);

        mBinder.unmuteGroup(groupId, mAttributionSource);
        verify(mNativeInterface).unmuteGroup(groupId);
    }

    @Test
    public void dump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
    }

    @Test
    public void volumeControlChangedCallback() throws Exception {
        int groupId = 1;
        int groupVolume = 56;
        int deviceOneVolume = 46;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, 2);

        mService.setDeviceVolume(mDevice, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(eq(groupId), eq(groupVolume));

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

        when(mCsipService.getGroupDevicesOrdered(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        // Send group volume change.
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);

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
        int groupId = 1;
        int groupVolume = 30;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(groupVolume);

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice);

        // Make active group as null and broadcast not active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());

        // Group is not broadcast primary group, AF will not be notified
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make active group as null and broadcast active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks())
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        when(mLeAudioService.isPrimaryGroup(groupId)).thenReturn(true);
        // Group is broadcast primary group, AF will be notified
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);
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
            BluetoothDevice device, int numberOfExtOffsets) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = device;
        event.valueInt1 = numberOfExtOffsets; // number of external outputs
        mService.messageFromNative(event);
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
