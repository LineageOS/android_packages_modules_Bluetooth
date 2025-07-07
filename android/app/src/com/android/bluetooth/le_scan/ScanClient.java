/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import static android.bluetooth.le.ScanSettings.getScanModeString;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.UserHandle;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Helper class identifying a client that has requested LE scan results. */
class ScanClient {
    final int mScannerId;
    final int mScanModeApp;
    final List<ScanFilter> mFilters;
    final int mAppUid;

    ScanSettings mSettings;
    boolean mStarted = false;
    boolean mIsInternalClient = false;
    // App associated with the scan client died.
    boolean mAppDied;
    boolean mHasLocationPermission;
    UserHandle mUserHandle;
    boolean mIsQApp;
    boolean mEligibleForSanitizedExposureNotification;
    boolean mHasNetworkSettingsPermission;
    boolean mHasNetworkSetupWizardPermission;
    boolean mHasScanWithoutLocationPermission;
    boolean mHasDisavowedLocation;
    List<String> mAssociatedDevices;
    Optional<AppScanStats> mStats = Optional.empty();

    ScanClient(int scannerId, ScanSettings settings, List<ScanFilter> filters, int appUid) {
        mScannerId = scannerId;
        mSettings = settings;
        mScanModeApp = settings.getScanMode();
        mFilters = (filters == null) ? Collections.emptyList() : filters;
        mAppUid = appUid;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ScanClient other)) {
            return false;
        }
        return mScannerId == other.mScannerId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mScannerId);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ScanClient {");
        sb.append(" scanModeApp=").append(getScanModeString(mScanModeApp));
        sb.append(", scanModeUsed=").append(getScanModeString(mSettings.getScanMode()));
        sb.append(", scannerId=").append(mScannerId);

        mStats.map(stats -> stats.mAppName)
                .filter(appName -> appName != null)
                .ifPresent(appName -> sb.append(", appScanStats.appName=").append(appName));

        return sb.append(" }").toString();
    }

    /**
     * Update scan settings with the new scan mode.
     *
     * @return true if scan settings are updated, false otherwise.
     */
    boolean updateScanMode(int newScanMode) {
        if (mSettings.getScanMode() == newScanMode) {
            return false;
        }

        mSettings =
                new ScanSettings.Builder()
                        .setScanMode(newScanMode)
                        .setCallbackType(mSettings.getCallbackType())
                        .setScanResultType(mSettings.getScanResultType())
                        .setReportDelay(mSettings.getReportDelayMillis())
                        .setNumOfMatches(mSettings.getNumOfMatches())
                        .setMatchMode(mSettings.getMatchMode())
                        .setLegacy(mSettings.getLegacy())
                        .setPhy(mSettings.getPhy())
                        .build();
        return true;
    }
}
