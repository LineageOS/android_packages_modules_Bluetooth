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
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getRealDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.ParcelUuid;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

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

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Test cases for {@link VolumeControlService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class VolumeControlServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private BassClientService mBassClientService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private VolumeControlNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private CsipSetCoordinatorService mCsipService;

    private static final int BT_LE_AUDIO_MAX_VOL = 255;
    private static final int MEDIA_MIN_VOL = 0;
    private static final int MEDIA_MAX_VOL = 25;
    private static final int CALL_MIN_VOL = 1;
    private static final int CALL_MAX_VOL = 8;
    private static final int GROUP_ID = 1;
    private static final int GROUP_ID_2 = 2;
    private static final int GROUP_ID_INVALID = -1;

    private final BluetoothDevice mDevice1 = getRealDevice(134);
    private final BluetoothDevice mDevice2 = getRealDevice(231);

    private VolumeControlService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public VolumeControlServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() {
        doReturn(true).when(mNativeInterface).connectVolumeControl(any());
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any());

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());

        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.VOLUME_CONTROL})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doReturn(Optional.of(mCsipService)).when(mAdapterService).getCsipSetCoordinatorService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(Optional.of(mBassClientService)).when(mAdapterService).getBassClientService();

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
        TestUtils.mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService = new VolumeControlService(mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);
    }

    @After
    public void tearDown() {
        assertThat(mLooper.nextMessage()).isNull();
        mService.cleanup();
        mLooper.dispatchAll();
    }

    @Test
    public void getConnectionPolicy() {
        for (int policy :
                List.of(
                        CONNECTION_POLICY_UNKNOWN,
                        CONNECTION_POLICY_FORBIDDEN,
                        CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.getConnectionPolicy(mDevice1)).isEqualTo(policy);
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
                doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
                assertThat(mService.okToConnect(mDevice1)).isFalse();
            }
        }
    }

    @Test
    public void canConnect_whenBonded() {
        int badPolicyValue = 1024;
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());

        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice1)).isFalse();
        }
        for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice1)).isTrue();
        }
    }

    @Test
    public void connectToDevice_whenUuidIsMissing_returnFalse() {
        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void disconnect_whenConnecting_isDisconnectedWithBroadcast() {
        assertThat(mService.connect(mDevice1)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        assertThat(mService.disconnect(mDevice1)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice1, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mDevice1, BluetoothProfile.VOLUME_CONTROL);

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(mService.connect(mDevice1)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(VolumeControlStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void incomingConnecting_whenNoDevice_createStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
    }

    @Test
    public void incomingDisconnect_whenConnectingDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mDevice1);
    }

    @Test
    public void incomingConnect_whenNoDevice_createStateMachine() {
        // Theoretically impossible case
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
    }

    @Test
    public void incomingDisconnect_whenConnectedDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
    }

    @Test
    public void incomingDisconnecting_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice1);
    }

    @Test
    public void incomingDisconnect_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice1);
    }

    @Test
    public void unbondDevice_whenConnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.bondStateChanged(mDevice1, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice1);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenConnected_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.bondStateChanged(mDevice1, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice1);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenDisconnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.bondStateChanged(mDevice1, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice1);
        assertThat(mLooper.nextMessage().what)
                .isEqualTo(VolumeControlStateMachine.MESSAGE_DISCONNECT);
    }

    @Test
    public void unbondDevice_whenDisconnected_removeStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.bondStateChanged(mDevice1, BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices()).doesNotContain(mDevice1);
    }

    @Test
    public void unbondDevice_whenDisconnected_removeDeviceData() {
        int groupVolume = 6;

        // Both devices are in the same group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        // Connect and disconnect first device
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        // Connect and disconnect second device
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice2, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice2, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);

        // Set group volume, check devices volume and group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond first device, group and second device volume should remain
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice1);
        mService.bondStateChanged(mDevice1, BOND_NONE);

        expect.that(mService.getDevices()).doesNotContain(mDevice1);
        expect.that(mService.getDevices()).contains(mDevice2);
        expect.that(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond second device, both devices and group data should be removed
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice2);
        mService.bondStateChanged(mDevice2, BOND_NONE);
        mLooper.dispatchAll();

        expect.that(mService.getDevices()).doesNotContain(mDevice1);
        expect.that(mService.getDevices()).doesNotContain(mDevice2);
        expect.that(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    @Test
    public void disconnect_whenBonded_keepStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
    }

    @Test
    public void disconnect_whenUnbonded_removeStateMachine() {
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        doReturn(BOND_NONE).when(mAdapterService).getBondState(any());
        mService.bondStateChanged(mDevice1, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice1);

        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_DISCONNECTING);

        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mDevice1);
    }

    @Test
    public void disconnect_whenUnbonded_removeDeviceData() {
        int groupVolume = 6;

        // Both devices are in the same group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        // Connect and go to disconnecting on first device
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTING, STATE_CONNECTED);

        // Connect and go to disconnecting on second device
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice2, STATE_DISCONNECTING, STATE_CONNECTED);

        // Set group volume, check devices volume and group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Unbond both devices, data should remain
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice1);
        mService.bondStateChanged(mDevice1, BOND_NONE);
        doReturn(BOND_NONE).when(mAdapterService).getBondState(mDevice2);
        mService.bondStateChanged(mDevice2, BOND_NONE);
        assertThat(mService.getDevices()).contains(mDevice1);
        assertThat(mService.getDevices()).contains(mDevice2);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Disconnect first device, group and second device volume should remain
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_DISCONNECTING);
        expect.that(mService.getDevices()).doesNotContain(mDevice1);
        expect.that(mService.getDevices()).contains(mDevice2);
        expect.that(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
        expect.that(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        // Disconnect second device, both devices and group data should be removed
        generateConnectionMessageFromNative(mDevice2, STATE_DISCONNECTED, STATE_DISCONNECTING);
        expect.that(mService.getDevices()).doesNotContain(mDevice1);
        expect.that(mService.getDevices()).doesNotContain(mDevice2);
        expect.that(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        expect.that(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
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
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();

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
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();

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
    public void volumeCache_groupAndDevices() {
        int groupVolume = 6;
        int devOneVolume = 20;
        int devTwoVolume = 30;

        // Both devices are in the same group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);

        // Send autonomous volume change.
        int autonomousVolume = 10;
        generateVolumeStateChanged(null, GROUP_ID, autonomousVolume, 0, false, true);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(autonomousVolume);

        // Set first device volume
        mService.setDeviceVolume(mDevice1, devOneVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(autonomousVolume);

        // Set second device volume
        mService.setDeviceVolume(mDevice2, devTwoVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(autonomousVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(devOneVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(devTwoVolume);

        // Set group volume again
        mService.setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);
    }

    @Test
    public void volumeCache_multipleDevicesAndStreamTypes() {
        int group1_mediaVolume = 5;
        int group1_callVolume = 10;
        int dev1_g1_mediaVolume = 15;
        int dev2_g1_callVolume = 20;

        BluetoothDevice device1_g2 = getRealDevice(101);
        BluetoothDevice device2_g2 = getRealDevice(102);
        int group2_mediaVolume = 55;
        int group2_callVolume = 60;
        int dev1_g2_mediaVolume = 65;
        int dev2_g2_callVolume = 70;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(device1_g2, GROUP_ID_2, 1, 1);
        generateDeviceAvailableMessageFromNative(device2_g2, GROUP_ID_2, 1, 1);

        // Calculated audio volume will be the same as ble volume
        doReturn(BT_LE_AUDIO_MAX_VOL).when(mAudioManager).getStreamMaxVolume(anyInt());

        // Group 1 active
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());

        // MEDIA (MODE_NORMAL/STREAM_MUSIC)
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group 1 volume during media
        mService.setGroupVolume(GROUP_ID, group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set dev1_g1 volume during media
        mService.setDeviceVolume(mDevice1, dev1_g1_mediaVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Group 2 active, cached volume not changed
        doReturn(GROUP_ID_2).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID_2, true);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group 2 volume during media
        mService.setGroupVolume(GROUP_ID_2, group2_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_mediaVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_mediaVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(group2_mediaVolume);

        // Set dev2_g1 volume during media
        mService.setDeviceVolume(device1_g2, dev1_g2_mediaVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_mediaVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(dev1_g2_mediaVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(group2_mediaVolume);

        // CALL (MODE_IN_CALL/STREAM_VOICE_CALL)
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);

        // Set group 2 volume during call
        mService.setGroupVolume(GROUP_ID_2, group2_callVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(group2_callVolume);

        // Set dev2_g2 volume during call
        mService.setDeviceVolume(device2_g2, dev2_g2_callVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(dev2_g2_callVolume);

        // Group 1 active, updated AF but cached volume not changed
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        inOrderAudio
                .verify(mAudioManager)
                .setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        group1_mediaVolume,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(dev2_g2_callVolume);

        // Set group 1 volume during call
        mService.setGroupVolume(GROUP_ID, group1_callVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_callVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(group1_callVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_callVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(dev2_g2_callVolume);

        // Set dev2_g1 volume during call
        mService.setDeviceVolume(mDevice2, dev2_g1_callVolume, false);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_callVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(group1_callVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(dev2_g1_callVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(group2_callVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(dev2_g2_callVolume);

        // MEDIA (MODE_NORMAL/STREAM_MUSIC)
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_mediaVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(dev1_g2_mediaVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(group2_mediaVolume);

        // Group 2 active, updated AF but cached volume not changed
        doReturn(GROUP_ID_2).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID_2, true);
        inOrderAudio
                .verify(mAudioManager)
                .setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        group2_callVolume,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        inOrderAudio
                .verify(mAudioManager)
                .setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        group2_mediaVolume,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(dev1_g1_mediaVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(group1_mediaVolume);
        assertThat(mService.getGroupVolume(GROUP_ID_2)).isEqualTo(group2_mediaVolume);
        assertThat(mService.getDeviceVolume(device1_g2)).isEqualTo(dev1_g2_mediaVolume);
        assertThat(mService.getDeviceVolume(device2_g2)).isEqualTo(group2_mediaVolume);
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
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);

        // Expected index for STREAM_MUSIC
        int expectedVol =
                (int) Math.round((double) (volumeGroup_1 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        doReturn(GROUP_ID_2).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID_2, true);

        expectedVol =
                (int) Math.round((double) (volumeGroup_2 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedVol), anyInt());
    }

    @Test
    public void muteCache_groupAndDevices() {
        int groupVolume = 6;

        // Both devices are in the same group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();

        // Send autonomous volume change
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);

        // Mute
        mService.muteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isTrue();

        // Make sure the volume is kept even when muted
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(groupVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(groupVolume);

        // Send autonomous unmute
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();

        // Mute first device
        mService.mute(mDevice1);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isFalse();

        // Mute second device
        mService.mute(mDevice2);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isTrue();

        // Unmute group should unmute devices even if group is unmuted
        mService.unmuteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
    }

    @Test
    public void muteCache_multipleDevicesAndStreamTypes() {
        BluetoothDevice device1_g2 = getRealDevice(101);
        BluetoothDevice device2_g2 = getRealDevice(102);

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(device1_g2, GROUP_ID_2, 1, 1);
        generateDeviceAvailableMessageFromNative(device2_g2, GROUP_ID_2, 1, 1);

        // Stream is always unmuted
        doReturn(false).when(mAudioManager).isStreamMute(anyInt());

        // Group 1 active
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio
                .verify(mAudioManager, never())
                .adjustStreamVolume(anyInt(), anyInt(), anyInt());

        // MEDIA (MODE_NORMAL/STREAM_MUSIC)
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isFalse();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Mute group 1 during media
        mService.muteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isFalse();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Unmute dev1_g1 during media
        mService.unmute(mDevice1);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isFalse();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Group 2 active, cached mute not changed
        doReturn(GROUP_ID_2).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID_2, true);
        inOrderAudio
                .verify(mAudioManager, never())
                .adjustStreamVolume(anyInt(), anyInt(), anyInt());
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isFalse();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Mute group 2 during media
        mService.muteGroup(GROUP_ID_2);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isTrue();

        // Unmute dev1_g2 during media
        mService.unmute(device1_g2);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isTrue();

        // CALL (MODE_IN_CALL/STREAM_VOICE_CALL)
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isFalse();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Mute group 2 during call
        mService.muteGroup(GROUP_ID_2);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isTrue();

        // Unmute dev2_g2 during media
        mService.unmute(device2_g2);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Group 1 active, updated AF but cached mute not changed
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        inOrderAudio
                .verify(mAudioManager)
                .adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Unmute group 1 during call to store values
        mService.unmuteGroup(GROUP_ID);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Mute dev1_g1 during call
        mService.mute(mDevice1);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isFalse();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // Mute dev2_g1 during call
        mService.mute(mDevice2);
        assertThat(mService.getGroupMute(GROUP_ID)).isFalse();
        assertThat(mService.getMute(mDevice1)).isTrue();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isTrue();
        assertThat(mService.getMute(device2_g2)).isFalse();

        // MEDIA (MODE_NORMAL/STREAM_MUSIC)
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isTrue();

        // Group 2 active, updated AF but cached mute not changed
        doReturn(GROUP_ID_2).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID_2, true);
        inOrderAudio
                .verify(mAudioManager)
                .adjustStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        inOrderAudio
                .verify(mAudioManager)
                .adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        assertThat(mService.getGroupMute(GROUP_ID)).isTrue();
        assertThat(mService.getMute(mDevice1)).isFalse();
        assertThat(mService.getMute(mDevice2)).isTrue();
        assertThat(mService.getGroupMute(GROUP_ID_2)).isTrue();
        assertThat(mService.getMute(device1_g2)).isFalse();
        assertThat(mService.getMute(device2_g2)).isTrue();
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

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        doReturn(new ArrayList<>()).when(mBassClientService).getSyncedBroadcastSinks();
        // Group is not active unicast and not active primary broadcast, AF will not be notified
        generateVolumeStateChanged(
                mDevice1,
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
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        int expectedAfVol =
                (int) Math.round((double) (volumeDevice * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(expectedAfVol), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(
                mDevice2,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDeviceTwo,
                flags,
                initialMuteState,
                initialAutonomousFlag);

        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(volumeDevice));
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

        // Connect device, first group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        doReturn(new ArrayList<>()).when(mBassClientService).getSyncedBroadcastSinks();

        // Device volume updated with persisted flag, mIgnoreSetVolumeFromAF is set
        generateVolumeStateChanged(
                mDevice1,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDevice,
                persistFlag,
                initialMuteState,
                initialAutonomousFlag);

        // AF not set volume before device disconnected
        generateConnectionMessageFromNative(mDevice1, STATE_DISCONNECTED, STATE_CONNECTED);

        // Connected second device, second group
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID_2, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);

        // Device volume updated with reset flag and no cache, mIgnoreSetVolumeFromAF is cleared
        generateVolumeStateChanged(
                mDevice2,
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

    @Test
    public void connectedDeviceWithUserPersistFlagSet_whileBroadcastActive() {
        int volumeDevice = 56;
        int volumeFromAf = 123;
        int flags = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        // Simulate active broadcast
        doReturn(true).when(mLeAudioService).isBroadcastActive();

        // Device with persisted volume connects.
        // `mIgnoreSetVolumeFromAF=true` should be skipped due to active broadcast.
        generateVolumeStateChanged(
                mDevice1,
                LE_AUDIO_GROUP_ID_INVALID,
                volumeDevice,
                flags,
                initialMuteState,
                initialAutonomousFlag);

        // Volume from AF should NOT be ignored and should be sent to native.
        mService.setGroupVolume(GROUP_ID, volumeFromAf);
        verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(volumeFromAf));
    }

    private void testConnectedDeviceWithResetFlag(
            int resetVolumeDeviceOne, int resetVolumeDeviceTwo) {
        int streamVolume = 30;
        int streamMaxVolume = 100;
        int resetFlag = 0;

        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        doReturn(streamVolume).when(mAudioManager).getStreamVolume(anyInt());
        doReturn(streamMaxVolume).when(mAudioManager).getStreamMaxVolume(anyInt());

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        int expectedAfVol =
                (int) Math.round((double) streamVolume * BT_LE_AUDIO_MAX_VOL / streamMaxVolume);

        // Group is not active, AF will not be notified. Device volume updated to system volume.
        generateVolumeStateChanged(
                mDevice1,
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
        doReturn(GROUP_ID).when(mLeAudioService).getActiveGroupId();
        mService.setGroupActive(GROUP_ID, true);
        inOrderAudio.verify(mAudioManager).setStreamVolume(anyInt(), eq(streamVolume), anyInt());

        // Connect second device and read different volume. Expect it will NOT be set to AF
        // and to another set member, but the existing volume gets applied to it
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(
                mDevice2,
                LE_AUDIO_GROUP_ID_INVALID,
                resetVolumeDeviceTwo,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);

        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(expectedAfVol));
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
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.setGroupVolume(GROUP_ID, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(mDevice2, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(groupVolume));
    }

    /**
     * Test setting volume for a new group member who is discovered after the volume level for a
     * group was already changed and cached.
     */
    @Test
    public void lateDiscoveredGroupMember() {
        int groupVolume = 56;

        // For now only one device is in the group
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);

        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        // Set the group volume
        mService.setGroupVolume(GROUP_ID, groupVolume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // But gets the volume when it becomes the group member
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        mService.handleGroupNodeAdded(GROUP_ID, mDevice2);
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(groupVolume));
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
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

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
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(mDevice2, LE_AUDIO_GROUP_ID_INVALID, volume_2, 0, false, true);

        // Check if new device was muted
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(volume));
        inOrderNative.verify(mNativeInterface).mute(eq(mDevice2));
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
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);

        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(GROUP_ID, volume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(mDevice2, LE_AUDIO_GROUP_ID_INVALID, volume, 0, false, true);

        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        // Check if it was not muted
        inOrderNative.verify(mNativeInterface, never()).mute(any());

        // But gets the volume when it becomes the group member
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        mService.handleGroupNodeAdded(GROUP_ID, mDevice2);
        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(volume));
        inOrderNative.verify(mNativeInterface).mute(eq(mDevice2));
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        assertThat(mService.getDevicesMatchingConnectionStates(null)).isEmpty();
    }

    @Test
    public void setConnectionPolicy() {
        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_UNKNOWN)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.VOLUME_CONTROL, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void volumeOffsetMethods() {
        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 2, 1);

        assertThat(mService.isVolumeOffsetAvailable(mDevice1)).isTrue();

        int numberOfInstances = mService.getNumberOfVolumeOffsetInstances(mDevice1);
        assertThat(numberOfInstances).isEqualTo(2);

        int id = 1;
        int volumeOffset = 100;
        mService.setVolumeOffset(mDevice1, id, volumeOffset);
        verify(mNativeInterface).setExtAudioOutVolumeOffset(mDevice1, id, volumeOffset);
    }

    @Test
    public void getGroupId() {
        int groupVolume = 56;

        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.setDeviceVolume(mDevice1, groupVolume, true);
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        mService.setDeviceVolume(mDevice1, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(GROUP_ID, groupVolume);
    }

    @Test
    public void getGroupDevices() throws Exception {
        int groupVolume = 56;

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        InOrder inOrderCallback = inOrder(callback);

        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback, never()).onDeviceVolumeChanged(any(), anyInt());

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);

        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(groupVolume));

        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, false);
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice2), eq(groupVolume));
        inOrderCallback.verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(groupVolume));
    }

    @Test
    public void setDeviceVolumeMethods() {
        int groupVolume = 56;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        InOrder inOrderNative = inOrder(mNativeInterface);

        mService.setDeviceVolume(mDevice1, groupVolume, true);
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());
        inOrderNative.verify(mNativeInterface).setGroupVolume(GROUP_ID, groupVolume);
        assertThat(mService.getGroupVolume(GROUP_ID)).isEqualTo(groupVolume);

        mService.setDeviceVolume(mDevice1, deviceOneVolume, false);
        inOrderNative.verify(mNativeInterface).setVolume(mDevice1, deviceOneVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(deviceOneVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isNotEqualTo(deviceOneVolume);
        inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        mService.setDeviceVolume(mDevice2, deviceTwoVolume, false);
        inOrderNative.verify(mNativeInterface).setVolume(mDevice2, deviceTwoVolume);
        assertThat(mService.getDeviceVolume(mDevice2)).isEqualTo(deviceTwoVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isNotEqualTo(deviceTwoVolume);
        inOrderNative.verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());
    }

    @Test
    public void testServiceSetDeviceVolumeNoGroupId() throws Exception {
        int deviceVolume = 42;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID_INVALID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.setDeviceVolume(mDevice1, deviceVolume, true);
        verify(mNativeInterface, never()).setVolume(any(), anyInt());
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());

        mService.setDeviceVolume(mDevice1, deviceVolume, false);
        verify(mNativeInterface).setVolume(mDevice1, deviceVolume);
        assertThat(mService.getDeviceVolume(mDevice1)).isEqualTo(deviceVolume);
        verify(mNativeInterface, never()).setGroupVolume(anyInt(), anyInt());
    }

    @Test
    public void testServiceRegisterUnregisterCallback() throws Exception {
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);

            mService.unregisterCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size);
        }
    }

    @Test
    public void registerCallbackWhenDeviceAlreadyConnected() throws Exception {
        int groupVolume = 56;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 2, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        mService.setGroupVolume(GROUP_ID, groupVolume);
        InOrder inOrderNative = inOrder(mNativeInterface);
        inOrderNative.verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));
        inOrderNative.verify(mNativeInterface, never()).setVolume(any(), anyInt());

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        generateVolumeStateChanged(
                mDevice2, LE_AUDIO_GROUP_ID_INVALID, groupVolume, 0, false, true);

        inOrderNative.verify(mNativeInterface).setVolume(eq(mDevice2), eq(groupVolume));

        // Generate events for both devices
        generateDeviceOffsetChangedMessageFromNative(mDevice1, 1, 100);
        generateDeviceLocationChangedMessageFromNative(mDevice1, 1, 1);
        final String testDevice1Desc1 = "testDevice1Desc1";
        generateDeviceDescriptionChangedMessageFromNative(mDevice1, 1, testDevice1Desc1);

        generateDeviceOffsetChangedMessageFromNative(mDevice1, 2, 200);
        generateDeviceLocationChangedMessageFromNative(mDevice1, 2, 2);
        final String testDevice1Desc2 = "testDevice1Desc2";
        generateDeviceDescriptionChangedMessageFromNative(mDevice1, 2, testDevice1Desc2);

        generateDeviceOffsetChangedMessageFromNative(mDevice2, 1, 250);
        generateDeviceLocationChangedMessageFromNative(mDevice2, 1, 3);
        final String testDevice2Desc = "testDevice2Desc";
        generateDeviceDescriptionChangedMessageFromNative(mDevice2, 1, testDevice2Desc);

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        verify(callback).onVolumeOffsetChanged(eq(mDevice1), eq(1), eq(100));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice1), eq(1), eq(1));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice1), eq(1), eq(testDevice1Desc1));

        verify(callback).onVolumeOffsetChanged(eq(mDevice1), eq(2), eq(200));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice1), eq(2), eq(2));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice1), eq(2), eq(testDevice1Desc2));

        verify(callback).onVolumeOffsetChanged(eq(mDevice2), eq(1), eq(250));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice2), eq(1), eq(3));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice2), eq(1), eq(testDevice2Desc));

        generateDeviceOffsetChangedMessageFromNative(mDevice1, 1, 50);
        generateDeviceLocationChangedMessageFromNative(mDevice1, 1, 0);
        final String testDevice1Desc3 = "testDevice1Desc3";
        generateDeviceDescriptionChangedMessageFromNative(mDevice1, 1, testDevice1Desc3);

        verify(callback).onVolumeOffsetChanged(eq(mDevice1), eq(1), eq(50));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice1), eq(1), eq(0));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice1), eq(1), eq(testDevice1Desc3));
    }

    @Test
    public void registerVolumeChangedCallbackWhenDeviceAlreadyConnected() throws Exception {
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
        mService.setDeviceVolume(mDevice1, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice1), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        mService.setDeviceVolume(mDevice2, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice2), eq(deviceTwoVolume));

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(deviceOneVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDevice2), eq(deviceTwoVolume));
    }

    @Test
    public void testNotifyNewRegisteredCallback() throws Exception {
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);
        mService.setDeviceVolume(mDevice1, deviceOneVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice1), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice2)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice2);
        mService.setDeviceVolume(mDevice2, deviceTwoVolume, false);
        verify(mNativeInterface).setVolume(eq(mDevice2), eq(deviceTwoVolume));

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        int size;
        synchronized (mService.mCallbacks) {
            size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        IBluetoothVolumeControlCallback callback_new_client =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder_new_client = Mockito.mock(Binder.class);
        doReturn(binder_new_client).when(callback_new_client).asBinder();

        mService.notifyNewRegisteredCallback(callback_new_client);

        synchronized (mService.mCallbacks) {
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        // This shall be done only once after mService.registerCallback
        verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(deviceOneVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDevice2), eq(deviceTwoVolume));

        // This shall be done only once after mService.updateNewRegisteredCallback
        verify(callback_new_client).onDeviceVolumeChanged(eq(mDevice1), eq(deviceOneVolume));
        verify(callback_new_client).onDeviceVolumeChanged(eq(mDevice2), eq(deviceTwoVolume));
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

        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateDeviceAvailableMessageFromNative(mDevice2, GROUP_ID, 1, 1);

        mService.setDeviceVolume(mDevice1, groupVolume, true);
        verify(mNativeInterface).setGroupVolume(eq(GROUP_ID), eq(groupVolume));

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            assertThat(mService.mCallbacks.getRegisteredCallbackCount()).isEqualTo(size + 1);
        }

        // Send group volume change.
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);

        verify(callback).onDeviceVolumeChanged(eq(mDevice2), eq(groupVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(groupVolume));

        // Send device volume change only for one device
        generateVolumeStateChanged(
                mDevice1, LE_AUDIO_GROUP_ID_INVALID, deviceOneVolume, 0, false, false);

        verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(deviceOneVolume));
        verify(callback, never()).onDeviceVolumeChanged(eq(mDevice2), eq(deviceOneVolume));
    }

    @Test
    @EnableFlags(Flags.FLAG_VCP_NOTIFY_VOLUME_ON_EACH_DEVICE_CONNECTION)
    public void initialAutonomousVolume_notifiesRegisteredCallbacks() throws Exception {
        int initialVolume = 128;
        int flags = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        boolean isMuted = false;
        boolean isAutonomous = true;

        // Register a callback before the device connects
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        mService.registerCallback(callback);

        // Simulate device connecting and providing initial volume
        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);

        // Trigger the autonomous volume change event
        generateVolumeStateChanged(
                mDevice1, LE_AUDIO_GROUP_ID_INVALID, initialVolume, flags, isMuted, isAutonomous);

        // With the flag enabled, the callback should be notified of the initial volume.
        // Without the fix, this verification would fail.
        verify(callback).onDeviceVolumeChanged(eq(mDevice1), eq(initialVolume));
    }

    /** Test Volume Control changed for broadcast primary group. */
    @Test
    public void volumeControlChangedForBroadcastPrimaryGroup() {
        int groupVolume = 30;

        doReturn(groupVolume).when(mAudioManager).getStreamVolume(anyInt());

        generateDeviceAvailableMessageFromNative(mDevice1, GROUP_ID, 1, 1);
        generateConnectionMessageFromNative(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice1)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mDevice1);

        // Make active group as null and broadcast not active
        doReturn(LE_AUDIO_GROUP_ID_INVALID).when(mLeAudioService).getActiveGroupId();
        doReturn(new ArrayList<>()).when(mBassClientService).getSyncedBroadcastSinks();

        // Group is not broadcast primary group, AF will not be notified
        generateVolumeStateChanged(null, GROUP_ID, groupVolume, 0, false, true);
        InOrder inOrderAudio = inOrder(mAudioManager);
        inOrderAudio.verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make active group as null and broadcast active
        doReturn(LE_AUDIO_GROUP_ID_INVALID).when(mLeAudioService).getActiveGroupId();
        doReturn(Arrays.asList(mDevice1, mDevice2))
                .when(mBassClientService)
                .getSyncedBroadcastSinks();
        doReturn(true).when(mLeAudioService).isPrimaryGroup(GROUP_ID);
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
