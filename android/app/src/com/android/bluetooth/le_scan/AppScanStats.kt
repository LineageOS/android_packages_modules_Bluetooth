/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

import static com.android.bluetooth.le_scan.ScanUtil.isBackgroundScan;
import static com.android.bluetooth.le_scan.ScanUtil.isBatchScan;
import static com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScan;
import static com.android.bluetooth.le_scan.ScanUtil.scanFilterToStringWithoutNullParam;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.Nullable;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.BatteryStatsManager;
import android.os.WorkSource;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.util.TimeProvider;
import com.android.bluetooth.util.WorkSourceUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** ScanStats class helps keep track of information about scans on a per application basis. */
public class AppScanStats {
    private static final String TAG = AppScanStats.class.getSimpleName();

    private static final int LARGE_SCAN_TIME_GAP_MS = 24000;

    private static final AtomicBoolean sIsScreenOn = new AtomicBoolean(false);

    public static class LastScan {
        final StringBuilder mFilterString = new StringBuilder();
        final int mScannerId;
        final int mScanMode;
        final int mScanCallbackType;
        final boolean mIsBackgroundScan;
        final boolean mIsBatchScan;
        final boolean mIsCallbackScan;
        final boolean mIsFilterScan;
        final boolean mIsOpportunisticScan;
        final long mReportDelayMillis;
        final int mAppImportanceOnStart;
        @Nullable final String mAttributionTag;

        final long mStartTimestamp;
        long mEndTimestamp;

        long mSuspendDuration;
        long mSuspendStartTime;
        boolean mIsSuspended;
        boolean mIsTimeout;
        private boolean mIsDowngraded;
        boolean mIsAutoBatchScan;
        int mResultsScreenOn = 0;
        int mResultsScreenOff = 0;

        private LastScan(
                long startTimestamp,
                int scannerId,
                int scanMode,
                int scanCallbackType,
                long reportDelayMillis,
                boolean isBackgroundScan,
                boolean isBatchScan,
                boolean isCallbackScan,
                boolean isFilterScan,
                boolean isOpportunisticScan,
                int appImportanceOnStart,
                @Nullable String attributionTag) {
            mStartTimestamp = startTimestamp;
            mScannerId = scannerId;
            mScanMode = scanMode;
            mScanCallbackType = scanCallbackType;
            mReportDelayMillis = reportDelayMillis;
            mIsBackgroundScan = isBackgroundScan;
            mIsBatchScan = isBatchScan;
            mIsCallbackScan = isCallbackScan;
            mIsFilterScan = isFilterScan;
            mIsOpportunisticScan = isOpportunisticScan;
            mAppImportanceOnStart = appImportanceOnStart;
            mAttributionTag = attributionTag;
        }
    }

    final List<LastScan> mLastScans = new ArrayList<>();
    final Map<Integer, LastScan> mOngoingScans = new HashMap<>();

    final String mAppName;
    final WorkSourceUtil mWorkSourceUtil;
    private final AdapterService mAdapterService;
    final TimeProvider mTimeProvider;
    private final ScanMetricsReporter mScanMetricsReporter;

    boolean mIsAppDead = false;
    boolean mIsRegistered = false;
    int mAppImportance = IMPORTANCE_CACHED;
    int mScansStarted = 0;
    int mScansStopped = 0;
    private long mScanStartTimestamp = 0;
    long mTotalActiveTime = 0;
    long mTotalSuspendTime = 0;
    long mTotalScanTime = 0;
    long mOppScanTime = 0;
    long mLowPowerScanTime = 0;
    long mBalancedScanTime = 0;
    long mLowLatencyScanTime = 0;
    long mAmbientDiscoveryScanTime = 0;
    int mOppScan = 0;
    int mLowPowerScan = 0;
    int mBalancedScan = 0;
    int mLowLatencyScan = 0;
    int mAmbientDiscoveryScan = 0;
    int mResultsScreenOn = 0;
    int mResultsScreenOff = 0;
    int mScheduledBatchAlarmCount = 0;

    AppScanStats(
            String name,
            WorkSource source,
            int uid,
            AdapterService adapterService,
            TimeProvider timeProvider) {
        mAppName = name;
        mAdapterService = requireNonNull(adapterService);
        // Bill the caller uid if the work source isn't passed through
        var workSource = requireNonNullElseGet(source, () -> new WorkSource(uid, mAppName));
        mWorkSourceUtil = new WorkSourceUtil(workSource);
        var batteryStatsManager =
                requireNonNull(mAdapterService.getSystemService(BatteryStatsManager.class));
        mScanMetricsReporter =
                new ScanMetricsReporter(workSource, mWorkSourceUtil, batteryStatsManager);
        mTimeProvider = requireNonNull(timeProvider);
    }

