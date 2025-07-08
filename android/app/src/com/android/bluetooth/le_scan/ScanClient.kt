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
// TODO(b/429793161) Remove all `@JvmField` declarations
// TODO(b/429793161) Remove all `m` prefix
class ScanClient(scannerId: Int, settings: ScanSettings, filters: List<ScanFilter>?, appUid: Int) {
    @JvmField val mScannerId: Int = scannerId
    @JvmField val mScanModeApp: Int
    @JvmField val mFilters: List<ScanFilter>
    @JvmField val mAppUid: Int

    @JvmField var mSettings: ScanSettings
    @JvmField var mStarted = false
    @JvmField var mIsInternalClient = false
    // App associated with the scan client died.
    @JvmField var mAppDied = false
    @JvmField var mHasLocationPermission = false
    @JvmField var mUserHandle: UserHandle? = null
    @JvmField var mIsQApp = false
    @JvmField var mEligibleForSanitizedExposureNotification = false
    @JvmField var mHasNetworkSettingsPermission = false
    @JvmField var mHasNetworkSetupWizardPermission = false
    @JvmField var mHasScanWithoutLocationPermission = false
    @JvmField var mHasDisavowedLocation = false
    @JvmField var mAssociatedDevices: List<String>? = null
    // TODO(b/429793161) Convert to Kotlin native optional
    @JvmField internal var mStats: Optional<AppScanStats> = Optional.empty()

    init {
        mSettings = settings
        mScanModeApp = settings.scanMode
        mFilters = filters ?: emptyList()
        mAppUid = appUid
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ScanClient) {
            return false
        }
        return mScannerId == other.mScannerId
    }

    override fun hashCode(): Int {
        return Objects.hash(mScannerId)
    }

    override fun toString(): String {
        val sb = StringBuilder("ScanClient {")
        sb.append(" scanModeApp=").append(ScanSettings.getScanModeString(mScanModeApp))
        sb.append(", scanModeUsed=").append(ScanSettings.getScanModeString(mSettings.scanMode))
        sb.append(", scannerId=").append(mScannerId)

        mStats.getOrNull()?.let { stats ->
            sb.append(", appScanStats.appName=").append(stats.mAppName)
        }

        return sb.append(" }").toString()
    }

    /**
     * Update scan settings with the new scan mode.
     *
     * @return true if scan settings are updated, false otherwise.
     */
    fun updateScanMode(newScanMode: Int): Boolean {
        if (mSettings.getScanMode() == newScanMode) {
            return false
        }

        mSettings =
            ScanSettings.Builder()
                .setScanMode(newScanMode)
                .setCallbackType(mSettings.getCallbackType())
                .setScanResultType(mSettings.getScanResultType())
                .setReportDelay(mSettings.getReportDelayMillis())
                .setNumOfMatches(mSettings.getNumOfMatches())
                .setMatchMode(mSettings.getMatchMode())
                .setLegacy(mSettings.getLegacy())
                .setPhy(mSettings.getPhy())
                .build()
        return true
    }
}
