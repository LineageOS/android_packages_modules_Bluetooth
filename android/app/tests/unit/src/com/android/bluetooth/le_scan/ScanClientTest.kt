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
    @Test
    fun constructor() {
        val appUid = 1234
        val scannerId = 1
        val scanClientWithFilters = ScanClient(appUid, scannerId)
        assertThat(scanClientWithFilters.appUid).isEqualTo(appUid)
        assertThat(scanClientWithFilters.scannerId).isEqualTo(scannerId)
    }

    @Test
    fun constructorWithBasicSettingsAndFilters() {
        val appUid = 1234
        val scannerId = 1
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val scanClientWithFilters = ScanClient(appUid, scannerId, scanSettings, filters)
        assertThat(scanClientWithFilters.settings).isEqualTo(scanSettings)
        assertThat(scanClientWithFilters.filters).isEqualTo(filters)
    }

    @Test
    fun updateScanMode() {
        val appUid = 1234
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val scanClient = ScanClient(appUid, 1, scanSettings, filters)

        val newScanMode = ScanSettings.SCAN_MODE_BALANCED
        val updated = scanClient.updateScanMode(newScanMode)
        assertThat(updated).isTrue()
        assertThat(scanClient.settings.scanMode).isEqualTo(newScanMode)

        val sameScanMode = scanClient.settings.scanMode
        val notUpdated = scanClient.updateScanMode(sameScanMode)
        assertThat(notUpdated).isFalse()
        assertThat(scanClient.settings.scanMode).isEqualTo(sameScanMode)
    }

    @Test
    fun equals() {
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        EqualsTester()
            .addEqualityGroup(
                ScanClient(1234, 1),
                ScanClient(5678, 1),
                ScanClient(1234, 1, scanSettings, filters),
                ScanClient(5678, 1, scanSettings, filters),
                ScanClient(1234, 1, scanSettings, listOf(ScanFilter.Builder().build())),
                ScanClient(5678, 1, scanSettings, listOf(ScanFilter.Builder().build())),
            )
            .addEqualityGroup(ScanClient(1234, 2, scanSettings, filters))
            .testEquals()
    }

    @Test
    fun toString_doesNotCrash() {
        val appUid = 1234
        val scanSettings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val scanClient = ScanClient(appUid, 1, scanSettings, filters)
        scanClient.toString()
    }
}
