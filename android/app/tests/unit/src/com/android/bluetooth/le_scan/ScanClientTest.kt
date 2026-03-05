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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.UserHandle
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [ScanClient]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanClientTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Test
    fun constructor_external() {
        val id = 5
        val uid = 1000
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val userHandle = UserHandle.getUserHandleForUid(uid)

        val client = ScanClient(uid, id, settings, emptyList(), userHandle)

        assertThat(client.scannerId).isEqualTo(id)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.scanModeApp).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        assertThat(client.userHandle).isEqualTo(userHandle)
    }

    @Test
    fun constructor_internal() {
        val id = 10
        val uid = 1002
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val userHandle = UserHandle.getUserHandleForUid(uid)

        val client =
            ScanClient(
                uid,
                id,
                settings,
                emptyList(),
                userHandle,
                hasNetworkSettingsPermission = true,
                hasNetworkSetupWizardPermission = true,
                hasScanWithoutLocationPermission = true,
            )

        assertThat(client.scannerId).isEqualTo(id)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.scanModeApp).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
        assertThat(client.userHandle).isEqualTo(userHandle)
        assertThat(client.isInternal).isTrue()
    }

    @Test
    fun constructor_pendingIntentInfo() {
        val id = 77
        val uid = 54321
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val filters = emptyList<ScanFilter>()
        val userHandle = UserHandle.getUserHandleForUid(uid)

        val pendingIntent = mock<PendingIntent>()
        val piInfo =
            ScanController.PendingIntentInfo(
                pendingIntent,
                settings,
                filters,
                "com.test.package",
                uid,
                999,
            )

        val app = mock<ScannerApp>()
        doReturn(userHandle).whenever(app).userHandle
        doReturn(true).whenever(app).hasLocationPermission
        doReturn(true).whenever(app).eligibleForSanitizedExposureNotification
        doReturn(false).whenever(app).hasDisavowedLocation

        val client = ScanClient(id, piInfo, app)

        assertThat(client.scannerId).isEqualTo(id)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.filters).isEqualTo(filters)
        assertThat(client.userHandle).isEqualTo(userHandle)
        assertThat(client.hasLocationPermission).isTrue()
        assertThat(client.isEligibleForSanitizedExposureNotification).isTrue()
    }

    @Test
    fun updateScanMode() {
        val client =
            ScanClient(
                1000,
                1,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                emptyList(),
            )
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_POWER)

        val result = client.updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        assertThat(result).isTrue()
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_allEmptyFiltersIsFiltered() {
        val settings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val client = ScanClient(1000, 1, settings, filters)

        assertThat(client.isFiltered).isTrue()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_allEmptyFiltersIsUnfiltered() {
        val settings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        val client = ScanClient(1000, 1, settings, filters)

        assertThat(client.isFiltered).isFalse()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_anyFieldSetFiltersIsFiltered() {
        val settings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("TestName").build())
        val client = ScanClient(1000, 1, settings, filters)

        assertThat(client.isFiltered).isTrue()
    }

    @Test
    fun toString_doesNotCrash() {
        val scanClient = ScanClient(1000, 1, ScanSettings.Builder().build(), emptyList())
        scanClient.toString()
    }
}
