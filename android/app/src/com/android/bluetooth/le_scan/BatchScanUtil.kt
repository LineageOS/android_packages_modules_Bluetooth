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

import android.bluetooth.le.ScanSettings
import android.provider.Settings
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.scanModeToString

private const val TAG = "BatchScanUtil"

data class BatchScanParams(
    val scanMode: Int,
    val fullScanScannerId: Int,
    val truncatedScanScannerId: Int,
)

object BatchScanUtil {

    /** Return batch scan result type value defined in bt stack. */
    @JvmStatic
    fun resultType(params: BatchScanParams) =
        when {
            params.fullScanScannerId != -1 && params.truncatedScanScannerId != -1 ->
                ScanUtil.SCAN_RESULT_TYPE_BOTH
            params.truncatedScanScannerId != -1 -> ScanUtil.SCAN_RESULT_TYPE_TRUNCATED
            params.fullScanScannerId != -1 -> ScanUtil.SCAN_RESULT_TYPE_FULL
            else -> -1
        }

    @JvmStatic
    fun fullScanStoragePercent(resultType: Int) =
        when (resultType) {
            ScanUtil.SCAN_RESULT_TYPE_FULL -> 100
            ScanUtil.SCAN_RESULT_TYPE_TRUNCATED -> 0
            ScanUtil.SCAN_RESULT_TYPE_BOTH -> 50
            else -> 50
        }

    // Batched scan doesn't require high duty cycle scan because scan result is reported
    // infrequently anyway. To avoid redefining parameter sets, map to the low duty cycle parameter
    // set as follows.
    @JvmStatic
    fun windowMillis(adapterService: AdapterService, scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                    SCAN_MODE_BALANCED_WINDOW_MS,
                )
            ScanSettings.SCAN_MODE_SCREEN_OFF ->
                adapterService.screenOffLowPowerWindow.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    SCAN_MODE_LOW_POWER_WINDOW_MS,
                )
        }.also { windowMs ->
            Log.d(TAG, "windowMillis=${windowMs}ms for scan mode=${scanModeToString(scanMode)}")
        }

    @JvmStatic
    fun intervalMillis(adapterService: AdapterService, scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                    SCAN_MODE_BALANCED_INTERVAL_MS,
                )
            ScanSettings.SCAN_MODE_SCREEN_OFF ->
                adapterService.screenOffLowPowerInterval.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    SCAN_MODE_LOW_POWER_INTERVAL_MS,
                )
        }.also { intervalMs ->
            Log.d(TAG, "intervalMillis=${intervalMs}ms for scan mode=${scanModeToString(scanMode)}")
        }
}
