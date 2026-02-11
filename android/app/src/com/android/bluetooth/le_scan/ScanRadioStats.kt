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

import android.app.ActivityManager
import android.bluetooth.BluetoothProtoEnums
import android.util.Log
import com.android.bluetooth.metrics.MetricsLogger
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil

private const val TAG = ScanUtil.TAG_PREFIX + "ScanRadioStats"

class ScanRadioStats(private val timeProvider: TimeProvider) {

    private var isRadioStarted = false
    private var isScreenOn = false
    private var radioStartTime = 0L
    private var radioScanWorkSourceUtil: WorkSourceUtil? = null
    private var radioScanType = 0
    private var radioScanMode = 0
    private var radioScanWindowMs = 0
    private var radioScanIntervalMs = 0
    private var radioScanAppImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
    private var radioScanAttributionTag: String? = null

    private val logger: MetricsLogger
        get() = MetricsLogger.getInstance()

    fun setScreenState(screenOn: Boolean) {
        if (isScreenOn == screenOn) {
            return
        }
        if (isRadioStarted) {
            recordScanRadioDurationMetrics()
            radioStartTime = timeProvider.elapsedRealtime()
        }
        isScreenOn = screenOn
        recordScreenOnOffMetrics()
    }

    fun recordScanRadioStart(
        scanMode: Int,
        scannerId: Int,
        stats: AppScanStats,
        scanWindowMs: Int,
        scanIntervalMs: Int,
    ) {
        if (isRadioStarted) {
            Log.w(TAG, "recordScanRadioStart(): Scan radio already started")
            return
        }
        radioStartTime = timeProvider.elapsedRealtime()
        radioScanWorkSourceUtil = stats.workSourceUtil
        radioScanType = ScanMetricsReporter.convertScanType(stats.getScanFromScannerId(scannerId))
        radioScanMode = scanMode
        radioScanWindowMs = scanWindowMs
        radioScanIntervalMs = scanIntervalMs
        isRadioStarted = true
        radioScanAppImportance = stats.appImportance
        radioScanAttributionTag = stats.getAttributionTagFromScannerId(scannerId)
    }

    fun recordScanRadioStop(caller: String?) {
        if (!isRadioStarted) {
            Log.w(TAG, "recordScanRadioStop(caller=$caller): No scan radio to stop")
            return
        }
        recordScanRadioDurationMetrics()
    }

    fun recordScanRadioResultCount() {
        if (!isRadioStarted) {
            return
        }
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR, 1)
        if (isScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_ON, 1)
        } else {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_REGULAR_SCREEN_OFF, 1)
        }
    }

    fun recordBatchScanRadioResultCount(numRecords: Int) {
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE, 1)
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH, numRecords.toLong())
        if (isScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_ON, 1)
            logger.cacheCount(
                BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_ON,
                numRecords.toLong(),
            )
        } else {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_BUNDLE_SCREEN_OFF, 1)
            logger.cacheCount(
                BluetoothProtoEnums.LE_SCAN_RESULTS_COUNT_BATCH_SCREEN_OFF,
                numRecords.toLong(),
            )
        }
    }

    private fun recordScanRadioDurationMetrics() {
        if (!isRadioStarted) {
            return
        }
        val currentTime = timeProvider.elapsedRealtime()
        val radioScanDuration = currentTime - radioStartTime
        val scanWeight = ScanUtil.weightForScanMode(radioScanMode) * 0.01
        val weightedDuration = (radioScanDuration * scanWeight).toLong()

        logger.logRadioScanStopped(
            radioScanUids(),
            radioScanTags(),
            radioScanType,
            ScanMetricsReporter.convertScanMode(radioScanMode),
            radioScanIntervalMs.toLong(),
            radioScanWindowMs.toLong(),
            isScreenOn,
            radioScanDuration,
            radioScanAppImportance,
            radioScanAttributionTag ?: "",
        )
        radioStartTime = 0
        isRadioStarted = false
        if (weightedDuration > 0) {
            logger.cacheCount(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR, weightedDuration)
            if (isScreenOn) {
                logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON,
                    weightedDuration,
                )
            } else {
                logger.cacheCount(
                    BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF,
                    weightedDuration,
                )
            }
        }
    }

    private fun recordScreenOnOffMetrics() {
        if (isScreenOn) {
            logger.cacheCount(BluetoothProtoEnums.SCREEN_ON_EVENT, 1)
        } else {
            logger.cacheCount(BluetoothProtoEnums.SCREEN_OFF_EVENT, 1)
        }
    }

    private fun radioScanUids() = radioScanWorkSourceUtil?.uids ?: intArrayOf(0)

    private fun radioScanTags() = radioScanWorkSourceUtil?.tags ?: arrayOf("")
}
