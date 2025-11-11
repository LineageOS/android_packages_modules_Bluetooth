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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothActivityEnergyInfoListener;
import android.bluetooth.IBluetoothOobDataCallback;
import android.content.AttributionSource;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.FileDescriptor;

/** Test cases for {@link AdapterServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mAttributionSource;
    @Mock private AdapterService mService;
    @Mock private AdapterProperties mAdapterProperties;
    @Mock private BluetoothDevice mDevice;

    private AdapterServiceBinder mBinder;

    @Before
    public void setUp() {
        when(mService.getAdapterProperties()).thenReturn(mAdapterProperties);
        doReturn(true).when(mService).isAvailable();
        doNothing().when(mService).enforceCallingOrSelfPermission(any(), any());
        mBinder = new AdapterServiceBinder(mService);
    }

    @Test
    public void cancelDiscovery_whenServiceNotAvailable_returnsFalse() {
        // Setup: Simulate the service being unavailable.
        doReturn(false).when(mService).isAvailable();

        boolean result = mBinder.cancelDiscovery(mAttributionSource);

        assertThat(result).isFalse();
        verify(mService, never()).cancelDiscovery(any());
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
    public void requestActivityInfo() throws RemoteException {
        var listener = mock(IBluetoothActivityEnergyInfoListener.class);
        mBinder.requestActivityInfo(listener, mAttributionSource);
        verify(mService).requestActivityInfo();
        verify(listener).onBluetoothActivityEnergyInfoAvailable(any());
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

    @Test
    public void setPreferredAudioProfiles_deviceNotBonded_returnsError() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_NONE);

        int result = mBinder.setPreferredAudioProfiles(mDevice, new Bundle(), mAttributionSource);

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
        verify(mService, never()).setPreferredAudioProfiles(any(), any());
    }

    @Test
    public void setPreferredAudioProfiles_deviceBonded_callsService() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_BONDED);
        Bundle bundle = new Bundle();

        mBinder.setPreferredAudioProfiles(mDevice, bundle, mAttributionSource);

        verify(mService).setPreferredAudioProfiles(mDevice, bundle);
    }

    @Test
    public void getPreferredAudioProfiles_deviceNotBonded_returnsEmptyBundle() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_NONE);

        Bundle result = mBinder.getPreferredAudioProfiles(mDevice, mAttributionSource);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(Bundle.EMPTY);
        verify(mService, never()).getPreferredAudioProfiles(any());
    }

    @Test
    public void getPreferredAudioProfiles_deviceBonded_callsService() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_BONDED);

        mBinder.getPreferredAudioProfiles(mDevice, mAttributionSource);

        verify(mService).getPreferredAudioProfiles(mDevice);
    }

    @Test
    public void notifyActiveDeviceChangeApplied_deviceNotBonded_returnsError() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_NONE);

        int result = mBinder.notifyActiveDeviceChangeApplied(mDevice, mAttributionSource);

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
        verify(mService, never()).notifyActiveDeviceChangeApplied(any());
    }

    @Test
    public void notifyActiveDeviceChangeApplied_deviceBonded_callsService() {
        when(mService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_BONDED);

        mBinder.notifyActiveDeviceChangeApplied(mDevice, mAttributionSource);

        verify(mService).notifyActiveDeviceChangeApplied(mDevice);
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).when(mService).isAvailable();

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.connectAllEnabledProfiles(mDevice, mAttributionSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).connectAllEnabledProfiles(any());
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceNotEnabled_returnsError() {
        // The service is available but not enabled
        when(mService.isEnabled()).thenReturn(false);

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.connectAllEnabledProfiles(mDevice, mAttributionSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).connectAllEnabledProfiles(any());
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceEnabled_callsService() {
        // The service is available and enabled
        when(mService.isEnabled()).thenReturn(true);

        // Call the method and verify that the underlying service method is called
        mBinder.connectAllEnabledProfiles(mDevice, mAttributionSource);
        verify(mService).connectAllEnabledProfiles(mDevice);
    }

    @Test
    public void disconnectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).when(mService).isAvailable();

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.disconnectAllEnabledProfiles(mDevice, mAttributionSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).disconnectAllEnabledProfiles(any());
    }

    @Test
    public void disconnectAllEnabledProfiles_whenServiceAvailable_callsService() {
        // The service is available
        // Call the method and verify that the underlying service method is called
        mBinder.disconnectAllEnabledProfiles(mDevice, mAttributionSource);
        verify(mService).disconnectAllEnabledProfiles(mDevice);
    }
}
