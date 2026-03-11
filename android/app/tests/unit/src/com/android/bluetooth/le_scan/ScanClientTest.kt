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
        val scannerId = 5
        val uid = 1000
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = emptyList<ScanFilter>()
        val app =
            mock<ScannerApp> {
                doReturn(scannerId).whenever(it).scannerId
                doReturn(uid).whenever(it).uid
                doReturn(filters).whenever(it).filters
            }
        val userHandle = UserHandle.getUserHandleForUid(uid)

        val client = ScanClient(app, settings, userHandle)
        assertThat(client.scannerId).isEqualTo(scannerId)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.scanModeApp).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        assertThat(client.filters).isEqualTo(filters)
        assertThat(client.userHandle).isEqualTo(userHandle)
        assertThat(client.isInternal).isEqualTo(false)
    }

    @Test
    fun constructor_internal() {
        val scannerId = 10
        val uid = 1002
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val filters = emptyList<ScanFilter>()
        val app =
            mock<ScannerApp> {
                doReturn(scannerId).whenever(it).scannerId
                doReturn(settings).whenever(it).settings
                doReturn(filters).whenever(it).filters
            }
        val userHandle = UserHandle.getUserHandleForUid(uid)
        val hasNetworkSettingsPermission = true
        val hasNetworkSetupWizardPermission = true
        val hasScanWithoutLocationPermission = true

        val client =
            ScanClient(
                app,
                uid,
                userHandle,
                hasNetworkSettingsPermission,
                hasNetworkSetupWizardPermission,
                hasScanWithoutLocationPermission,
            )
        assertThat(client.scannerId).isEqualTo(scannerId)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.scanModeApp).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
        assertThat(client.filters).isEqualTo(filters)
        assertThat(client.userHandle).isEqualTo(userHandle)
        assertThat(client.hasNetworkSettingsPermission).isEqualTo(hasNetworkSettingsPermission)
        assertThat(client.hasNetworkSetupWizardPermission)
            .isEqualTo(hasNetworkSetupWizardPermission)
        assertThat(client.hasScanWithoutLocationPermission)
            .isEqualTo(hasScanWithoutLocationPermission)
        assertThat(client.isInternal).isTrue()
    }

    @Test
    fun constructor_pendingIntentInfo() {
        val scannerId = 77
        val uid = 54321
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val filters = emptyList<ScanFilter>()
        val userHandle = UserHandle.getUserHandleForUid(uid)

        val app =
            mock<ScannerApp> {
                doReturn(scannerId).whenever(it).scannerId
                doReturn(uid).whenever(it).uid
                doReturn(settings).whenever(it).settings
                doReturn(filters).whenever(it).filters
                doReturn(userHandle).whenever(it).userHandle
            }

        val client = ScanClient(app)
        assertThat(client.scannerId).isEqualTo(scannerId)
        assertThat(client.appUid).isEqualTo(uid)
        assertThat(client.settings).isEqualTo(settings)
        assertThat(client.filters).isEqualTo(filters)
        assertThat(client.userHandle).isEqualTo(userHandle)
        assertThat(client.isInternal).isFalse()
    }

    @Test
    fun updateScanMode() {
        val client =
            createScanClient(
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            )
        val result = client.updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        assertThat(result).isTrue()
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_allEmptyFiltersIsFiltered() {
        val client = createScanClient()
        assertThat(client.isFiltered).isTrue()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_allEmptyFiltersIsUnfiltered() {
        val client = createScanClient()
        assertThat(client.isFiltered).isFalse()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TREAT_EMPTY_FILTERS_AS_UNFILTERED)
    fun isFiltered_anyFieldSetFiltersIsFiltered() {
        val client =
            createScanClient(
                filters = listOf(ScanFilter.Builder().setDeviceName("TestName").build())
            )

        assertThat(client.isFiltered).isTrue()
    }

    @Test
    fun toString_doesNotCrash() {
        val client = createScanClient()
        client.toString()
    }

    private fun createScanClient(
        settings: ScanSettings = ScanSettings.Builder().build(),
        filters: List<ScanFilter> = listOf(ScanFilter.Builder().build()),
    ) =
        ScanClient(
            mock<ScannerApp> {
                doReturn(filters).whenever(it).filters
                doReturn(mock<AppScanStats>()).whenever(it).appScanStats
            },
            settings,
            mock<UserHandle>(),
            eligibleForSanitizedExposureNotification = false,
            hasDisavowedLocation = false,
            hasLocationPermission = false,
            hasNetworkSettingsPermission = false,
            hasNetworkSetupWizardPermission = false,
            hasScanWithoutLocationPermission = false,
            associatedDevices = emptyList(),
        )
}
