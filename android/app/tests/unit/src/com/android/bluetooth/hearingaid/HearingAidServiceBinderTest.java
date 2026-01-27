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

package com.android.bluetooth.hearingaid;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.content.AttributionSource;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link HearingAidServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HearingAidServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private HearingAidService mService;

    private HearingAidServiceBinder mBinder;
    private final BluetoothDevice mDevice = getTestDevice(0);

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).isAvailable();
        mBinder = new HearingAidServiceBinder(mService);
    }

    @Test
    public void connect() {
        mBinder.connect(mDevice, mSource);
        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mDevice, mSource);
        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        connectedDevices.add(mDevice);
        doReturn(connectedDevices).when(mService).getConnectedDevices();

        mBinder.getConnectedDevices(mSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED, STATE_DISCONNECTED};
        List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(mDevice);
        doReturn(devices).when(mService).getDevicesMatchingConnectionStates(states);

        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        doReturn(STATE_CONNECTED).when(mService).getConnectionState(mDevice);

        mBinder.getConnectionState(mDevice, mSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setActiveDevice() {
        mBinder.setActiveDevice(mDevice, mSource);
        verify(mService).setActiveDevice(mDevice);
    }

    @Test
    public void removeActiveDevice() {
        mBinder.setActiveDevice(null, mSource);
        verify(mService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevices() {
        List<BluetoothDevice> activeDevices = new ArrayList<>();
        activeDevices.add(mDevice);
        doReturn(activeDevices).when(mService).getActiveDevices();

        mBinder.getActiveDevices(mSource);
        verify(mService).getActiveDevices();
    }

    @Test
    public void setConnectionPolicy() {
        mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mSource);
        verify(mService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void getConnectionPolicy() {
        doReturn(CONNECTION_POLICY_FORBIDDEN).when(mService).getConnectionPolicy(mDevice);

        mBinder.getConnectionPolicy(mDevice, mSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void setVolume() {
        int volume = 50;

        mBinder.setVolume(volume, mSource);
        verify(mService).setVolume(volume);
    }

    @Test
    public void getHiSyncId() {
        long hiSyncId = 1234567890L;
        doReturn(hiSyncId).when(mService).getHiSyncId(mDevice);

        mBinder.getHiSyncId(mDevice, mSource);
        verify(mService).getHiSyncId(mDevice);
    }

    @Test
    public void getDeviceSide() {
        int side = BluetoothHearingAid.SIDE_LEFT;
        doReturn(side).when(mService).getCapabilities(mDevice);

        mBinder.getDeviceSide(mDevice, mSource);
        verify(mService).getCapabilities(mDevice);
    }

    @Test
    public void getDeviceMode() {
        int mode = BluetoothHearingAid.MODE_BINAURAL;
        doReturn(mode << 1).when(mService).getCapabilities(mDevice);

        mBinder.getDeviceMode(mDevice, mSource);
        verify(mService).getCapabilities(mDevice);
    }

    @Test
    public void getAdvertisementServiceData() {
        BluetoothHearingAid.AdvertisementServiceData data =
                new BluetoothHearingAid.AdvertisementServiceData(0, 0);
        doReturn(data).when(mService).getAdvertisementServiceData(mDevice);

        mBinder.getAdvertisementServiceData(mDevice, mSource);
        verify(mService).getAdvertisementServiceData(mDevice);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
