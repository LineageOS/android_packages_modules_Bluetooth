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

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothActivityEnergyInfoListener;
import android.bluetooth.IBluetoothHciVendorSpecificCallback;
import android.bluetooth.IBluetoothOobDataCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.UserManager;

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
import java.util.Set;

/** Test cases for {@link AdapterServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private AdapterService mService;
    @Mock private AdapterProperties mAdapterProperties;
    @Mock private BluetoothDevice mDevice;
    @Mock private RemoteDevices mRemoteDevices;
    @Mock private UserManager mUserManager;
    @Mock private BluetoothHciVendorSpecificDispatcher mDispatcher;

    private AdapterServiceBinder mBinder;

    // Transport constants from BluetoothDevice
    private static final int INVALID_TRANSPORT_NEGATIVE = -1;
    private static final int INVALID_TRANSPORT_POSITIVE = 3;

    @Before
    public void setUp() {
        doReturn(mAdapterProperties).when(mService).getAdapterProperties();
        doReturn(mRemoteDevices).when(mService).getRemoteDevices();
        doReturn(mDispatcher).when(mService).getBluetoothHciVendorSpecificDispatcher();
        doReturn(true).when(mService).isAvailable();
        // Default for other permission checks if any
        doNothing().when(mService).enforceCallingOrSelfPermission(any(), any());

        // Setup mock UserManager to be returned by mService
        doReturn(mUserManager).when(mService).getSystemService(Context.USER_SERVICE);
        mockGetSystemService(mService, UserManager.class, mUserManager);
        // Default: Simulate caller is system/active
        mockCallerIsSystemOrActive(true);

        mBinder = new AdapterServiceBinder(mService);
    }

    private void mockCallerIsSystemOrActive(boolean isSystemOrActive) {
        // This is an approximation. The real static method is more complex.
        doReturn(isSystemOrActive).when(mUserManager).isSystemUser();
    }

    @Test
    public void cancelDiscovery_whenServiceNotAvailable_returnsFalse() {
        // Setup: Simulate the service being unavailable.
        doReturn(false).when(mService).isAvailable();

        boolean result = mBinder.cancelDiscovery(mSource);

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

        mBinder.generateLocalOobData(transport, cb, mSource);

        verify(mService).generateLocalOobData(transport, cb);
    }

    @Test
    public void generateLocalOobDataWhenNotAvailable() {
        int transport = 0;
        IBluetoothOobDataCallback cb = Mockito.mock(IBluetoothOobDataCallback.class);
        doReturn(false).when(mService).isAvailable();

        mBinder.generateLocalOobData(transport, cb, mSource);

        verify(mService, never()).generateLocalOobData(transport, cb);
    }

    @Test
    public void getLeMaximumAdvertisingDataLength() {
        mBinder.getLeMaximumAdvertisingDataLength();
        verify(mService).getLeMaximumAdvertisingDataLength();
    }

    @Test
    public void getScanMode() {
        mBinder.getScanMode(mSource);
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
        mBinder.removeActiveDevice(profiles, mSource);
        verify(mService).setActiveDevice(null, profiles);
    }

    @Test
    public void requestActivityInfo() throws RemoteException {
        var listener = mock(IBluetoothActivityEnergyInfoListener.class);
        mBinder.requestActivityInfo(listener, mSource);
        verify(mService).requestActivityInfo();
        verify(listener).onBluetoothActivityEnergyInfoAvailable(any());
    }

    @Test
    public void retrievePendingSocketForServiceRecord() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.retrievePendingSocketForServiceRecord(uuid, mSource);
        verify(mService).retrievePendingSocketForServiceRecord(uuid, mSource);
    }

    @Test
    public void stopRfcommListener() {
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        mBinder.stopRfcommListener(uuid, mSource);
        verify(mService).stopRfcommListener(uuid, mSource);
    }

    @Test
    public void setPreferredAudioProfiles_deviceNotBonded_returnsError() {
        doReturn(BluetoothDevice.BOND_NONE).when(mService).getBondState(mDevice);

        int result = mBinder.setPreferredAudioProfiles(mDevice, new Bundle(), mSource);

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
        verify(mService, never()).setPreferredAudioProfiles(any(), any());
    }

    @Test
    public void setPreferredAudioProfiles_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).when(mService).getBondState(mDevice);
        Bundle bundle = new Bundle();

        mBinder.setPreferredAudioProfiles(mDevice, bundle, mSource);

        verify(mService).setPreferredAudioProfiles(mDevice, bundle);
    }

    @Test
    public void getPreferredAudioProfiles_deviceNotBonded_returnsEmptyBundle() {
        doReturn(BluetoothDevice.BOND_NONE).when(mService).getBondState(mDevice);

        Bundle result = mBinder.getPreferredAudioProfiles(mDevice, mSource);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(Bundle.EMPTY);
        verify(mService, never()).getPreferredAudioProfiles(any());
    }

    @Test
    public void getPreferredAudioProfiles_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).when(mService).getBondState(mDevice);

        mBinder.getPreferredAudioProfiles(mDevice, mSource);

        verify(mService).getPreferredAudioProfiles(mDevice);
    }

    @Test
    public void notifyActiveDeviceChangeApplied_deviceNotBonded_returnsError() {
        doReturn(BluetoothDevice.BOND_NONE).when(mService).getBondState(mDevice);

        int result = mBinder.notifyActiveDeviceChangeApplied(mDevice, mSource);

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
        verify(mService, never()).notifyActiveDeviceChangeApplied(any());
    }

    @Test
    public void notifyActiveDeviceChangeApplied_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).when(mService).getBondState(mDevice);

        mBinder.notifyActiveDeviceChangeApplied(mDevice, mSource);

        verify(mService).notifyActiveDeviceChangeApplied(mDevice);
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).when(mService).isAvailable();

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.connectAllEnabledProfiles(mDevice, mSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).connectAllEnabledProfiles(any());
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceNotEnabled_returnsError() {
        // The service is available but not enabled
        doReturn(false).when(mService).isEnabled();

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.connectAllEnabledProfiles(mDevice, mSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).connectAllEnabledProfiles(any());
    }

    @Test
    public void connectAllEnabledProfiles_whenServiceEnabled_callsService() {
        // The service is available and enabled
        doReturn(true).when(mService).isEnabled();

        // Call the method and verify that the underlying service method is called
        mBinder.connectAllEnabledProfiles(mDevice, mSource);
        verify(mService).connectAllEnabledProfiles(mDevice);
    }

    @Test
    public void disconnectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).when(mService).isAvailable();

        // Call the method and verify that it returns an error and doesn't proceed
        int result = mBinder.disconnectAllEnabledProfiles(mDevice, mSource);
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        verify(mService, never()).disconnectAllEnabledProfiles(any(), anyInt());
    }

    @Test
    public void disconnectAllEnabledProfiles_whenServiceAvailable_callsService() {
        // The service is available
        // Call the method and verify that the underlying service method is called
        mBinder.disconnectAllEnabledProfiles(mDevice, mSource);
        verify(mService)
                .disconnectAllEnabledProfiles(
                        mDevice, BluetoothStatusCodes.ERROR_DISCONNECT_REASON_USER_REQUEST);
    }

    @Test(expected = NullPointerException.class)
    public void fetchRemoteUuidsWithSdp_nullDevice_throwsNullPointerException() {
        mBinder.fetchRemoteUuidsWithSdp(null, TRANSPORT_AUTO, mSource);
    }

    @Test
    public void fetchRemoteUuidsWithSdp_serviceUnavailable_returnsFalse() {
        doReturn(false).when(mService).isAvailable();
        assertThat(mBinder.fetchRemoteUuidsWithSdp(mDevice, TRANSPORT_AUTO, mSource)).isFalse();
        verify(mRemoteDevices, never()).fetchUuids(any(), anyInt());
    }

    @Test
    public void
            fetchRemoteUuidsWithSdp_transportNotAuto_noPrivilegedPerm_throwsSecurityException() {
        doThrow(new SecurityException("BT PRIVILEGED permission required"))
                .when(mService)
                .enforceCallingOrSelfPermission(eq(BLUETOOTH_PRIVILEGED), any());
        assertThrows(
                SecurityException.class,
                () -> mBinder.fetchRemoteUuidsWithSdp(mDevice, TRANSPORT_BREDR, mSource));
        verify(mRemoteDevices, never()).fetchUuids(any(), anyInt());
    }

    @Test(expected = NullPointerException.class)
    public void fetchRemoteUuids_nullDevice_throwsNullPointerException() {
        mBinder.fetchRemoteUuids(null, TRANSPORT_AUTO, mSource);
    }

    @Test
    public void fetchRemoteUuids_serviceUnavailable_returnsFalse() {
        doReturn(false).when(mService).isAvailable();
        assertThat(mBinder.fetchRemoteUuids(mDevice, TRANSPORT_AUTO, mSource)).isFalse();
        verify(mRemoteDevices, never()).fetchUuids(any(), anyInt());
    }

    @Test
    public void fetchRemoteUuids_invalidTransport_throwsIllegalArgumentException() {
        // Test with a negative invalid value
        assertThrows(
                IllegalArgumentException.class,
                () -> mBinder.fetchRemoteUuids(mDevice, INVALID_TRANSPORT_NEGATIVE, mSource));

        // Test with a positive out-of-range value
        assertThrows(
                IllegalArgumentException.class,
                () -> mBinder.fetchRemoteUuids(mDevice, INVALID_TRANSPORT_POSITIVE, mSource));

        // Verify that the call does not reach the RemoteDevices
        verify(mRemoteDevices, never()).fetchUuids(any(), anyInt());
    }

    @Test
    public void fetchRemoteUuids_validTransports_doesNotThrowIllegalArgumentException() {
        // This test ensures that for valid transport types, no IllegalArgumentException is thrown.

        // Call with TRANSPORT_AUTO
        mBinder.fetchRemoteUuids(mDevice, TRANSPORT_AUTO, mSource);
        verify(mRemoteDevices).fetchUuids(eq(mDevice), eq(TRANSPORT_AUTO));
        Mockito.reset(mRemoteDevices);

        // Call with TRANSPORT_BREDR
        mBinder.fetchRemoteUuids(mDevice, TRANSPORT_BREDR, mSource);
        verify(mRemoteDevices).fetchUuids(eq(mDevice), eq(TRANSPORT_BREDR));
        Mockito.reset(mRemoteDevices);

        // Call with TRANSPORT_LE
        mBinder.fetchRemoteUuids(mDevice, TRANSPORT_LE, mSource);
        verify(mRemoteDevices).fetchUuids(eq(mDevice), eq(TRANSPORT_LE));
        Mockito.reset(mRemoteDevices);
    }

    @Test
    public void registerHciVendorSpecificCallback_nullAclHandles_throwsNullPointerException() {
        IBluetoothHciVendorSpecificCallback callback =
                mock(IBluetoothHciVendorSpecificCallback.class);
        int[] eventCodes = new int[] {0x01};

        assertThrows(
                NullPointerException.class,
                () -> mBinder.registerHciVendorSpecificCallback(callback, eventCodes, null));
    }

    @Test
    public void
            registerHciVendorSpecificCallback_invalidAclHandle_throwsIllegalArgumentException() {
        IBluetoothHciVendorSpecificCallback callback =
                mock(IBluetoothHciVendorSpecificCallback.class);
        int[] eventCodes = new int[] {0x01};

        // Test with handle <= 0
        int[] invalidAclHandlesZero = new int[] {0x01, 0};
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mBinder.registerHciVendorSpecificCallback(
                                callback, eventCodes, invalidAclHandlesZero));

        int[] invalidAclHandlesNegative = new int[] {0x01, -1};
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mBinder.registerHciVendorSpecificCallback(
                                callback, eventCodes, invalidAclHandlesNegative));

        // Test with handle > 0xfff
        int[] invalidAclHandlesTooLarge = new int[] {0x01, 0x1000};
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mBinder.registerHciVendorSpecificCallback(
                                callback, eventCodes, invalidAclHandlesTooLarge));
    }

    @Test
    public void registerHciVendorSpecificCallback_validArgs_callsDispatcherRegister() {
        IBluetoothHciVendorSpecificCallback callback =
                mock(IBluetoothHciVendorSpecificCallback.class);
        int[] eventCodes = new int[] {0x01, 0x02};
        int[] aclHandles = new int[] {0x01, 0x02};

        mBinder.registerHciVendorSpecificCallback(callback, eventCodes, aclHandles);

        Set<Integer> expectedEventCodes = Set.of(0x01, 0x02);
        Set<Integer> expectedAclHandles = Set.of(0x01, 0x02);
        verify(mDispatcher).register(eq(callback), eq(expectedEventCodes), eq(expectedAclHandles));
    }
}
