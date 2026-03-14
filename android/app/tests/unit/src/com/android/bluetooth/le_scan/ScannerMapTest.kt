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
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryStatsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [ScannerMap]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScannerMapTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var batteryStatsManager: BatteryStatsManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var scannerCallback: IScannerCallback

    private lateinit var scannerMap: ScannerMap

    @Before
    fun setUp() {
        adapterService.mockPackageManager(packageManager)
        doReturn(APP_NAME).whenever(packageManager).getNameForUid(any())
        doReturn(UID).whenever(source).uid
        doReturn(PID).whenever(source).pid
        scannerMap = ScannerMap(adapterService, batteryStatsManager)
    }

    @Test
    fun getByMethodsWithPii() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app = scannerMap.addWithPendingIntent(source, intent, scanSettings, filters)
        app.scannerId = SCANNER_ID

        assertThat(scannerMap.getById(SCANNER_ID)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getByUuid(app.uuid)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getByPendingIntent(intent)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isEqualTo(app.appScanStats)
        assertThat(scannerMap.getAppScanStatsByUid(UID)).isEqualTo(app.appScanStats)
    }

    @Test
    fun getByMethodsWithoutPii() {
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app = scannerMap.addWithCallback(source, null, scannerCallback, scanSettings, filters)
        app.scannerId = SCANNER_ID

        val scannerMapById = scannerMap.getById(SCANNER_ID)
        assertThat(scannerMapById?.name).isEqualTo(APP_NAME)
        assertThat(scannerMapById?.callback).isEqualTo(scannerCallback)
        assertThat(scannerMap.getByUuid(app.uuid)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isEqualTo(app.appScanStats)
        assertThat(scannerMap.getAppScanStatsByUid(UID)).isEqualTo(app.appScanStats)
    }

    @Test
    fun removeById() {
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app = scannerMap.addWithCallback(source, null, scannerCallback, scanSettings, filters)
        app.scannerId = SCANNER_ID

        assertThat(scannerMap.getById(SCANNER_ID)?.name).isEqualTo(APP_NAME)

        scannerMap.remove(SCANNER_ID)
        assertThat(scannerMap.getById(SCANNER_ID)).isNull()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        scannerMap.addWithCallback(source, null, scannerCallback, scanSettings, filters)
        scannerMap.dump(sb)
    }

    companion object {
        private const val APP_NAME = "com.android.what.a.name"
        private const val UID = 12345
        private const val PID = 19435
        private const val SCANNER_ID = 321
    }
}
