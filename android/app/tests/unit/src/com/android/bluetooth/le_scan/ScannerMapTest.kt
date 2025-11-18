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
import android.os.Binder
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
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
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var scannerCallback: IScannerCallback

    @Before
    fun setUp() {
        adapterService.mockGetSystemService<BatteryStatsManager>()
        adapterService.mockPackageManager(packageManager)
        doReturn(APP_NAME).whenever(packageManager).getNameForUid(any())
    }

    @Test
    fun getByMethodsWithPii() {
        val scannerMap = ScannerMap()
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val info = ScanController.PendingIntentInfo(intent, null, null, APP_NAME, UID, PID)
        val uuid = UUID.randomUUID()
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app =
            scannerMap.addWithPendingIntent(
                APP_NAME,
                uuid,
                mock(UserHandle::class.java),
                source,
                info,
                scanSettings,
                filters,
                adapterService,
            )
        app.id = SCANNER_ID

        assertThat(scannerMap.getById(SCANNER_ID)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getByUuid(uuid)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getByPendingIntentInfo(intent)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull()
        assertThat(scannerMap.getAppScanStatsByUid(UID)).isNotNull()
    }

    @Test
    fun getByMethodsWithoutPii() {
        val scannerMap = ScannerMap()
        val uuid = UUID.randomUUID()
        val appUid = Binder.getCallingUid()
        val appPid = Binder.getCallingPid()
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app =
            scannerMap.addWithCallback(
                appUid,
                appPid,
                APP_NAME,
                uuid,
                source,
                null,
                scannerCallback,
                scanSettings,
                filters,
                adapterService,
            )
        app.id = SCANNER_ID

        val scannerMapById = scannerMap.getById(SCANNER_ID)
        assertThat(scannerMapById?.name).isEqualTo(APP_NAME)
        assertThat(scannerMapById?.callback).isEqualTo(scannerCallback)
        assertThat(scannerMap.getByUuid(uuid)?.name).isEqualTo(APP_NAME)
        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull()
        assertThat(scannerMap.getAppScanStatsByUid(appUid)).isNotNull()
    }

    @Test
    fun removeById() {
        val scannerMap = ScannerMap()
        val uuid = UUID.randomUUID()
        val appUid = 1234
        val appPid = Binder.getCallingPid()
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val app =
            scannerMap.addWithCallback(
                appUid,
                appPid,
                APP_NAME,
                uuid,
                source,
                null,
                scannerCallback,
                scanSettings,
                filters,
                adapterService,
            )
        app.id = SCANNER_ID

        assertThat(scannerMap.getById(SCANNER_ID)?.name).isEqualTo(APP_NAME)

        scannerMap.remove(SCANNER_ID)
        assertThat(scannerMap.getById(SCANNER_ID)).isNull()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        val scannerMap = ScannerMap()
        val appUid = 1234
        val appPid = Binder.getCallingPid()
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        scannerMap.addWithCallback(
            appUid,
            appPid,
            APP_NAME,
            UUID.randomUUID(),
            source,
            null,
            scannerCallback,
            scanSettings,
            filters,
            adapterService,
        )
        scannerMap.dump(sb, emptyMap())
    }

    companion object {
        private const val APP_NAME = "com.android.what.a.name"
        private const val UID = 12345
        private const val PID = 19435
        private const val SCANNER_ID = 321
    }
}
