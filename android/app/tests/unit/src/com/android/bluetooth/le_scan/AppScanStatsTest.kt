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

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.BatteryStatsManager
import android.os.WorkSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.FakeTimeProvider
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

/** Test cases for [AppScanStats]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AppScanStatsTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var batteryStatsManager: BatteryStatsManager
    @Mock private lateinit var scanController: ScanController

    private val timeProvider = FakeTimeProvider()

    @Before
    fun setUp() {
        mockGetSystemService(adapterService, BatteryStatsManager::class.java, batteryStatsManager)
    }

    @Test
    fun constructor_initializesCorrectly() {
        val name = "appName"
        val source: WorkSource? = null
        val uid = 1234
        val appScanStats =
            AppScanStats(name, source, uid, adapterService, scanController, timeProvider)

        assertThat(appScanStats.mScanController).isEqualTo(scanController)
        assertThat(appScanStats.isScanning).isFalse()
    }

    @Test
    fun dump_doesNotCrash() {
        val name = "appName"
        val source: WorkSource? = null
        val uid = 1234
        val appScanStats =
            AppScanStats(name, source, uid, adapterService, scanController, timeProvider)

        val settings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("TestName").build())
        val isFilterScan = false
        val isCallbackScan = false
        val scannerId = 0

        appScanStats.recordScanStart(
            settings,
            filters,
            isFilterScan,
            isCallbackScan,
            scannerId,
            "tag",
        )
        appScanStats.mIsRegistered = true

        val stringBuilder = StringBuilder()
        appScanStats.dump(stringBuilder, emptyList())
    }
}
