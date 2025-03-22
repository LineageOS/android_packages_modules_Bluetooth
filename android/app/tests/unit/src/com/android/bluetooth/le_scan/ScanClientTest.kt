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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [ScanClient]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanClientTest {

    private val scanClient = ScanClient(1)

    @Test
    fun constructor() {
        val scanClientWithDefaultSettings = ScanClient(1)
        assertThat(scanClientWithDefaultSettings.mSettings.scanMode)
            .isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    fun constructor_withFilters() {
        val filters = listOf(ScanFilter.Builder().build())
        val scanSettings = ScanSettings.Builder().build()
        val scanClientWithFilters = ScanClient(1, scanSettings, filters)
        assertThat(scanClientWithFilters.mFilters).isEqualTo(filters)
    }

    @Test
    fun constructor_withAppUid() {
        val appUid = 1234
        val scanSettings = ScanSettings.Builder().build()
        val scanClientWithAppUid = ScanClient(1, scanSettings, null, appUid)
        assertThat(scanClientWithAppUid.mAppUid).isEqualTo(appUid)
    }

    @Test
    fun equals() {
        val scanSettings = ScanSettings.Builder().build()
        EqualsTester()
            .addEqualityGroup(
                ScanClient(1, scanSettings, null),
                ScanClient(1, scanSettings, null),
                ScanClient(1, scanSettings, listOf(ScanFilter.Builder().build())),
                ScanClient(1, scanSettings, null, 1234),
                ScanClient(1, scanSettings, null, 5678),
            )
            .addEqualityGroup(ScanClient(2, scanSettings, null))
            .testEquals()
    }

    @Test
    fun toString_doesNotCrash() {
        scanClient.toString()
    }

    @Test
    fun updateScanMode() {
        val newScanMode = ScanSettings.SCAN_MODE_BALANCED
        val updated = scanClient.updateScanMode(newScanMode)
        assertThat(updated).isTrue()
        assertThat(scanClient.mSettings.scanMode).isEqualTo(newScanMode)

        val sameScanMode = scanClient.mSettings.scanMode
        val notUpdated = scanClient.updateScanMode(sameScanMode)
        assertThat(notUpdated).isFalse()
        assertThat(scanClient.mSettings.scanMode).isEqualTo(sameScanMode)
    }
}
