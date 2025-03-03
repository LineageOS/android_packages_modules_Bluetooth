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

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.Binder;
import android.os.UserHandle;

import java.util.List;
import java.util.Objects;

/** Helper class identifying a client that has requested LE scan results. */
class ScanClient {
    private static final ScanSettings DEFAULT_SCAN_SETTINGS =
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

    final int mScannerId;
    final int mAppUid;
    final List<ScanFilter> mFilters;

    ScanSettings mSettings;
    int mScanModeApp;
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
    AppScanStats mStats = null;

    ScanClient(int scannerId) {
        this(scannerId, DEFAULT_SCAN_SETTINGS, null);
    }

    ScanClient(int scannerId, ScanSettings settings, List<ScanFilter> filters) {
        this(scannerId, settings, filters, Binder.getCallingUid());
    }

    ScanClient(int scannerId, ScanSettings settings, List<ScanFilter> filters, int appUid) {
        mScannerId = scannerId;
        mSettings = settings;
        mScanModeApp = settings.getScanMode();
        mFilters = filters;
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
        StringBuilder sb = new StringBuilder(" [ScanClient");
        sb.append(" scanModeApp ")
                .append(mScanModeApp)
                .append(" scanModeUsed ")
                .append(mSettings.getScanMode());

        if (mStats != null && mStats.mAppName != null) {
            sb.append(" [appScanStats ").append(mStats.mAppName).append("]");
        }

        return sb.append("]").toString();
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
