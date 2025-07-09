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
// TODO(b/429793161) Remove all `@JvmField` declarations and `m` prefix
class ScanClient
private constructor(
    val scannerId: Int,
    var settings: ScanSettings,
    val scanModeApp: Int,
    val filters: List<ScanFilter>,
    val appUid: Int,
    val isInternalClient: Boolean,
    var started: Boolean = false,
    var appDied: Boolean = false,
    @JvmField var mHasLocationPermission: Boolean = false,
    @JvmField var mUserHandle: UserHandle? = null,
    @JvmField var mIsQApp: Boolean = false,
    @JvmField var mEligibleForSanitizedExposureNotification: Boolean = false,
    @JvmField var mHasNetworkSettingsPermission: Boolean = false,
    @JvmField var mHasNetworkSetupWizardPermission: Boolean = false,
    @JvmField var mHasScanWithoutLocationPermission: Boolean = false,
    @JvmField var mHasDisavowedLocation: Boolean = false,
    @JvmField var mAssociatedDevices: List<String>? = null,
    // TODO(b/429793161) Convert to Kotlin native optional
    @JvmField internal var mStats: Optional<AppScanStats> = Optional.empty(),
) {
    @JvmOverloads
    constructor(
        scannerId: Int,
        settings: ScanSettings,
        filterList: List<ScanFilter>?,
        appUid: Int,
        isInternalClient: Boolean = false,
    ) : this(
        scannerId,
        settings,
        settings.scanMode,
        filterList ?: emptyList(),
        appUid,
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
        mStats.getOrNull()?.let { stats ->
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
        if (settings.getScanMode() == newScanMode) {
            return false
        }

        settings =
            ScanSettings.Builder()
                .setScanMode(newScanMode)
                .setCallbackType(settings.getCallbackType())
                .setScanResultType(settings.getScanResultType())
                .setReportDelay(settings.getReportDelayMillis())
                .setNumOfMatches(settings.getNumOfMatches())
                .setMatchMode(settings.getMatchMode())
                .setLegacy(settings.getLegacy())
                .setPhy(settings.getPhy())
                .build()
        return true
    }
}
