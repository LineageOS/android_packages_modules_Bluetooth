/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.os.SystemProperties
import android.provider.DeviceConfig
import android.util.Log
import com.android.bluetooth.le_scan.BatchScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS
import com.android.bluetooth.util.TimeProvider
import com.android.internal.annotations.VisibleForTesting
import kotlin.math.max
import kotlin.math.min

private const val TAG = ScanUtil.TAG_PREFIX + "BatchScanThrottler"

/**
 * Throttler to reduce the number of times the Bluetooth process wakes up to check for pending batch
 * scan results. The wake-up intervals are increased when no matching results are found and are
 * longer when the screen is off.
 */
class BatchScanThrottler(private val timeProvider: TimeProvider, screenOn: Boolean) {
    private val screenOffMinimumDelayFloorMs =
        SystemProperties.getInt(
            SCREEN_OFF_MINIMUM_DELAY_FLOOR_PROP,
            SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT,
        )
    private val unfilteredDelayFloorMs =
        SystemProperties.getInt(UNFILTERED_DELAY_FLOOR_PROP, UNFILTERED_DELAY_FLOOR_DEFAULT)
    private val unfilteredScreenOffDelayFloorMs =
        SystemProperties.getInt(
            UNFILTERED_SCREEN_OFF_DELAY_FLOOR_PROP,
            UNFILTERED_SCREEN_OFF_DELAY_FLOOR_DEFAULT,
        )
    private val screenOffDelayMs =
        SystemProperties.getInt(SCREEN_OFF_DELAY_PROP, SCREEN_OFF_DELAY_DEFAULT)
    private val delayFloorMs =
        DeviceConfig.getLong(
            DeviceConfig.NAMESPACE_BLUETOOTH,
            "report_delay",
            DEFAULT_REPORT_DELAY_FLOOR_MS,
        )
    private val screenOffDelayFloorMs = max(delayFloorMs, screenOffMinimumDelayFloorMs.toLong())

    private var backoffStage = 0
    private var screenOffTriggerTime = 0L
    private var screenOffThrottling = false

    init {
        Log.d(
            TAG,
            "Initialized with: screenOffMinimumDelayFloorMs=$screenOffMinimumDelayFloorMs" +
                ", unfilteredDelayFloorMs=$unfilteredDelayFloorMs" +
                ", unfilteredScreenOffDelayFloorMs=$unfilteredScreenOffDelayFloorMs" +
                ", screenOffDelayMs=$screenOffDelayMs, delayFloorMs=$delayFloorMs," +
                " screenOffDelayFloorMs=$screenOffDelayFloorMs",
        )
        onScreenOn(screenOn)
    }

    fun resetBackoff() {
        Log.d(TAG, "resetBackoff() called")
        backoffStage = 0
    }

    fun onScreenOn(screenOn: Boolean) {
        if (screenOn) {
            screenOffTriggerTime = 0L
            screenOffThrottling = false
            resetBackoff()
        } else {
            // Screen-off intervals to be used after the trigger time
            screenOffTriggerTime = timeProvider.elapsedRealtime() + screenOffDelayMs
        }
    }

    fun getBatchTriggerIntervalMillis(batchClients: Set<ScanClient>): Long {
        // Check if we're past the screen-off time and should be using screen-off backoff values
        if (
            !screenOffThrottling &&
                screenOffTriggerTime != 0L &&
                timeProvider.elapsedRealtime() >= screenOffTriggerTime
        ) {
            screenOffThrottling = true
            resetBackoff()
        }

        val minimumReportDelayMs = getMinimumReportDelayMillis(batchClients)

        val backoffIndex =
            if (backoffStage >= BACKOFF_MULTIPLIERS.size) BACKOFF_MULTIPLIERS.size - 1
            else backoffStage++
        val finalInterval =
            max(
                minimumReportDelayMs,
                (if (screenOffThrottling) screenOffDelayFloorMs else delayFloorMs) *
                    BACKOFF_MULTIPLIERS[backoffIndex],
            )
        Log.d(TAG, "Batch trigger interval: ${finalInterval}ms")
        return finalInterval
    }

    private fun getMinimumReportDelayMillis(batchClients: Set<ScanClient>): Long {
        val unfilteredFloor =
            (if (screenOffThrottling) unfilteredScreenOffDelayFloorMs else unfilteredDelayFloorMs)
        var minimumReportDelayMs = Long.MAX_VALUE
        for (client in batchClients) {
            if (client.settings.reportDelayMillis > 0) {
                var clientReportDelayMs = client.settings.reportDelayMillis
                if (!client.isFiltered && clientReportDelayMs < unfilteredFloor) {
                    clientReportDelayMs = unfilteredFloor.toLong()
                }
                minimumReportDelayMs = min(minimumReportDelayMs, clientReportDelayMs)
            }
        }
        return minimumReportDelayMs
    }

    companion object {
        // Minimum batch trigger interval to check for batched results when the screen is off
        private const val SCREEN_OFF_MINIMUM_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.screen_off_minimum_delay_floor_ms.config"
        // Adjusted minimum report delay for unfiltered batch scan clients
        private const val UNFILTERED_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.unfiltered_delay_floor_ms.config"
        // Adjusted minimum report delay for unfiltered batch scan clients when the screen is off
        private const val UNFILTERED_SCREEN_OFF_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.unfiltered_screen_off_delay_floor_ms.config"
        // Start screen-off trigger interval throttling after the screen has been off for this
        // period of time. This allows the screen-on intervals to be used for a short period of time
        // after the screen has gone off, and avoids too much flipping between screen-off and
        // screen-on backoffs when the screen is off for a short period of time
        private const val SCREEN_OFF_DELAY_PROP =
            "bluetooth.ble.batch_scan.screen_off_delay_ms.config"

        @VisibleForTesting const val SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT = 20000
        @VisibleForTesting const val UNFILTERED_DELAY_FLOOR_DEFAULT = 20000
        @VisibleForTesting const val UNFILTERED_SCREEN_OFF_DELAY_FLOOR_DEFAULT = 60000
        @VisibleForTesting const val SCREEN_OFF_DELAY_DEFAULT = 60000

        // Backoff stages used as multipliers for the minimum delay floor (standard or screen-off)
        @VisibleForTesting val BACKOFF_MULTIPLIERS = intArrayOf(1, 1, 2, 2, 4)
    }
}
