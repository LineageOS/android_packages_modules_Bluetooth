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

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.Nullable;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.WorkSource;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.util.WorkSourceUtil;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** ScanStats class helps keep track of information about scans on a per application basis. */
class AppScanStats {
    private static final String TAG = AppScanStats.class.getSimpleName();

    // Weight is the duty cycle of the scan mode
    static final int OPPORTUNISTIC_WEIGHT = 0;
    static final int SCREEN_OFF_LOW_POWER_WEIGHT = 5;
    static final int LOW_POWER_WEIGHT = 10;
    static final int AMBIENT_DISCOVERY_WEIGHT = 25;
    static final int BALANCED_WEIGHT = 25;
    static final int LOW_LATENCY_WEIGHT = 100;

    static final int LARGE_SCAN_TIME_GAP_MS = 24000;

    private static final ThreadLocal<DateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm:ss"));

    static WorkSourceUtil sRadioScanWorkSourceUtil;
    static int sRadioScanType;
    static int sRadioScanMode;
    static int sRadioScanWindowMs;
    static int sRadioScanIntervalMs;
    static boolean sIsRadioStarted = false;
    static boolean sIsScreenOn = false;
    static int sRadioScanAppImportance = IMPORTANCE_CACHED;

    @GuardedBy("sLock")
    static long sRadioStartTime = 0;

    private static final Object sLock = new Object();

    private static class LastScan {
        public long duration;
        public long suspendDuration;
        public long suspendStartTime;
        public boolean isSuspended;
        public final long timestamp;
        public final long reportDelayMillis;
        public boolean isOpportunisticScan;
        public boolean isTimeout;
        public boolean isDowngraded;
        public boolean isBackgroundScan;
        public final boolean isFilterScan;
        public final boolean isCallbackScan;
        public boolean isBatchScan;
        public boolean isAutoBatchScan;
        public int results;
        public final int scannerId;
        public final int scanMode;
        public final int scanCallbackType;
        public final StringBuilder filterString;
        @Nullable public final String attributionTag;
        public final int appImportanceOnStart;

        LastScan(
                long timestamp,
                long reportDelayMillis,
                boolean isFilterScan,
                boolean isCallbackScan,
                int scannerId,
                int scanMode,
                int scanCallbackType,
                @Nullable String attributionTag,
                int appImportanceOnStart) {
            this.duration = 0;
            this.timestamp = timestamp;
            this.reportDelayMillis = reportDelayMillis;
            this.isOpportunisticScan = false;
            this.isTimeout = false;
            this.isDowngraded = false;
            this.isBackgroundScan = false;
            this.isFilterScan = isFilterScan;
            this.isCallbackScan = isCallbackScan;
            this.isBatchScan = false;
            this.isAutoBatchScan = false;
            this.scanMode = scanMode;
            this.scanCallbackType = scanCallbackType;
            this.attributionTag = attributionTag;
            this.results = 0;
            this.scannerId = scannerId;
            this.suspendDuration = 0;
            this.suspendStartTime = 0;
            this.isSuspended = false;
            this.appImportanceOnStart = appImportanceOnStart;
            this.filterString = new StringBuilder();
        }
    }

    private final List<LastScan> mLastScans = new ArrayList<>();
    private final Map<Integer, LastScan> mOngoingScans = new HashMap<>();

    final String mAppName;
    final ScannerMap mScannerMap; // Used to grab Apps
    final BatteryStatsManager mBatteryStatsManager; // Used to keep track of scans and result stats
    final ScanController mScanController; // Used to add scan event protos to be dumped later

    private final WorkSource mWorkSource; // Used for BatteryStatsManager
    private final WorkSourceUtil mWorkSourceUtil; // Used for BluetoothStatsLog
    private final AdapterService mAdapterService;
    private final TimeProvider mTimeProvider;

    public boolean isAppDead = false;
    public boolean isRegistered = false;
    private int mScansStarted = 0;
    private int mScansStopped = 0;
    private long mScanStartTime = 0;
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
    private int mAppImportance = IMPORTANCE_CACHED;
    private long startTime = 0;
    private int results = 0;

    AppScanStats(
            String name,
            WorkSource source,
            ScannerMap map,
            AdapterService adapterService,
            ScanController scanController,
            TimeProvider timeProvider) {
        mAppName = name;
        mWorkSource =
                requireNonNullElseGet(
                        // Bill the caller if the work source isn't passed through
                        source, () -> new WorkSource(Binder.getCallingUid(), mAppName));
        mWorkSourceUtil = new WorkSourceUtil(mWorkSource);
        mScannerMap = map;
        mAdapterService = requireNonNull(adapterService);
        mBatteryStatsManager = adapterService.getSystemService(BatteryStatsManager.class);
        mScanController = scanController;
        mTimeProvider = requireNonNull(timeProvider);
    }

