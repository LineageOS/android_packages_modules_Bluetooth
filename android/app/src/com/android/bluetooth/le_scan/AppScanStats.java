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
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.BatteryStatsManager;
import android.os.WorkSource;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.util.WorkSourceUtil;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** ScanStats class helps keep track of information about scans on a per application basis. */
class AppScanStats {
    private static final String TAG = AppScanStats.class.getSimpleName();

    private static final int LARGE_SCAN_TIME_GAP_MS = 24000;

    private static final AtomicBoolean sIsScreenOn = new AtomicBoolean(false);

    private static class LastScan {
        private final StringBuilder mFilterString = new StringBuilder();
        private final int mScannerId;
        private final int mScanMode;
        private final int mScanCallbackType;
        private final boolean mIsBackgroundScan;
        private final boolean mIsBatchScan;
        private final boolean mIsCallbackScan;
        private final boolean mIsFilterScan;
        private final boolean mIsOpportunisticScan;
        private final long mReportDelayMillis;
        private final int mAppImportanceOnStart;
        @Nullable private final String mAttributionTag;

        private final long mStartTimestamp;
        private long mEndTimestamp;

        private long mSuspendDuration;
        private long mSuspendStartTime;
        private boolean mIsSuspended;
        private boolean mIsTimeout;
        private boolean mIsDowngraded;
        private boolean mIsAutoBatchScan;
        private int mResults;

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

        private String getAttributionTag() {
            return mAttributionTag != null ? mAttributionTag : "";
        }
    }

