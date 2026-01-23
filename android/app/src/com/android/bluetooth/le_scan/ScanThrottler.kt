/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.le_scan

import android.bluetooth.le.ScanSettings
import android.util.Log
import com.android.bluetooth.le_scan.ScanUtil.isForceDowngradedScanClient
import com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScanClient
import com.android.bluetooth.le_scan.ScanUtil.minScanMode
import com.android.bluetooth.le_scan.ScanUtil.scanModeToString

private const val TAG = ScanUtil.TAG_PREFIX + "ScanThrottler"

/**
 * Throttler to adjusts scan settings based on system state, like screen status, app visibility
 * (foreground vs. background) and hardware resource contention (connecting state).
 */
class ScanThrottler(private val scanManager: ScanManager) {

    fun throttleScanMode(client: ScanClient, targetMode: Int, isScreenOn: Boolean): Boolean {
        var targetScanMode = targetMode
        if (isOpportunisticScanClient(client)) {
            return false
        }

        // background throttling
        if (!scanManager.isAppForeground(client) || isForceDowngradedScanClient(client)) {
            val limitMode =
                if (!isScreenOn) {
                    ScanSettings.SCAN_MODE_SCREEN_OFF
                } else {
                    ScanSettings.SCAN_MODE_LOW_POWER
                }
            targetScanMode = minScanMode(limitMode, targetScanMode)
        }
        // screen off throttling
        else if (!isScreenOn) {
            targetScanMode =
                when (targetScanMode) {
                    ScanSettings.SCAN_MODE_LOW_POWER -> ScanSettings.SCAN_MODE_SCREEN_OFF
                    ScanSettings.SCAN_MODE_BALANCED,
                    ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                        ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED
                    ScanSettings.SCAN_MODE_LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    else -> return false
                }
        }
        return client.updateScanMode(targetScanMode)
    }

    fun throttleScanModeScreenOff(client: ScanClient): Boolean {
        val targetScanMode = client.scanModeApp
        if (throttleScanMode(client, targetScanMode, isScreenOn = false)) {
            Log.d(
                TAG,
                "throttleScanModeScreenOff(): for $client from=${scanModeToString(targetScanMode)} " +
                    "to=${scanModeToString(client.settings.scanMode)}",
            )
            return true
        }
        return false
    }
}
