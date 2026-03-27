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

package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import static com.android.bluetooth.TestUtils.getRealDevice;
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hfpclient.HeadsetClientService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.pbapclient.PbapClientService;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.Optional;

/** Test cases for {@link BondStateMachine}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class BondStateMachineTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private AdapterNativeInterface mNativeInterface;
    @Mock private PackageManager mPackageManager;

    @Mock HidHostService mHidHostService;
    @Mock A2dpService mA2dpService;
    @Mock HeadsetService mHeadsetService;
    @Mock HeadsetClientService mHeadsetClientService;
    @Mock A2dpSinkService mA2dpSinkService;
    @Mock PbapClientService mPbapClientService;
    @Mock LeAudioService mLeAudioService;
    @Mock CsipSetCoordinatorService mCsipSetCoordinatorService;
    @Mock VolumeControlService mVolumeControlService;
    @Mock HapClientService mHapClientService;

    private static final int TEST_BOND_REASON = 0;
    private static final byte[] TEST_BT_ADDR_BYTES = {00, 11, 22, 33, 44, 55};
    private static final byte[] TEST_BT_ADDR_BYTES_2 = {00, 11, 22, 33, 44, 66};
    private static final int[] DEVICE_TYPES = {
        BluetoothDevice.DEVICE_TYPE_CLASSIC,
        BluetoothDevice.DEVICE_TYPE_DUAL,
        BluetoothDevice.DEVICE_TYPE_LE
    };
    private static final ParcelUuid[] TEST_UUIDS = {
        ParcelUuid.fromString("0000111E-0000-1000-8000-00805F9B34FB")
    };

    private AdapterProperties mAdapterProperties;
    private BluetoothDevice mDevice;
    private RemoteDevices mRemoteDevices;
    private BondStateMachine mStateMachine;
    private TestLooper mLooper;
    private RemoteDevices.DeviceProperties mDeviceProperties;
    private int mVerifyCount = 0;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public BondStateMachineTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();

        doReturn(mNativeInterface).when(mAdapterService).getNative();
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();

        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, CompanionDeviceManager.class);
        mRemoteDevices = new RemoteDevices(mAdapterService, mLooper.getLooper());

        var context = InstrumentationRegistry.getInstrumentation().getContext();
        doReturn(context.getResources()).when(mAdapterService).getResources();
        mAdapterProperties =
                new AdapterProperties(mAdapterService, mRemoteDevices, mLooper.getLooper());
        mAdapterProperties.init();
        mStateMachine =
                new BondStateMachine(
                        mAdapterService, mLooper.getLooper(), mAdapterProperties, mRemoteDevices);
    }

    @Test
    public void testCreateBondAfterRemoveBond() {
        // Set up two devices already bonded.
        RemoteDevices.DeviceProperties deviceProperties1, deviceProperties2;
        deviceProperties1 = mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        deviceProperties2 = mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES_2);
        BluetoothDevice device1, device2;
        device1 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES);
        device2 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES_2);
        deviceProperties1.mBondState = BOND_BONDED;
        deviceProperties2.mBondState = BOND_BONDED;

        doReturn(true).when(mNativeInterface).removeBond(any(byte[].class));
        doReturn(true)
                .when(mNativeInterface)
                .createBond(any(byte[].class), eq(BluetoothDevice.ADDRESS_TYPE_PUBLIC), anyInt());

        // The removeBond() request for a bonded device should invoke the removeBondNative() call.
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device1);
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device2);

        verify(mNativeInterface).removeBond(eq(TEST_BT_ADDR_BYTES));
        verify(mNativeInterface).removeBond(eq(TEST_BT_ADDR_BYTES_2));

        mStateMachine.bondStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                TEST_BT_ADDR_BYTES,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                0,
                0,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                0);
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE);
        mStateMachine.bondStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                TEST_BT_ADDR_BYTES_2,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                0,
                0,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                0);
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE);

        // Try to pair these two devices again, createBondNative() should be invoked.
        sendAndDispatchMessage(BondStateMachine.MESSAGE_CREATE_BOND, device1);
        sendAndDispatchMessage(BondStateMachine.MESSAGE_CREATE_BOND, device2);

        verify(mNativeInterface)
                .createBond(
                        eq(TEST_BT_ADDR_BYTES), eq(BluetoothDevice.ADDRESS_TYPE_PUBLIC), anyInt());
        verify(mNativeInterface)
                .createBond(
                        eq(TEST_BT_ADDR_BYTES_2),
                        eq(BluetoothDevice.ADDRESS_TYPE_PUBLIC),
                        anyInt());
    }

    @Test
    public void testCreateBondWithLeDevice() {
        mStateMachine.mDevicesWaitingForUuids.clear();

        BluetoothDevice device1 =
                getRealDevice(
                        Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES),
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC);
        BluetoothDevice device2 =
                getRealDevice(
                        Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES_2),
                        BluetoothDevice.ADDRESS_TYPE_RANDOM);

        // The createBond() request for two devices with different address types.
        sendAndDispatchMessage(BondStateMachine.MESSAGE_CREATE_BOND, device1);
        sendAndDispatchMessage(BondStateMachine.MESSAGE_CREATE_BOND, device2);

        verify(mNativeInterface)
                .createBond(
                        eq(TEST_BT_ADDR_BYTES), eq(BluetoothDevice.ADDRESS_TYPE_PUBLIC), anyInt());
        verify(mNativeInterface)
                .createBond(
                        eq(TEST_BT_ADDR_BYTES_2),
                        eq(BluetoothDevice.ADDRESS_TYPE_RANDOM),
                        anyInt());
    }

    @Test
    public void testUuidUpdateWithPendingDevice() {
        mRemoteDevices.reset();
        mStateMachine.mDevicesWaitingForUuids.clear();

        RemoteDevices.DeviceProperties pendingDeviceProperties =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES_2);
        BluetoothDevice pendingDevice = pendingDeviceProperties.getDevice();
        assertThat(pendingDevice).isNotNull();
        mStateMachine.handleBondStateChanged(
                pendingDevice,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_BONDED,
                null,
                null,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                TEST_BOND_REASON,
                0);

        RemoteDevices.DeviceProperties testDeviceProperties =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        testDeviceProperties.mUuidsBrEdr = TEST_UUIDS;
        BluetoothDevice testDevice = testDeviceProperties.getDevice();
        assertThat(testDevice).isNotNull();

        sendAndDispatchMessage(
                BondStateMachine.MESSAGE_BOND_STATE_CHANGE,
                BOND_BONDING,
                AbstractionLayer.BT_STATUS_RMT_DEV_DOWN,
                testDevice);

        pendingDeviceProperties.mUuidsBrEdr = TEST_UUIDS;
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE); // message was deferred

        sendAndDispatchMessage(BondStateMachine.MESSAGE_UUID_UPDATE, pendingDevice);
        sendAndDispatchMessage(
                BondStateMachine.MESSAGE_BOND_STATE_CHANGE,
                BOND_BONDED,
                AbstractionLayer.BT_STATUS_SUCCESS,
                testDevice);

        syncHandler(BondStateMachine.MESSAGE_UUID_UPDATE); // message was deferred

        assertThat(mLooper.nextMessage()).isNull();
        assertThat(mStateMachine.mDevicesWaitingForUuids).isEmpty();
    }

    private void resetRemoteDevice(int deviceType) {
        // Reset mRemoteDevices for the test.
        mRemoteDevices.reset();
        mDeviceProperties = mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        mDevice = mDeviceProperties.getDevice();
        assertThat(mDevice).isNotNull();
        mDeviceProperties.mDeviceType = deviceType;
        mStateMachine.mDevicesWaitingForUuids.clear();
    }

    @Test
    public void testSendIntent() {
        int badBondState = 42;
        mVerifyCount = 0;

        // Uuid not available, mPendingBondedDevice is empty.
        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_NONE, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);

        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_BONDING, false, BOND_BONDING, true, BOND_NONE, BOND_BONDING, false);
        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_BONDED, false, BOND_BONDED, true, BOND_NONE, BOND_BONDING, true);
        testSendIntentNoPendingDevice(
                BOND_NONE, badBondState, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, BOND_NONE, false, BOND_NONE, true, BOND_BONDING, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING,
                BOND_BONDING,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, BOND_BONDED, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, true);
        testSendIntentNoPendingDevice(
                BOND_BONDING,
                badBondState,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, BOND_NONE, false, BOND_NONE, true, BOND_BONDED, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED,
                BOND_BONDING,
                false,
                BOND_BONDING,
                true,
                BOND_BONDED,
                BOND_BONDING,
                false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, BOND_BONDED, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, badBondState, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);

        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_NONE, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_BONDING, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_NONE, BOND_BONDED, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_NONE, badBondState, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, BOND_NONE, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, BOND_BONDING, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, BOND_BONDED, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDING, badBondState, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, BOND_NONE, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, BOND_BONDING, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, BOND_BONDED, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDevice(
                BOND_BONDED, badBondState, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);

        // Uuid not available, mPendingBondedDevice contains a remote device.
        testSendIntentPendingDevice(
                BOND_NONE, BOND_NONE, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, BOND_BONDING, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, BOND_BONDED, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, badBondState, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING, BOND_NONE, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING,
                BOND_BONDING,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentPendingDevice(
                BOND_BONDING, BOND_BONDED, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING,
                badBondState,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentPendingDevice(
                BOND_BONDED, BOND_NONE, false, BOND_NONE, true, BOND_BONDING, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDED, BOND_BONDING, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDED, BOND_BONDED, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, true);
        testSendIntentPendingDevice(
                BOND_BONDED, badBondState, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, BOND_NONE, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, BOND_BONDING, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, BOND_BONDED, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_NONE, badBondState, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING, BOND_NONE, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING, BOND_BONDING, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING, BOND_BONDED, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDING, badBondState, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDevice(
                BOND_BONDED,
                BOND_BONDED,
                true,
                BOND_BONDED,
                true,
                BOND_BONDING,
                BOND_BONDED,
                false);

        // Uuid available, mPendingBondedDevice is empty.
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_NONE, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDING, false, BOND_BONDING, true, BOND_NONE, BOND_BONDING, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDED, false, BOND_BONDED, true, BOND_NONE, BOND_BONDED, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, badBondState, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING, BOND_NONE, false, BOND_NONE, true, BOND_BONDING, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING,
                BOND_BONDING,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING,
                BOND_BONDED,
                false,
                BOND_BONDED,
                true,
                BOND_BONDING,
                BOND_BONDED,
                false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING,
                badBondState,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, BOND_NONE, false, BOND_NONE, true, BOND_BONDED, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED,
                BOND_BONDING,
                false,
                BOND_BONDING,
                true,
                BOND_BONDED,
                BOND_BONDING,
                false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, BOND_BONDED, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, badBondState, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);

        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_NONE, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDING, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDED, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_NONE, badBondState, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING, BOND_NONE, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING, BOND_BONDING, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING, BOND_BONDED, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDING, badBondState, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, BOND_NONE, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, BOND_BONDING, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, BOND_BONDED, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);
        testSendIntentNoPendingDeviceWithUuid(
                BOND_BONDED, badBondState, true, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);

        // Uuid available, mPendingBondedDevice contains a remote device.
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_NONE, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDING, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDED, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, badBondState, false, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, BOND_NONE, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING,
                BOND_BONDING,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, BOND_BONDED, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING,
                badBondState,
                false,
                BOND_BONDING,
                false,
                BOND_NONE,
                BOND_NONE,
                false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDED, BOND_NONE, false, BOND_NONE, true, BOND_BONDING, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDED, BOND_BONDING, false, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDED,
                BOND_BONDED,
                false,
                BOND_BONDED,
                true,
                BOND_BONDING,
                BOND_BONDED,
                false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDED, badBondState, false, BOND_BONDED, false, BOND_NONE, BOND_NONE, false);

        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_NONE, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDING, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, BOND_BONDED, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_NONE, badBondState, true, BOND_NONE, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, BOND_NONE, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, BOND_BONDING, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, BOND_BONDED, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDING, badBondState, true, BOND_BONDING, false, BOND_NONE, BOND_NONE, false);
        testSendIntentPendingDeviceWithUuid(
                BOND_BONDED,
                BOND_BONDED,
                true,
                BOND_BONDED,
                true,
                BOND_BONDING,
                BOND_BONDED,
                false);
    }

    @Test
    public void handleBondStateChanged_fromBondingToNone_resetsKeyMissingCount() {
        // Set up a device and set its initial state to BONDING
        mDeviceProperties = mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        mDevice = mDeviceProperties.getDevice();
        mDeviceProperties.mBondState = BOND_BONDING;

        // Trigger the state change from BONDING to NONE
        mStateMachine.handleBondStateChanged(
                mDevice,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                null, // pairingAlgorithm
                null, // pairingVariant
                AbstractionLayer.BT_PAIRING_INITIATOR_APP, // pairingInitiator
                TEST_BOND_REASON,
                0); // hciReason

        // Verify that the key missing count is reset. This is crucial for scenarios like
        // autonomous repair, where a failed pairing attempt (BONDING -> NONE) should clear
        // the bond-loss state.
        verify(mAdapterService).updateKeyMissingCount(eq(mDevice), eq(false));
    }

    @Test
    public void clearProfilePriority() {
        doReturn(Optional.of(mHidHostService)).when(mAdapterService).getHidHostService();
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        // Random profile intentionally left out to test empty case
        doReturn(Optional.empty()).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHeadsetClientService))
                .when(mAdapterService)
                .getHeadsetClientService();
        doReturn(Optional.of(mA2dpSinkService)).when(mAdapterService).getA2dpSinkService();
        // Random profile intentionally left out to test empty case
        doReturn(Optional.empty()).when(mAdapterService).getPbapClientService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(Optional.of(mCsipSetCoordinatorService))
                .when(mAdapterService)
                .getCsipSetCoordinatorService();
        doReturn(Optional.of(mVolumeControlService))
                .when(mAdapterService)
                .getVolumeControlService();
        // Random profile intentionally left out to test empty case
        doReturn(Optional.empty()).when(mAdapterService).getHapClientService();

        mStateMachine.clearPermissionsAndPolicies(mDevice);

        InOrder inOrder =
                inOrder(
                        mHidHostService,
                        mA2dpService,
                        mHeadsetClientService,
                        mA2dpSinkService,
                        mLeAudioService,
                        mCsipSetCoordinatorService,
                        mVolumeControlService);

        inOrder.verify(mHidHostService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mA2dpService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mHeadsetClientService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mA2dpSinkService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mLeAudioService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mCsipSetCoordinatorService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));
        inOrder.verify(mVolumeControlService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_UNKNOWN));

        verify(mHeadsetService, never()).setConnectionPolicy(any(), anyInt());
        verify(mPbapClientService, never()).setConnectionPolicy(any(), anyInt());
        verify(mHapClientService, never()).setConnectionPolicy(any(), anyInt());
    }

    private void testSendIntentCase(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist) {
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);

        // Setup old state before start test.
        mDeviceProperties.mBondState = oldState;

        try {
            if (uuidUpdate) {
                mStateMachine.handlePendingUuids(mDevice);
            } else {
                mStateMachine.handleBondStateChanged(
                        mDevice,
                        BluetoothDevice.TRANSPORT_BREDR,
                        newState,
                        null,
                        null,
                        AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                        TEST_BOND_REASON,
                        0);
            }
        } catch (IllegalArgumentException e) {
            // Do nothing.
        }

        // Properties are removed when bond is removed
        if (newState != BluetoothDevice.BOND_NONE) {
            assertThat(mDeviceProperties.getBondState()).isEqualTo(expectedNewState);
        }

        // Check for bond state Intent status.
        if (shouldBroadcast) {
            verify(mAdapterService, times(++mVerifyCount))
                    .sendBroadcast(
                            intentArgument.capture(), eq(BLUETOOTH_CONNECT), any(Bundle.class));
            verifyBondStateChangeIntent(
                    broadcastOldState, broadcastNewState, intentArgument.getValue());
        } else {
            verify(mAdapterService, times(mVerifyCount))
                    .sendBroadcast(any(Intent.class), anyString(), any(Bundle.class));
        }

        if (shouldDelayMessageExist) {
            assertThat(mStateMachine.hasMessage(mStateMachine.MESSAGE_SERVICE_DISCOVERY_TIMEOUT))
                    .isTrue();
            mStateMachine.removeMessage(mStateMachine.MESSAGE_SERVICE_DISCOVERY_TIMEOUT);
        } else {
            assertThat(mStateMachine.hasMessage(mStateMachine.MESSAGE_SERVICE_DISCOVERY_TIMEOUT))
                    .isFalse();
        }
    }

    private void testSendIntentForAllDeviceTypes(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist,
            BluetoothDevice pendingBondedDevice,
            ParcelUuid[] uuids) {
        for (int deviceType : DEVICE_TYPES) {
            resetRemoteDevice(deviceType);
            if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                // Add audio support to validate tests.
                mDeviceProperties.setBluetoothClass(BluetoothClass.Service.LE_AUDIO);
            }
            if (pendingBondedDevice != null) {
                mStateMachine.mDevicesWaitingForUuids.add(mDevice);
            }
            if (uuids != null) {
                // Add dummy UUID for the device.
                mDeviceProperties.mUuidsBrEdr = TEST_UUIDS;
            }
            testSendIntentCase(
                    oldState,
                    newState,
                    uuidUpdate,
                    expectedNewState,
                    shouldBroadcast,
                    broadcastOldState,
                    broadcastNewState,
                    shouldDelayMessageExist);
        }
    }

    private void testSendIntentNoPendingDeviceWithUuid(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist) {
        testSendIntentForAllDeviceTypes(
                oldState,
                newState,
                uuidUpdate,
                expectedNewState,
                shouldBroadcast,
                broadcastOldState,
                broadcastNewState,
                shouldDelayMessageExist,
                null,
                TEST_UUIDS);
    }

    private void testSendIntentPendingDeviceWithUuid(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist) {
        testSendIntentForAllDeviceTypes(
                oldState,
                newState,
                uuidUpdate,
                expectedNewState,
                shouldBroadcast,
                broadcastOldState,
                broadcastNewState,
                shouldDelayMessageExist,
                mDevice,
                TEST_UUIDS);
    }

    private void testSendIntentPendingDevice(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist) {
        testSendIntentForAllDeviceTypes(
                oldState,
                newState,
                uuidUpdate,
                expectedNewState,
                shouldBroadcast,
                broadcastOldState,
                broadcastNewState,
                shouldDelayMessageExist,
                mDevice,
                null);
    }

    private void testSendIntentNoPendingDevice(
            int oldState,
            int newState,
            boolean uuidUpdate,
            int expectedNewState,
            boolean shouldBroadcast,
            int broadcastOldState,
            int broadcastNewState,
            boolean shouldDelayMessageExist) {
        testSendIntentForAllDeviceTypes(
                oldState,
                newState,
                uuidUpdate,
                expectedNewState,
                shouldBroadcast,
                broadcastOldState,
                broadcastNewState,
                shouldDelayMessageExist,
                null,
                null);
    }

    private void verifyBondStateChangeIntent(int oldState, int newState, Intent intent) {
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        assertThat(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(mDevice);
        assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)).isEqualTo(newState);
        assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1))
                .isEqualTo(oldState);
        if (newState == BOND_NONE) {
            assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_UNBOND_REASON, -1))
                    .isEqualTo(TEST_BOND_REASON);
        } else {
            assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_UNBOND_REASON, -1)).isEqualTo(-1);
        }
    }

    private void syncHandler(int what) {
        TestUtils.syncHandler(mLooper, what);
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        sendAndDispatchMessage(what, 0, 0, obj);
    }

    private void sendAndDispatchMessage(int what, int arg1, int arg2, Object obj) {
        mStateMachine.sendMessage(what, arg1, arg2, obj);
        syncHandler(what);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_BOND_IN_IDLE_STATE)
    public void testRemoveBondInIdleState_concurrentRequests() {
        // Set up two devices that are bonded
        RemoteDevices.DeviceProperties deviceProperties1 =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        BluetoothDevice device1 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES);
        deviceProperties1.mBondState = BOND_BONDED;

        RemoteDevices.DeviceProperties deviceProperties2 =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES_2);
        BluetoothDevice device2 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES_2);
        deviceProperties2.mBondState = BOND_BONDED;

        doReturn(true).when(mNativeInterface).removeBond(any(byte[].class));

        // Send remove bond message for device 1
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device1);

        // Verify native removeBond called for device 1
        verify(mNativeInterface).removeBond(eq(TEST_BT_ADDR_BYTES));

        // Verify we are still in StateIdle
        assertThat(mStateMachine.getCurrentState().getName()).isEqualTo("StateIdle");

        // Send remove bond message for device 2 BEFORE callback for device 1
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device2);

        // Verify native removeBond called for device 2
        verify(mNativeInterface).removeBond(eq(TEST_BT_ADDR_BYTES_2));
        assertThat(mStateMachine.getCurrentState().getName()).isEqualTo("StateIdle");

        // Now simulate callbacks
        // Callback for device 1
        mStateMachine.bondStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                TEST_BT_ADDR_BYTES,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                0,
                0,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                0);
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE);

        // Callback for device 2
        mStateMachine.bondStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                TEST_BT_ADDR_BYTES_2,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                0,
                0,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                0);
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE);

        // Verify state remains idle
        assertThat(mStateMachine.getCurrentState().getName()).isEqualTo("StateIdle");
        assertThat(mRemoteDevices.getBondState(device1)).isEqualTo(BOND_NONE);
        assertThat(mRemoteDevices.getBondState(device2)).isEqualTo(BOND_NONE);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_BOND_IN_IDLE_STATE)
    public void testBondStateChangeInIdleState_clearsPermissionsAndSetsReason() {
        // Set up a device that is bonded
        RemoteDevices.DeviceProperties deviceProperties =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        BluetoothDevice device = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES);
        deviceProperties.mBondState = BOND_BONDED;

        // Mock profile services to be present
        doReturn(Optional.of(mHidHostService)).when(mAdapterService).getHidHostService();
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHeadsetClientService))
                .when(mAdapterService)
                .getHeadsetClientService();
        doReturn(Optional.of(mA2dpSinkService)).when(mAdapterService).getA2dpSinkService();
        doReturn(Optional.of(mPbapClientService)).when(mAdapterService).getPbapClientService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(Optional.of(mCsipSetCoordinatorService))
                .when(mAdapterService)
                .getCsipSetCoordinatorService();
        doReturn(Optional.of(mVolumeControlService))
                .when(mAdapterService)
                .getVolumeControlService();
        doReturn(Optional.of(mHapClientService)).when(mAdapterService).getHapClientService();

        // Simulate native callback for bond removal success
        mStateMachine.bondStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                TEST_BT_ADDR_BYTES,
                BluetoothDevice.TRANSPORT_BREDR,
                BOND_NONE,
                0,
                0,
                AbstractionLayer.BT_PAIRING_INITIATOR_APP,
                0);
        syncHandler(BondStateMachine.MESSAGE_BOND_STATE_CHANGE);

        // Verify permissions are cleared
        verify(mAdapterService)
                .setPhonebookAccessPermission(eq(device), eq(BluetoothDevice.ACCESS_UNKNOWN));
        verify(mAdapterService)
                .setMessageAccessPermission(eq(device), eq(BluetoothDevice.ACCESS_UNKNOWN));
        verify(mAdapterService)
                .setSimAccessPermission(eq(device), eq(BluetoothDevice.ACCESS_UNKNOWN));

        // Verify profile policies are cleared
        verify(mHidHostService).setConnectionPolicy(eq(device), eq(CONNECTION_POLICY_UNKNOWN));
        verify(mA2dpService).setConnectionPolicy(eq(device), eq(CONNECTION_POLICY_UNKNOWN));

        // Verify Intent was broadcast with correct reason
        ArgumentCaptor<Intent> intentArgument = ArgumentCaptor.forClass(Intent.class);
        verify(mAdapterService, times(1))
                .sendBroadcast(intentArgument.capture(), anyString(), any(Bundle.class));

        Intent intent = intentArgument.getValue();
        assertThat(intent.getAction()).isEqualTo(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)).isEqualTo(BOND_NONE);
        assertThat(intent.getIntExtra(BluetoothDevice.EXTRA_UNBOND_REASON, -1))
                .isEqualTo(BluetoothDevice.UNBOND_REASON_REMOVED);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_BOND_IN_IDLE_STATE)
    public void testDuplicateRemoveBondRequests_doesNotBlockCreateBond() {
        // Set up a device that is bonded
        RemoteDevices.DeviceProperties deviceProperties =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES);
        BluetoothDevice device = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES);
        deviceProperties.mBondState = BOND_BONDED;

        doReturn(true).when(mNativeInterface).removeBond(any(byte[].class));
        doReturn(true).when(mNativeInterface).createBond(any(byte[].class), anyInt(), anyInt());

        // Send first remove bond message
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device);
        verify(mNativeInterface).removeBond(eq(TEST_BT_ADDR_BYTES));

        // Send second remove bond message (duplicate)
        sendAndDispatchMessage(BondStateMachine.MESSAGE_REMOVE_BOND, device);
        verify(mNativeInterface, times(2)).removeBond(eq(TEST_BT_ADDR_BYTES));

        // Verify state is still Idle
        assertThat(mStateMachine.getCurrentState().getName()).isEqualTo("StateIdle");

        // Now send create bond for another device.
        // We do NOT simulate a bond state change callback for the remove operations.
        // This simulates the "native stack does not respond" scenario.
        RemoteDevices.DeviceProperties deviceProperties2 =
                mRemoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES_2);
        BluetoothDevice device2 = deviceProperties2.getDevice();

        sendAndDispatchMessage(BondStateMachine.MESSAGE_CREATE_BOND, device2);

        // Verify createBond is called immediately
        verify(mNativeInterface).createBond(eq(TEST_BT_ADDR_BYTES_2), anyInt(), anyInt());
    }
}
