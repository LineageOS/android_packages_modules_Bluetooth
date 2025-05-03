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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.content.AttributionSource;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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

    @Mock private AttributionSource mAttributionSource;
    @Mock private HearingAidService mService;

    private HearingAidServiceBinder mBinder;
    private final BluetoothDevice mDevice = getTestDevice(0);

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new HearingAidServiceBinder(mService);
    }

    @Test
    public void connect() {
        mBinder.connect(mDevice, mAttributionSource);
        verify(mService).connect(mDevice);
    }

    @Test
    public void disconnect() {
        mBinder.disconnect(mDevice, mAttributionSource);
        verify(mService).disconnect(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        connectedDevices.add(mDevice);
        when(mService.getConnectedDevices()).thenReturn(connectedDevices);

        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED, STATE_DISCONNECTED};
        List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(mDevice);
        when(mService.getDevicesMatchingConnectionStates(states)).thenReturn(devices);

        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        when(mService.getConnectionState(mDevice)).thenReturn(STATE_CONNECTED);

        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void setActiveDevice() {
        mBinder.setActiveDevice(mDevice, mAttributionSource);
        verify(mService).setActiveDevice(mDevice);
    }

    @Test
    public void removeActiveDevice() {
        mBinder.setActiveDevice(null, mAttributionSource);
        verify(mService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevices() {
        List<BluetoothDevice> activeDevices = new ArrayList<>();
        activeDevices.add(mDevice);
        when(mService.getActiveDevices()).thenReturn(activeDevices);

        mBinder.getActiveDevices(mAttributionSource);
        verify(mService).getActiveDevices();
    }

    @Test
    public void setConnectionPolicy() {
        mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void getConnectionPolicy() {
        when(mService.getConnectionPolicy(mDevice)).thenReturn(CONNECTION_POLICY_FORBIDDEN);

        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void setVolume() {
        int volume = 50;

        mBinder.setVolume(volume, mAttributionSource);
        verify(mService).setVolume(volume);
    }

    @Test
    public void getHiSyncId() {
        long hiSyncId = 1234567890L;
        when(mService.getHiSyncId(mDevice)).thenReturn(hiSyncId);

        mBinder.getHiSyncId(mDevice, mAttributionSource);
        verify(mService).getHiSyncId(mDevice);
    }

    @Test
    public void getDeviceSide() {
        int side = BluetoothHearingAid.SIDE_LEFT;
        when(mService.getCapabilities(mDevice)).thenReturn(side);

        mBinder.getDeviceSide(mDevice, mAttributionSource);
        verify(mService).getCapabilities(mDevice);
    }

    @Test
    public void getDeviceMode() {
        int mode = BluetoothHearingAid.MODE_BINAURAL;
        when(mService.getCapabilities(mDevice)).thenReturn(mode << 1);

        mBinder.getDeviceMode(mDevice, mAttributionSource);
        verify(mService).getCapabilities(mDevice);
    }

    @Test
    public void getAdvertisementServiceData() {
        BluetoothHearingAid.AdvertisementServiceData data =
                new BluetoothHearingAid.AdvertisementServiceData(0, 0);
        when(mService.getAdvertisementServiceData(mDevice)).thenReturn(data);

        mBinder.getAdvertisementServiceData(mDevice, mAttributionSource);
        verify(mService).getAdvertisementServiceData(mDevice);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
