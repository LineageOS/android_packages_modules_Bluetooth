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

package com.android.bluetooth.le_scan;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

import android.annotation.Nullable;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.le.ScanSettings;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.util.WorkSourceUtil;

class ScanRadioStats {
    private static final String TAG = ScanRadioStats.class.getSimpleName();

    // Weight is the duty cycle of the scan mode
    static final int OPPORTUNISTIC_WEIGHT = 0;
    static final int SCREEN_OFF_LOW_POWER_WEIGHT = 5;
    static final int LOW_POWER_WEIGHT = 10;
    static final int AMBIENT_DISCOVERY_WEIGHT = 25;
    static final int BALANCED_WEIGHT = 25;
    static final int LOW_LATENCY_WEIGHT = 100;

    private final TimeProvider mTimeProvider;

    private boolean mIsRadioStarted = false;
    private boolean mIsScreenOn = false;
    private long mRadioStartTime = 0;
    private WorkSourceUtil mRadioScanWorkSourceUtil;
    private int mRadioScanType;
    private int mRadioScanMode;
    private int mRadioScanWindowMs;
    private int mRadioScanIntervalMs;
    private int mRadioScanAppImportance = IMPORTANCE_CACHED;
    @Nullable private String mRadioScanAttributionTag;

    ScanRadioStats(TimeProvider timeProvider) {
        mTimeProvider = timeProvider;
    }

    void initScanRadioState() {
        mIsRadioStarted = false;
    }

    void setScreenState(boolean isScreenOn) {
        if (mIsScreenOn == isScreenOn) {
            return;
        }
        if (mIsRadioStarted) {
            recordScanRadioDurationMetrics();
            mRadioStartTime = mTimeProvider.elapsedRealtime();
        }
        mIsScreenOn = isScreenOn;
        recordScreenOnOffMetrics();
    }

    boolean recordScanRadioStart(
            int scanMode, int scannerId, AppScanStats stats, int scanWindowMs, int scanIntervalMs) {
        if (mIsRadioStarted) {
            return false;
        }
        mRadioStartTime = mTimeProvider.elapsedRealtime();
        mRadioScanWorkSourceUtil = stats.mWorkSourceUtil;
        mRadioScanType = AppScanStats.convertScanType(stats.getScanFromScannerId(scannerId));
        mRadioScanMode = scanMode;
        mRadioScanWindowMs = scanWindowMs;
        mRadioScanIntervalMs = scanIntervalMs;
        mIsRadioStarted = true;
        mRadioScanAppImportance = stats.mAppImportance;
        mRadioScanAttributionTag = stats.getAttributionTagFromScannerId(scannerId);
        return true;
    }

    boolean recordScanRadioStop() {
        if (!mIsRadioStarted) {
            return false;
        }
        recordScanRadioDurationMetrics();
        return true;
    }

    void recordScanRadioResultCount() {
        if (!mIsRadioStarted) {
            return;
        }
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                getRadioScanUids(),
                getRadioScanTags(),
                1 /* num_results */,
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR,
                mIsScreenOn,
                getRadioScanAttributionTag());
        final var logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR, 1);
        if (mIsScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_ON, 1);
        } else {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_OFF, 1);
        }
    }

    void recordBatchScanRadioResultCount(int numRecords) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                getRadioScanUids(),
                getRadioScanTags(),
                numRecords,
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_BATCH,
                mIsScreenOn,
                getRadioScanAttributionTag());
        final var logger = MetricsLogger.getInstance();
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE, 1);
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH, numRecords);
        if (mIsScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_ON, 1);
            logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_ON, numRecords);
        } else {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_OFF, 1);
            logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_OFF, numRecords);
        }
    }

    private void recordScanRadioDurationMetrics() {
        if (!mIsRadioStarted) {
            return;
        }
        long currentTime = mTimeProvider.elapsedRealtime();
        long radioScanDuration = currentTime - mRadioStartTime;
        double scanWeight = getScanWeight(mRadioScanMode) * 0.01;
        long weightedDuration = (long) (radioScanDuration * scanWeight);

        final var logger = MetricsLogger.getInstance();
        logger.logRadioScanStopped(
                getRadioScanUids(),
                getRadioScanTags(),
                mRadioScanType,
                AppScanStats.convertScanMode(mRadioScanMode),
                mRadioScanIntervalMs,
                mRadioScanWindowMs,
                mIsScreenOn,
                radioScanDuration,
                mRadioScanAppImportance,
                getRadioScanAttributionTag());
        mRadioStartTime = 0;
        mIsRadioStarted = false;
        if (weightedDuration > 0) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR, weightedDuration);
            if (mIsScreenOn) {
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

    private void recordScreenOnOffMetrics() {
        final var logger = MetricsLogger.getInstance();
        if (mIsScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.SCREEN_ON_EVENT, 1);
        } else {
            logger.cacheCount(BluetoothProtoEnums.SCREEN_OFF_EVENT, 1);
        }
    }

    private int[] getRadioScanUids() {
        return mRadioScanWorkSourceUtil != null
                ? mRadioScanWorkSourceUtil.getUids()
                : new int[] {0};
    }

    private String[] getRadioScanTags() {
        return mRadioScanWorkSourceUtil != null
                ? mRadioScanWorkSourceUtil.getTags()
                : new String[] {""};
    }

    private String getRadioScanAttributionTag() {
        return mRadioScanAttributionTag != null ? mRadioScanAttributionTag : "";
    }

    private static int getScanWeight(int scanMode) {
        return switch (scanMode) {
            case ScanSettings.SCAN_MODE_OPPORTUNISTIC -> OPPORTUNISTIC_WEIGHT;
            case ScanSettings.SCAN_MODE_SCREEN_OFF -> SCREEN_OFF_LOW_POWER_WEIGHT;
            case ScanSettings.SCAN_MODE_LOW_POWER -> LOW_POWER_WEIGHT;
            case ScanSettings.SCAN_MODE_LOW_LATENCY -> LOW_LATENCY_WEIGHT;
            case ScanSettings.SCAN_MODE_BALANCED,
                    ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
                    ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED ->
                    BALANCED_WEIGHT;
            default -> LOW_POWER_WEIGHT;
        };
    }
}
