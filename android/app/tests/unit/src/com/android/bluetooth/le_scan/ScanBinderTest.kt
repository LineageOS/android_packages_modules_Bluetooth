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

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.WorkSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestUtils.MockitoRule
import com.android.bluetooth.TestUtils.getTestDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/** Test cases for [ScanBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanBinderTest {

    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var scanController: ScanController

    private val attributionSource =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSystemService(BluetoothManager::class.java)
            .adapter
            .attributionSource
    private val device: BluetoothDevice = getTestDevice(89)
    private lateinit var binder: ScanBinder

    @Before
    fun setUp() {
        binder = ScanBinder(scanController)
    }

    @Test
    fun registerScanner() {
        val callback = mock(IScannerCallback::class.java)
        val workSource = mock(WorkSource::class.java)

        binder.registerScanner(callback, workSource, attributionSource)
        verify(scanController).registerScanner(callback, workSource, attributionSource)
    }

    @Test
    fun unregisterScanner() {
        val scannerId = 1

        binder.unregisterScanner(scannerId, attributionSource)
        verify(scanController).unregisterScanner(scannerId, attributionSource)
    }

    @Test
    fun startScan() {
        val scannerId = 1
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()

        binder.startScan(scannerId, settings, filters, attributionSource)
        verify(scanController).startScan(scannerId, settings, filters, attributionSource)
    }

    @Test
    fun startScanForIntent() {
        val intent =
            PendingIntent.getBroadcast(
                InstrumentationRegistry.getInstrumentation().targetContext,
                0,
                Intent(),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val settings = ScanSettings.Builder().build()
        val filters = listOf<ScanFilter>()

        binder.startScanForIntent(intent, settings, filters, attributionSource)
        verify(scanController).registerPiAndStartScan(intent, settings, filters, attributionSource)
    }

    @Test
    fun stopScan_withScannerId() {
        val scannerId = 1

        binder.stopScan(scannerId, attributionSource)
        verify(scanController).stopScan(scannerId, attributionSource)
    }

    @Test
    fun stopScan_withIntent() {
        val intent =
            PendingIntent.getBroadcast(
                InstrumentationRegistry.getInstrumentation().targetContext,
                0,
                Intent(),
                PendingIntent.FLAG_IMMUTABLE,
            )

        binder.stopScanForIntent(intent, attributionSource)
        verify(scanController).stopScan(intent, attributionSource)
    }

    @Test
    fun flushPendingBatchResults() {
        val scannerId = 1

        binder.flushPendingBatchResults(scannerId, attributionSource)
        verify(scanController).flushPendingBatchResults(scannerId, attributionSource)
    }

    @Test
    fun registerSync() {
        val scanResult = mock(ScanResult::class.java)
        val skip = 1
        val timeout = 2
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        binder.registerSync(scanResult, skip, timeout, callback, attributionSource)
        verify(scanController).registerSync(scanResult, skip, timeout, callback, attributionSource)
    }

    @Test
    fun unregisterSync() {
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        binder.unregisterSync(callback, attributionSource)
        verify(scanController).unregisterSync(callback, attributionSource)
    }

    @Test
    fun transferSync() {
        val serviceData = 1
        val syncHandle = 2

        binder.transferSync(device, serviceData, syncHandle, attributionSource)
        verify(scanController).transferSync(device, serviceData, syncHandle, attributionSource)
    }

    @Test
    fun transferSetInfo() {
        val serviceData = 1
        val advHandle = 2
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        binder.transferSetInfo(device, serviceData, advHandle, callback, attributionSource)
        verify(scanController)
            .transferSetInfo(device, serviceData, advHandle, callback, attributionSource)
    }

    @Test
    fun numHwTrackFiltersAvailable() {
        binder.numHwTrackFiltersAvailable(attributionSource)
        verify(scanController).numHwTrackFiltersAvailable(attributionSource)
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }
}
