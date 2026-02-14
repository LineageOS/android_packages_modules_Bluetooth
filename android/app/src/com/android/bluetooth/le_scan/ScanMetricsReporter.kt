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

import android.bluetooth.BluetoothProtoEnums
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_DISABLE
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_AUTO_BATCH_ENABLE
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_DISABLE
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_BATCH_ENABLE
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_DISABLE
import android.bluetooth.BluetoothProtoEnums.LE_SCAN_COUNT_FILTERED_ENABLE
import android.bluetooth.le.ScanSettings
import android.os.BatteryStatsManager
import android.os.WorkSource
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.metrics.MetricsLogger
import com.android.bluetooth.util.WorkSourceUtil

/**
 * This class handles all logic related to reporting scan metrics to BatteryStats,
 * BluetoothStatsLog, and MetricsLogger. It is designed to be a stateless helper, receiving all
 * necessary information to perform its reporting tasks.
 */
class ScanMetricsReporter(
    private val workSource: WorkSource,
    private val workSourceUtil: WorkSourceUtil,
    private val batteryStatsManager: BatteryStatsManager,
) {
    private val logger: MetricsLogger
        get() = MetricsLogger.getInstance()

    fun reportLeScanResult(
        isBatch: Boolean,
        numRecords: Int,
        isScreenOn: Boolean,
        attributionTag: String,
        scan: AppScanStats.LastScan,
    ) =
        if (isBatch) {
            BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                workSourceUtil.uids,
                workSourceUtil.tags,
                numRecords,
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_BATCH,
                isScreenOn,
                attributionTag,
                scan.isFilterScan,
                scan.isCallbackScan,
                convertScanCallbackType(scan.callbackType),
                convertScanMode(scan.scanMode.value),
            )
        } else {
            BluetoothStatsLog.write(
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED,
                workSourceUtil.uids,
                workSourceUtil.tags,
                1, /* num_results */
                BluetoothStatsLog.LE_SCAN_RESULT_RECEIVED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR,
                isScreenOn,
                attributionTag,
                scan.isFilterScan,
                scan.isCallbackScan,
                convertScanCallbackType(scan.callbackType),
                convertScanMode(scan.scanMode.value),
            )
        }

    fun reportScanResults(numberOfNewResults: Int) {
        batteryStatsManager.reportBleScanResults(workSource, numberOfNewResults)
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            numberOfNewResults,
        )
    }

    fun recordScanStart(
        scan: AppScanStats.LastScan,
        ongoingScansCount: Int,
        isScreenOn: Boolean,
        isAppDead: Boolean,
        appImportance: Int,
    ) {
        val isUnoptimized =
            !(scan.isFilterScan || scan.isBackgroundScan || scan.isOpportunisticScan)
        batteryStatsManager.reportBleScanStarted(workSource, isUnoptimized)
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__ON,
            scan.isFilterScan,
            scan.isBackgroundScan,
            scan.isOpportunisticScan,
        )
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_ENABLE, 1)
        logger.logAppScanStateChanged(
            workSourceUtil.uids,
            workSourceUtil.tags,
            true, /* enabled */
            scan.isFilterScan,
            scan.isCallbackScan,
            convertScanCallbackType(scan.callbackType),
            convertScanType(scan),
            convertScanMode(scan.scanMode.value),
            scan.reportDelayMillis,
            0, /* app_scan_duration_ms */
            ongoingScansCount,
            isScreenOn,
            isAppDead,
            appImportance,
            scan.attributionTag ?: "",
        )
        when {
            scan.isAutoBatchScan -> logger.cacheCount(LE_SCAN_COUNT_AUTO_BATCH_ENABLE, 1)
            scan.isBatchScan -> logger.cacheCount(LE_SCAN_COUNT_BATCH_ENABLE, 1)
            scan.isFilterScan -> logger.cacheCount(LE_SCAN_COUNT_FILTERED_ENABLE, 1)
            else -> logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_ENABLE, 1)
        }
    }

    fun recordScanStop(
        scan: AppScanStats.LastScan,
        duration: Long,
        ongoingScansCount: Int,
        isScreenOn: Boolean,
        isAppDead: Boolean,
        appImportance: Int,
    ) {
        // Inform battery stats of any results it might be missing on scan stop
        val isUnoptimized =
            !(scan.isFilterScan || scan.isBackgroundScan || scan.isOpportunisticScan)
        val results = scan.resultsScreenOff + scan.resultsScreenOn
        batteryStatsManager.reportBleScanResults(workSource, results % 100)
        batteryStatsManager.reportBleScanStopped(workSource, isUnoptimized)
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLE_SCAN_RESULT_RECEIVED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            results % 100,
        )
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__OFF,
            scan.isFilterScan,
            scan.isBackgroundScan,
            scan.isOpportunisticScan,
        )
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_TOTAL_DISABLE, 1)
        logger.logAppScanStateChanged(
            workSourceUtil.uids,
            workSourceUtil.tags,
            false, /* enabled */
            scan.isFilterScan,
            scan.isCallbackScan,
            convertScanCallbackType(scan.callbackType),
            convertScanType(scan),
            convertScanMode(scan.scanMode.value),
            scan.reportDelayMillis,
            duration,
            ongoingScansCount,
            isScreenOn,
            isAppDead,
            appImportance,
            scan.attributionTag ?: "",
        )
        when {
            scan.isAutoBatchScan -> logger.cacheCount(LE_SCAN_COUNT_AUTO_BATCH_DISABLE, 1)
            scan.isBatchScan -> logger.cacheCount(LE_SCAN_COUNT_BATCH_DISABLE, 1)
            scan.isFilterScan -> logger.cacheCount(LE_SCAN_COUNT_FILTERED_DISABLE, 1)
            else -> logger.cacheCount(BluetoothProtoEnums.LE_SCAN_COUNT_UNFILTERED_DISABLE, 1)
        }
    }

    fun recordScanTimeoutCount(scan: AppScanStats.LastScan?, scanTimeoutMillis: Long) {
        BluetoothStatsLog.write(
            BluetoothStatsLog.LE_SCAN_ABUSED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            convertScanType(scan),
            BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_SCAN_TIMEOUT,
            scanTimeoutMillis,
            scan?.attributionTag ?: "",
        )
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_SCAN_TIMEOUT, 1)
    }

    fun recordHwFilterNotAvailableCount(scan: AppScanStats.LastScan?, numOfFilterSupported: Long) {
        BluetoothStatsLog.write(
            BluetoothStatsLog.LE_SCAN_ABUSED,
            workSourceUtil.uids,
            workSourceUtil.tags,
            convertScanType(scan),
            BluetoothStatsLog.LE_SCAN_ABUSED__LE_SCAN_ABUSE_REASON__REASON_HW_FILTER_NA,
            numOfFilterSupported,
            scan?.attributionTag ?: "",
        )
        logger.cacheCount(BluetoothProtoEnums.LE_SCAN_ABUSE_COUNT_HW_FILTER_NOT_AVAILABLE, 1)
    }

    private fun convertScanCallbackType(callbackType: CallbackType): Int =
        when (callbackType.value) {
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES ->
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH ->
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_FIRST_MATCH
            ScanSettings.CALLBACK_TYPE_MATCH_LOST ->
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_MATCH_LOST
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH ->
                BluetoothStatsLog
                    .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES_AUTO_BATCH
            else -> BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_UNKNOWN
        }

    companion object {
        @JvmStatic
        fun convertScanMode(mode: Int): Int =
            when (mode) {
                ScanSettings.SCAN_MODE_OPPORTUNISTIC ->
                    BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_OPPORTUNISTIC
                ScanSettings.SCAN_MODE_LOW_POWER ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER
                ScanSettings.SCAN_MODE_BALANCED ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED
                ScanSettings.SCAN_MODE_LOW_LATENCY ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                    BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_AMBIENT_DISCOVERY
                ScanSettings.SCAN_MODE_SCREEN_OFF ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF
                ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED ->
                    BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_SCREEN_OFF_BALANCED
                else -> BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_UNKNOWN
            }

        @JvmStatic
        fun convertScanType(scan: AppScanStats.LastScan?): Int =
            when {
                scan == null ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_UNKNOWN
                scan.isAutoBatchScan ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_AUTO_BATCH
                scan.isBatchScan ->
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_BATCH
                else -> BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR
            }
    }
}
