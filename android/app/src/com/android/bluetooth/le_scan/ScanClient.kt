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
import com.android.bluetooth.le_scan.ScanUtil.scanModeToString
import java.util.Objects
import java.util.function.Consumer

/** Helper class identifying a client that has requested LE scan results. */
class ScanClient
private constructor(
    val appUid: Int,
    val scannerId: Int,
    var settings: ScanSettings,
    val scanModeApp: Int,
    val filters: List<ScanFilter>,
    val userHandle: UserHandle?,
    val isInternal: Boolean = false,
    var started: Boolean = false,
    var appDied: Boolean = false,
    var hasLocationPermission: Boolean = false,
    var isEligibleForSanitizedExposureNotification: Boolean = false,
    var hasNetworkSettingsPermission: Boolean = false,
    var hasNetworkSetupWizardPermission: Boolean = false,
    var hasScanWithoutLocationPermission: Boolean = false,
    var hasDisavowedLocation: Boolean = false,
    var associatedDevices: List<String> = emptyList(),
    var appScanStats: AppScanStats? = null,
) {
    @JvmOverloads
    constructor(
        appUid: Int,
        scannerId: Int,
        settings: ScanSettings = ScanSettings.Builder().build(),
        filters: List<ScanFilter> = emptyList(),
        userHandle: UserHandle? = null,
    ) : this(appUid, scannerId, settings, settings.scanMode, filters, userHandle)

    // Constructor to be used for internal clients only
    constructor(
        appUid: Int,
        scannerId: Int,
        settings: ScanSettings = ScanSettings.Builder().build(),
        filters: List<ScanFilter> = emptyList(),
        userHandle: UserHandle? = null,
        hasNetworkSettingsPermission: Boolean,
        hasNetworkSetupWizardPermission: Boolean,
        hasScanWithoutLocationPermission: Boolean,
    ) : this(
        appUid = appUid,
        scannerId = scannerId,
        settings = settings,
        scanModeApp = settings.scanMode,
        filters = filters,
        userHandle = userHandle,
        isInternal = true,
        hasNetworkSettingsPermission = hasNetworkSettingsPermission,
        hasNetworkSetupWizardPermission = hasNetworkSetupWizardPermission,
        hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
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
        "ScanClient(" +
            (appScanStats?.let { "${it.name}, " } ?: "") +
            "id=$scannerId, " +
            "mode[${scanModeToString(scanModeApp)}, used=${scanModeToString(settings.scanMode)}])"

    fun ifAppScanStatsPresent(action: Consumer<AppScanStats>) =
        appScanStats?.let { action.accept(it) }

    /**
     * Update scan settings with the new scan mode.
     *
     * @return true if scan settings are updated, false otherwise.
     */
    fun updateScanMode(newScanMode: Int): Boolean {
        if (settings.scanMode == newScanMode) {
            return false
        }

        settings =
            ScanSettings.Builder()
                .setScanMode(newScanMode)
                .setCallbackType(settings.callbackType)
                .setScanResultType(settings.scanResultType)
                .setReportDelay(settings.reportDelayMillis)
                .setNumOfMatches(settings.numOfMatches)
                .setMatchMode(settings.matchMode)
                .setLegacy(settings.legacy)
                .setPhy(settings.phy)
                .build()
        return true
    }
}
