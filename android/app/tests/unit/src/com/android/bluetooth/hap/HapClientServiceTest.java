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
import static android.bluetooth.BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID;
import static android.bluetooth.BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
import static android.bluetooth.BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;
import static android.bluetooth.BluetoothStatusCodes.REASON_REMOTE_REQUEST;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.hap.HapClientService.PRESET_INFO_REASON_ALL_PRESET_INFO;
import static com.android.bluetooth.hap.HapClientService.PRESET_INFO_REASON_PRESET_INFO_UPDATE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHapClientCallback;
import android.content.Intent;
import android.os.Binder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test cases for {@link HapClientService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class HapClientServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private HapClientNativeInterface mNativeInterface;
    @Mock private CsipSetCoordinatorService mCsipService;
    @Mock private IBluetoothHapClientCallback mFrameworkCallback;
    @Mock private Binder mBinder;

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mDevice3 = getTestDevice(2);

    private HapClientService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_HAP_ON_MAIN_LOOPER);
    }

    public HapClientServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    // Don't use @Before because the setUp and the test would be running on different thread. This
    // creates issues with the TestLooper, as it overrides Looper.myLooper for the current thread
    // only.
    public void initTest() {
        final byte[] byteAddress1 = getByteAddress(mDevice1);
        doReturn(mDevice1).when(mAdapterService).getDeviceFromByte(eq(byteAddress1));
        final byte[] byteAddress2 = getByteAddress(mDevice2);
        doReturn(mDevice2).when(mAdapterService).getDeviceFromByte(eq(byteAddress2));
        final byte[] byteAddress3 = getByteAddress(mDevice3);
        doReturn(mDevice3).when(mAdapterService).getDeviceFromByte(eq(byteAddress3));

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());

        doReturn(Optional.of(mCsipService)).when(mAdapterService).getCsipSetCoordinatorService();

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

        doReturn(List.of(mDevice1, mDevice2)).when(mCsipService).getGroupDevicesOrdered(groupId2);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice1);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice2);

        doReturn(List.of(mDevice3)).when(mCsipService).getGroupDevicesOrdered(groupId3);
        doReturn(groups3).when(mCsipService).getGroupUuidMapByDevice(mDevice3);

        doReturn(List.of(mDevice1)).when(mCsipService).getGroupDevicesOrdered(0x01);

        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.HAS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService =
                new HapClientService(
                        mAdapterService,
                        mActiveDeviceManager,
                        mLooper.getLooper(),
                        mNativeInterface);
        mService.setAvailable(true);

        synchronized (mService.mCallbacks) {
            mService.mCallbacks.register(mFrameworkCallback);
        }
    }

    @Test
    public void getConnectionPolicy() {
        initTest();
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
        initTest();
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
        initTest();
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
        initTest();
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void connectToDevice_whenPolicyForbid_returnFalse() {
        initTest();
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());

        assertThat(mService.connect(mDevice1)).isFalse();
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnected() {
        initTest();
        assertThat(mService.connect(mDevice1)).isTrue();
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(HapClientStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(mDevice1, STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void connectTwoDevices() {
        initTest();
        testConnectingDevice(mDevice1);
        testConnectingDevice(mDevice2);

        assertThat(mService.getConnectedDevices()).containsExactly(mDevice1, mDevice2);
    }

    @Test
    public void getActivePresetIndex_whenNoConnected_isUnavailable() {
        initTest();
        assertThat(mService.getActivePresetIndex(mDevice1)).isEqualTo(PRESET_INDEX_UNAVAILABLE);
    }

    @Test
    public void testGetHapGroupCoordinatedOps() {
        initTest();
        testConnectingDevice(mDevice1);
        testConnectingDevice(mDevice2);
        testConnectingDevice(mDevice3);

        mService.onFeaturesUpdate(mDevice1, 0x04);
        mService.onFeaturesUpdate(mDevice3, 0x04);

        /* This one has no coordinated operation support but is part of a coordinated set with
         * mDevice1, which supports it, thus mDevice1 will forward the operation to
         * mDevice2. This device should also be recognized as grouped one.
         */
        mService.onFeaturesUpdate(mDevice2, 0);

        /* Two devices support coordinated operations thus shall report valid group ID */
        assertThat(mService.getHapGroup(mDevice1)).isEqualTo(2);
        assertThat(mService.getHapGroup(mDevice3)).isEqualTo(3);

        /* Third one has no coordinated operations support but is part of the group */
        int hapGroup = mService.getHapGroup(mDevice2);
        assertThat(hapGroup).isEqualTo(2);
    }

    @Test
    public void testSelectPresetNative() throws RemoteException {
        initTest();
        testConnectingDevice(mDevice1);

        // Verify Native Interface call
        mService.selectPreset(mDevice1, 0x00);
        verify(mNativeInterface, never()).selectActivePreset(eq(mDevice1), eq(0x00));
        verify(mFrameworkCallback)
                .onPresetSelectionFailed(eq(mDevice1), eq(ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPreset(mDevice1, 0x01);
        verify(mNativeInterface).selectActivePreset(eq(mDevice1), eq(0x01));
    }

    @Test
    public void testGroupSelectActivePresetNative() throws RemoteException {
        initTest();
        testConnectingDevice(mDevice3);

        int flags = 0x01;
        mService.onFeaturesUpdate(mDevice3, flags);

        // Verify Native Interface call
        mService.selectPresetForGroup(0x03, 0x00);
        verify(mFrameworkCallback)
                .onPresetSelectionForGroupFailed(eq(0x03), eq(ERROR_HAP_INVALID_PRESET_INDEX));

        mService.selectPresetForGroup(0x03, 0x01);
        verify(mNativeInterface).groupSelectActivePreset(eq(0x03), eq(0x01));
    }

    @Test
    public void testSwitchToNextPreset() {
        initTest();
        testConnectingDevice(mDevice1);

        // Verify Native Interface call
        mService.switchToNextPreset(mDevice1);
        verify(mNativeInterface).nextActivePreset(eq(mDevice1));
    }

    @Test
    public void testSwitchToNextPresetForGroup() {
        initTest();
        testConnectingDevice(mDevice3);
        int flags = 0x01;
        mService.onFeaturesUpdate(mDevice3, flags);

        // Verify Native Interface call
        mService.switchToNextPresetForGroup(0x03);
        verify(mNativeInterface).groupNextActivePreset(eq(0x03));
    }

    @Test
    public void testSwitchToPreviousPreset() {
        initTest();
        testConnectingDevice(mDevice1);

        // Verify Native Interface call
        mService.switchToPreviousPreset(mDevice1);
        verify(mNativeInterface).previousActivePreset(eq(mDevice1));
    }

    @Test
    public void testSwitchToPreviousPresetForGroup() {
        initTest();
        testConnectingDevice(mDevice1);
        testConnectingDevice(mDevice2);

        int flags = 0x01;
        mService.onFeaturesUpdate(mDevice1, flags);

        // Verify Native Interface call
        mService.switchToPreviousPresetForGroup(0x02);
        verify(mNativeInterface).groupPreviousActivePreset(eq(0x02));
    }

    @Test
    public void testGetActivePresetIndex() throws RemoteException {
        initTest();
        testConnectingDevice(mDevice1);
        testOnPresetSelected(mDevice1, 0x01);

        assertThat(mService.getActivePresetIndex(mDevice1)).isEqualTo(0x01);
    }

    @Test
    public void testGetPresetInfoAndActivePresetInfo() throws RemoteException {
        initTest();
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
        initTest();
        testConnectingDevice(mDevice1);

        mService.setPresetName(mDevice1, 0x00, "ExamplePresetName");
        verify(mNativeInterface, never())
                .setPresetName(eq(mDevice1), eq(0x00), eq("ExamplePresetName"));
        verify(mFrameworkCallback)
                .onSetPresetNameFailed(eq(mDevice1), eq(ERROR_HAP_INVALID_PRESET_INDEX));

        // Verify Native Interface call
        mService.setPresetName(mDevice1, 0x01, "ExamplePresetName");
        verify(mNativeInterface).setPresetName(eq(mDevice1), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void testSetPresetNameForGroup() throws RemoteException {
        initTest();
        int test_group = 0x02;

        testConnectingDevice(mDevice1);
        testConnectingDevice(mDevice2);

        int flags = 0x21;
        mService.onFeaturesUpdate(mDevice1, flags);

        mService.setPresetNameForGroup(test_group, 0x00, "ExamplePresetName");
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(eq(test_group), eq(ERROR_HAP_INVALID_PRESET_INDEX));

        mService.setPresetNameForGroup(-1, 0x01, "ExamplePresetName");
        verify(mFrameworkCallback)
                .onSetPresetNameForGroupFailed(eq(-1), eq(ERROR_CSIP_INVALID_GROUP_ID));

        // Verify Native Interface call
        mService.setPresetNameForGroup(test_group, 0x01, "ExamplePresetName");
        verify(mNativeInterface)
                .groupSetPresetName(eq(test_group), eq(0x01), eq("ExamplePresetName"));
    }

    @Test
    public void onPresetSelected() throws RemoteException {
        initTest();
        int presetIndex = 0x01;

        mService.onPresetSelected(mDevice1, presetIndex);

        verify(mFrameworkCallback)
                .onPresetSelected(eq(mDevice1), eq(presetIndex), eq(REASON_LOCAL_STACK_REQUEST));
        assertThat(mService.getActivePresetIndex(mDevice1)).isEqualTo(presetIndex);
    }

    @Test
    public void onPresetSelectionFailed() throws RemoteException {
        initTest();
        mService.onPresetSelectionFailed(mDevice1, ERROR_HAP_INVALID_PRESET_INDEX);

        verify(mFrameworkCallback)
                .onPresetSelectionFailed(mDevice1, ERROR_HAP_INVALID_PRESET_INDEX);
    }

    @Test
    public void updatePreset_whenPresent_isBroadcast() throws RemoteException {
        initTest();
        testConnectingDevice(mDevice1);

        int infoReason = PRESET_INFO_REASON_PRESET_INFO_UPDATE;
        BluetoothHapPresetInfo[] info = {
            new BluetoothHapPresetInfo.Builder(0x01, "OneChangedToUnavailable")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };

        mService.onPresetInfo(mDevice1, infoReason, List.of(info));

        ArgumentCaptor<List<BluetoothHapPresetInfo>> presetsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mFrameworkCallback)
                .onPresetInfoChanged(
                        eq(mDevice1), presetsCaptor.capture(), eq(REASON_REMOTE_REQUEST));

        List<BluetoothHapPresetInfo> presets = presetsCaptor.getValue();
        assertThat(presets).hasSize(3);

        BluetoothHapPresetInfo preset =
                presets.stream().filter(p -> 0x01 == p.getIndex()).findFirst().get();

        assertThat(preset.getName()).isEqualTo("OneChangedToUnavailable");
        assertThat(preset.isAvailable()).isFalse();
        assertThat(preset.isWritable()).isTrue();
    }

    @Test
    public void onSetPresetNameFailed_broadcastToClient() throws RemoteException {
        initTest();
        mService.onSetPresetNameFailed(mDevice1, 1);
        verify(mFrameworkCallback).onSetPresetNameFailed(eq(mDevice1), eq(1));
    }

    @Test
    public void onSetPresetNameFailedForGroup_broadcastToClient() throws RemoteException {
        initTest();
        int groupId = 0x01;
        mService.onSetPresetNameForGroupFailed(groupId, 1);
        verify(mFrameworkCallback).onSetPresetNameForGroupFailed(eq(groupId), eq(1));
    }

    @Test
    public void getDevicesMatchingConnectionStates_whenNull_isEmpty() {
        initTest();
        assertThat(mService.getDevicesMatchingConnectionStates(null)).isEmpty();
    }

    @Test
    public void setConnectionPolicy() {
        initTest();
        assertThat(mService.setConnectionPolicy(mDevice1, CONNECTION_POLICY_UNKNOWN)).isTrue();
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void getFeatures() {
        initTest();
        assertThat(mService.getFeatures(mDevice1)).isEqualTo(0x00);
    }

    @Test
    public void registerUnregisterCallback() {
        initTest();
        IBluetoothHapClientCallback callback = Mockito.mock(IBluetoothHapClientCallback.class);
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
    public void dumpDoesNotCrash() {
        initTest();
        // Add state machine for testing dump()
        mService.connect(mDevice1);
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

        mService.onDeviceAvailable(device, 0x01);

        verifyIntentSent(
                hasAction(ACTION_HAP_DEVICE_AVAILABLE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, 0x01));

        mService.onFeaturesUpdate(device, 0x01);

        // Inject some initial presets
        List<BluetoothHapPresetInfo> presets =
                List.of(
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
                                .build());
        mService.updateDevicePresetsCache(device, PRESET_INFO_REASON_ALL_PRESET_INFO, presets);
    }

    private void testOnPresetSelected(BluetoothDevice device, int index) throws RemoteException {
        mService.onPresetSelected(device, index);

        verify(mFrameworkCallback)
                .onPresetSelected(eq(device), eq(index), eq(REASON_LOCAL_STACK_REQUEST));
    }

    /** Helper function to get byte array for a device address */
    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Util.getBytesFromAddress("00:00:00:00:00:00");
        }
        final String address = device.getAddress();
        return Util.getBytesFromAddress(address);
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastWithMultiplePermissions(argThat(AllOf.allOf(matchers)), any());
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
        mService.onConnectionStateChanged(device, newState);
        if (!Flags.hapOnMainLooper()) {
            mLooper.dispatchAll();
        }

        verifyConnectionStateIntent(device, newState, oldState);
    }
}
