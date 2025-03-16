/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothOobDataCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.FileDescriptor;

/** Test cases for {@link AdapterServiceBinder}. */
public class AdapterServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mService;
    @Mock private AdapterProperties mAdapterProperties;

    private AdapterServiceBinder mBinder;
    private AttributionSource mAttributionSource;

    @Before
    public void setUp() {
        when(mService.getAdapterProperties()).thenReturn(mAdapterProperties);
        doReturn(true).when(mService).isAvailable();
        doNothing().when(mService).enforceCallingOrSelfPermission(any(), any());
        mBinder = new AdapterServiceBinder(mService);
        mAttributionSource = new AttributionSource.Builder(0).build();
    }

    @Test
    public void getAddress() {
        mBinder.getAddress(mAttributionSource);
        verify(mAdapterProperties).getAddress();
    }

    @Test
    public void dump() {
        FileDescriptor fd = new FileDescriptor();
        String[] args = new String[] {};
        mBinder.dump(fd, args);
        verify(mService).dump(any(), any(), any());
    }

    @Test
    public void dumpWhenNotAvailable() {
        FileDescriptor fd = new FileDescriptor();
        String[] args = new String[] {};
        doReturn(false).when(mService).isAvailable();

        mBinder.dump(fd, args);

        verify(mService, never()).dump(any(), any(), any());
    }

    @Test
    public void generateLocalOobData() {
        int transport = 0;
        IBluetoothOobDataCallback cb = Mockito.mock(IBluetoothOobDataCallback.class);

        mBinder.generateLocalOobData(transport, cb, mAttributionSource);

        verify(mService).generateLocalOobData(transport, cb);
    }

    @Test
    public void generateLocalOobDataWhenNotAvailable() {
        int transport = 0;
        IBluetoothOobDataCallback cb = Mockito.mock(IBluetoothOobDataCallback.class);
        doReturn(false).when(mService).isAvailable();

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
        verify(mAdapterProperties).isActivityAndEnergyReportingSupported();
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
