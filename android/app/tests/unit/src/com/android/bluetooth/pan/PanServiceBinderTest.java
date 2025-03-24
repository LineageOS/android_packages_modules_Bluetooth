/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.pan;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link PanServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PanServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private PanService mService;

    private final AttributionSource mAttributionSource = new AttributionSource.Builder(1).build();
    private final BluetoothDevice mDevice = getTestDevice(64);

    private PanServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new PanServiceBinder(mService);
    }

    @Test
    public void connect_callsServiceMethod() {
        mBinder.connect(mDevice, mAttributionSource);
        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect_callsServiceMethod() {
        mBinder.disconnect(mDevice, mAttributionSource);
        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices_callsServiceMethod() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates_callsServiceMethod() {
        int[] states = new int[] {STATE_CONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState_callsServiceMethod() {
        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setConnectionPolicy_callsServiceMethod() {
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;

        mBinder.setConnectionPolicy(mDevice, connectionPolicy, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, connectionPolicy);
    }

    @Test
    public void isTetheringOn_callsServiceMethod() {
        mBinder.isTetheringOn(mAttributionSource);
        verify(mService).isTetheringOn();
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
