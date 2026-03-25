/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.csip;

import static android.bluetooth.BluetoothCsipSetCoordinator.ACTION_CSIS_DEVICE_AVAILABLE;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothCsipSetCoordinator;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.Intent;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.tests.bluetooth.MockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test cases for {@link CsipSetCoordinatorService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class CsipSetCoordinatorServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private CsipSetCoordinatorNativeInterface mNativeInterface;
    @Mock private IBluetoothCsipSetCoordinatorLockCallback mCsipSetCoordinatorLockCallback;

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mDevice3 = getTestDevice(2);

    private CsipSetCoordinatorService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;
    private CsipSetCoordinatorNativeInterface mNativeCallback;

    @Before
    public void setUp() throws Exception {
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.COORDINATED_SET})
                .when(mAdapterService)
                .getRemoteUuids(any());

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());
        doReturn(true).when(mNativeInterface).connect(any());
        doReturn(true).when(mNativeInterface).disconnect(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.COORDINATED_SET})
                .when(mAdapterService)
                .getRemoteUuids(any());

        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService =
                new CsipSetCoordinatorService(
                        mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);
        mNativeCallback = new CsipSetCoordinatorNativeInterface(mAdapterService, mService);

        mockGetRemoteDevice(mAdapterService, mDevice1, mDevice2, mDevice3);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
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
    public void removeFoundSetMember_noIntentAfterBondedAndConnectedWithOtherDevice() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();
        UUID uuid = new UUID(uuidMsb, uuidLsb);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_BONDED);
        // First intent - ACTION_CSIS_DEVICE_AVAILABLE with device1
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice1),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_BONDING);

        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);
        // Second intent - ACTION_CSIS_SET_MEMBER_AVAILABLE with device2
        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));

        // Remove bond for first device and the set member
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_NONE);
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_NONE);
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_DISCONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);

        // Bonded with another device
        mService.bondStateChanged(mDevice3, BluetoothDevice.BOND_BONDED);
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice3), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        // Third intent - ACTION_CSIS_DEVICE_AVAILABLE with device3
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice3),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice3), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice3, STATE_CONNECTING, STATE_CONNECTED);
        // No extra intent is sent after device3 connected, still 3 broadcasts
        verify(mAdapterService, times(3)).sendOrderedBroadcast(any(), any());
    }

    /** Test that call to groupLockSet method calls corresponding native interface method */
    @Test
    public void testGroupLockSetNative() throws RemoteException {
        int group_id = 0x01;
        int group_size = 0x01;
        long uuidLsb = 0x01;
        long uuidMsb = 0x01;

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 1, uuidMsb, uuidLsb);
        assertThat(mService.isGroupLocked(group_id)).isFalse();

        UUID lock_uuid = mService.lockGroup(group_id, mCsipSetCoordinatorLockCallback);
        assertThat(lock_uuid).isNotNull();
        verify(mNativeInterface).groupLockSet(eq(group_id), eq(true));
        assertThat(mService.isGroupLocked(group_id)).isTrue();

        mNativeCallback.onGroupLockChanged(
                group_id, true, IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_SUCCESS);

        verify(mCsipSetCoordinatorLockCallback)
                .onGroupLockSet(group_id, BluetoothStatusCodes.SUCCESS, true);

        mService.unlockGroup(lock_uuid);
        verify(mNativeInterface).groupLockSet(eq(group_id), eq(false));

        mNativeCallback.onGroupLockChanged(
                group_id, false, IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_SUCCESS);
        assertThat(mService.isGroupLocked(group_id)).isFalse();

        verify(mCsipSetCoordinatorLockCallback)
                .onGroupLockSet(group_id, BluetoothStatusCodes.SUCCESS, false);
    }

    /** Test that call to groupLockSet method calls corresponding native interface method */
    @Test
    public void testGroupExclusiveLockSet() throws RemoteException {
        int group_id = 0x01;
        int group_size = 0x01;
        long uuidLsb = 0x01;
        long uuidMsb = 0x01;

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 1, uuidMsb, uuidLsb);
        assertThat(mService.isGroupLocked(group_id)).isFalse();

        UUID lock_uuid = mService.lockGroup(group_id, mCsipSetCoordinatorLockCallback);
        verify(mNativeInterface).groupLockSet(eq(group_id), eq(true));
        assertThat(lock_uuid).isNotNull();
        assertThat(mService.isGroupLocked(group_id)).isTrue();

        lock_uuid = mService.lockGroup(group_id, mCsipSetCoordinatorLockCallback);
        verify(mNativeInterface).groupLockSet(eq(group_id), eq(true));

        verify(mCsipSetCoordinatorLockCallback)
                .onGroupLockSet(
                        group_id, BluetoothStatusCodes.ERROR_CSIP_GROUP_LOCKED_BY_OTHER, true);
        assertThat(lock_uuid).isNull();
    }

    @Test
    public void connectToDevice_whenUuidIsMissing_returnFalse() {
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(mService.connect(mDevice1)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(CsipSetCoordinatorStateMachine.sConnectTimeoutMs);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void deviceAvailable_withDifferentRank_areOrdered() {
        int group_id = 0x01;
        int group_size = 0x03;
        long uuidLsb = 0x01;
        long uuidMsb = 0x01;
        UUID uuid = new UUID(uuidMsb, uuidLsb);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x02, uuidMsb, uuidLsb);

        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice1),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        // Another device with the highest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x01, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        // Yet another device with the lowest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice3), group_id, group_size, 0x03, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice3),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        assertThat(mService.getGroupDevicesOrdered(group_id))
                .containsExactly(mDevice2, mDevice1, mDevice3)
                .inOrder();
    }

    /** Test that native callback generates proper intent after group connected. */
    @Test
    public void nativeCallback_afterGroupConnected_generateIntent() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x02, uuidMsb, uuidLsb);

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);

        // Comes from state machine
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);

        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);

        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));
    }

    /** Test that native callback generates proper intent before group connected. */
    @Test
    public void testStackEventSetMemberAvailableBeforeGroupConnected() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);
        // verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTED);

        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);

        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());

        // Comes from state machine
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);

        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FIX_REPORTING_CSIS_MEMBERS)
    public void testTrackingAvailableSetMembersForDevicesUsingMultipleRpaAdvertisingInstances() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();

        /* Scenario:
         * 1. Set contains 2 devices but Device 2 is using mulltple advertising instances.which results in Device 3 being visible.
         * 2. Bond Device 1 and connect to it
         * 3. Simulate CSIS founds 2 more set members even this is single device (multiple RPA advertising instances)
         * 4. Bond and connect to Device 2
         * 5. Make sure mFoundSetMemberToGroupId does not contain any more members to bond.
         **/

        doReturn(CONNECTION_POLICY_ALLOWED).when(mLeAudioService).getConnectionPolicy(any());

        // Bond and connect Device 1.
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_BONDED);
        mService.connect(mDevice1);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x01, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTED, STATE_CONNECTING);

        /* Remote device has 2 advertising instances for the second set member */
        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);
        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));

        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice3), group_id);
        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice3),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));

        // Bond and connect Device 2.
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_BONDED);
        mService.connect(mDevice2);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice2, STATE_CONNECTING, STATE_DISCONNECTED);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice2), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice2, STATE_CONNECTING, STATE_CONNECTED);

        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice2, STATE_CONNECTED, STATE_CONNECTING);

        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());
        assertThat(mService.mFoundSetMemberToGroupId.isEmpty()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FIX_REPORTING_CSIS_MEMBERS)
    public void testTrackingAvailableSetMembersForDevicesUsingMultipleCsisGoups() {
        // Common size
        int group_size = 0x02;
        // Group 1 with UUID 1
        int group_id = 0x01;
        long uuidLsb_1 = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb_1 = BluetoothUuid.CAP.getUuid().getMostSignificantBits();
        // Group 2 with UUID 2
        int group_id_2 = 0x02;
        long uuidLsb_2 = 0x01;
        long uuidMsb_2 = 0x02;

        /* Scenario
         * 1. Device 1 and Device 2 are a set members for UUID 1
         * 2. Device 2 and Device 3 are a set members for UUID 2
         * 3. Bond and Connect device 1
         * 4. Symulate Device 2 is found
         * 5. Bond and Connect with Device 2
         * 6. Make sure device 3 is Broadcasted as available set member.
         * 7. Bond and connect Device 3.
         * 8. Verify state of mFoundSetMemberToGroupId and mGroupIdToConnectedDevices
         */
        doReturn(CONNECTION_POLICY_ALLOWED).when(mLeAudioService).getConnectionPolicy(any());

        // Bond and connect Device 1
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_BONDED);

        mService.connect(mDevice1);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        // Device 1 supports UUID 1
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x01, uuidMsb_1, uuidLsb_1);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        /* Remote device has 2 advertising instances for the second set member */
        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);
        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTED, STATE_CONNECTING);

        // Expect all the available set members are notified
        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));

        // Bond and connect Device 2
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_BONDED);
        mService.connect(mDevice2);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice2, STATE_CONNECTING, STATE_DISCONNECTED);

        // Device 2 is part of two groups.
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x02, uuidMsb_1, uuidLsb_1);
        mLooper.dispatchAll();
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id_2, group_size, 0x01, uuidMsb_2, uuidLsb_2);
        mLooper.dispatchAll();
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        // Device 3 found as a member of group 2
        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice3), group_id_2);
        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice2), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice2, STATE_CONNECTING, STATE_CONNECTED);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice2, STATE_CONNECTED, STATE_CONNECTING);

        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice3),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id_2));

        // Bond and connect Device 3
        mService.bondStateChanged(mDevice3, BluetoothDevice.BOND_BONDED);
        mService.connect(mDevice3);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice3, STATE_CONNECTING, STATE_DISCONNECTED);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice3), group_id_2, group_size, 0x02, uuidMsb_2, uuidLsb_2);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice3), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice3, STATE_CONNECTING, STATE_CONNECTED);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice3, STATE_CONNECTED, STATE_CONNECTING);

        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());

        assertThat(mService.mFoundSetMemberToGroupId.isEmpty()).isTrue();
        assertThat(mService.mGroupIdToConnectedDevices.get(group_id_2).size())
                .isEqualTo(group_size);
        assertThat(mService.mGroupIdToConnectedDevices.get(group_id).size()).isEqualTo(group_size);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FIX_REPORTING_CSIS_MEMBERS)
    public void testTrackingAvailableSetMembersAfterBondRemoval() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();

        /* Scenario:
         * 1. Set contains 2 devices but Device 2 is using mulltple advertising instances.which results in Device 3 being visible.
         * 2. Bond Device 1 and connect to it
         * 3. Bond and connect to Device 2
         * 4. Disconnect and remove Device 1
         * 5. Simulate Native CSIS reporting device 1 as a set member available to pair (note: native shall not do it anymore)
         * 6. Disconnect and remove Device 2.
         * 7. Make sure that mFoundSetMemberToGroupId does not contain any more members to bond i.e. Device 1.
         **/
        doReturn(CONNECTION_POLICY_ALLOWED).when(mLeAudioService).getConnectionPolicy(any());

        // Bond and Connect device 1
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_BONDED);

        mService.connect(mDevice1);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x01, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTED, STATE_CONNECTING);

        // Bond and Connect device 2
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_BONDED);
        mService.connect(mDevice2);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice2, STATE_CONNECTING, STATE_DISCONNECTED);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice2), STATE_CONNECTED);
        mService.connectionStateChanged(mDevice2, STATE_CONNECTING, STATE_CONNECTED);

        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice2, STATE_CONNECTED, STATE_CONNECTING);

        // Forget device set
        // Removing device 1
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice1), STATE_DISCONNECTED);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTED, STATE_DISCONNECTED);

        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice1, STATE_DISCONNECTED, STATE_CONNECTED);
        mService.bondStateChanged(mDevice1, BluetoothDevice.BOND_NONE);

        // Inject incorrect SET MEMBER AVAILABLE event for device 1
        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice1), group_id);
        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice1),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));

        // Removing device 2
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice2), STATE_DISCONNECTED);
        mService.connectionStateChanged(mDevice2, STATE_CONNECTED, STATE_DISCONNECTED);

        mLooper.dispatchAll();
        verifyConnectionStateIntent(mDevice2, STATE_DISCONNECTED, STATE_CONNECTED);
        mService.bondStateChanged(mDevice2, BluetoothDevice.BOND_NONE);

        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());
        assertThat(mService.mFoundSetMemberToGroupId.isEmpty()).isTrue();
    }

    /**
     * Test that we make CSIP FORBIDDEN after all set members are paired if the LE Audio connection
     * policy is FORBIDDEN.
     */
    @Test
    public void testDisableCsipAfterConnectingIfLeAudioDisabled() {
        int group_id = 0x01;
        int group_size = 0x02;
        long uuidLsb = BluetoothUuid.CAP.getUuid().getLeastSignificantBits();
        long uuidMsb = BluetoothUuid.CAP.getUuid().getMostSignificantBits();

        doReturn(CONNECTION_POLICY_FORBIDDEN).when(mLeAudioService).getConnectionPolicy(any());

        // Make first set device available and connected
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice1), group_id, group_size, 0x02, uuidMsb, uuidLsb);
        mService.connectionStateChanged(mDevice1, STATE_CONNECTING, STATE_CONNECTED);

        // Another device with the highest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x01, uuidMsb, uuidLsb);

        // When LEA is FORBIDDEN, verify we don't disable CSIP until all set devices are available
        verify(mAdapterService, never())
                .setProfileConnectionPolicy(
                        mDevice1,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService, never())
                .setProfileConnectionPolicy(
                        mDevice2,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);

        // Mark the second device as connected
        mService.connectionStateChanged(mDevice2, STATE_CONNECTING, STATE_CONNECTED);

        // When LEA is FORBIDDEN, verify we disable CSIP once all set devices are available
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice2,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
    }

    @Test
    public void testDump_doesNotCrash() {
        // add state machines for testing dump()
        mService.connect(mDevice1);
        mService.dump(new StringBuilder());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, prevState));
        assertThat(mService.getConnectionState(device)).isEqualTo(newState);
    }

    /** Helper function to get byte array for a device address */
    private static byte[] getByteAddress(BluetoothDevice device) {
        return Util.getBytesFromAddress(device.getAddress());
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    @SafeVarargs
    private void verifyOrderedIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendOrderedBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }
}