    final String mAppName;
    final WorkSourceUtil mWorkSourceUtil; // Used for BluetoothStatsLog
    private final List<LastScan> mLastScans = new ArrayList<>();
    private final Map<Integer, LastScan> mOngoingScans = new HashMap<>();
    private final WorkSource mWorkSource; // Used for BatteryStatsManager
    private final AdapterService mAdapterService;
    // Used to keep track of scans and result stats
    private final BatteryStatsManager mBatteryStatsManager;
    // Used to add scan event protos to be dumped later
    @VisibleForTesting final ScanController mScanController;
    private final TimeProvider mTimeProvider;

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
            ScanController scanController,
            TimeProvider timeProvider) {
        mAppName = name;
        // Bill the caller uid if the work source isn't passed through
        mWorkSource = requireNonNullElseGet(source, () -> new WorkSource(uid, mAppName));
        mWorkSourceUtil = new WorkSourceUtil(mWorkSource);
        mAdapterService = requireNonNull(adapterService);
        mBatteryStatsManager = adapterService.getSystemService(BatteryStatsManager.class);
        mScanController = scanController;
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
            reportScanResults(100);
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
            reportScanResults(100);
        }
    }

    private void reportScanResults(int numberOfNewResults) {
        mBatteryStatsManager.reportBleScanResults(mWorkSource, numberOfNewResults);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                numberOfNewResults);
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
                        .append("\n      └ ")
                        .append(scanFilterToStringWithoutNullParam(filter));
            }
        }

        if (!isScanning()) {
            mScanStartTimestamp = startTimestamp;
        }
        boolean isUnoptimized =
                !(scan.mIsFilterScan || scan.mIsBackgroundScan || scan.mIsOpportunisticScan);
        mBatteryStatsManager.reportBleScanStarted(mWorkSource, isUnoptimized);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__ON,
                scan.mIsFilterScan,
                scan.mIsBackgroundScan,
                scan.mIsOpportunisticScan);
        recordScanAppCountMetricsStart(scan);

        mOngoingScans.put(scannerId, scan);
    }

    synchronized void recordScanStop(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return;
        }
        this.mScansStopped++;
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

        // Inform battery stats of any results it might be missing on scan stop
        boolean isUnoptimized =
                !(scan.mIsFilterScan || scan.mIsBackgroundScan || scan.mIsOpportunisticScan);
        mBatteryStatsManager.reportBleScanResults(mWorkSource, scan.mResults % 100);
        mBatteryStatsManager.reportBleScanStopped(mWorkSource, isUnoptimized);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                scan.mResults % 100);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__OFF,
                scan.mIsFilterScan,
                scan.mIsBackgroundScan,
                scan.mIsOpportunisticScan);
        recordScanAppCountMetricsStop(scan, scanDuration);
    }

    private void recordScanAppCountMetricsStart(LastScan scan) {
        MetricsLogger logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_ENABLE, 1);
        logger.logAppScanStateChanged(
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                true /* enabled */,
                scan.mIsFilterScan,
                scan.mIsCallbackScan,
                convertScanCallbackType(scan.mScanCallbackType),
                convertScanType(scan),
                convertScanMode(scan.mScanMode),
                scan.mReportDelayMillis,
                0 /* app_scan_duration_ms */,
                mOngoingScans.size(),
                sIsScreenOn.get(),
                mIsAppDead,
                mAppImportance,
                scan.getAttributionTag());
        if (scan.mIsAutoBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_ENABLE, 1);
        } else if (scan.mIsBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_ENABLE, 1);
        } else {
            if (scan.mIsFilterScan) {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_ENABLE, 1);
            } else {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_ENABLE, 1);
            }
        }
    }

    private void recordScanAppCountMetricsStop(LastScan scan, long duration) {
        MetricsLogger logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_DISABLE, 1);
        logger.logAppScanStateChanged(
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                false /* enabled */,
                scan.mIsFilterScan,
                scan.mIsCallbackScan,
                convertScanCallbackType(scan.mScanCallbackType),
                convertScanType(scan),
                convertScanMode(scan.mScanMode),
                scan.mReportDelayMillis,
                duration,
                mOngoingScans.size(),
                sIsScreenOn.get(),
                mIsAppDead,
                mAppImportance,
                scan.getAttributionTag());
        if (scan.mIsAutoBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_DISABLE, 1);
        } else if (scan.mIsBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_DISABLE, 1);
        } else {
            if (scan.mIsFilterScan) {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_DISABLE, 1);
            } else {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_DISABLE, 1);
            }
        }
    }

    private static int convertScanCallbackType(int type) {
        return switch (type) {
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES;
            case ScanSettings.CALLBACK_TYPE_FIRST_MATCH ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_FIRST_MATCH;
            case ScanSettings.CALLBACK_TYPE_MATCH_LOST ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_MATCH_LOST;
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES_AUTO_BATCH;
            default ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_UNKNOWN;
        };
    }

    static int convertScanType(LastScan scan) {
        if (scan == null) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_UNKNOWN;
        }
        if (scan.mIsAutoBatchScan) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_AUTO_BATCH;
        } else if (scan.mIsBatchScan) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_BATCH;
        } else {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR;
        }
    }

    static int convertScanMode(int mode) {
        return switch (mode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_OPPORTUNISTIC;
            case ScanSettings.SCAN_MODE_LOW_POWER ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER;
            case ScanSettings.SCAN_MODE_BALANCED ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED;
            case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY;
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_AMBIENT_DISCOVERY;
            case ScanSettings.SCAN_MODE_SCREEN_OFF ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF;
            case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED ->
                    BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF_BALANCED;
            default -> BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_UNKNOWN;
        };
    }

    synchronized void recordScanTimeoutCountMetrics(int scannerId, long scanTimeoutMillis) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_ABUSED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                convertScanType(getScanFromScannerId(scannerId)),
                BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_SCAN_TIMEOUT,
                scanTimeoutMillis,
                getAttributionTagFromScannerId(scannerId));
        MetricsLogger.getInstance()
                .cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_SCAN_TIMEOUT, 1);
    }

    synchronized void recordHwFilterNotAvailableCountMetrics(
            int scannerId, long numOfFilterSupported) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_ABUSED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                convertScanType(getScanFromScannerId(scannerId)),
                BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_HW_FILTER_NA,
                numOfFilterSupported,
                getAttributionTagFromScannerId(scannerId));
        MetricsLogger.getInstance()
                .cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_HW_FILTER_NOT_AVAILABLE, 1);
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
        return scan == null ? "" : scan.getAttributionTag();
    }

    synchronized void dump(StringBuilder sb, List<ScannerMap.ScannerApp> scannerApps) {
        final long currentTime = System.currentTimeMillis();
        final long currTime = mTimeProvider.elapsedRealtime();
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
            final var scanDuration = currTime - ongoingScan.mStartTimestamp;
            final long suspendDuration =
                    ongoingScan.mIsSuspended ? currTime - ongoingScan.mSuspendStartTime : 0;
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
            for (ScannerMap.ScannerApp scannerApp : scannerApps) {
                sb.append("\n    Application ID: ").append(scannerApp.mId);
                sb.append(", UUID: ").append(scannerApp.mUuid);
                if (scannerApp.mAttributionTag != null) {
                    sb.append(", Tag: ").append(scannerApp.mAttributionTag);
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
            appendScanDetails(sb, mLastScans, currentTime, currTime, false);
        }

        if (!mOngoingScans.isEmpty()) {
            sb.append("\n    Ongoing ").append(mOngoingScans.size()).append(" scans:");
            appendScanDetails(sb, mOngoingScans.values(), currentTime, currTime, true);
        }

        sb.append("\n\n");
    }

    private static void appendScanDetails(
            StringBuilder sb,
            Collection<LastScan> scans,
            long currentTime,
            long currTime,
            boolean isOngoing) {
        for (LastScan scan : scans) {
            final var timestamp =
                    Instant.ofEpochMilli(currentTime - currTime + scan.mStartTimestamp);
            sb.append("\n      ").append(Utils.formatInstant(timestamp)).append(" - ");

            final long duration;
            if (isOngoing) {
                duration = currTime - scan.mStartTimestamp;
                sb.append("Elapsed: ").append(duration).append("ms ");
            } else {
                duration = scan.mEndTimestamp - scan.mStartTimestamp;
                sb.append("Duration: ").append(duration).append("ms ");
            }

            if (scan.mIsOpportunisticScan) sb.append("(Opp) ");
            if (scan.mIsBackgroundScan) sb.append("(Back) ");
            if (scan.mIsTimeout) sb.append("(Forced) ");
            if (scan.mIsFilterScan) sb.append("(Filter) ");
            if (isOngoing && scan.mIsSuspended) sb.append("(Suspended) ");

            sb.append("Results: ").append(scan.mResults);
            sb.append(" id: (").append(scan.mScannerId).append(") ");

            if (scan.mAttributionTag != null) {
                sb.append("[").append(scan.mAttributionTag).append("] ");
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
                    suspendDuration = (currTime - scan.mSuspendStartTime) + scan.mSuspendDuration;
                } else {
                    suspendDuration = scan.mSuspendDuration;
                }
                final var activeDuration = duration - suspendDuration;

                sb.append("\n        └ ");
                sb.append("Active Time: ").append(activeDuration).append("ms");
                sb.append(", Suspended Time: ").append(suspendDuration).append("ms");
            }

            sb.append("\n        └ ").append("Scan Config: ");
            sb.append("[ScanMode=").append(scanModeToString(scan.mScanMode));
            sb.append(", callbackType=").append(callbackTypeToString(scan.mScanCallbackType));
            sb.append("]");

            if (scan.mIsFilterScan) {
                sb.append(scan.mFilterString);
            }
        }
    }
}
