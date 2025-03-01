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

package com.android.bluetooth.hearingaid;

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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;

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
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HearingAidServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HearingAidNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;

    private final BluetoothDevice mLeftDevice = getTestDevice(43);
    private final BluetoothDevice mRightDevice = getTestDevice(23);
    private final BluetoothDevice mSingleDevice = getTestDevice(13);

    private HearingAidService mService;
    private HearingAidService.BluetoothHearingAidBinder mBinder;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() {
        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        TestUtils.mockGetSystemService(
                mAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mAudioManager);

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.HEARING_AID})
                .when(mAdapterService)
                .getRemoteUuids(any());
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();

        doReturn(true).when(mNativeInterface).connectHearingAid(any());
        doReturn(true).when(mNativeInterface).disconnectHearingAid(any());

        mService = new HearingAidService(mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);
        mBinder = (HearingAidService.BluetoothHearingAidBinder) mService.initBinder();
    }

    @After
    public void tearDown() {
        mService.cleanup();
        assertThat(HearingAidService.getHearingAidService()).isNull();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(UserHandle.ALL),
                        any(),
                        any());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyConnectionStateIntent(device, newState, prevState, true);
    }

    private void verifyConnectionStateIntent(
            BluetoothDevice device, int newState, int prevState, boolean stopAudio) {
        verifyIntentSent(
                hasAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState));

        if (newState == STATE_CONNECTED) {
            // ActiveDeviceManager calls setActiveDevice when connected.
            mService.setActiveDevice(device);
        } else if (prevState == STATE_CONNECTED) {
            if (mService.getConnectedDevices().isEmpty()) {
                mService.removeActiveDevice(stopAudio);
            }
        }
    }

    @Test
    public void getHearingAidService() {
        assertThat(HearingAidService.getHearingAidService()).isEqualTo(mService);
    }

    @Test
    public void getConnectionPolicy() {
        for (int policy :
                List.of(
                        CONNECTION_POLICY_UNKNOWN,
                        CONNECTION_POLICY_FORBIDDEN,
                        CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.getConnectionPolicy(mLeftDevice)).isEqualTo(policy);
        }
    }

    @Test
    public void okToConnect_whenInvalidBonded_returnFalse() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        doReturn(badBondState).when(mAdapterService).getBondState(any());
        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mSingleDevice)).isEqualTo(false);
        }
    }

    @Test
    public void okToConnect_whenNotBonded_returnTrue() {
        // allow connect Due to desync between BondStateMachine and AdapterProperties
        for (int bondState : List.of(BOND_NONE, BOND_BONDING)) {
            doReturn(bondState).when(mAdapterService).getBondState(any());
            for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
                doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
                assertThat(mService.okToConnect(mSingleDevice))
                        .isEqualTo(Flags.donotValidateBondStateFromProfiles());
            }
        }
    }

    @Test
    public void okToConnect_whenBonded() {
        int badPolicyValue = 1024;
        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mSingleDevice)).isEqualTo(false);
        }
        for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mDatabaseManager).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mSingleDevice)).isEqualTo(true);
        }
    }

    @Test
    public void connectToDevice_whenUuidIsMissing_returnFalse() {
        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mService.connect(mLeftDevice)).isFalse();
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        assertThat(mService.connect(mLeftDevice)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);

        mLooper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void connectLeft_whenInAPair_connectBothDevices() {
        getHiSyncIdFromNative();

        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void connectDifferentPair_whenConnected_currentIsDisconnected() {
        getHiSyncIdFromNative();

        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_CONNECTING);

        assertThat(mService.connect(mSingleDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        verifyConnectionStateIntent(mRightDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        verifyConnectionStateIntent(mSingleDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        assertThat(mService.getConnectedDevices()).isEmpty();
        assertThat(mService.getConnectionState(mSingleDevice)).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void disconnect_whenAudioRoutedToHa_audioIsPaused() {
        getHiSyncIdFromNative();

        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);

        assertThat(mService.connect(mRightDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTING);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_CONNECTING);

        assertThat(mService.getConnectedDevices()).containsExactly(mLeftDevice, mRightDevice);

        // Verify the audio is routed to Hearing Aid Profile
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mLeftDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        assertThat(mService.disconnect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);

        assertThat(mService.disconnect(mRightDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mRightDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_DISCONNECTING);

        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        generateConnectionMessageFromNative(mRightDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);

        assertThat(mService.getConnectedDevices()).isEmpty();

        // Verify the audio is not routed to Hearing Aid Profile.
        // Music should be paused (i.e. should not suppress noisy intent)
        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mLeftDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connectionInfo.isSuppressNoisyIntent()).isFalse();
    }

    @Test
    public void outgoingDisconnect_whenAudioRoutedToHa_audioIsNotPaused() {
        getHiSyncIdFromNative();

        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);

        assertThat(mService.getConnectedDevices()).containsExactly(mLeftDevice);

        // Verify the audio is routed to Hearing Aid Profile
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mLeftDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        assertThat(mService.disconnect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        // Note that we call verifyConnectionStateIntent() with (stopAudio == false).
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED, false);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);

        // Verify the audio is not routed to Hearing Aid Profile.
        // Note that music should be not paused (i.e. should suppress noisy intent)
        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mLeftDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connectionInfo.isSuppressNoisyIntent()).isTrue();
    }

    @Test
    public void incomingConnecting_whenNoDevice_createStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void incomingDisconnect_whenConnectingDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void incomingConnect_whenNoDevice_createStateMachine() {
        // Theoretically impossible case
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void incomingDisconnect_whenConnectedDevice_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void incomingDisconnecting_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mLeftDevice);
    }

    @Test
    public void incomingDisconnect_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mLeftDevice);
    }

    @Test
    public void unBondDevice_whenConnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void unBondDevice_whenConnected_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void unBondDevice_whenDisconnecting_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void unBondDevice_whenDisconnected_removeStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getDevices()).doesNotContain(mLeftDevice);
    }

    @Test
    public void disconnect_whenBonded_keepStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);
    }

    @Test
    public void disconnect_whenUnBonded_removeStateMachine() {
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        doReturn(BOND_NONE).when(mAdapterService).getBondState(any());
        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getDevices()).contains(mLeftDevice);

        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);

        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices()).doesNotContain(mLeftDevice);
    }

    @Test
    public void getActiveDevice() {
        getHiSyncIdFromNative();

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(null, mRightDevice);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(mRightDevice, mLeftDevice);

        generateConnectionMessageFromNative(mRightDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(null, mLeftDevice);

        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(null, null);
    }

    @Test
    public void connectNewDevice_whenOtherPairIsActive_newDeviceIsActive() {
        getHiSyncIdFromNative();

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(mRightDevice, mLeftDevice);

        generateConnectionMessageFromNative(mSingleDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getActiveDevices()).containsExactly(null, mSingleDevice);

        assertThat(mService.setActiveDevice(null)).isTrue();
        assertThat(mService.getActiveDevices()).containsExactly(null, null);
    }

    // Verify the correctness during first time connection.
    // Connect to left device -> Get left device hiSyncId -> Connect to right device ->
    // Get right device hiSyncId -> Both devices should be always connected
    @Test
    public void firstTimeConnection_shouldConnectToBothDevices() {
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);

        // Get hiSyncId for left device
        HearingAidStackEvent hiSyncIdEvent =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        hiSyncIdEvent.device = mLeftDevice;
        hiSyncIdEvent.valueInt1 = 0x02;
        hiSyncIdEvent.valueLong2 = 0x0101;
        messageFromNativeAndDispatch(hiSyncIdEvent);

        assertThat(mService.connect(mRightDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTING);
        // Verify the left device is still connected
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_CONNECTING);

        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);

        // Get hiSyncId for right device
        hiSyncIdEvent = new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        hiSyncIdEvent.device = mRightDevice;
        hiSyncIdEvent.valueInt1 = 0x02;
        hiSyncIdEvent.valueLong2 = 0x0101;
        messageFromNativeAndDispatch(hiSyncIdEvent);

        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void getHiSyncId_afterFirstDeviceConnected() {
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_DISCONNECTED);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);

        getHiSyncIdFromNative();

        assertThat(mService.connect(mRightDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTING);
        // Verify the left device is still connected
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_CONNECTING);

        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
    }

    /** Test that the service can update HiSyncId from native message */
    @Test
    public void getHiSyncIdFromNative_addToMap() {
        getHiSyncIdFromNative();
        assertThat(mService.getHiSyncIdMap()).containsKey(mLeftDevice);
        assertThat(mService.getHiSyncIdMap()).containsKey(mRightDevice);
        assertThat(mService.getHiSyncIdMap()).containsKey(mSingleDevice);

        long id = mBinder.getHiSyncId(mLeftDevice, null);
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID);

        id = mBinder.getHiSyncId(mRightDevice, null);
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID);

        id = mBinder.getHiSyncId(mSingleDevice, null);
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID);
    }

    /** Test that the service removes the device from HiSyncIdMap when it's unbonded */
    @Test
    public void deviceUnbonded_removeHiSyncId() {
        getHiSyncIdFromNative();
        mService.bondStateChanged(mLeftDevice, BOND_NONE);
        assertThat(mService.getHiSyncIdMap()).doesNotContainKey(mLeftDevice);
    }

    @Test
    public void serviceBinder_callGetDeviceMode() {
        int mode = mBinder.getDeviceMode(mSingleDevice, null);
        // return unknown value if no device connected
        assertThat(mode).isEqualTo(BluetoothHearingAid.MODE_UNKNOWN);
    }

    @Test
    public void serviceBinder_callGetDeviceSide() {
        int side = mBinder.getDeviceSide(mSingleDevice, null);

        // return unknown value if no device connected
        assertThat(side).isEqualTo(BluetoothHearingAid.SIDE_UNKNOWN);
    }

    @Test
    public void serviceBinder_setConnectionPolicy() {
        when(mDatabaseManager.setProfileConnectionPolicy(
                        mSingleDevice, BluetoothProfile.HEARING_AID, CONNECTION_POLICY_UNKNOWN))
                .thenReturn(true);

        assertThat(mBinder.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_UNKNOWN, null))
                .isTrue();
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mSingleDevice, BluetoothProfile.HEARING_AID, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void serviceBinder_setVolume() {
        mBinder.setVolume(0, null);
        verify(mNativeInterface).setVolume(0);
    }

    @Test
    public void dump_doesNotCrash() {
        mService.connect(mSingleDevice);
        mLooper.dispatchAll();

        mService.dump(new StringBuilder());
    }

    private void messageFromNativeAndDispatch(HearingAidStackEvent event) {
        mService.messageFromNative(event);
        mLooper.dispatchAll();
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        HearingAidStackEvent stackEvent =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        messageFromNativeAndDispatch(stackEvent);
        verifyConnectionStateIntent(device, newConnectionState, oldConnectionState);
        assertThat(mService.getConnectionState(device)).isEqualTo(newConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        HearingAidStackEvent stackEvent =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        messageFromNativeAndDispatch(stackEvent);
        mInOrder.verify(mAdapterService, never())
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(
                                hasAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED)),
                        eq(UserHandle.ALL),
                        any(),
                        any());
    }

    // Emulate hiSyncId map update from native stack
    private void getHiSyncIdFromNative() {
        HearingAidStackEvent event =
                new HearingAidStackEvent(HearingAidStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = mLeftDevice;
        event.valueInt1 = 0x02;
        event.valueLong2 = 0x0101;
        messageFromNativeAndDispatch(event);

        event.device = mRightDevice;
        event.valueInt1 = 0x03;
        messageFromNativeAndDispatch(event);

        event.device = mSingleDevice;
        event.valueInt1 = 0x00;
        event.valueLong2 = 0x0102;
        messageFromNativeAndDispatch(event);
    }
}
