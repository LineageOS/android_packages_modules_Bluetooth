/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapClientBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private PbapClientService mMockService;
    private BluetoothDevice mTestDevice;
    private AttributionSource mAttributionSource;

    private PbapClientBinder mPbapClientBinder;

    @Before
    public void setUp() throws Exception {
        mTestDevice = getTestDevice(1);
        mAttributionSource = new AttributionSource.Builder(1).build();
        mPbapClientBinder = new PbapClientBinder(mMockService);
    }

    @After
    public void tearDown() throws Exception {
        if (mPbapClientBinder != null) {
            mPbapClientBinder.cleanup();
            mPbapClientBinder = null;
        }
    }

    // *********************************************************************************************
    // * API Methods
    // *********************************************************************************************

    @Test
    public void testConnect() {
        mPbapClientBinder.connect(mTestDevice, mAttributionSource);
        verify(mMockService).connect(eq(mTestDevice));
    }

    @Test
    public void testDisconnect() {
        mPbapClientBinder.disconnect(mTestDevice, mAttributionSource);
        verify(mMockService).disconnect(eq(mTestDevice));
    }

    @Test
    public void testGetConnectedDevices() {
        mPbapClientBinder.getConnectedDevices(mAttributionSource);
        verify(mMockService).getConnectedDevices();
    }

    @Test
    public void testGetDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};
        mPbapClientBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mMockService).getDevicesMatchingConnectionStates(eq(states));
    }

    @Test
    public void testGetConnectionState() {
        mPbapClientBinder.getConnectionState(mTestDevice, mAttributionSource);
        verify(mMockService).getConnectionState(eq(mTestDevice));
    }

    @Test
    public void testSetConnectionPolicy() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        mPbapClientBinder.setConnectionPolicy(mTestDevice, connectionPolicy, mAttributionSource);
        verify(mMockService).setConnectionPolicy(eq(mTestDevice), eq(connectionPolicy));
    }

    @Test
    public void testGetConnectionPolicy() {
        mPbapClientBinder.getConnectionPolicy(mTestDevice, mAttributionSource);
        verify(mMockService).getConnectionPolicy(eq(mTestDevice));
    }

    // *********************************************************************************************
    // * API Methods (Without service set, i.e. profile not up)
    // *********************************************************************************************

    @Test
    public void testConnect_afterCleanup_returnsFalse() {
        mPbapClientBinder.cleanup();
        boolean result = mPbapClientBinder.connect(mTestDevice, mAttributionSource);
        verify(mMockService, never()).connect(any(BluetoothDevice.class));
        assertThat(result).isFalse();
    }

    @Test
    public void testDisconnect_afterCleanup_returnsFalse() {
        mPbapClientBinder.cleanup();
        boolean result = mPbapClientBinder.disconnect(mTestDevice, mAttributionSource);
        verify(mMockService, never()).disconnect(any(BluetoothDevice.class));
        assertThat(result).isFalse();
    }

    @Test
    public void testGetConnectedDevices_afterCleanup_returnsEmptyList() {
        mPbapClientBinder.cleanup();
        List<BluetoothDevice> devices = mPbapClientBinder.getConnectedDevices(mAttributionSource);
        verify(mMockService, never()).getConnectedDevices();
        assertThat(devices).isEmpty();
    }

    @Test
    public void testGetDevicesMatchingConnectionStates_afterCleanup_returnsEmptyList() {
        mPbapClientBinder.cleanup();
        int[] states = new int[] {STATE_CONNECTED};
        List<BluetoothDevice> devices =
                mPbapClientBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mMockService, never()).getDevicesMatchingConnectionStates(any(int[].class));
        assertThat(devices).isEmpty();
    }

    @Test
    public void testGetConnectionState_afterCleanup_returnsDisconnected() {
        mPbapClientBinder.cleanup();
        int state = mPbapClientBinder.getConnectionState(mTestDevice, mAttributionSource);
        verify(mMockService, never()).getConnectionState(any(BluetoothDevice.class));
        assertThat(state).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testSetConnectionPolicy_afterCleanup_returnsFalse() {
        mPbapClientBinder.cleanup();
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;
        boolean result =
                mPbapClientBinder.setConnectionPolicy(
                        mTestDevice, connectionPolicy, mAttributionSource);
        verify(mMockService, never()).setConnectionPolicy(any(BluetoothDevice.class), anyInt());
        assertThat(result).isFalse();
    }

    @Test
    public void testGetConnectionPolicy_afterCleanup_returnsUnknown() {
        mPbapClientBinder.cleanup();
        int result = mPbapClientBinder.getConnectionPolicy(mTestDevice, mAttributionSource);
        verify(mMockService, never()).getConnectionPolicy(any(BluetoothDevice.class));
        assertThat(result).isEqualTo(CONNECTION_POLICY_UNKNOWN);
    }
}
