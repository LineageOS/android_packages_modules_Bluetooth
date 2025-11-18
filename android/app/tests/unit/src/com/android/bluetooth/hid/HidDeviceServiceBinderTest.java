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

package com.android.bluetooth.hid;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.IBluetoothHidDeviceCallback;
import android.content.AttributionSource;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link HidDeviceServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HidDeviceServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private HidDeviceService mService;

    private final BluetoothDevice mDevice = getTestDevice(29);

    private HidDeviceServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new HidDeviceServiceBinder(mService);
    }

    @Test
    public void cleanup() {
        mBinder.cleanup();
    }

    @Test
    public void registerApp() {
        String name = "test-name";
        String description = "test-description";
        String provider = "test-provider";
        byte subclass = 1;
        byte[] descriptors = new byte[] {10};
        BluetoothHidDeviceAppSdpSettings sdp =
                new BluetoothHidDeviceAppSdpSettings(
                        name, description, provider, subclass, descriptors);

        int tokenRate = 800;
        int tokenBucketSize = 9;
        int peakBandwidth = 10;
        int latency = 11250;
        int delayVariation = BluetoothHidDeviceAppQosSettings.MAX;
        BluetoothHidDeviceAppQosSettings inQos =
                new BluetoothHidDeviceAppQosSettings(
                        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                        tokenRate,
                        tokenBucketSize,
                        peakBandwidth,
                        latency,
                        delayVariation);
        BluetoothHidDeviceAppQosSettings outQos =
                new BluetoothHidDeviceAppQosSettings(
                        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                        tokenRate,
                        tokenBucketSize,
                        peakBandwidth,
                        latency,
                        delayVariation);
        IBluetoothHidDeviceCallback cb = mock(IBluetoothHidDeviceCallback.class);

        mBinder.registerApp(sdp, inQos, outQos, cb, mSource);
        verify(mService).registerApp(sdp, inQos, outQos, cb);
    }

    @Test
    public void unregisterApp() {
        mBinder.unregisterApp(mSource);
        verify(mService).unregisterApp();
    }

    @Test
    public void sendReport() {
        int id = 100;
        byte[] data = new byte[] {0x00, 0x01};
        mBinder.sendReport(mDevice, id, data, mSource);
        verify(mService).sendReport(mDevice, id, data);
    }

    @Test
    public void replyReport() {
        byte type = 0;
        byte id = 100;
        byte[] data = new byte[] {0x00, 0x01};
        mBinder.replyReport(mDevice, type, id, data, mSource);
        verify(mService).replyReport(mDevice, type, id, data);
    }

    @Test
    public void unplug() {
        mBinder.unplug(mDevice, mSource);
        verify(mService).unplug(mDevice);
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
    public void reportError() {
        byte error = -1;
        mBinder.reportError(mDevice, error, mSource);
        verify(mService).reportError(mDevice, error);
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mSource);
        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mSource);
        verify(mService).getDevicesMatchingConnectionStates(any(int[].class));
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getUserAppName() {
        mBinder.getUserAppName(mSource);
        verify(mService).getUserAppName();
    }
}
