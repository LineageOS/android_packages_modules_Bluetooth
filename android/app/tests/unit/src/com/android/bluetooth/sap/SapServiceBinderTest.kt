/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.sap;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link SapServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SapServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private SapService mService;

    private SapServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).isAvailable();
        mBinder = new SapServiceBinder(mService);
    }

    @Test
    public void getState() {
        mBinder.getState(mSource);
        verify(mService).getState();
    }

    @Test
    public void getClient() {
        mBinder.getClient(mSource);
        // times(2) due to the Log
        verify(mService, times(2)).getRemoteDevice();
    }

    @Test
    public void isConnected() {
        BluetoothDevice device = mock(BluetoothDevice.class);

        mBinder.isConnected(device, mSource);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = mock(BluetoothDevice.class);

        mBinder.disconnect(device, mSource);
        verify(mService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED, STATE_DISCONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = mock(BluetoothDevice.class);

        mBinder.getConnectionState(device, mSource);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        int connectionPolicy = 1;

        mBinder.setConnectionPolicy(device, connectionPolicy, mSource);
        verify(mService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = mock(BluetoothDevice.class);

        mBinder.getConnectionPolicy(device, mSource);
        verify(mService).getConnectionPolicy(device);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
