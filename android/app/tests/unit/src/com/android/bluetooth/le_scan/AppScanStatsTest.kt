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

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [AppScanStats]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AppScanStatsTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var workSourceUtil: WorkSourceUtil
    @Mock private lateinit var metricsReporter: ScanMetricsReporter
    @Mock private lateinit var timeProvider: TimeProvider

    @Before
    fun setUp() {
        doReturn(ScanUtil.DEFAULT_SCAN_QUOTA_COUNT).whenever(adapterService).scanQuotaCount
    }

    @Test
    fun constructor_initializesCorrectly() {
        val name = "appName"
        val uid = 1234
        val pid = 5678
        val appScanStats =
            AppScanStats(
                uid,
                pid,
                name,
                workSourceUtil,
                adapterService,
                metricsReporter,
                timeProvider,
            )
        assertThat(appScanStats.isScanning()).isFalse()
    }

    @Test
    fun dump_doesNotCrash() {
        val name = "appName"
        val uid = 1234
        val pid = 5678
        val appScanStats =
            AppScanStats(
                uid,
                pid,
                name,
                workSourceUtil,
                adapterService,
                metricsReporter,
                timeProvider,
            )

        val app1 = mock<ScannerApp>()
        doReturn(101).whenever(app1).scannerId
        doReturn(UUID.randomUUID()).whenever(app1).uuid
        doReturn("appTag1").whenever(app1).attributionTag

        val app2 = mock<ScannerApp>()
        doReturn(102).whenever(app2).scannerId
        doReturn(UUID.randomUUID()).whenever(app2).uuid
        doReturn(null).whenever(app2).attributionTag

        AppScanStats.setScreenState(true)
        appScanStats.isRegistered = true
        appScanStats.recordBatchAlarmScheduled()

        // Scan 1: Ongoing, Suspended, Filtered, Opp, Callback, Timeout, High Importance
        val settings1 =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC).build()
        val filters1 = listOf(ScanFilter.Builder().setDeviceName("TestName").build())
        appScanStats.appImportance = IMPORTANCE_FOREGROUND_SERVICE - 1
        appScanStats.recordScanStart(
            settings = settings1,
            filters = filters1,
            isFilterScan = true,
            isCallbackScan = true,
            scannerId = 1,
            attributionTag = "tag1",
        )
        appScanStats.recordScanSuspend(1)
        appScanStats.setScanTimeout(1)

        // Scan 2: Completed, Batch, Low Power, PI, Normal Importance
        val settings2 =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(5000) // Makes it a batch scan
                .build()
        appScanStats.appImportance = IMPORTANCE_FOREGROUND_SERVICE
        appScanStats.recordScanStart(
            settings = settings2,
            filters = emptyList(),
            isFilterScan = false,
            isCallbackScan = false,
            scannerId = 2,
            attributionTag = null,
        )
        appScanStats.recordScanStop(2)

        // Scan 3: Ongoing, Active, AutoBatch, Balanced, CB, Low Importance
        val settings3 = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        appScanStats.appImportance = IMPORTANCE_FOREGROUND_SERVICE + 1
        appScanStats.recordScanStart(
            settings = settings3,
            filters = emptyList(),
            isFilterScan = false,
            isCallbackScan = true,
            scannerId = 3,
            attributionTag = "tag3",
        )
        appScanStats.setAutoBatchScan(3, true)

        // Scan 4: Completed, Suspended/Resumed, Low Latency
        val settings4 =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        appScanStats.appImportance = IMPORTANCE_FOREGROUND_SERVICE + 1
        appScanStats.recordScanStart(
            settings = settings4,
            filters = emptyList(),
            isFilterScan = false,
            isCallbackScan = true,
            scannerId = 4,
            attributionTag = "tag4",
        )
        appScanStats.recordScanSuspend(4)
        appScanStats.recordScanResume(4)
        appScanStats.recordScanStop(4)

        // Scan 5: Ongoing, Ambient Discovery
        val settings5 =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY).build()
        appScanStats.recordScanStart(
            settings = settings5,
            filters = emptyList(),
            isFilterScan = false,
            isCallbackScan = true,
            scannerId = 5,
            attributionTag = "tag5",
        )

        appScanStats.addResults(1, 50, true)
        AppScanStats.setScreenState(false)
        appScanStats.addResults(2, 60, true)
        appScanStats.addResults(1, 70, true)
        AppScanStats.setScreenState(true)

        appScanStats.dump(listOf(app1, app2))
    }
}
