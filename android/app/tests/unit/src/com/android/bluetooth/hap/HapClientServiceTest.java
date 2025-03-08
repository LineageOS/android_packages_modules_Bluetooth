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

package com.android.bluetooth.hap;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE;
import static android.bluetooth.BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
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

import static org.hamcrest.core.AllOf.allOf;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHapClientCallback;
import android.content.Intent;
import android.os.Binder;
import android.os.ParcelUuid;
import android.os.RemoteException;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HapClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HapClientNativeInterface mNativeInterface;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private CsipSetCoordinatorService mCsipService;
    @Mock private IBluetoothHapClientCallback mFrameworkCallback;
    @Mock private Binder mBinder;

    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mDevice3 = getTestDevice(2);

    private HapClientService mService;
    private HapClientNativeCallback mNativeCallback;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Before
    public void setUp() {
        doReturn(mDevice).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice)));
        doReturn(mDevice2).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice2)));
        doReturn(mDevice3).when(mAdapterService).getDeviceFromByte(eq(getByteAddress(mDevice3)));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mDatabaseManager)
                .getProfileConnectionPolicy(any(), anyInt());

        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();

        doReturn(mBinder).when(mFrameworkCallback).asBinder();

        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        /* Prepare CAS groups */
        doReturn(List.of(0x02, 0x03)).when(mCsipService).getAllGroupIds(BluetoothUuid.CAP);

        int groupId2 = 0x02;
        Map groups2 =
                Map.of(groupId2, ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        int groupId3 = 0x03;
        Map groups3 =
                Map.of(groupId3, ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        doReturn(List.of(mDevice, mDevice2)).when(mCsipService).getGroupDevicesOrdered(groupId2);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice2);

        doReturn(List.of(mDevice3)).when(mCsipService).getGroupDevicesOrdered(groupId3);
        doReturn(groups3).when(mCsipService).getGroupUuidMapByDevice(mDevice3);

        doReturn(List.of(mDevice)).when(mCsipService).getGroupDevicesOrdered(0x01);

        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService = new HapClientService(mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);
        mNativeCallback = new HapClientNativeCallback(mAdapterService, mService);

        mService.mFactory = mServiceFactory;
        synchronized (mService.mCallbacks) {
            mService.mCallbacks.register(mFrameworkCallback);
        }
    }

    @After
    public void tearDown() {
        synchronized (mService.mCallbacks) {
            mService.mCallbacks.unregister(mFrameworkCallback);
        }

        mService.cleanup();
        assertThat(HapClientService.getHapClientService()).isNull();
    }

    @Test
    public void getHapService() {
        assertThat(HapClientService.getHapClientService()).isEqualTo(mService);
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

        verifyConnectionStateIntent(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(HapClientStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void connectTwoDevices() {
        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);

        assertThat(mService.getConnectedDevices()).containsExactly(mDevice, mDevice2);
    }

    @Test
    public void getActivePresetIndex_whenNoConnected_isUnavailable() {
        assertThat(mService.getActivePresetIndex(mDevice)).isEqualTo(PRESET_INDEX_UNAVAILABLE);
    }

    @Test
    public void testGetHapGroupCoordinatedOps() {
        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);
        testConnectingDevice(mDevice3);

        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), 0x04);
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), 0x04);

        /* This one has no coordinated operation support but is part of a coordinated set with
         * mDevice, which supports it, thus mDevice will forward the operation to mDevice2.
         * This device should also be recognized as grouped one.
         */
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice2), 0);

        /* Two devices support coordinated operations thus shall report valid group ID */
        assertThat(mService.getHapGroup(mDevice)).isEqualTo(2);
        assertThat(mService.getHapGroup(mDevice3)).isEqualTo(3);

        /* Third one has no coordinated operations support but is part of the group */
        int hapGroup = mService.getHapGroup(mDevice2);
        assertThat(hapGroup).isEqualTo(2);
    }

    @Test
    public void testSelectPresetNative() throws RemoteException {
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.selectPreset(mDevice, 0x00);
        verify(mNativeInterface, never()).selectActivePreset(eq(mDevice), eq(0x00));
        verify(mFrameworkCallback)
                .onPresetSelectionFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPreset(mDevice, 0x01);
        verify(mNativeInterface).selectActivePreset(eq(mDevice), eq(0x01));
    }

    @Test
    public void testGroupSelectActivePresetNative() throws RemoteException {
        testConnectingDevice(mDevice3);

        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.selectPresetForGroup(0x03, 0x00);
        verify(mFrameworkCallback)
                .onPresetSelectionForGroupFailed(
                        eq(0x03), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPresetForGroup(0x03, 0x01);
        verify(mNativeInterface).groupSelectActivePreset(eq(0x03), eq(0x01));
    }

    @Test
    public void testSwitchToNextPreset() {
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.switchToNextPreset(mDevice);
        verify(mNativeInterface).nextActivePreset(eq(mDevice));
    }

    @Test
    public void testSwitchToNextPresetForGroup() {
        testConnectingDevice(mDevice3);
        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.switchToNextPresetForGroup(0x03);
        verify(mNativeInterface).groupNextActivePreset(eq(0x03));
    }

    @Test
    public void testSwitchToPreviousPreset() {
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.switchToPreviousPreset(mDevice);
        verify(mNativeInterface).previousActivePreset(eq(mDevice));
    }

    @Test
    public void testSwitchToPreviousPresetForGroup() {
        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);

        int flags = 0x01;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), flags);

        // Verify Native Interface call
        mService.switchToPreviousPresetForGroup(0x02);
        verify(mNativeInterface).groupPreviousActivePreset(eq(0x02));
    }

    @Test
    public void testGetActivePresetIndex() throws RemoteException {
        testConnectingDevice(mDevice);
        testOnPresetSelected(mDevice, 0x01);

        assertThat(mService.getActivePresetIndex(mDevice)).isEqualTo(0x01);
    }

    @Test
    public void testGetPresetInfoAndActivePresetInfo() throws RemoteException {
        testConnectingDevice(mDevice2);

        // Check when active preset is not known yet
        List<BluetoothHapPresetInfo> presetList = mService.getAllPresetInfo(mDevice2);

        BluetoothHapPresetInfo presetInfo = mService.getPresetInfo(mDevice2, 0x01);
        assertThat(presetList).contains(presetInfo);
        assertThat(presetInfo.getIndex()).isEqualTo(0x01);

        assertThat(mService.getActivePresetIndex(mDevice2)).isEqualTo(PRESET_INDEX_UNAVAILABLE);
        assertThat(mService.getActivePresetInfo(mDevice2)).isNull();

        // Inject active preset change event
        testOnPresetSelected(mDevice2, 0x01);

        // Check when active preset is known
        assertThat(mService.getActivePresetIndex(mDevice2)).isEqualTo(0x01);
        BluetoothHapPresetInfo info = mService.getActivePresetInfo(mDevice2);
        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("One");
    }

    @Test
    public void testSetPresetNameNative() throws RemoteException {
        testConnectingDevice(mDevice);

        mService.setPresetName(mDevice, 0x00, "ExamplePresetName");
        verify(mNativeInterface, never())
                .setPresetName(eq(mDevice), eq(0x00), eq("ExamplePresetName"));
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        // Verify Native Interface call
        mService.setPresetName(mDevice, 0x01, "ExamplePresetName");
        verify(mNativeInterface).setPresetName(eq(mDevice), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void testSetPresetNameForGroup() throws RemoteException {
        int test_group = 0x02;

        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);

        int flags = 0x21;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), flags);

        mService.setPresetNameForGroup(test_group, 0x00, "ExamplePresetName");
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(test_group), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        mService.setPresetNameForGroup(-1, 0x01, "ExamplePresetName");
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(-1), eq(BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID));

        // Verify Native Interface call
        mService.setPresetNameForGroup(test_group, 0x01, "ExamplePresetName");
        verify(mNativeInterface)
                .groupSetPresetName(eq(test_group), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void testStackEventDeviceAvailable() {
        int features = 0x03;

        mNativeCallback.onDeviceAvailable(getByteAddress(mDevice), features);

        verify(mAdapterService)
                .sendBroadcastWithMultiplePermissions(
                        argThat(
                                allOf(
                                        hasAction(ACTION_HAP_DEVICE_AVAILABLE),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                                        hasExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, features))),
                        any());
    }

    @Test
    public void testStackEventOnPresetSelected() throws RemoteException {
        int presetIndex = 0x01;

        mNativeCallback.onActivePresetSelected(getByteAddress(mDevice), presetIndex);

        verify(mFrameworkCallback)
                .onPresetSelected(
                        eq(mDevice),
                        eq(presetIndex),
                        eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        assertThat(mService.getActivePresetIndex(mDevice)).isEqualTo(presetIndex);
    }

    @Test
    public void testStackEventOnActivePresetSelectError() throws RemoteException {
        mNativeCallback.onActivePresetSelectError(getByteAddress(mDevice), 0x05);

        verify(mFrameworkCallback)
                .onPresetSelectionFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
    }

    @Test
    public void testStackEventOnPresetInfo() throws RemoteException {
        testConnectingDevice(mDevice);

        int infoReason = HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_UPDATE;
        BluetoothHapPresetInfo[] info = {
            new BluetoothHapPresetInfo.Builder(0x01, "OneChangedToUnavailable")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };

        mNativeCallback.onPresetInfo(getByteAddress(mDevice), infoReason, info);

        ArgumentCaptor<List<BluetoothHapPresetInfo>> presetsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mFrameworkCallback)
                .onPresetInfoChanged(
                        eq(mDevice),
                        presetsCaptor.capture(),
                        eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));

        List<BluetoothHapPresetInfo> presets = presetsCaptor.getValue();
        assertThat(presets).hasSize(3);

        Optional<BluetoothHapPresetInfo> preset =
                presetsCaptor.getValue().stream().filter(p -> 0x01 == p.getIndex()).findFirst();
        assertThat(preset.get().getName()).isEqualTo("OneChangedToUnavailable");
        assertThat(preset.get().isAvailable()).isFalse();
        ;
        assertThat(preset.get().isWritable()).isTrue();
    }

    @Test
    public void testStackEventOnPresetNameSetError() throws RemoteException {
        /* Not a valid name length */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice),
                0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG));

        /* Invalid preset index provided */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        /* Not allowed on this particular preset */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        verify(mFrameworkCallback, times(2))
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onPresetNameSetError(
                getByteAddress(mDevice), 0x01, HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(
                        eq(mDevice), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED));
    }

    @Test
    public void testStackEventOnGroupPresetNameSetError() throws RemoteException {
        int groupId = 0x01;
        int presetIndex = 0x04;
        /* Not a valid name length */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG));

        /* Invalid preset index provided */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));

        /* Not allowed on this particular preset */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        verify(mFrameworkCallback, times(2))
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));

        /* Not allowed on all presets - for example if peer is missing optional CP characteristic */
        mNativeCallback.onGroupPresetNameSetError(
                groupId, presetIndex, HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(
                        eq(groupId), eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED));
    }

    @Test
    public void getDevicesMatchingConnectionStates_whenNull_isEmpty() {
        assertThat(mService.getDevicesMatchingConnectionStates(null)).isEmpty();
    }

    @Test
    public void setConnectionPolicy() {
        assertThat(mService.setConnectionPolicy(mDevice, CONNECTION_POLICY_UNKNOWN)).isTrue();
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void getFeatures() {
        assertThat(mService.getFeatures(mDevice)).isEqualTo(0x00);
    }

    @Test
    public void registerUnregisterCallback() {
        IBluetoothHapClientCallback callback = Mockito.mock(IBluetoothHapClientCallback.class);
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
    public void dumpDoesNotCrash() {
        // Add state machine for testing dump()
        mService.connect(mDevice);
        mLooper.dispatchAll();

        mService.dump(new StringBuilder());
    }

    /** Helper function to test device connecting */
    private void testConnectingDevice(BluetoothDevice device) {
        assertThat(mService.connect(device)).isTrue();
        mLooper.dispatchAll();
        verifyConnectingDevice(device);
    }

    /** Helper function to test device connecting */
    private void verifyConnectingDevice(BluetoothDevice device) {
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(device, STATE_CONNECTED, STATE_CONNECTING);

        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        evt.device = device;
        evt.valueInt1 = 0x01; // features
        mService.messageFromNative(evt);
        mLooper.dispatchAll();

        verifyIntentSent(
                hasAction(ACTION_HAP_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, 0x01));

        evt = new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES);
        evt.device = device;
        evt.valueInt1 = 0x01; // features
        mService.messageFromNative(evt);
        mLooper.dispatchAll();

        // Inject some initial presets
        List<BluetoothHapPresetInfo> presets =
                new ArrayList<>(
                        Arrays.asList(
                                new BluetoothHapPresetInfo.Builder(0x01, "One")
                                        .setAvailable(true)
                                        .setWritable(false)
                                        .build(),
                                new BluetoothHapPresetInfo.Builder(0x02, "Two")
                                        .setAvailable(true)
                                        .setWritable(true)
                                        .build(),
                                new BluetoothHapPresetInfo.Builder(0x03, "Three")
                                        .setAvailable(false)
                                        .setWritable(false)
                                        .build()));
        mService.updateDevicePresetsCache(
                device, HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO, presets);
    }

    private void testOnPresetSelected(BluetoothDevice device, int index) throws RemoteException {
        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        evt.device = device;
        evt.valueInt1 = index;
        mService.messageFromNative(evt);
        mLooper.dispatchAll();

        verify(mFrameworkCallback)
                .onPresetSelected(
                        eq(device),
                        eq(evt.valueInt1),
                        eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
    }

    /** Helper function to get byte array for a device address */
    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastWithMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, prevState));
        assertThat(mService.getConnectionState(device)).isEqualTo(newState);
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newState, int oldState) {
        HapClientStackEvent event =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = device;
        event.valueInt1 = newState;
        mService.messageFromNative(event);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, newState, oldState);
    }
}
