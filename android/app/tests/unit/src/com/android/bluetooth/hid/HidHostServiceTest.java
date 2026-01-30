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

package com.android.bluetooth.hid;

import static android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.btservice.AdapterSuspend.AWAKE;
import static com.android.bluetooth.btservice.AdapterSuspend.DEEP_SLEEP;
import static com.android.bluetooth.btservice.AdapterSuspend.SHALLOW_SLEEP;
import static com.android.bluetooth.hid.HidHostService.RECONNECT_ALLOWED;
import static com.android.bluetooth.hid.HidHostService.RECONNECT_NOT_ALLOWED_TEMPORARY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.List;

/** Test cases for {@link HidHostService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HidHostServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private HidHostNativeInterface mNativeInterface;
    @Mock private BluetoothDevice mBluetoothDevice;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private HidHostService mService;
    private TestLooper mLooper;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mService = new HidHostService(mAdapterService, mNativeInterface, mLooper.getLooper());
        mService.setAvailable(true);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
    }

    @Test
    public void okToConnect_whenInvalidBonded_returnFalse() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        doReturn(badBondState).when(mAdapterService).getBondState(any());
        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice)).isFalse();
        }
    }

    @Test
    public void okToConnect_whenNotBonded_returnTrue() {
        // allow connect Due to desync between BondStateMachine and AdapterProperties
        for (int bondState : List.of(BOND_NONE, BOND_BONDING)) {
            doReturn(bondState).when(mAdapterService).getBondState(any());
            for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
                doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
                assertThat(mService.okToConnect(mDevice)).isTrue();
            }
        }
    }

    @Test
    public void canConnect_whenBonded() {
        int badPolicyValue = 1024;
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any());

        for (int policy : List.of(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice)).isFalse();
        }
        for (int policy : List.of(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).when(mAdapterService).getProfileConnectionPolicy(any(), anyInt());
            assertThat(mService.okToConnect(mDevice)).isTrue();
        }
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }

    private void setupPeerWithUuid(ParcelUuid uuid) {
        String address = "11:22:33:44:55:66";
        ParcelUuid[] uuids = {uuid};

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());
        doReturn(uuids).when(mAdapterService).getRemoteUuids(any());
        doReturn(mBluetoothDevice).when(mAdapterService).getDeviceFromByte(any());

        doReturn(address).when(mBluetoothDevice).getAddress();
        doReturn(ADDRESS_TYPE_PUBLIC).when(mBluetoothDevice).getAddressType();
    }

    private void connectDevice(InOrder order, int transport) {
        mService.connect(mBluetoothDevice);
        TestUtils.syncHandler(mLooper, 1);
        order.verify(mNativeInterface).connectHid(any(), anyInt(), anyInt(), anyBoolean());

        mService.onConnectStateChanged(
                Utils.getByteAddress(mBluetoothDevice),
                ADDRESS_TYPE_PUBLIC,
                transport,
                STATE_CONNECTED,
                0);
        TestUtils.syncHandler(mLooper, 3);
    }

    private void disconnectDevice(InOrder order, int transport) {
        mService.disconnect(mBluetoothDevice);
        TestUtils.syncHandler(mLooper, 2);
        order.verify(mNativeInterface).disconnectHid(any(), anyInt(), anyInt(), anyInt());

        mService.onConnectStateChanged(
                Utils.getByteAddress(mBluetoothDevice),
                ADDRESS_TYPE_PUBLIC,
                transport,
                STATE_DISCONNECTED,
                0);
        TestUtils.syncHandler(mLooper, 3);
    }

    @Test
    public void suspend_shallowSleepConnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(SHALLOW_SLEEP);

        // Disconnect and allow reconnection
        order.verify(mNativeInterface)
                .disconnectHid(
                        eq(Utils.getByteAddress(mBluetoothDevice)),
                        anyInt(),
                        eq(TRANSPORT_LE),
                        eq(RECONNECT_ALLOWED));
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void suspend_shallowSleepDisconnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        disconnectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(SHALLOW_SLEEP);

        // No-op for connection/disconnection.
        order.verify(mNativeInterface, never()).disconnectHid(any(), anyInt(), anyInt(), anyInt());
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void suspend_deepSleepConnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(DEEP_SLEEP);

        // Just disconnect and not rearm the connection
        order.verify(mNativeInterface)
                .disconnectHid(
                        eq(Utils.getByteAddress(mBluetoothDevice)),
                        anyInt(),
                        eq(TRANSPORT_LE),
                        eq(RECONNECT_NOT_ALLOWED_TEMPORARY));
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void suspend_deepSleepDisconnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        disconnectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(DEEP_SLEEP);

        // Disconnect to remove the accept list, and not rearm the connection
        order.verify(mNativeInterface)
                .disconnectHid(
                        eq(Utils.getByteAddress(mBluetoothDevice)),
                        anyInt(),
                        eq(TRANSPORT_LE),
                        eq(RECONNECT_NOT_ALLOWED_TEMPORARY));
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void suspend_awakeConnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(AWAKE);

        // Already connected - No-op
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
        order.verify(mNativeInterface, never()).disconnectHid(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void suspend_awakeDisconnected() {
        setupPeerWithUuid(BluetoothUuid.HOGP);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_LE);
        disconnectDevice(order, TRANSPORT_LE);
        mService.onSuspendStateChange(AWAKE);

        // Initiate background connection
        order.verify(mNativeInterface)
                .connectHid(
                        eq(Utils.getByteAddress(mBluetoothDevice)),
                        anyInt(),
                        eq(TRANSPORT_LE),
                        eq(false));
        order.verify(mNativeInterface, never()).disconnectHid(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void suspend_connectedBredr() {
        setupPeerWithUuid(BluetoothUuid.HID);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_BREDR);
        mService.onSuspendStateChange(SHALLOW_SLEEP);
        mService.onSuspendStateChange(AWAKE);

        // Don't manage BREDR connection
        order.verify(mNativeInterface, never()).disconnectHid(any(), anyInt(), anyInt(), anyInt());
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void suspend_disconnectedBredr() {
        setupPeerWithUuid(BluetoothUuid.HID);
        InOrder order = inOrder(mNativeInterface);

        connectDevice(order, TRANSPORT_BREDR);
        disconnectDevice(order, TRANSPORT_BREDR);
        mService.onSuspendStateChange(SHALLOW_SLEEP);
        mService.onSuspendStateChange(AWAKE);

        // Don't manage BREDR connection
        order.verify(mNativeInterface, never()).disconnectHid(any(), anyInt(), anyInt(), anyInt());
        order.verify(mNativeInterface, never()).connectHid(any(), anyInt(), anyInt(), anyBoolean());
    }
}
