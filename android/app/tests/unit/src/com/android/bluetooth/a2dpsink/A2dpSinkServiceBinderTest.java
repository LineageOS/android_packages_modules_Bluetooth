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

package com.android.bluetooth.a2dpsink;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;

import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class A2dpSinkServiceBinderTest {
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private A2dpSinkService mService;
    private A2dpSinkService.A2dpSinkServiceBinder mBinder;
    private BluetoothAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBinder = new A2dpSinkService.A2dpSinkServiceBinder(mService);
    }

    @After
    public void cleaUp() {
        mBinder.cleanup();
    }

    @Test
    public void connect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.connect(device, source);
        verify(mService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.disconnect(device, source);
        verify(mService).disconnect(device);
    }

    @Test
    public void getConnectedDevices() {
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectedDevices(source);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {BluetoothProfile.STATE_CONNECTED};
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getDevicesMatchingConnectionStates(states, source);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionState(device, source);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        int connectionPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setConnectionPolicy(device, connectionPolicy, source);
        verify(mService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionPolicy(device, source);
        verify(mService).getConnectionPolicy(device);
    }

    @Test
    public void isA2dpPlaying() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.isA2dpPlaying(device, source);
        verify(mService).isA2dpPlaying(device);
    }

    @Test
    public void getAudioConfig() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAudioConfig(device, source);
        verify(mService).getAudioConfig(device);
    }
}
