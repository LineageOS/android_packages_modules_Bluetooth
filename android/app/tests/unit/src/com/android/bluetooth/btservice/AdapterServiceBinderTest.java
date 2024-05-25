/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothOobDataCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.FileDescriptor;

public class AdapterServiceBinderTest {
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mService;
    @Mock private AdapterProperties mAdapterProperties;

    private AdapterService.AdapterServiceBinder mBinder;
    private AttributionSource mAttributionSource;

    @Before
    public void setUp() {
        mService.mAdapterProperties = mAdapterProperties;
        doReturn(true).when(mService).isAvailable();
        doNothing().when(mService).enforceCallingOrSelfPermission(any(), any());
        mBinder = new AdapterService.AdapterServiceBinder(mService);
        mAttributionSource = new AttributionSource.Builder(0).build();
    }

    @After
    public void cleanUp() {
        mBinder.cleanup();
    }

    @Test
    public void getAddress() {
        mBinder.getAddress(mAttributionSource);
        verify(mService.mAdapterProperties).getAddress();
    }

    @Test
    public void dump() {
        FileDescriptor fd = new FileDescriptor();
        String[] args = new String[] { };
        mBinder.dump(fd, args);
        verify(mService).dump(any(), any(), any());

        Mockito.clearInvocations(mService);
        mBinder.cleanup();
        mBinder.dump(fd, args);
        verify(mService, never()).dump(any(), any(), any());
    }

    @Test
    public void generateLocalOobData() {
        int transport = 0;
        IBluetoothOobDataCallback cb = Mockito.mock(IBluetoothOobDataCallback.class);

        mBinder.generateLocalOobData(transport, cb, mAttributionSource);
        verify(mService).generateLocalOobData(transport, cb);

        Mockito.clearInvocations(mService);
        mBinder.cleanup();
        mBinder.generateLocalOobData(transport, cb, mAttributionSource);
        verify(mService, never()).generateLocalOobData(transport, cb);
    }

    @Test
    public void getLeMaximumAdvertisingDataLength() {
        mBinder.getLeMaximumAdvertisingDataLength();
        verify(mService).getLeMaximumAdvertisingDataLength();
    }

    @Test
    public void getScanMode() {
        mBinder.getScanMode(mAttributionSource);
        verify(mService).getScanMode();
    }

    @Test
    public void isActivityAndEnergyReportingSupported() {
        mBinder.isActivityAndEnergyReportingSupported();
        verify(mService.mAdapterProperties).isActivityAndEnergyReportingSupported();
    }

    @Test
    public void isLe2MPhySupported() {
        mBinder.isLe2MPhySupported();
        verify(mService).isLe2MPhySupported();
    }

    @Test
    public void isLeCodedPhySupported() {
        mBinder.isLeCodedPhySupported();
        verify(mService).isLeCodedPhySupported();
    }

    @Test
    public void isLeExtendedAdvertisingSupported() {
        mBinder.isLeExtendedAdvertisingSupported();
        verify(mService).isLeExtendedAdvertisingSupported();
    }

    @Test
    public void removeActiveDevice() {
        int profiles = BluetoothAdapter.ACTIVE_DEVICE_ALL;
        mBinder.removeActiveDevice(profiles, mAttributionSource);
        verify(mService).setActiveDevice(null, profiles);
    }

    @Test
    public void reportActivityInfo() {
        mBinder.reportActivityInfo(mAttributionSource);
        verify(mService).reportActivityInfo();
    }

    @Test
    public void retrievePendingSocketForServiceRecord() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.retrievePendingSocketForServiceRecord(uuid, mAttributionSource);
        verify(mService).retrievePendingSocketForServiceRecord(uuid, mAttributionSource);
    }

    @Test
    public void stopRfcommListener() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.stopRfcommListener(uuid, mAttributionSource);
        verify(mService).stopRfcommListener(uuid, mAttributionSource);
    }
}
