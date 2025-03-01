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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

public class A2dpSinkServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpSinkService mService;
    private A2dpSinkService.A2dpSinkServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new A2dpSinkService.A2dpSinkServiceBinder(mService);
    }

    @After
    public void cleanUp() {
        mBinder.cleanup();
    }

    @Test
    public void connect() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.connect(device, source);
        verify(mService).connect(device);
    }

    @Test
    public void disconnect() {
        BluetoothDevice device = getTestDevice(0);
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
        int[] states = new int[] {STATE_CONNECTED};
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getDevicesMatchingConnectionStates(states, source);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionState(device, source);
        verify(mService).getConnectionState(device);
    }

    @Test
    public void setConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.setConnectionPolicy(device, connectionPolicy, source);
        verify(mService).setConnectionPolicy(device, connectionPolicy);
    }

    @Test
    public void getConnectionPolicy() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getConnectionPolicy(device, source);
        verify(mService).getConnectionPolicy(device);
    }

    @Test
    public void isA2dpPlaying() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.isA2dpPlaying(device, source);
        verify(mService).isA2dpPlaying(device);
    }

    @Test
    public void getAudioConfig() {
        BluetoothDevice device = getTestDevice(0);
        AttributionSource source = new AttributionSource.Builder(0).build();

        mBinder.getAudioConfig(device, source);
        verify(mService).getAudioConfig(device);
    }
}
