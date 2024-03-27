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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class BluetoothCsisBinderTest {
    private static final String TEST_DEVICE_ADDRESS = "00:00:00:00:00:00";

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private CsipSetCoordinatorService mService;

    private AttributionSource mAttributionSource;
    private BluetoothDevice mTestDevice;

    private CsipSetCoordinatorService.BluetoothCsisBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new CsipSetCoordinatorService.BluetoothCsisBinder(mService);
        mAttributionSource = new AttributionSource.Builder(1).build();
        mTestDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TEST_DEVICE_ADDRESS);
    }

    @Test
    public void connect() {
        mBinder.connect(mTestDevice, mAttributionSource);
        verify(mService).connect(mTestDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mTestDevice, mAttributionSource);
        verify(mService).disconnect(mTestDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] { BluetoothProfile.STATE_CONNECTED };
        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mTestDevice, mAttributionSource);
        verify(mService).getConnectionState(mTestDevice);
    }

    @Test
    public void setConnectionPolicy() {
        int connectionPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mTestDevice, connectionPolicy, mAttributionSource);
        verify(mService).setConnectionPolicy(mTestDevice, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mTestDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mTestDevice);
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
        mBinder.getGroupUuidMapByDevice(mTestDevice, mAttributionSource);
        verify(mService).getGroupUuidMapByDevice(mTestDevice);
    }

    @Test
    public void getDesiredGroupSize() {
        int groupId = 100;
        mBinder.getDesiredGroupSize(groupId, mAttributionSource);
        verify(mService).getDesiredGroupSize(groupId);
    }
}