    @Nullable
    synchronized LastScan getScanFromScannerId(int scannerId) {
        return mOngoingScans.get(scannerId);
    }

    static void setScreenState(boolean isScreenOn) {
        sIsScreenOn.set(isScreenOn);
    }

    synchronized void addResult(int scannerId) {
        var isScreenOn = sIsScreenOn.get();
        if (isScreenOn) {
            mResultsScreenOn++;
        } else {
            mResultsScreenOff++;
        }

        var scan = getScanFromScannerId(scannerId);
        if (scan == null) return;
        if (isScreenOn) {
            scan.mResultsScreenOn++;
        } else {
            scan.mResultsScreenOff++;
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.mResultsScreenOn + scan.mResultsScreenOff) % 100 == 0) {
            mScanMetricsReporter.reportScanResults(100);
        }
    }

    synchronized void addResults(int scannerId, int numberOfNewResults) {
        var isScreenOn = sIsScreenOn.get();
        if (isScreenOn) {
            mResultsScreenOn += numberOfNewResults;
        } else {
            mResultsScreenOff += numberOfNewResults;
        }

        var scan = getScanFromScannerId(scannerId);
        if (scan == null) return;

        var resultsBeforeUpdate = scan.mResultsScreenOn + scan.mResultsScreenOff;
        if (isScreenOn) {
            scan.mResultsScreenOn += numberOfNewResults;
        } else {
            scan.mResultsScreenOff += numberOfNewResults;
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if (((scan.mResultsScreenOn + scan.mResultsScreenOff) / 100)
                > (resultsBeforeUpdate / 100)) {
            mScanMetricsReporter.reportScanResults(100);
        }
    }

    synchronized boolean isScanning() {
        return !mOngoingScans.isEmpty();
    }

    synchronized boolean isScanTimeout(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.mIsTimeout;
    }

    synchronized boolean isScanDowngraded(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.mIsDowngraded;
    }

    synchronized boolean isAutoBatchScan(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.mIsAutoBatchScan;
    }

    synchronized void setAppImportance(int importance) {
        mAppImportance = importance;
    }

    synchronized void recordScanStart(
            ScanSettings settings,
            List<ScanFilter> filters,
            boolean isFilterScan,
            boolean isCallbackScan,
            int scannerId,
            @Nullable String attributionTag) {
        LastScan existingScan = getScanFromScannerId(scannerId);
        if (existingScan != null) {
            return;
        }
        mScansStarted++;
        final var startTimestamp = mTimeProvider.elapsedRealtime();
        LastScan scan =
                new LastScan(
                        startTimestamp,
                        scannerId,
                        settings.getScanMode(),
                        settings.getCallbackType(),
                        settings.getReportDelayMillis(),
                        isBackgroundScan(settings),
                        isBatchScan(settings),
                        isCallbackScan,
                        isFilterScan,
                        isOpportunisticScan(settings),
                        mAppImportance,
                        attributionTag);
        switch (scan.mScanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC -> mOppScan++;
            case ScanSettings.SCAN_MODE_LOW_POWER -> mLowPowerScan++;
            case ScanSettings.SCAN_MODE_BALANCED -> mBalancedScan++;
            case ScanSettings.SCAN_MODE_LOW_LATENCY -> mLowLatencyScan++;
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> mAmbientDiscoveryScan++;
            default -> {} // Nothing to do
        }

        if (isFilterScan) {
            for (ScanFilter filter : filters) {
                scan.mFilterString
                        .append("\n        └ ")
                        .append(scanFilterToStringWithoutNullParam(filter));
            }
        }

        if (!isScanning()) {
            mScanStartTimestamp = startTimestamp;
        }

        mScanMetricsReporter.recordScanStart(
                scan, mOngoingScans.size(), sIsScreenOn.get(), mIsAppDead, mAppImportance);

        mOngoingScans.put(scannerId, scan);
    }

    synchronized void recordScanStop(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return;
        }
        mScansStopped++;
        long stopTime = mTimeProvider.elapsedRealtime();
        scan.mEndTimestamp = stopTime;
        if (scan.mIsSuspended) {
            long suspendDuration = stopTime - scan.mSuspendStartTime;
            scan.mSuspendDuration += suspendDuration;
            mTotalSuspendTime += suspendDuration;
        }
        mOngoingScans.remove(scannerId);
        if (mLastScans.size() >= mAdapterService.getScanQuotaCount()) {
            mLastScans.remove(0);
        }
        mLastScans.add(scan);

        long scanDuration = scan.mEndTimestamp - scan.mStartTimestamp;
        mTotalScanTime += scanDuration;
        long activeDuration = scanDuration - scan.mSuspendDuration;
        mTotalActiveTime += activeDuration;
        switch (scan.mScanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC -> mOppScanTime += activeDuration;
            case ScanSettings.SCAN_MODE_LOW_POWER -> mLowPowerScanTime += activeDuration;
            case ScanSettings.SCAN_MODE_BALANCED -> mBalancedScanTime += activeDuration;
            case ScanSettings.SCAN_MODE_LOW_LATENCY -> mLowLatencyScanTime += activeDuration;
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                    mAmbientDiscoveryScanTime += activeDuration;
            default -> {} // Nothing to do
        }

        mScanMetricsReporter.recordScanStop(
                scan,
                scanDuration,
                mOngoingScans.size(),
                sIsScreenOn.get(),
                mIsAppDead,
                mAppImportance);
    }

    synchronized void recordScanTimeoutCountMetrics(int scannerId, long scanTimeoutMillis) {
        var scan = getScanFromScannerId(scannerId);
        mScanMetricsReporter.recordScanTimeoutCountMetrics(scan, scanTimeoutMillis);
    }

    synchronized void recordHwFilterNotAvailableCountMetrics(
            int scannerId, long numOfFilterSupported) {
        var scan = getScanFromScannerId(scannerId);
        mScanMetricsReporter.recordHwFilterNotAvailableCountMetrics(scan, numOfFilterSupported);
    }

    synchronized void recordScanSuspend(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null || scan.mIsSuspended) {
            return;
        }
        scan.mSuspendStartTime = mTimeProvider.elapsedRealtime();
        scan.mIsSuspended = true;
    }

    synchronized void recordScanResume(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null || !scan.mIsSuspended) {
            return;
        }
        scan.mIsSuspended = false;
        long stopTime = mTimeProvider.elapsedRealtime();
        long suspendDuration = stopTime - scan.mSuspendStartTime;
        scan.mSuspendDuration += suspendDuration;
        mTotalSuspendTime += suspendDuration;
    }

    synchronized void setScanTimeout(int scannerId) {
        if (!isScanning()) {
            return;
        }

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.mIsTimeout = true;
        }
    }

    synchronized void setScanDowngrade(int scannerId, boolean isDowngrade) {
        if (!isScanning()) {
            return;
        }

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.mIsDowngraded = isDowngrade;
        }
    }

    synchronized void setAutoBatchScan(int scannerId, boolean isBatchScan) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.mIsAutoBatchScan = isBatchScan;
        }
    }

    synchronized boolean isScanningTooFrequently() {
        if (mLastScans.size() < mAdapterService.getScanQuotaCount()) {
            return false;
        }

        var oldestLastScanStartTimestamp = mLastScans.getFirst().mStartTimestamp;
        return Duration.ofMillis(mTimeProvider.elapsedRealtime() - oldestLastScanStartTimestamp)
                        .compareTo(mAdapterService.getScanQuotaWindow())
                < 0;
    }

    synchronized boolean isScanningTooLong() {
        if (!isScanning()) {
            return false;
        }

        return Duration.ofMillis(mTimeProvider.elapsedRealtime() - mScanStartTimestamp)
                        .compareTo(mAdapterService.getScanTimeout())
                >= 0;
    }

    synchronized boolean hasRecentScan() {
        if (!isScanning() || mLastScans.isEmpty()) {
            return false;
        }
        var lastScan = mLastScans.getLast();
        return (mTimeProvider.elapsedRealtime() - lastScan.mEndTimestamp) < LARGE_SCAN_TIME_GAP_MS;
    }

    synchronized void recordBatchAlarmScheduled() {
        mScheduledBatchAlarmCount++;
    }

    String getAttributionTagFromScannerId(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        return scan == null ? "" : (scan.mAttributionTag == null ? "" : scan.mAttributionTag);
    }

    synchronized void dump(StringBuilder sb, List<ScannerApp> apps) {
        // TODO(b/397863857) Inline this on `Flags.scanControllerThread()` cleanup within ScannerMap
        ScanUtil.dump(this, sb, apps);
    }
}
