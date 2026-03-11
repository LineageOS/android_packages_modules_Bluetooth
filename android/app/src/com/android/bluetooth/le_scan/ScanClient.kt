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
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.ScanUtil.toBuilder
import java.util.Objects

/** Helper class identifying a client that has requested LE scan results. */
class ScanClient
private constructor(
    val app: ScannerApp,
    val appUid: Int,
    val scannerId: Int,
    var settings: ScanSettings,
    val scanModeApp: Int,
    val filters: List<ScanFilter>,
    val userHandle: UserHandle?,
    val isInternal: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val isEligibleForSanitizedExposureNotification: Boolean = false,
    val hasNetworkSettingsPermission: Boolean = false,
    val hasNetworkSetupWizardPermission: Boolean = false,
    val hasScanWithoutLocationPermission: Boolean = false,
    val hasDisavowedLocation: Boolean = false,
    val associatedDevices: List<String> = emptyList(),
) {
    val appScanStats = app.appScanStats
    val isFiltered: Boolean
        get() =
            if (Flags.treatEmptyFiltersAsUnfiltered()) hasNonEmptyFilters else filters.isNotEmpty()

    // TODO(b/461650493) inline within the above `val isFiltered` on flag cleanup
    // A valid filter need at least one field not empty
    val hasNonEmptyFilters = filters.any { !it.isAllFieldsEmpty }

    var started = false
    var appDied = false

    constructor(
        app: ScannerApp,
        settings: ScanSettings,
        userHandle: UserHandle,
        eligibleForSanitizedExposureNotification: Boolean = false,
        hasDisavowedLocation: Boolean = false,
        hasLocationPermission: Boolean = false,
        hasNetworkSettingsPermission: Boolean = false,
        hasNetworkSetupWizardPermission: Boolean = false,
        hasScanWithoutLocationPermission: Boolean = false,
        associatedDevices: List<String> = emptyList(),
    ) : this(
        app = app,
        appUid = app.uid,
        scannerId = app.scannerId,
        settings = settings,
        scanModeApp = settings.scanMode,
        filters = app.filters,
        userHandle = userHandle,
        isEligibleForSanitizedExposureNotification = eligibleForSanitizedExposureNotification,
        hasDisavowedLocation = hasDisavowedLocation,
        hasLocationPermission = hasLocationPermission,
        hasNetworkSettingsPermission = hasNetworkSettingsPermission,
        hasNetworkSetupWizardPermission = hasNetworkSetupWizardPermission,
        hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
        associatedDevices = associatedDevices,
    )

    // Constructor to be used for internal clients only
    constructor(
        app: ScannerApp,
        appUid: Int,
        userHandle: UserHandle,
        hasNetworkSettingsPermission: Boolean,
        hasNetworkSetupWizardPermission: Boolean,
        hasScanWithoutLocationPermission: Boolean,
    ) : this(
        app = app,
        appUid = appUid,
        scannerId = app.scannerId,
        settings = app.settings,
        scanModeApp = app.settings.scanMode,
        filters = app.filters,
        userHandle = userHandle,
        isInternal = true,
        hasNetworkSettingsPermission = hasNetworkSettingsPermission,
        hasNetworkSetupWizardPermission = hasNetworkSetupWizardPermission,
        hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
    )

    constructor(
        app: ScannerApp
    ) : this(
        app = app,
        appUid = app.uid,
        scannerId = app.scannerId,
        settings = app.settings,
        scanModeApp = app.settings.scanMode,
        filters = app.filters,
        userHandle = app.userHandle,
        hasLocationPermission = app.hasLocationPermission,
        isEligibleForSanitizedExposureNotification = app.eligibleForSanitizedExposureNotification,
        hasNetworkSettingsPermission = app.hasNetworkSettingsPermission,
        hasNetworkSetupWizardPermission = app.hasNetworkSetupWizardPermission,
        hasScanWithoutLocationPermission = app.hasScanWithoutLocationPermission,
        associatedDevices = app.associatedDevices ?: emptyList(),
        hasDisavowedLocation = app.hasDisavowedLocation,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ScanClient) {
            return false
        }
        return scannerId == other.scannerId
    }

    override fun hashCode(): Int {
        return Objects.hash(scannerId)
    }

    override fun toString() =
        "ScanClient(${appScanStats.name}" +
            "id=$scannerId, mode[${ScanMode(scanModeApp)}, used=${ScanMode(settings.scanMode)}])"

    /**
     * Update scan settings with the new scan mode.
     *
     * @return true if scan settings are updated, false otherwise.
     */
    fun updateScanMode(newScanMode: Int): Boolean {
        if (settings.scanMode == newScanMode) {
            return false
        }

        settings = settings.toBuilder().setScanMode(newScanMode).build()
        return true
    }
}