    private synchronized LastScan getScanFromScannerId(int scannerId) {
        return mOngoingScans.get(scannerId);
    }

    private BluetoothMetricsProto.ScanEvent.Builder createBaseScanEvent(
            BluetoothMetricsProto.ScanEvent.ScanEventType type) {
        return BluetoothMetricsProto.ScanEvent.newBuilder()
                .setScanEventType(type)
                .setScanTechnologyType(
                        BluetoothMetricsProto.ScanEvent.ScanTechnologyType.SCAN_TECH_TYPE_LE)
                .setEventTimeMillis(System.currentTimeMillis())
                .setInitiator(truncateAppName(mAppName));
    }

    synchronized void addResult(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.results++;

            // Only update battery stats after receiving 100 new results in order
            // to lower the cost of the binder transaction
            if (scan.results % 100 == 0) {
                mBatteryStatsManager.reportBleScanResults(mWorkSource, 100);
                BluetoothStatsLog.write(
                        BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
                        mWorkSourceUtil.getUids(),
                        mWorkSourceUtil.getTags(),
                        100);
            }
        }

        results++;
    }

    synchronized boolean isScanning() {
        return !mOngoingScans.isEmpty();
    }

    synchronized boolean isScanTimeout(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.isTimeout;
    }

    synchronized boolean isScanDowngraded(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.isDowngraded;
    }

    synchronized boolean isAutoBatchScan(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null) {
            return false;
        }
        return scan.isAutoBatchScan;
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
        this.mScansStarted++;
        startTime = mTimeProvider.elapsedRealtime();

        LastScan scan =
                new LastScan(
                        startTime,
                        settings.getReportDelayMillis(),
                        isFilterScan,
                        isCallbackScan,
                        scannerId,
                        settings.getScanMode(),
                        settings.getCallbackType(),
                        attributionTag,
                        mAppImportance);
        if (settings != null) {
            scan.isOpportunisticScan = scan.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC;
            scan.isBackgroundScan =
                    (scan.scanCallbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0;
            scan.isBatchScan =
                    settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                            && settings.getReportDelayMillis() != 0;
            switch (scan.scanMode) {
                case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                    mOppScan++;
                    break;
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    mLowPowerScan++;
                    break;
                case ScanSettings.SCAN_MODE_BALANCED:
                    mBalancedScan++;
                    break;
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    mLowLatencyScan++;
                    break;
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                    mAmbientDiscoveryScan++;
                    break;
            }
        }

        if (isFilterScan) {
            for (ScanFilter filter : filters) {
                scan.filterString
                        .append("\n      └ ")
                        .append(filterToStringWithoutNullParam(filter));
            }
        }

        BluetoothMetricsProto.ScanEvent scanEvent =
                createBaseScanEvent(BluetoothMetricsProto.ScanEvent.ScanEventType.SCAN_EVENT_START)
                        .build();
        mScanController.addScanEvent(scanEvent);

        if (!isScanning()) {
            mScanStartTime = startTime;
        }
        boolean isUnoptimized =
                !(scan.isFilterScan || scan.isBackgroundScan || scan.isOpportunisticScan);
        mBatteryStatsManager.reportBleScanStarted(mWorkSource, isUnoptimized);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__ON,
                scan.isFilterScan,
                scan.isBackgroundScan,
                scan.isOpportunisticScan);
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
        long scanDuration = stopTime - scan.timestamp;
        scan.duration = scanDuration;
        if (scan.isSuspended) {
            long suspendDuration = stopTime - scan.suspendStartTime;
            scan.suspendDuration += suspendDuration;
            mTotalSuspendTime += suspendDuration;
        }
        mOngoingScans.remove(scannerId);
        if (mLastScans.size() >= mAdapterService.getScanQuotaCount()) {
            mLastScans.remove(0);
        }
        mLastScans.add(scan);

        BluetoothMetricsProto.ScanEvent scanEvent =
                createBaseScanEvent(BluetoothMetricsProto.ScanEvent.ScanEventType.SCAN_EVENT_STOP)
                        .setNumberResults(scan.results)
                        .build();
        mScanController.addScanEvent(scanEvent);

        mTotalScanTime += scanDuration;
        long activeDuration = scanDuration - scan.suspendDuration;
        mTotalActiveTime += activeDuration;
        switch (scan.scanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                mOppScanTime += activeDuration;
                break;
            case ScanSettings.SCAN_MODE_LOW_POWER:
                mLowPowerScanTime += activeDuration;
                break;
            case ScanSettings.SCAN_MODE_BALANCED:
                mBalancedScanTime += activeDuration;
                break;
            case ScanSettings.SCAN_MODE_LOW_LATENCY:
                mLowLatencyScanTime += activeDuration;
                break;
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                mAmbientDiscoveryScanTime += activeDuration;
                break;
        }

        // Inform battery stats of any results it might be missing on scan stop
        boolean isUnoptimized =
                !(scan.isFilterScan || scan.isBackgroundScan || scan.isOpportunisticScan);
        mBatteryStatsManager.reportBleScanResults(mWorkSource, scan.results % 100);
        mBatteryStatsManager.reportBleScanStopped(mWorkSource, isUnoptimized);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                scan.results % 100);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
                mWorkSourceUtil.getUids(),
                mWorkSourceUtil.getTags(),
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__OFF,
                scan.isFilterScan,
                scan.isBackgroundScan,
                scan.isOpportunisticScan);
        recordScanAppCountMetricsStop(scan);
    }

    private void recordScanAppCountMetricsStart(LastScan scan) {
        MetricsLogger logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_ENABLE, 1);
        if (Flags.bleScanAdvMetricsRedesign()) {
            logger.logAppScanStateChanged(
                    mWorkSourceUtil.getUids(),
                    mWorkSourceUtil.getTags(),
                    true /* enabled */,
                    scan.isFilterScan,
                    scan.isCallbackScan,
                    convertScanCallbackType(scan.scanCallbackType),
                    convertScanType(scan),
                    convertScanMode(scan.scanMode),
                    scan.reportDelayMillis,
                    0 /* app_scan_duration_ms */,
                    mOngoingScans.size(),
                    sIsScreenOn,
                    isAppDead,
                    mAppImportance);
        }
        if (scan.isAutoBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_ENABLE, 1);
        } else if (scan.isBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_ENABLE, 1);
        } else {
            if (scan.isFilterScan) {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_ENABLE, 1);
            } else {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_ENABLE, 1);
            }
        }
    }

    private void recordScanAppCountMetricsStop(LastScan scan) {
        MetricsLogger logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_DISABLE, 1);
        if (Flags.bleScanAdvMetricsRedesign()) {
            logger.logAppScanStateChanged(
                    mWorkSourceUtil.getUids(),
                    mWorkSourceUtil.getTags(),
                    false /* enabled */,
                    scan.isFilterScan,
                    scan.isCallbackScan,
                    convertScanCallbackType(scan.scanCallbackType),
                    convertScanType(scan),
                    convertScanMode(scan.scanMode),
                    scan.reportDelayMillis,
                    scan.duration,
                    mOngoingScans.size(),
                    sIsScreenOn,
                    isAppDead,
                    mAppImportance);
        }
        if (scan.isAutoBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_DISABLE, 1);
        } else if (scan.isBatchScan) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_DISABLE, 1);
        } else {
            if (scan.isFilterScan) {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_DISABLE, 1);
            } else {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_DISABLE, 1);
            }
        }
    }

    private static int convertScanCallbackType(int type) {
        switch (type) {
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES;
            case ScanSettings.CALLBACK_TYPE_FIRST_MATCH:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_FIRST_MATCH;
            case ScanSettings.CALLBACK_TYPE_MATCH_LOST:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_MATCH_LOST;
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES_AUTO_BATCH;
            default:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_UNKNOWN;
        }
    }

    private static int convertScanType(LastScan scan) {
        if (scan == null) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_UNKNOWN;
        }
        if (scan.isAutoBatchScan) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_AUTO_BATCH;
        } else if (scan.isBatchScan) {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_BATCH;
        } else {
            return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR;
        }
    }

    @VisibleForTesting
    static int convertScanMode(int mode) {
        switch (mode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_OPPORTUNISTIC;
            case ScanSettings.SCAN_MODE_LOW_POWER:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER;
            case ScanSettings.SCAN_MODE_BALANCED:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED;
            case ScanSettings.SCAN_MODE_LOW_LATENCY:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY;
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_AMBIENT_DISCOVERY;
            case ScanSettings.SCAN_MODE_SCREEN_OFF:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF;
            case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED:
                return BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF_BALANCED;
            default:
                return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_UNKNOWN;
        }
    }

    synchronized void recordScanTimeoutCountMetrics(int scannerId, long scanTimeoutMillis) {
        if (Flags.bleScanAdvMetricsRedesign()) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.LE_SCAN_ABUSED,
                    mWorkSourceUtil.getUids(),
                    mWorkSourceUtil.getTags(),
                    convertScanType(getScanFromScannerId(scannerId)),
                    BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_SCAN_TIMEOUT,
                    scanTimeoutMillis);
        }
        MetricsLogger.getInstance()
                .cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_SCAN_TIMEOUT, 1);
    }

    synchronized void recordHwFilterNotAvailableCountMetrics(
            int scannerId, long numOfFilterSupported) {
        if (Flags.bleScanAdvMetricsRedesign()) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.LE_SCAN_ABUSED,
                    mWorkSourceUtil.getUids(),
                    mWorkSourceUtil.getTags(),
                    convertScanType(getScanFromScannerId(scannerId)),
                    BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_HW_FILTER_NA,
                    numOfFilterSupported);
        }
        MetricsLogger.getInstance()
                .cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_HW_FILTER_NOT_AVAILABLE, 1);
    }

    synchronized void recordTrackingHwFilterNotAvailableCountMetrics(
            int scannerId, long numOfTrackableAdv) {
        if (Flags.bleScanAdvMetricsRedesign()) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.LE_SCAN_ABUSED,
                    mWorkSourceUtil.getUids(),
                    mWorkSourceUtil.getTags(),
                    convertScanType(getScanFromScannerId(scannerId)),
                    BluetoothStatsLog
                            .LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_TRACKING_HW_FILTER_NA,
                    numOfTrackableAdv);
        }
        MetricsLogger.getInstance()
                .cacheCount(
                        BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_TRACKING_HW_FILTER_NOT_AVAILABLE,
                        1);
    }

    static void initScanRadioState() {
        synchronized (sLock) {
            sIsRadioStarted = false;
        }
    }

    static boolean recordScanRadioStart(
            int scanMode,
            int scannerId,
            AppScanStats stats,
            int scanWindowMs,
            int scanIntervalMs,
            TimeProvider timeProvider) {
        synchronized (sLock) {
            if (sIsRadioStarted) {
                return false;
            }
            sRadioStartTime = timeProvider.elapsedRealtime();
            sRadioScanWorkSourceUtil = stats.mWorkSourceUtil;
            sRadioScanType = convertScanType(stats.getScanFromScannerId(scannerId));
            sRadioScanMode = scanMode;
            sRadioScanWindowMs = scanWindowMs;
            sRadioScanIntervalMs = scanIntervalMs;
            sIsRadioStarted = true;
            sRadioScanAppImportance = stats.mAppImportance;
        }
        return true;
    }

    static boolean recordScanRadioStop(TimeProvider timeProvider) {
        synchronized (sLock) {
            if (!sIsRadioStarted) {
                return false;
            }
            recordScanRadioDurationMetrics(timeProvider);
            if (!Flags.bleScanAdvMetricsRedesign()) {
                sRadioStartTime = 0;
                sIsRadioStarted = false;
            }
        }
        return true;
    }

    @GuardedBy("sLock")
    private static void recordScanRadioDurationMetrics(TimeProvider timeProvider) {
        if (!sIsRadioStarted) {
            return;
        }
        MetricsLogger logger = MetricsLogger.getInstance();
        long currentTime = timeProvider.elapsedRealtime();
        long radioScanDuration = currentTime - sRadioStartTime;
        double scanWeight = getScanWeight(sRadioScanMode) * 0.01;
        long weightedDuration = (long) (radioScanDuration * scanWeight);

        if (Flags.bleScanAdvMetricsRedesign()) {
            logger.logRadioScanStopped(
                    getRadioScanUids(),
                    getRadioScanTags(),
                    sRadioScanType,
                    convertScanMode(sRadioScanMode),
                    sRadioScanIntervalMs,
                    sRadioScanWindowMs,
                    sIsScreenOn,
                    radioScanDuration,
                    sRadioScanAppImportance);
            sRadioStartTime = 0;
            sIsRadioStarted = false;
        }
        if (weightedDuration > 0) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR, weightedDuration);
            if (sIsScreenOn) {
                logger.cacheCount(
                        BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON,
                        weightedDuration);
            } else {
                logger.cacheCount(
                        BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF,
                        weightedDuration);
            }
        }
    }

    private static int[] getRadioScanUids() {
        synchronized (sLock) {
            return sRadioScanWorkSourceUtil != null
                    ? sRadioScanWorkSourceUtil.getUids()
                    : new int[] {0};
        }
    }

    private static String[] getRadioScanTags() {
        synchronized (sLock) {
            return sRadioScanWorkSourceUtil != null
                    ? sRadioScanWorkSourceUtil.getTags()
                    : new String[] {""};
        }
    }

    @GuardedBy("sLock")
    private static void recordScreenOnOffMetrics(boolean isScreenOn) {
        if (isScreenOn) {
            MetricsLogger.getInstance().cacheCount(BluetoothProtoEnums.SCREEN_ON_EVENT, 1);
        } else {
            MetricsLogger.getInstance().cacheCount(BluetoothProtoEnums.SCREEN_OFF_EVENT, 1);
        }
    }

    private static int getScanWeight(int scanMode) {
        switch (scanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                return OPPORTUNISTIC_WEIGHT;
            case ScanSettings.SCAN_MODE_SCREEN_OFF:
                return SCREEN_OFF_LOW_POWER_WEIGHT;
            case ScanSettings.SCAN_MODE_LOW_POWER:
                return LOW_POWER_WEIGHT;
            case ScanSettings.SCAN_MODE_BALANCED:
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
            case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED:
                return BALANCED_WEIGHT;
            case ScanSettings.SCAN_MODE_LOW_LATENCY:
                return LOW_LATENCY_WEIGHT;
            default:
                return LOW_POWER_WEIGHT;
        }
    }

    static void recordScanRadioResultCount() {
        synchronized (sLock) {
            if (!sIsRadioStarted) {
                return;
            }
            if (Flags.bleScanAdvMetricsRedesign()) {
                BluetoothStatsLog.write(
                        BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                        getRadioScanUids(),
                        getRadioScanTags(),
                        1 /* num_results */,
                        BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR,
                        sIsScreenOn);
            }
            MetricsLogger logger = MetricsLogger.getInstance();
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR, 1);
            if (sIsScreenOn) {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_ON, 1);
            } else {
                logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_OFF, 1);
            }
        }
    }

    static void recordBatchScanRadioResultCount(int numRecords) {
        boolean isScreenOn;
        synchronized (sLock) {
            isScreenOn = sIsScreenOn;
        }
        if (Flags.bleScanAdvMetricsRedesign()) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                    getRadioScanUids(),
                    getRadioScanTags(),
                    numRecords,
                    BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_BATCH,
                    sIsScreenOn);
        }
        MetricsLogger logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE, 1);
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH, numRecords);
        if (isScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_ON, 1);
            logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_ON, numRecords);
        } else {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_OFF, 1);
            logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_OFF, numRecords);
        }
    }

    static void setScreenState(boolean isScreenOn, TimeProvider timeProvider) {
        synchronized (sLock) {
            if (sIsScreenOn == isScreenOn) {
                return;
            }
            if (sIsRadioStarted) {
                recordScanRadioDurationMetrics(timeProvider);
                sRadioStartTime = timeProvider.elapsedRealtime();
            }
            recordScreenOnOffMetrics(isScreenOn);
            sIsScreenOn = isScreenOn;
        }
    }

    synchronized void recordScanSuspend(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null || scan.isSuspended) {
            return;
        }
        scan.suspendStartTime = mTimeProvider.elapsedRealtime();
        scan.isSuspended = true;
    }

    synchronized void recordScanResume(int scannerId) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan == null || !scan.isSuspended) {
            return;
        }
        scan.isSuspended = false;
        long stopTime = mTimeProvider.elapsedRealtime();
        long suspendDuration = stopTime - scan.suspendStartTime;
        scan.suspendDuration += suspendDuration;
        mTotalSuspendTime += suspendDuration;
    }

    synchronized void setScanTimeout(int scannerId) {
        if (!isScanning()) {
            return;
        }

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.isTimeout = true;
        }
    }

    synchronized void setScanDowngrade(int scannerId, boolean isDowngrade) {
        if (!isScanning()) {
            return;
        }

        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.isDowngraded = isDowngrade;
        }
    }

    synchronized void setAutoBatchScan(int scannerId, boolean isBatchScan) {
        LastScan scan = getScanFromScannerId(scannerId);
        if (scan != null) {
            scan.isAutoBatchScan = isBatchScan;
        }
    }

    synchronized boolean isScanningTooFrequently() {
        if (mLastScans.size() < mAdapterService.getScanQuotaCount()) {
            return false;
        }

        return (mTimeProvider.elapsedRealtime() - mLastScans.get(0).timestamp)
                < mAdapterService.getScanQuotaWindowMillis();
    }

    synchronized boolean isScanningTooLong() {
        if (!isScanning()) {
            return false;
        }
        return (mTimeProvider.elapsedRealtime() - mScanStartTime)
                >= mAdapterService.getScanTimeoutMillis();
    }

    synchronized boolean hasRecentScan() {
        if (!isScanning() || mLastScans.isEmpty()) {
            return false;
        }
        LastScan lastScan = mLastScans.get(mLastScans.size() - 1);
        return ((mTimeProvider.elapsedRealtime() - lastScan.duration - lastScan.timestamp)
                < LARGE_SCAN_TIME_GAP_MS);
    }

    // This function truncates the app name for privacy reasons. Apps with
    // four part package names or more get truncated to three parts, and apps
    // with three part package names names get truncated to two. Apps with two
    // or less package names names are untouched.
    // Examples: one.two.three.four => one.two.three
    //           one.two.three => one.two
    private static String truncateAppName(String name) {
        String initiator = name;
        String[] nameSplit = initiator.split("\\.");
        if (nameSplit.length > 3) {
            initiator = nameSplit[0] + "." + nameSplit[1] + "." + nameSplit[2];
        } else if (nameSplit.length == 3) {
            initiator = nameSplit[0] + "." + nameSplit[1];
        }

        return initiator;
    }

    private static String filterToStringWithoutNullParam(ScanFilter filter) {
        StringBuilder filterString = new StringBuilder("BluetoothLeScanFilter [");
        if (filter.getDeviceName() != null) {
            filterString.append(" DeviceName=").append(filter.getDeviceName());
        }
        if (filter.getDeviceAddress() != null) {
            filterString.append(" DeviceAddress=").append(filter.getDeviceAddress());
        }
        if (filter.getServiceUuid() != null) {
            filterString.append(" ServiceUuid=").append(filter.getServiceUuid());
        }
        if (filter.getServiceUuidMask() != null) {
            filterString.append(" ServiceUuidMask=").append(filter.getServiceUuidMask());
        }
        if (filter.getServiceSolicitationUuid() != null) {
            filterString
                    .append(" ServiceSolicitationUuid=")
                    .append(filter.getServiceSolicitationUuid());
        }
        if (filter.getServiceSolicitationUuidMask() != null) {
            filterString
                    .append(" ServiceSolicitationUuidMask=")
                    .append(filter.getServiceSolicitationUuidMask());
        }
        if (filter.getServiceDataUuid() != null) {
            filterString
                    .append(" ServiceDataUuid=")
                    .append(Objects.toString(filter.getServiceDataUuid()));
        }
        if (filter.getServiceData() != null) {
            filterString.append(" ServiceData=").append(Arrays.toString(filter.getServiceData()));
        }
        if (filter.getServiceDataMask() != null) {
            filterString
                    .append(" ServiceDataMask=")
                    .append(Arrays.toString(filter.getServiceDataMask()));
        }
        if (filter.getManufacturerId() >= 0) {
            filterString.append(" ManufacturerId=").append(filter.getManufacturerId());
        }
        if (filter.getManufacturerData() != null) {
            filterString
                    .append(" ManufacturerData=")
                    .append(Arrays.toString(filter.getManufacturerData()));
        }
        if (filter.getManufacturerDataMask() != null) {
            filterString
                    .append(" ManufacturerDataMask=")
                    .append(Arrays.toString(filter.getManufacturerDataMask()));
        }
        filterString.append(" ]");
        return filterString.toString();
    }

    private static String scanModeToString(int scanMode) {
        switch (scanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                return "OPPORTUNISTIC";
            case ScanSettings.SCAN_MODE_LOW_LATENCY:
                return "LOW_LATENCY";
            case ScanSettings.SCAN_MODE_BALANCED:
                return "BALANCED";
            case ScanSettings.SCAN_MODE_LOW_POWER:
                return "LOW_POWER";
            case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                return "AMBIENT_DISCOVERY";
            default:
                return "UNKNOWN(" + scanMode + ")";
        }
    }

    private static String callbackTypeToString(int callbackType) {
        switch (callbackType) {
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES:
                return "ALL_MATCHES";
            case ScanSettings.CALLBACK_TYPE_FIRST_MATCH:
                return "FIRST_MATCH";
            case ScanSettings.CALLBACK_TYPE_MATCH_LOST:
                return "LOST";
            case ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH:
                return "ALL_MATCHES_AUTO_BATCH";
            default:
                return callbackType
                                == (ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                        ? "[FIRST_MATCH | LOST]"
                        : "UNKNOWN: " + callbackType;
        }
    }

    @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
    public synchronized void dumpToString(StringBuilder sb) {
        long currentTime = System.currentTimeMillis();
        long currTime = mTimeProvider.elapsedRealtime();
        long scanDuration = 0;
        long suspendDuration = 0;
        long activeDuration = 0;
        long totalActiveTime = mTotalActiveTime;
        long totalSuspendTime = mTotalSuspendTime;
        long totalScanTime = mTotalScanTime;
        long oppScanTime = mOppScanTime;
        long lowPowerScanTime = mLowPowerScanTime;
        long balancedScanTime = mBalancedScanTime;
        long lowLatencyScanTime = mLowLatencyScanTime;
        long ambientDiscoveryScanTime = mAmbientDiscoveryScanTime;
        int oppScan = mOppScan;
        int lowPowerScan = mLowPowerScan;
        int balancedScan = mBalancedScan;
        int lowLatencyScan = mLowLatencyScan;
        long ambientDiscoveryScan = mAmbientDiscoveryScan;

        for (LastScan scan : mOngoingScans.values()) {
            scanDuration = currTime - scan.timestamp;

            if (scan.isSuspended) {
                suspendDuration = currTime - scan.suspendStartTime;
                totalSuspendTime += suspendDuration;
            }

            totalScanTime += scanDuration;
            totalSuspendTime += suspendDuration;
            activeDuration = scanDuration - scan.suspendDuration - suspendDuration;
            totalActiveTime += activeDuration;
            switch (scan.scanMode) {
                case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                    oppScanTime += activeDuration;
                    break;
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    lowPowerScanTime += activeDuration;
                    break;
                case ScanSettings.SCAN_MODE_BALANCED:
                    balancedScanTime += activeDuration;
                    break;
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    lowLatencyScanTime += activeDuration;
                    break;
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                    ambientDiscoveryScan += activeDuration;
                    break;
            }
        }

        long Score =
                (oppScanTime * OPPORTUNISTIC_WEIGHT
                                + lowPowerScanTime * LOW_POWER_WEIGHT
                                + balancedScanTime * BALANCED_WEIGHT
                                + lowLatencyScanTime * LOW_LATENCY_WEIGHT
                                + ambientDiscoveryScanTime * AMBIENT_DISCOVERY_WEIGHT)
                        / 100;

        sb.append("  ").append(mAppName);
        if (isRegistered) {
            sb.append(" (Registered)");
        }

        sb.append("\n  LE scans (started/stopped)                                  : ")
                .append(mScansStarted)
                .append(" / ")
                .append(mScansStopped);
        sb.append("\n  Scan time in ms (active/suspend/total)                      : ")
                .append(totalActiveTime)
                .append(" / ")
                .append(totalSuspendTime)
                .append(" / ")
                .append(totalScanTime);
        sb.append("\n  Scan time with mode in ms ")
                .append("(Opp/LowPower/Balanced/LowLatency/AmbientDiscovery):")
                .append(oppScanTime)
                .append(" / ")
                .append(lowPowerScanTime)
                .append(" / ")
                .append(balancedScanTime)
                .append(" / ")
                .append(lowLatencyScanTime)
                .append(" / ")
                .append(ambientDiscoveryScanTime);
        sb.append("\n  Scan mode counter (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery):")
                .append(oppScan)
                .append(" / ")
                .append(lowPowerScan)
                .append(" / ")
                .append(balancedScan)
                .append(" / ")
                .append(lowLatencyScan)
                .append(" / ")
                .append(ambientDiscoveryScan);
        sb.append("\n  Score                                                       : ")
                .append(Score);
        sb.append("\n  Total number of results                                     : ")
                .append(results);

        if (!mLastScans.isEmpty()) {
            sb.append("\n  Last ")
                    .append(mLastScans.size())
                    .append(" scans                                                :");

            for (int i = 0; i < mLastScans.size(); i++) {
                LastScan scan = mLastScans.get(i);
                Date timestamp = new Date(currentTime - currTime + scan.timestamp);
                sb.append("\n    ").append(DATE_FORMAT.get().format(timestamp)).append(" - ");
                sb.append(scan.duration).append("ms ");
                if (scan.isOpportunisticScan) {
                    sb.append("Opp ");
                }
                if (scan.isBackgroundScan) {
                    sb.append("Back ");
                }
                if (scan.isTimeout) {
                    sb.append("Forced ");
                }
                if (scan.isFilterScan) {
                    sb.append("Filter ");
                }
                sb.append(scan.results).append(" results");
                sb.append(" (").append(scan.scannerId).append(") ");
                if (scan.attributionTag != null) {
                    sb.append(" [").append(scan.attributionTag).append("] ");
                }
                if (scan.isCallbackScan) {
                    sb.append("CB ");
                } else {
                    sb.append("PI ");
                }
                if (scan.isBatchScan) {
                    sb.append("Batch Scan");
                } else if (scan.isAutoBatchScan) {
                    sb.append("Auto Batch Scan");
                } else {
                    sb.append("Regular Scan");
                }
                if (scan.appImportanceOnStart < IMPORTANCE_FOREGROUND_SERVICE) {
                    sb.append("\n      └ ")
                            .append("App Importance: higher than Foreground Service");
                } else if (scan.appImportanceOnStart > IMPORTANCE_FOREGROUND_SERVICE) {
                    sb.append("\n      └ ").append("App Importance: lower than Foreground Service");
                } else {
                    sb.append("\n      └ ").append("App Importance: Foreground Service");
                }
                if (scan.suspendDuration != 0) {
                    activeDuration = scan.duration - scan.suspendDuration;
                    sb.append("\n      └ ")
                            .append("Suspended Time: ")
                            .append(scan.suspendDuration)
                            .append("ms, Active Time: ")
                            .append(activeDuration);
                }
                sb.append("\n      └ ")
                        .append("Scan Config: [ ScanMode=")
                        .append(scanModeToString(scan.scanMode))
                        .append(", callbackType=")
                        .append(callbackTypeToString(scan.scanCallbackType))
                        .append(" ]");
                if (scan.isFilterScan) {
                    sb.append(scan.filterString);
                }
            }
        }

        if (!mOngoingScans.isEmpty()) {
            sb.append("\n  Ongoing scans                                               :");
            for (LastScan scan : mOngoingScans.values()) {
                Date timestamp = new Date(currentTime - currTime + scan.timestamp);
                sb.append("\n    ").append(DATE_FORMAT.get().format(timestamp)).append(" - ");
                sb.append((currTime - scan.timestamp)).append("ms ");
                if (scan.isOpportunisticScan) {
                    sb.append("Opp ");
                }
                if (scan.isBackgroundScan) {
                    sb.append("Back ");
                }
                if (scan.isTimeout) {
                    sb.append("Forced ");
                }
                if (scan.isFilterScan) {
                    sb.append("Filter ");
                }
                if (scan.isSuspended) {
                    sb.append("Suspended ");
                }
                sb.append(scan.results).append(" results");
                sb.append(" (").append(scan.scannerId).append(") ");
                if (scan.isCallbackScan) {
                    sb.append("CB ");
                } else {
                    sb.append("PI ");
                }
                if (scan.isBatchScan) {
                    sb.append("Batch Scan");
                } else if (scan.isAutoBatchScan) {
                    sb.append("Auto Batch Scan");
                } else {
                    sb.append("Regular Scan");
                }
                if (scan.suspendStartTime != 0) {
                    activeDuration = scan.duration - scan.suspendDuration;
                    sb.append("\n      └ ")
                            .append("Suspended Time:")
                            .append(scan.suspendDuration)
                            .append("ms, Active Time:")
                            .append(activeDuration);
                }
                sb.append("\n      └ ")
                        .append("Scan Config: [ ScanMode=")
                        .append(scanModeToString(scan.scanMode))
                        .append(", callbackType=")
                        .append(callbackTypeToString(scan.scanCallbackType))
                        .append(" ]");
                if (scan.isFilterScan) {
                    sb.append(scan.filterString);
                }
            }
        }

        if (isRegistered) {
            List<ScannerMap.ScannerApp> appEntries = mScannerMap.getByName(mAppName);
            for (ScannerMap.ScannerApp appEntry : appEntries) {
                sb.append("\n  Application ID: ").append(appEntry.mId);
                sb.append(", UUID: ").append(appEntry.mUuid);
                if (appEntry.mAttributionTag != null) {
                    sb.append(", Tag: ").append(appEntry.mAttributionTag);
                }
            }
        }
        sb.append("\n\n");
    }
}
