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

package com.android.bluetooth.le_scan

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.app.PendingIntent
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.State
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
import android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.TransportBlockFilter
import android.content.AttributionSource
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.WorkSource
import android.permission.PermissionManager
import android.permission.PermissionManager.PERMISSION_GRANTED
import android.permission.PermissionManager.PERMISSION_SOFT_DENIED
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import java.util.function.Supplier
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [ScanBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanBinderTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var locationManager: LocationManager
    @Mock private lateinit var permissionManager: PermissionManager
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var scanController: ScanController

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val device = getTestDevice(89)

    private lateinit var binder: ScanBinder

    @Before
    fun setUp() {
        adapterService.mockPackageManager(context.packageManager)
        doReturn(adapterService)
            .whenever(adapterService)
            .createPackageContextAsUser(any(), any(), any())
        doReturn(context.attributionSource).whenever(adapterService).attributionSource
        doReturn(context.packageName).whenever(source).packageName
        adapterService.mockGetSystemService(locationManager)
        doReturn(true).whenever(locationManager).isLocationEnabledForUser(any())
        adapterService.mockGetSystemService(permissionManager)
        doReturn(PackageManager.PERMISSION_GRANTED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(eq(ACCESS_FINE_LOCATION), any(), any())
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(scanController)
            .doOnScanThread(any())
        doAnswer { invocation ->
                val supplier = invocation.getArgument<Supplier<*>>(0)
                supplier.get()
            }
            .whenever(scanController)
            .fetchOnScanThread<Any>(any(), any())
        doReturn(State.ON).whenever(adapterService).state
        binder = ScanBinder(adapterService, scanController, testModeEnabled = false)
    }

    @Test
    fun registerAndStartScan() {
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource = mock<WorkSource>()

        binder.registerAndStartScan(callback, settings, filters, workSource, source)

        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
        // The callback should not be invoked directly by the binder in the success path
        verify(callback, never()).onScannerRegistered(any<Int>(), any<Int>())
    }

    @Test
    @DisableFlags(Flags.FLAG_EARLY_REJECT_UNAUTHORIZED_SCANS)
    fun registerAndStartScan_doesNotFailEarly() {
        doReturn(false).whenever(locationManager).isLocationEnabledForUser(any())
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource = mock<WorkSource>()
        binder.registerAndStartScan(callback, settings, filters, workSource, source)

        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
    }

    @Test
    @EnableFlags(Flags.FLAG_EARLY_REJECT_UNAUTHORIZED_SCANS)
    fun registerAndStartScan_withoutLocationPermission_failsEarly() {
        doReturn(false).whenever(locationManager).isLocationEnabledForUser(any())
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource = mock<WorkSource>()
        binder.registerAndStartScan(callback, settings, filters, workSource, source)

        // Ensure we fast-fail and invoke the callback without calling registerAndStartScan
        verify(callback).onScannerRegistered(SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, -1)
        verify(scanController, never())
            .registerAndStartScan(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun registerAndStartScan_afterCleanup_callsOnScannerRegisteredFailed() {
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null
        binder.cleanup()

        binder.registerAndStartScan(callback, settings, filters, workSource, source)

        verify(scanController, never())
            .registerAndStartScan(any(), any(), any(), eq(true), any(), any())
        verify(callback).onScannerRegistered(SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, -1)
    }

    @Test
    fun unregisterScanner() {
        val scannerId = 1

        binder.unregisterScanner(scannerId, source)
        verify(scanController).unregisterScanner(scannerId)
    }

    @Test
    fun unregisterScanner_afterCleanup_doesNothing() {
        val scannerId = 1

        binder.cleanup()
        binder.unregisterScanner(scannerId, source)
        verify(scanController, never()).unregisterScanner(scannerId)
    }

    @Test
    fun registerAndStartScan_withDefaultSettings_doesNotEnforcePrivilegedPermission() {
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
        verify(adapterService, never()).enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
    }

    @Test
    fun registerAndStartScan_whenAdapterIsBleOn_enforcesPrivilegedPermission() {
        doReturn(State.BLE_ON).whenever(adapterService).state
        doReturn(PERMISSION_GRANTED)
            .whenever(adapterService)
            .checkCallingOrSelfPermission(BLUETOOTH_PRIVILEGED)
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(adapterService).enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
    }

    @Test
    fun registerAndStartScan_whenAdapterIsBleOn_failsWithoutPrivilegedPermission() {
        doReturn(State.BLE_ON).whenever(adapterService).state
        doReturn(PERMISSION_SOFT_DENIED)
            .whenever(adapterService)
            .checkCallingOrSelfPermission(BLUETOOTH_PRIVILEGED)
        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(callback).onScannerRegistered(SCAN_FAILED_INTERNAL_ERROR, -1)
        verify(adapterService, never())
            .enforceCallingOrSelfPermission(eq(BLUETOOTH_PRIVILEGED), any())
        verify(scanController, never())
            .registerAndStartScan(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun registerAndStartScan_withAmbientDiscoveryMode_enforcesPrivilegedPermission() {
        val callback = mock<IScannerCallback>()
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY).build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(adapterService).enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
    }

    @Test
    fun registerAndStartScan_withBatchScanTruncated_enforcesPrivilegedPermission() {
        val callback = mock<IScannerCallback>()
        val settings =
            ScanSettings.Builder()
                .setReportDelay(1000)
                .setScanResultType(ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED)
                .build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(adapterService).enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
    }

    @Test
    fun registerAndStartScan_withBatchScanFull_doesNotEnforcePrivilegedPermission() {
        val callback = mock<IScannerCallback>()
        val settings =
            ScanSettings.Builder()
                .setReportDelay(1000)
                .setScanResultType(ScanSettings.SCAN_RESULT_TYPE_FULL)
                .build()
        val filters = listOf<ScanFilter>()
        val workSource: WorkSource? = null

        binder.registerAndStartScan(callback, settings, filters, workSource, source)
        verify(adapterService, never()).enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        verify(scanController)
            .registerAndStartScan(callback, workSource, source, true, settings, filters)
    }

    @Test
    fun registerPiAndStartScan() {
        val intent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()

        binder.registerPiAndStartScan(intent, settings, filters, source)
        verify(scanController).registerPiAndStartScan(intent, settings, filters, source)
    }

    @Test
    fun stopScan_withScannerId() {
        val scannerId = 1

        binder.stopScan(scannerId, source)
        verify(scanController).stopScan(scannerId)
    }

    @Test
    fun stopScan_withIntent() {
        val intent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

        binder.stopScanForIntent(intent, source)
        verify(scanController).stopScan(intent)
    }

    @Test
    fun flushPendingBatchResults() {
        val scannerId = 1

        binder.flushPendingBatchResults(scannerId, source)
        verify(scanController).flushPendingBatchResults(scannerId)
    }

    @Test
    fun registerSync() {
        val scanResult = mock<ScanResult>()
        val skip = 1
        val timeout = 2
        val callback = mock<IPeriodicAdvertisingCallback>()

        binder.registerSync(scanResult, skip, timeout, callback, source)
        verify(scanController).registerSync(scanResult, skip, timeout, callback)
    }

    @Test
    fun unregisterSync() {
        val callback = mock<IPeriodicAdvertisingCallback>()

        binder.unregisterSync(callback, source)
        verify(scanController).unregisterSync(callback)
    }

    @Test
    fun transferSync() {
        val serviceData = 1
        val syncHandle = 2

        binder.transferSync(device, serviceData, syncHandle, source)
        verify(scanController).transferSync(device, serviceData, syncHandle)
    }

    @Test
    fun transferSetInfo() {
        val serviceData = 1
        val advHandle = 2
        val callback = mock<IPeriodicAdvertisingCallback>()

        binder.transferSetInfo(device, serviceData, advHandle, callback, source)
        verify(scanController).transferSetInfo(device, serviceData, advHandle, callback)
    }

    @Test
    fun numHwTrackFiltersAvailable() {
        binder.numHwTrackFiltersAvailable(source)
        verify(scanController).numHwTrackFiltersAvailable()
    }

    @Test
    fun registerAndStartScan_withTdsFilterAndSupported_doesNotThrow() {
        doReturn(BluetoothStatusCodes.FEATURE_SUPPORTED)
            .whenever(adapterService)
            .offloadedTransportDiscoveryDataScanSupported

        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val transportBlockFilter = mock<TransportBlockFilter>()
        val filter = ScanFilter.Builder().setTransportBlockFilter(transportBlockFilter).build()
        val filters = listOf(filter)

        binder.registerAndStartScan(callback, settings, filters, null, source)
        verify(scanController).registerAndStartScan(callback, null, source, true, settings, filters)
    }

    @Test
    fun registerAndStartScan_withTdsFilterAndNotSupported_throwsException() {
        doReturn(BluetoothStatusCodes.FEATURE_NOT_SUPPORTED)
            .whenever(adapterService)
            .offloadedTransportDiscoveryDataScanSupported

        val callback = mock<IScannerCallback>()
        val settings = ScanSettings.Builder().build()
        val transportBlockFilter = mock<TransportBlockFilter>()
        val filter = ScanFilter.Builder().setTransportBlockFilter(transportBlockFilter).build()
        val filters = listOf(filter)

        assertThrows(IllegalArgumentException::class.java) {
            binder.registerAndStartScan(callback, settings, filters, null, source)
        }
    }
}
