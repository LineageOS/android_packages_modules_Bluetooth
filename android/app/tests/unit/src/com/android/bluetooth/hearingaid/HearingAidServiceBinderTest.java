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
import android.bluetooth.BluetoothManager;
import android.content.AttributionSource;
import android.content.Context;

import androidx.test.InstrumentationRegistry;
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

    @Mock private HearingAidService mService;

    private HearingAidServiceBinder mBinder;
    private AttributionSource mAttributionSource;
    private BluetoothDevice mTestDevice;

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new HearingAidServiceBinder(mService);
        Context context = InstrumentationRegistry.getTargetContext();
        mAttributionSource =
                context.getSystemService(BluetoothManager.class)
                        .getAdapter()
                        .getAttributionSource();
        mTestDevice = getTestDevice(0);
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
        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        connectedDevices.add(mTestDevice);
        when(mService.getConnectedDevices()).thenReturn(connectedDevices);

        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED, STATE_DISCONNECTED};
        List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(mTestDevice);
        when(mService.getDevicesMatchingConnectionStates(states)).thenReturn(devices);

        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState() {
        when(mService.getConnectionState(mTestDevice)).thenReturn(STATE_CONNECTED);

        mBinder.getConnectionState(mTestDevice, mAttributionSource);
        verify(mService).getConnectionState(mTestDevice);
    }

    @Test
    public void setActiveDevice() {
        mBinder.setActiveDevice(mTestDevice, mAttributionSource);
        verify(mService).setActiveDevice(mTestDevice);
    }

    @Test
    public void removeActiveDevice() {
        mBinder.setActiveDevice(null, mAttributionSource);
        verify(mService).removeActiveDevice(false);
    }

    @Test
    public void getActiveDevices() {
        List<BluetoothDevice> activeDevices = new ArrayList<>();
        activeDevices.add(mTestDevice);
        when(mService.getActiveDevices()).thenReturn(activeDevices);

        mBinder.getActiveDevices(mAttributionSource);
        verify(mService).getActiveDevices();
    }

    @Test
    public void setConnectionPolicy() {
        mBinder.setConnectionPolicy(mTestDevice, CONNECTION_POLICY_ALLOWED, mAttributionSource);
        verify(mService).setConnectionPolicy(mTestDevice, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void getConnectionPolicy() {
        when(mService.getConnectionPolicy(mTestDevice)).thenReturn(CONNECTION_POLICY_FORBIDDEN);

        mBinder.getConnectionPolicy(mTestDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mTestDevice);
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
        when(mService.getHiSyncId(mTestDevice)).thenReturn(hiSyncId);

        mBinder.getHiSyncId(mTestDevice, mAttributionSource);
        verify(mService).getHiSyncId(mTestDevice);
    }

    @Test
    public void getDeviceSide() {
        int side = BluetoothHearingAid.SIDE_LEFT;
        when(mService.getCapabilities(mTestDevice)).thenReturn(side);

        mBinder.getDeviceSide(mTestDevice, mAttributionSource);
        verify(mService).getCapabilities(mTestDevice);
    }

    @Test
    public void getDeviceMode() {
        int mode = BluetoothHearingAid.MODE_BINAURAL;
        when(mService.getCapabilities(mTestDevice)).thenReturn(mode << 1);

        mBinder.getDeviceMode(mTestDevice, mAttributionSource);
        verify(mService).getCapabilities(mTestDevice);
    }

    @Test
    public void getAdvertisementServiceData() {
        BluetoothHearingAid.AdvertisementServiceData data =
                new BluetoothHearingAid.AdvertisementServiceData(0, 0);
        when(mService.getAdvertisementServiceData(mTestDevice)).thenReturn(data);

        mBinder.getAdvertisementServiceData(mTestDevice, mAttributionSource);
        verify(mService).getAdvertisementServiceData(mTestDevice);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
