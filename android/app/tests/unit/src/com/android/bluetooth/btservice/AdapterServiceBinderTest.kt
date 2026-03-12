/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.btservice

import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_AUTO
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.IBluetoothActivityEnergyInfoListener
import android.bluetooth.IBluetoothHciVendorSpecificCallback
import android.bluetooth.IBluetoothOobDataCallback
import android.content.AttributionSource
import android.content.Context
import android.os.Bundle
import android.os.ParcelUuid
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.mockGetSystemService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [AdapterServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdapterServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: AdapterService
    @Mock private lateinit var adapterProperties: AdapterProperties
    @Mock private lateinit var device: BluetoothDevice
    @Mock private lateinit var remoteDevices: RemoteDevices
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var dispatcher: BluetoothHciVendorSpecificDispatcher

    private lateinit var binder: AdapterServiceBinder

    @Before
    fun setUp() {
        doReturn(adapterProperties).whenever(service).adapterProperties
        doReturn(remoteDevices).whenever(service).remoteDevices
        doReturn(dispatcher).whenever(service).bluetoothHciVendorSpecificDispatcher
        doReturn(true).whenever(service).isAvailable
        // Default for other permission checks if any
        doNothing().whenever(service).enforceCallingOrSelfPermission(any(), any())

        // Setup mock UserManager to be returned by service
        doReturn(userManager).whenever(service).getSystemService(Context.USER_SERVICE)
        service.mockGetSystemService<UserManager>(userManager)
        // Default: Simulate caller is system/active
        mockCallerIsSystemOrActive(true)

        binder = AdapterServiceBinder(service)
    }

    private fun mockCallerIsSystemOrActive(isSystemOrActive: Boolean) {
        // This is an approximation. The real static method is more complex.
        doReturn(isSystemOrActive).whenever(userManager).isSystemUser
    }

    @Test
    fun cancelDiscovery_whenServiceNotAvailable_returnsFalse() {
        // Setup: Simulate the service being unavailable.
        doReturn(false).whenever(service).isAvailable

        val result = binder.cancelDiscovery(source)

        assertThat(result).isFalse()
        verify(service, never()).cancelDiscovery(any())
    }

    @Test
    fun dump() {
        val fd = FileDescriptor()
        val args = arrayOf<String>()
        binder.dump(fd, args)
        verify(service).dump(any(), any(), any())
    }

    @Test
    fun dumpWhenNotAvailable() {
        val fd = FileDescriptor()
        val args = arrayOf<String>()
        doReturn(false).whenever(service).isAvailable

        binder.dump(fd, args)

        verify(service, never()).dump(any(), any(), any())
    }

    @Test
    fun generateLocalOobData() {
        val transport = 0
        val cb = mock<IBluetoothOobDataCallback>()

        binder.generateLocalOobData(transport, cb, source)

        verify(service).generateLocalOobData(transport, cb)
    }

    @Test
    fun generateLocalOobDataWhenNotAvailable() {
        val transport = 0
        val cb = mock<IBluetoothOobDataCallback>()
        doReturn(false).whenever(service).isAvailable

        binder.generateLocalOobData(transport, cb, source)

        verify(service, never()).generateLocalOobData(transport, cb)
    }

    @Test
    fun getLeMaximumAdvertisingDataLength() {
        binder.leMaximumAdvertisingDataLength
        verify(service).leMaximumAdvertisingDataLength
    }

    @Test
    fun getScanMode() {
        binder.getScanMode(source)
        verify(service).scanMode
    }

    @Test
    fun isActivityAndEnergyReportingSupported() {
        binder.isActivityAndEnergyReportingSupported
        verify(adapterProperties).isActivityAndEnergyReportingSupported
    }

    @Test
    fun isLe2MPhySupported() {
        binder.isLe2MPhySupported
        verify(service).isLe2MPhySupported
    }

    @Test
    fun isLeCodedPhySupported() {
        binder.isLeCodedPhySupported
        verify(service).isLeCodedPhySupported
    }

    @Test
    fun isLeExtendedAdvertisingSupported() {
        binder.isLeExtendedAdvertisingSupported
        verify(service).isLeExtendedAdvertisingSupported
    }

    @Test
    fun removeActiveDevice() {
        val profiles = BluetoothAdapter.ACTIVE_DEVICE_ALL
        binder.removeActiveDevice(profiles, source)
        verify(service).setActiveDevice(null, profiles)
    }

    @Test
    fun requestActivityInfo() {
        val listener = mock<IBluetoothActivityEnergyInfoListener>()
        binder.requestActivityInfo(listener, source)
        verify(service).requestActivityInfo()
        verify(listener).onBluetoothActivityEnergyInfoAvailable(anyOrNull())
    }

    @Test
    fun retrievePendingSocketForServiceRecord() {
        val uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB")
        binder.retrievePendingSocketForServiceRecord(uuid, source)
        verify(service).retrievePendingSocketForServiceRecord(uuid, source)
    }

    @Test
    fun stopRfcommListener() {
        val uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB")
        binder.stopRfcommListener(uuid, source)
        verify(service).stopRfcommListener(uuid, source)
    }

    @Test
    fun setPreferredAudioProfiles_deviceNotBonded_returnsError() {
        doReturn(BluetoothDevice.BOND_NONE).whenever(service).getBondState(device)

        val result = binder.setPreferredAudioProfiles(device, Bundle(), source)

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
        verify(service, never()).setPreferredAudioProfiles(any(), any())
    }

    @Test
    fun setPreferredAudioProfiles_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(service).getBondState(device)
        val bundle = Bundle()

        binder.setPreferredAudioProfiles(device, bundle, source)

        verify(service).setPreferredAudioProfiles(device, bundle)
    }

    @Test
    fun getPreferredAudioProfiles_deviceNotBonded_returnsEmptyBundle() {
        doReturn(BluetoothDevice.BOND_NONE).whenever(service).getBondState(device)

        val result = binder.getPreferredAudioProfiles(device, source)

        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(Bundle.EMPTY)
        verify(service, never()).getPreferredAudioProfiles(any())
    }

    @Test
    fun getPreferredAudioProfiles_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(service).getBondState(device)

        binder.getPreferredAudioProfiles(device, source)

        verify(service).getPreferredAudioProfiles(device)
    }

    @Test
    fun notifyActiveDeviceChangeApplied_deviceNotBonded_returnsError() {
        doReturn(BluetoothDevice.BOND_NONE).whenever(service).getBondState(device)

        val result = binder.notifyActiveDeviceChangeApplied(device, source)

        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
        verify(service, never()).notifyActiveDeviceChangeApplied(any())
    }

    @Test
    fun notifyActiveDeviceChangeApplied_deviceBonded_callsService() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(service).getBondState(device)

        binder.notifyActiveDeviceChangeApplied(device, source)

        verify(service).notifyActiveDeviceChangeApplied(device)
    }

    @Test
    fun connectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).whenever(service).isAvailable

        // Call the method and verify that it returns an error and doesn't proceed
        val result = binder.connectAllEnabledProfiles(device, source)
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED)
        verify(service, never()).connectAllEnabledProfiles(any())
    }

    @Test
    fun connectAllEnabledProfiles_whenServiceNotEnabled_returnsError() {
        // The service is available but not enabled
        doReturn(false).whenever(service).isEnabled

        // Call the method and verify that it returns an error and doesn't proceed
        val result = binder.connectAllEnabledProfiles(device, source)
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED)
        verify(service, never()).connectAllEnabledProfiles(any())
    }

    @Test
    fun connectAllEnabledProfiles_whenServiceEnabled_callsService() {
        // The service is available and enabled
        doReturn(true).whenever(service).isEnabled

        // Call the method and verify that the underlying service method is called
        binder.connectAllEnabledProfiles(device, source)
        verify(service).connectAllEnabledProfiles(device)
    }

    @Test
    fun disconnectAllEnabledProfiles_whenServiceNotAvailable_returnsError() {
        // The service is not available
        doReturn(false).whenever(service).isAvailable

        // Call the method and verify that it returns an error and doesn't proceed
        val result = binder.disconnectAllEnabledProfiles(device, source)
        assertThat(result).isEqualTo(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED)
        verify(service, never()).disconnectAllEnabledProfiles(any(), anyInt())
    }

    @Test
    fun disconnectAllEnabledProfiles_whenServiceAvailable_callsService() {
        // The service is available
        // Call the method and verify that the underlying service method is called
        binder.disconnectAllEnabledProfiles(device, source)
        verify(service)
            .disconnectAllEnabledProfiles(
                device,
                BluetoothStatusCodes.ERROR_DISCONNECT_REASON_USER_REQUEST,
            )
    }

    @Test(expected = NullPointerException::class)
    fun fetchRemoteUuidsWithSdp_nullDevice_throwsNullPointerException() {
        binder.fetchRemoteUuidsWithSdp(null, TRANSPORT_AUTO, source)
    }

    @Test
    fun fetchRemoteUuidsWithSdp_serviceUnavailable_returnsFalse() {
        doReturn(false).whenever(service).isAvailable
        assertThat(binder.fetchRemoteUuidsWithSdp(device, TRANSPORT_AUTO, source)).isFalse()
        verify(remoteDevices, never()).fetchUuids(any(), anyInt())
    }

    @Test
    fun fetchRemoteUuidsWithSdp_transportNotAuto_noPrivilegedPerm_throwsSecurityException() {
        doThrow(SecurityException("BT PRIVILEGED permission required"))
            .whenever(service)
            .enforceCallingOrSelfPermission(eq(BLUETOOTH_PRIVILEGED), anyOrNull())
        assertThrows(SecurityException::class.java) {
            binder.fetchRemoteUuidsWithSdp(device, TRANSPORT_BREDR, source)
        }
        verify(remoteDevices, never()).fetchUuids(any(), anyInt())
    }

    @Test(expected = NullPointerException::class)
    fun fetchRemoteUuids_nullDevice_throwsNullPointerException() {
        binder.fetchRemoteUuids(null, TRANSPORT_AUTO, source)
    }

    @Test
    fun fetchRemoteUuids_serviceUnavailable_returnsFalse() {
        doReturn(false).whenever(service).isAvailable
        assertThat(binder.fetchRemoteUuids(device, TRANSPORT_AUTO, source)).isFalse()
        verify(remoteDevices, never()).fetchUuids(any(), anyInt())
    }

    @Test
    fun fetchRemoteUuids_invalidTransport_throwsIllegalArgumentException() {
        // Test with a negative invalid value
        assertThrows(IllegalArgumentException::class.java) {
            binder.fetchRemoteUuids(device, INVALID_TRANSPORT_NEGATIVE, source)
        }

        // Test with a positive out-of-range value
        assertThrows(IllegalArgumentException::class.java) {
            binder.fetchRemoteUuids(device, INVALID_TRANSPORT_POSITIVE, source)
        }

        // Verify that the call does not reach the RemoteDevices
        verify(remoteDevices, never()).fetchUuids(any(), anyInt())
    }

    @Test
    fun fetchRemoteUuids_validTransports_doesNotThrowIllegalArgumentException() {
        // This test ensures that for valid transport types, no IllegalArgumentException is thrown.

        // Call with TRANSPORT_AUTO
        binder.fetchRemoteUuids(device, TRANSPORT_AUTO, source)
        verify(remoteDevices).fetchUuids(eq(device), eq(TRANSPORT_AUTO))
        Mockito.reset(remoteDevices)

        // Call with TRANSPORT_BREDR
        binder.fetchRemoteUuids(device, TRANSPORT_BREDR, source)
        verify(remoteDevices).fetchUuids(eq(device), eq(TRANSPORT_BREDR))
        Mockito.reset(remoteDevices)

        // Call with TRANSPORT_LE
        binder.fetchRemoteUuids(device, TRANSPORT_LE, source)
        verify(remoteDevices).fetchUuids(eq(device), eq(TRANSPORT_LE))
        Mockito.reset(remoteDevices)
    }

    @Test
    fun registerHciVendorSpecificCallback_nullAclHandles_throwsNullPointerException() {
        val callback = mock<IBluetoothHciVendorSpecificCallback>()
        val eventCodes = intArrayOf(0x01)

        assertThrows(NullPointerException::class.java) {
            binder.registerHciVendorSpecificCallback(callback, eventCodes, null)
        }
    }

    @Test
    fun registerHciVendorSpecificCallback_invalidAclHandle_throwsIllegalArgumentException() {
        val callback = mock<IBluetoothHciVendorSpecificCallback>()
        val eventCodes = intArrayOf(0x01)

        // Test with handle <= 0
        val invalidAclHandlesZero = intArrayOf(0x01, 0)
        assertThrows(IllegalArgumentException::class.java) {
            binder.registerHciVendorSpecificCallback(callback, eventCodes, invalidAclHandlesZero)
        }

        val invalidAclHandlesNegative = intArrayOf(0x01, -1)
        assertThrows(IllegalArgumentException::class.java) {
            binder.registerHciVendorSpecificCallback(
                callback,
                eventCodes,
                invalidAclHandlesNegative,
            )
        }

        // Test with handle > 0xfff
        val invalidAclHandlesTooLarge = intArrayOf(0x01, 0x1000)
        assertThrows(IllegalArgumentException::class.java) {
            binder.registerHciVendorSpecificCallback(
                callback,
                eventCodes,
                invalidAclHandlesTooLarge,
            )
        }
    }

    @Test
    fun registerHciVendorSpecificCallback_validArgs_callsDispatcherRegister() {
        val callback = mock<IBluetoothHciVendorSpecificCallback>()
        val eventCodes = intArrayOf(0x01, 0x02)
        val aclHandles = intArrayOf(0x01, 0x02)

        binder.registerHciVendorSpecificCallback(callback, eventCodes, aclHandles)

        val expectedEventCodes = setOf(0x01, 0x02)
        val expectedAclHandles = setOf(0x01, 0x02)
        verify(dispatcher).register(eq(callback), eq(expectedEventCodes), eq(expectedAclHandles))
    }

    companion object {
        // Transport constants from BluetoothDevice
        private const val INVALID_TRANSPORT_NEGATIVE = -1
        private const val INVALID_TRANSPORT_POSITIVE = 3
    }
}
