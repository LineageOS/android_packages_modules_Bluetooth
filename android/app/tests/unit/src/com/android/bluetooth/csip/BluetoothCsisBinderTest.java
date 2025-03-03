/*
 * Copyright 2022 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

public class BluetoothCsisBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private CsipSetCoordinatorService mService;

    private final BluetoothDevice mDevice = getTestDevice(45);
    private final AttributionSource mAttributionSource = new AttributionSource.Builder(1).build();

    private CsipSetCoordinatorService.BluetoothCsisBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new CsipSetCoordinatorService.BluetoothCsisBinder(mService);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void lockGroup() {
        int groupId = 100;
        IBluetoothCsipSetCoordinatorLockCallback cb =
                mock(IBluetoothCsipSetCoordinatorLockCallback.class);
        mBinder.lockGroup(groupId, cb, mAttributionSource);
        verify(mService).lockGroup(groupId, cb);
    }

    @Test
    public void unlockGroup() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.unlockGroup(uuid, mAttributionSource);
        verify(mService).unlockGroup(uuid.getUuid());
    }

    @Test
    public void getAllGroupIds() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.getAllGroupIds(uuid, mAttributionSource);
        verify(mService).getAllGroupIds(uuid);
    }

    @Test
    public void getGroupUuidMapByDevice() {
        mBinder.getGroupUuidMapByDevice(mDevice, mAttributionSource);
        verify(mService).getGroupUuidMapByDevice(mDevice);
    }

    @Test
    public void getDesiredGroupSize() {
        int groupId = 100;
        mBinder.getDesiredGroupSize(groupId, mAttributionSource);
        verify(mService).getDesiredGroupSize(groupId);
    }
}
