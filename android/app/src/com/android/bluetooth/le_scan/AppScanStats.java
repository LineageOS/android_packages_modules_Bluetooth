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
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

import static com.android.bluetooth.le_scan.ScanUtil.callbackTypeToString;
import static com.android.bluetooth.le_scan.ScanUtil.isBackgroundScan;
import static com.android.bluetooth.le_scan.ScanUtil.isBatchScan;
import static com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScan;
import static com.android.bluetooth.le_scan.ScanUtil.scanFilterToStringWithoutNullParam;
import static com.android.bluetooth.le_scan.ScanUtil.scanModeToString;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.Nullable;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.BatteryStatsManager;
import android.os.WorkSource;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.util.TimeProvider;
import com.android.bluetooth.util.WorkSourceUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
        private final StringBuilder mFilterString = new StringBuilder();
        private final int mScannerId;
        final int mScanMode;
        final int mScanCallbackType;
        final boolean mIsBackgroundScan;
        final boolean mIsBatchScan;
        final boolean mIsCallbackScan;
        final boolean mIsFilterScan;
        final boolean mIsOpportunisticScan;
        final long mReportDelayMillis;
        private final int mAppImportanceOnStart;
        @Nullable final String mAttributionTag;

        private final long mStartTimestamp;
        private long mEndTimestamp;

        private long mSuspendDuration;
        private long mSuspendStartTime;
        private boolean mIsSuspended;
        private boolean mIsTimeout;
        private boolean mIsDowngraded;
        boolean mIsAutoBatchScan;
        int mResults;

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

    private final List<LastScan> mLastScans = new ArrayList<>();
    private final Map<Integer, LastScan> mOngoingScans = new HashMap<>();

    final String mAppName;
    final WorkSourceUtil mWorkSourceUtil;
    private final AdapterService mAdapterService;
    private final TimeProvider mTimeProvider;
    private final ScanMetricsReporter mScanMetricsReporter;

    boolean mIsAppDead = false;
    boolean mIsRegistered = false;
    int mAppImportance = IMPORTANCE_CACHED;
    private int mScansStarted = 0;
    private int mScansStopped = 0;
    private long mScanStartTimestamp = 0;
    private long mTotalActiveTime = 0;
    private long mTotalSuspendTime = 0;
    private long mTotalScanTime = 0;
    private long mOppScanTime = 0;
    private long mLowPowerScanTime = 0;
    private long mBalancedScanTime = 0;
    private long mLowLatencyScanTime = 0;
    private long mAmbientDiscoveryScanTime = 0;
    private int mOppScan = 0;
    private int mLowPowerScan = 0;
    private int mBalancedScan = 0;
    private int mLowLatencyScan = 0;
    private int mAmbientDiscoveryScan = 0;
    private int results = 0;
    private int mScheduledBatchAlarmCount = 0;

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
        results++;

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) return;
        scan.mResults++;

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if (scan.mResults % 100 == 0) {
            mScanMetricsReporter.reportScanResults(100);
        }
    }

    synchronized void addResults(int scannerId, int numberOfNewResults) {
        results += numberOfNewResults;

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) return;

        final int resultsBeforeUpdate = scan.mResults;
        scan.mResults += numberOfNewResults;

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.mResults / 100) > (resultsBeforeUpdate / 100)) {
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
        final long currentTimeMillis = System.currentTimeMillis();
        final long elapsedRealtimeMillis = mTimeProvider.elapsedRealtime();
        final int oppScan = mOppScan;
        final int lowPowerScan = mLowPowerScan;
        final int balancedScan = mBalancedScan;
        final int lowLatencyScan = mLowLatencyScan;
        final long ambientDiscoveryScan = mAmbientDiscoveryScan;
        long totalActiveTime = mTotalActiveTime;
        long totalSuspendTime = mTotalSuspendTime;
        long totalScanTime = mTotalScanTime;
        long oppScanTime = mOppScanTime;
        long lowPowerScanTime = mLowPowerScanTime;
        long balancedScanTime = mBalancedScanTime;
        long lowLatencyScanTime = mLowLatencyScanTime;
        long ambientDiscoveryScanTime = mAmbientDiscoveryScanTime;

        for (var ongoingScan : mOngoingScans.values()) {
            final var scanDuration = elapsedRealtimeMillis - ongoingScan.mStartTimestamp;
            final long suspendDuration =
                    ongoingScan.mIsSuspended
                            ? elapsedRealtimeMillis - ongoingScan.mSuspendStartTime
                            : 0;
            final var activeDuration =
                    scanDuration - ongoingScan.mSuspendDuration - suspendDuration;
            totalScanTime += scanDuration;
            totalSuspendTime += suspendDuration;
            totalActiveTime += activeDuration;
            switch (ongoingScan.mScanMode) {
                case ScanSettings.SCAN_MODE_OPPORTUNISTIC -> oppScanTime += activeDuration;
                case ScanSettings.SCAN_MODE_LOW_POWER -> lowPowerScanTime += activeDuration;
                case ScanSettings.SCAN_MODE_BALANCED -> balancedScanTime += activeDuration;
                case ScanSettings.SCAN_MODE_LOW_LATENCY -> lowLatencyScanTime += activeDuration;
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                        ambientDiscoveryScanTime += activeDuration;
                default -> {} // Nothing to do
            }
        }

        final long score =
                (oppScanTime * ScanUtil.WEIGHT_OPPORTUNISTIC
                                + lowPowerScanTime * ScanUtil.WEIGHT_LOW_POWER
                                + balancedScanTime * ScanUtil.WEIGHT_BALANCED
                                + lowLatencyScanTime * ScanUtil.WEIGHT_LOW_LATENCY
                                + ambientDiscoveryScanTime * ScanUtil.WEIGHT_AMBIENT_DISCOVERY)
                        / 100;

        sb.append("  ").append(mAppName);
        if (mIsRegistered) sb.append(" (Registered):");
        else sb.append(":");

        if (mIsRegistered) {
            for (ScannerApp app : apps) {
                sb.append("\n    Application ID: ").append(app.getId());
                sb.append(", UUID: ").append(app.getUuid());
                if (app.getAttributionTag() != null) {
                    sb.append(", Tag: ").append(app.getAttributionTag());
                }
            }
        }

        sb.append("\n    LE scans               ")
                .append("(started/stopped)                                   : ");
        sb.append(mScansStarted).append(" / ").append(mScansStopped);

        sb.append("\n    Scan time(ms)          ")
                .append("(active/suspend/total)                              : ");
        sb.append(totalActiveTime).append(" / ");
        sb.append(totalSuspendTime).append(" / ");
        sb.append(totalScanTime);

        sb.append("\n    Scan time per mode(ms) ")
                .append("(Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ");
        sb.append(oppScanTime).append(" / ");
        sb.append(lowPowerScanTime).append(" / ");
        sb.append(balancedScanTime).append(" / ");
        sb.append(lowLatencyScanTime).append(" / ");
        sb.append(ambientDiscoveryScanTime);

        sb.append("\n    Scan mode counter ")
                .append("     (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ");
        sb.append(oppScan).append(" / ");
        sb.append(lowPowerScan).append(" / ");
        sb.append(balancedScan).append(" / ");
        sb.append(lowLatencyScan).append(" / ");
        sb.append(ambientDiscoveryScan);

        sb.append("\n    Score ")
                .append("                                                                     : ")
                .append(score);

        sb.append("\n    Total number of results")
                .append("                                                    : ")
                .append(results);

        if (mScheduledBatchAlarmCount > 0) {
            sb.append("\n    Number of batch alarms scheduled")
                    .append("                                           : ")
                    .append(mScheduledBatchAlarmCount);
        }

        if (!mLastScans.isEmpty()) {
            sb.append("\n    Last ").append(mLastScans.size()).append(" scans:");
            appendScanDetails(sb, mLastScans, currentTimeMillis, elapsedRealtimeMillis, false);
        }

        if (!mOngoingScans.isEmpty()) {
            sb.append("\n    Ongoing ").append(mOngoingScans.size()).append(" scans:");
            appendScanDetails(
                    sb, mOngoingScans.values(), currentTimeMillis, elapsedRealtimeMillis, true);
        }

        sb.append("\n\n");
    }

    private static void appendScanDetails(
            StringBuilder sb,
            Collection<LastScan> scans,
            long currentTimeMillis,
            long elapsedRealtimeMillis,
            boolean isOngoing) {
        for (LastScan scan : scans) {
            final var bootEpochMillis = currentTimeMillis - elapsedRealtimeMillis;

            final var start = Instant.ofEpochMilli(bootEpochMillis + scan.mStartTimestamp);
            sb.append("\n      [").append(Utils.formatInstant(start));
            if (!isOngoing) {
                final var end = Instant.ofEpochMilli(bootEpochMillis + scan.mEndTimestamp);
                sb.append(" --> ").append(Utils.formatInstant(end));
            }
            sb.append("]  (");

            final long duration;
            if (isOngoing) {
                duration = elapsedRealtimeMillis - scan.mStartTimestamp;
                sb.append("Elapsed: ").append(duration).append("ms");
            } else {
                duration = scan.mEndTimestamp - scan.mStartTimestamp;
                sb.append("Duration: ").append(duration).append("ms");
            }

            sb.append(")\n        └ Info: ");

            if (scan.mIsOpportunisticScan) sb.append("(Opp) ");
            if (scan.mIsBackgroundScan) sb.append("(Back) ");
            if (scan.mIsTimeout) sb.append("(Forced) ");
            if (scan.mIsFilterScan) sb.append("(Filter) ");
            if (isOngoing && scan.mIsSuspended) sb.append("(Suspended) ");

            sb.append("Results: ").append(scan.mResults).append(" | ");
            sb.append("id: (").append(scan.mScannerId).append(") | ");

            if (scan.mAttributionTag != null) {
                sb.append("[").append(scan.mAttributionTag).append("] | ");
            }

            sb.append(scan.mIsCallbackScan ? "CB " : "PI ");
            if (scan.mIsBatchScan) {
                sb.append("Batch Scan");
            } else if (scan.mIsAutoBatchScan) {
                sb.append("Auto Batch Scan");
            } else {
                sb.append("Regular Scan");
            }

            if (!isOngoing) {
                if (scan.mAppImportanceOnStart < IMPORTANCE_FOREGROUND_SERVICE) {
                    sb.append("\n        └ ")
                            .append("App Importance: Higher than Foreground Service");
                } else if (scan.mAppImportanceOnStart > IMPORTANCE_FOREGROUND_SERVICE) {
                    sb.append("\n        └ ")
                            .append("App Importance: Lower than Foreground Service");
                } else {
                    sb.append("\n        └ ").append("App Importance: Foreground Service");
                }
            }

            if (scan.mSuspendStartTime != 0) {
                final long suspendDuration;
                if (isOngoing && scan.mIsSuspended) {
                    suspendDuration =
                            (elapsedRealtimeMillis - scan.mSuspendStartTime)
                                    + scan.mSuspendDuration;
                } else {
                    suspendDuration = scan.mSuspendDuration;
                }
                final var activeDuration = duration - suspendDuration;

                sb.append("\n        └ ");
                sb.append("Active Time: ").append(activeDuration).append("ms");
                sb.append(", Suspended Time: ").append(suspendDuration).append("ms");
            }

            sb.append("\n        └ ").append("Config: ");
            sb.append("[ScanMode=").append(scanModeToString(scan.mScanMode));
            sb.append(", callbackType=").append(callbackTypeToString(scan.mScanCallbackType));
            sb.append("]");

            if (scan.mIsFilterScan) {
                sb.append(scan.mFilterString);
            }
        }
    }
}
