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
import java.util.Objects
import java.util.Optional
import kotlin.jvm.optionals.getOrNull

/** Helper class identifying a client that has requested LE scan results. */
class ScanClient
private constructor(
    val scannerId: Int,
    var settings: ScanSettings,
    val scanModeApp: Int,
    val filters: List<ScanFilter>,
    val appUid: Int,
    val userHandle: UserHandle?,
    val isInternalClient: Boolean,
    var started: Boolean = false,
    var appDied: Boolean = false,
    var hasLocationPermission: Boolean = false,
    var isQApp: Boolean = false,
    var isEligibleForSanitizedExposureNotification: Boolean = false,
    var hasNetworkSettingsPermission: Boolean = false,
    var hasNetworkSetupWizardPermission: Boolean = false,
    var hasScanWithoutLocationPermission: Boolean = false,
    var hasDisavowedLocation: Boolean = false,
    var associatedDevices: List<String> = emptyList(),
    @get:JvmName("getAppScanStats")
    @set:JvmName("setAppScanStats")
    internal var appScanStats: Optional<AppScanStats> = Optional.empty(),
) {
    @JvmOverloads
    constructor(
        scannerId: Int,
        settings: ScanSettings,
        filterList: List<ScanFilter>?,
        appUid: Int,
        userHandle: UserHandle? = null,
        isInternalClient: Boolean = false,
    ) : this(
        scannerId,
        settings,
        settings.scanMode,
        filterList ?: emptyList(),
        appUid,
        userHandle,
        isInternalClient,
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

    override fun toString(): String {
        val sb = StringBuilder("ScanClient(")
        sb.append("scannerId=").append(scannerId)
        sb.append(", scanModeApp=").append(ScanSettings.getScanModeString(scanModeApp))
        sb.append(", scanModeUsed=").append(ScanSettings.getScanModeString(settings.scanMode))
        appScanStats.getOrNull()?.let { stats ->
            sb.append(", appScanStats.appName=").append(stats.mAppName)
        }
        return sb.append(")").toString()
    }

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
