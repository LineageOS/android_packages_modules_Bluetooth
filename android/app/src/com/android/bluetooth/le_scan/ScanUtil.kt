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

object ScanUtil {

    // Scan params corresponding to regular scan setting
    const val SCAN_MODE_LOW_POWER_WINDOW_MS = 140
    const val SCAN_MODE_LOW_POWER_INTERVAL_MS = 1400
    const val SCAN_MODE_BALANCED_WINDOW_MS = 183
    const val SCAN_MODE_BALANCED_INTERVAL_MS = 730
    const val SCAN_MODE_LOW_LATENCY_WINDOW_MS = 100
    const val SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 100

    const val SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW_MS = 512
    const val SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL_MS = 10240
    const val SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW_MS = 183
    const val SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL_MS = 730

    // Result types defined in bt stack
    const val SCAN_RESULT_TYPE_TRUNCATED = 1
    const val SCAN_RESULT_TYPE_FULL = 2
    const val SCAN_RESULT_TYPE_BOTH = 3

    // The default floor value for LE batch scan report delays greater than 0
    const val DEFAULT_REPORT_DELAY_FLOOR_MS = 5000L

    const val ACTION_REFRESH_BATCHED_SCAN = "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN"

    // Weights representing the duty cycle of each scan mode
    const val WEIGHT_OPPORTUNISTIC = 0
    const val WEIGHT_SCREEN_OFF_LOW_POWER = 5
    const val WEIGHT_LOW_POWER = 10
    const val WEIGHT_AMBIENT_DISCOVERY = 25
    const val WEIGHT_BALANCED = 25
    const val WEIGHT_LOW_LATENCY = 100

    @JvmStatic
    fun minScanMode(oldScanMode: Int, newScanMode: Int) =
        if (priorityForScanMode(oldScanMode) <= priorityForScanMode(newScanMode)) {
            oldScanMode
        } else {
            newScanMode
        }

    @JvmStatic
    fun priorityForScanMode(scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> 0
            ScanSettings.SCAN_MODE_SCREEN_OFF -> 1
            ScanSettings.SCAN_MODE_LOW_POWER -> 2
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED -> 3
            // BALANCED and AMBIENT_DISCOVERY have the same settings and priority
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> 4
            ScanSettings.SCAN_MODE_LOW_LATENCY -> 5
            else -> -1
        }

    @JvmStatic
    fun weightForScanMode(scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> WEIGHT_OPPORTUNISTIC
            ScanSettings.SCAN_MODE_SCREEN_OFF -> WEIGHT_SCREEN_OFF_LOW_POWER
            ScanSettings.SCAN_MODE_LOW_POWER -> WEIGHT_LOW_POWER
            ScanSettings.SCAN_MODE_LOW_LATENCY -> WEIGHT_LOW_LATENCY
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED -> WEIGHT_BALANCED
            else -> WEIGHT_LOW_POWER
        }

    @JvmStatic
    fun requiresScreenOn(client: ScanClient) =
        !isOpportunisticScanClient(client) && !isFilteredScan(client)

    @JvmStatic
    fun requiresLocationOn(client: ScanClient) =
        !client.mHasDisavowedLocation && !isFilteredScan(client)

    // A valid filter need at least one field not empty
    private fun isFilteredScan(client: ScanClient) = client.mFilters.any { !it.isAllFieldsEmpty() }

    @JvmStatic
    fun isExemptFromScanTimeout(client: ScanClient) =
        isOpportunisticScanClient(client) || isFirstMatchScanClient(client)

    @JvmStatic
    fun isExemptFromAutoBatchScanUpdate(client: ScanClient) =
        isOpportunisticScanClient(client) || !isAllMatchesAutoBatchScanClient(client)

    @JvmStatic
    fun isOpportunisticScanClient(client: ScanClient) =
        client.mSettings.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC

    private fun isFirstMatchScanClient(client: ScanClient) =
        (client.mSettings.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0

    @JvmStatic
    fun isAllMatchesAutoBatchScanClient(client: ScanClient) =
        client.mSettings.callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH

    @JvmStatic
    fun isForceDowngradedScanClient(client: ScanClient) =
        isTimeoutScanClient(client) || isDowngradedScanClient(client)

    private fun isTimeoutScanClient(client: ScanClient) =
        client.mStats.map { it.isScanTimeout(client.scannerId) }.orElse(false)

    @JvmStatic
    fun isDowngradedScanClient(client: ScanClient) =
        client.mStats.map { it.isScanDowngraded(client.scannerId) }.orElse(false)

    @JvmStatic
    fun isAutoBatchScanClientEnabled(client: ScanClient) =
        client.mStats.map { it.isAutoBatchScan(client.scannerId) }.orElse(false)
}
