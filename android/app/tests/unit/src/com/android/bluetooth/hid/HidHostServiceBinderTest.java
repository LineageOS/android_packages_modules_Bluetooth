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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link HidHostServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HidHostServiceBinderTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HidHostService mService;

    private final AttributionSource mAttributionSource = new AttributionSource.Builder(1).build();
    private final BluetoothDevice mDevice = getTestDevice(50);

    private HidHostServiceBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new HidHostServiceBinder(mService);
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
        verify(mService).getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED});
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
    public void getConnectionPolicy_callsServiceMethod() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);
    }

    @Test
    public void setPreferredTransport_callsServiceMethod() {
        int preferredTransport = BluetoothDevice.TRANSPORT_AUTO;

        mBinder.setPreferredTransport(mDevice, preferredTransport, mAttributionSource);
        verify(mService).setPreferredTransport(mDevice, preferredTransport);
    }

    @Test
    public void getPreferredTransport_callsServiceMethod() {
        mBinder.getPreferredTransport(mDevice, mAttributionSource);
        verify(mService).getPreferredTransport(mDevice);
    }

    @Test
    public void getProtocolMode_callsServiceMethod() {
        mBinder.getProtocolMode(mDevice, mAttributionSource);
        verify(mService).getProtocolMode(mDevice);
    }

    @Test
    public void virtualUnplug_callsServiceMethod() {
        mBinder.virtualUnplug(mDevice, mAttributionSource);
        verify(mService).virtualUnplug(mDevice);
    }

    @Test
    public void setProtocolMode_callsServiceMethod() {
        int protocolMode = 1;

        mBinder.setProtocolMode(mDevice, protocolMode, mAttributionSource);
        verify(mService).setProtocolMode(mDevice, protocolMode);
    }

    @Test
    public void getReport_callsServiceMethod() {
        byte reportType = 1;
        byte reportId = 2;
        int bufferSize = 16;

        mBinder.getReport(mDevice, reportType, reportId, bufferSize, mAttributionSource);
        verify(mService).getReport(mDevice, reportType, reportId, bufferSize);
    }

    @Test
    public void setReport_callsServiceMethod() {
        byte reportType = 1;
        String report = "test_report";

        mBinder.setReport(mDevice, reportType, report, mAttributionSource);
        verify(mService).setReport(mDevice, reportType, report);
    }

    @Test
    public void sendData_callsServiceMethod() {
        String report = "test_report";

        mBinder.sendData(mDevice, report, mAttributionSource);
        verify(mService).sendData(mDevice, report);
    }

    @Test
    public void setIdleTime_callsServiceMethod() {
        byte idleTime = 1;

        mBinder.setIdleTime(mDevice, idleTime, mAttributionSource);
        verify(mService).setIdleTime(mDevice, idleTime);
    }

    @Test
    public void getIdleTime_callsServiceMethod() {
        mBinder.getIdleTime(mDevice, mAttributionSource);
        verify(mService).getIdleTime(mDevice);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
