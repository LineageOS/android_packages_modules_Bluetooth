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

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
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
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.List;
import java.util.UUID;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class CsipSetCoordinatorServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Spy private ServiceFactory mServiceFactory = new ServiceFactory();
    @Mock private AdapterService mAdapterService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private CsipSetCoordinatorNativeInterface mNativeInterface;
    @Mock private IBluetoothCsipSetCoordinatorLockCallback mCsipSetCoordinatorLockCallback;

    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mDevice3 = getTestDevice(2);

    private CsipSetCoordinatorService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;
    private final CsipSetCoordinatorNativeInterface mNativeCallback =
            new CsipSetCoordinatorNativeInterface();

    @Before
    public void setUp() throws Exception {
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.COORDINATED_SET})
                .when(mAdapterService)
                .getRemoteUuids(any());

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());
        doReturn(true).when(mNativeInterface).connect(any());
        doReturn(true).when(mNativeInterface).disconnect(any());
        doReturn(new ParcelUuid[] {BluetoothUuid.COORDINATED_SET})
                .when(mAdapterService)
                .getRemoteUuids(any());

        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService =
                new CsipSetCoordinatorService(
                        mAdapterService, mLooper.getLooper(), mNativeInterface, mServiceFactory);
        mService.setAvailable(true);

    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        assertThat(CsipSetCoordinatorService.getCsipSetCoordinatorService()).isNull();
    }

    @Test
    public void getService() {
        assertThat(CsipSetCoordinatorService.getCsipSetCoordinatorService()).isEqualTo(mService);
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

    /** Test that call to groupLockSet method calls corresponding native interface method */
    @Test
    public void testGroupLockSetNative() throws RemoteException {
        int group_id = 0x01;
        int group_size = 0x01;
        long uuidLsb = 0x01;
        long uuidMsb = 0x01;

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice), group_id, group_size, 1, uuidLsb, uuidMsb);
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
                getByteAddress(mDevice), group_id, group_size, 1, uuidLsb, uuidMsb);
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

        assertThat(mService.connect(mDevice)).isFalse();
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        assertThat(mService.connect(mDevice)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(mService.connect(mDevice)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(CsipSetCoordinatorStateMachine.sConnectTimeoutMs);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void deviceAvailable_withDifferentRank_areOrdered() {
        int group_id = 0x01;
        int group_size = 0x03;
        long uuidLsb = 0x01;
        long uuidMsb = 0x01;
        UUID uuid = new UUID(uuidMsb, uuidLsb);

        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice), group_id, group_size, 0x02, uuidLsb, uuidMsb);

        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        // Another device with the highest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x01, uuidLsb, uuidMsb);
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        // Yet another device with the lowest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice3), group_id, group_size, 0x03, uuidLsb, uuidMsb);
        verifyOrderedIntentSent(
                hasAction(ACTION_CSIS_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice3),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, group_size),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, uuid));

        assertThat(mService.getGroupDevicesOrdered(group_id))
                .containsExactly(mDevice2, mDevice, mDevice3)
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
                getByteAddress(mDevice), group_id, group_size, 0x02, uuidLsb, uuidMsb);

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice), STATE_CONNECTED);

        // Comes from state machine
        mService.connectionStateChanged(mDevice, STATE_CONNECTING, STATE_CONNECTED);

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
                getByteAddress(mDevice), group_id, group_size, 0x02, uuidLsb, uuidMsb);
        verifyOrderedIntentSent(hasAction(ACTION_CSIS_DEVICE_AVAILABLE));

        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice), STATE_CONNECTED);
        // verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTED);

        mNativeCallback.onSetMemberAvailable(getByteAddress(mDevice2), group_id);

        mInOrder.verify(mAdapterService, never()).sendOrderedBroadcast(any(), any());

        // Comes from state machine
        mService.connectionStateChanged(mDevice, STATE_CONNECTING, STATE_CONNECTED);

        verifyOrderedIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice2),
                hasExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, group_id));
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
                getByteAddress(mDevice), group_id, group_size, 0x02, uuidLsb, uuidMsb);
        mService.connectionStateChanged(mDevice, STATE_CONNECTING, STATE_CONNECTED);

        // Another device with the highest rank
        mNativeCallback.onDeviceAvailable(
                getByteAddress(mDevice2), group_id, group_size, 0x01, uuidLsb, uuidMsb);

        // When LEA is FORBIDDEN, verify we don't disable CSIP until all set devices are available
        verify(mDatabaseManager, never())
                .setProfileConnectionPolicy(
                        mDevice,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
        verify(mDatabaseManager, never())
                .setProfileConnectionPolicy(
                        mDevice2,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);

        // Mark the second device as connected
        mService.connectionStateChanged(mDevice2, STATE_CONNECTING, STATE_CONNECTED);

        // When LEA is FORBIDDEN, verify we disable CSIP once all set devices are available
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice2,
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        CONNECTION_POLICY_FORBIDDEN);
    }

    @Test
    public void testDump_doesNotCrash() {
        // add state machines for testing dump()
        mService.connect(mDevice);

        mService.dump(new StringBuilder());
    }

    private void verifyConnectionStateIntent(int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, prevState));
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(newState);
    }

    /** Helper function to get byte array for a device address */
    private static byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
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
