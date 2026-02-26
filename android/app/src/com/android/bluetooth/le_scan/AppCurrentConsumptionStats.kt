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

import android.util.Log
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import com.android.internal.annotations.VisibleForTesting
import kotlin.time.Duration.Companion.hours

private const val TAG = ScanUtil.TAG_PREFIX + "AppCurrentConsumptionStats"

class AppCurrentConsumptionStats(private val timeProvider: TimeProvider) {

    @VisibleForTesting
    enum class Severity {
        NORMAL,
        LOW,
        MEDIUM,
        HIGH,
    }

    @VisibleForTesting
    data class SeverityThresholds(val low: Int, val medium: Int, val high: Int) {
        fun getSeverity(average: Int): Severity =
            when {
                average >= high -> Severity.HIGH
                average >= medium -> Severity.MEDIUM
                average >= low -> Severity.LOW
                else -> Severity.NORMAL
            }
    }

    @VisibleForTesting
    data class SeverityReport(val severity: Severity, val average: Int, val total: Int)

    private class HourStats(val hour: Int, var screenOn: Int = 0, var screenOff: Int = 0)

    private val resultsPerHour = ArrayDeque<HourStats>()

    private var hasLoggedViolation = false

    /** Updates the current hour's bucket and prunes old data */
    fun addScanResults(results: Int, isScreenOn: Boolean) {
        val currentHour = getCurrentUptimeHour()
        var hourStats = resultsPerHour.lastOrNull()

        if (hourStats == null || hourStats.hour != currentHour) {
            hourStats = HourStats(currentHour)
            resultsPerHour.addLast(hourStats)
        }

        if (isScreenOn) {
            hourStats.screenOn += results
        } else {
            hourStats.screenOff += results
        }

        // Prune data older than MAX_HOURS_TO_KEEP as we do not need it anymore
        val oldestAllowedHour = currentHour - MAX_HOURS_TO_KEEP + 1
        while (resultsPerHour.isNotEmpty() && resultsPerHour.first().hour < oldestAllowedHour) {
            resultsPerHour.removeFirst()
        }
    }

    fun checkThresholdViolation(app: String) {
        val severities = getCurrentSeverities()
        val violatingWindow = severities.entries.firstOrNull { it.value.severity == Severity.HIGH }

        if (violatingWindow == null) {
            hasLoggedViolation = false
            return
        }

        // Log a warning the first time an app hits HIGH severity limit for background scan results
        if (!hasLoggedViolation) {
            val window = violatingWindow.key
            val average = "Avg: ${violatingWindow.value.average}/hr"
            Log.w(TAG, "'$app' reached HIGH severity in the ${window}H window ($average)")
            hasLoggedViolation = true
        }
    }

    fun dump() = buildString {
        appendLine("Current Consumption Severities:")

        val severities = getCurrentSeverities().toSortedMap()
        if (severities.isNotEmpty()) {
            val severityTable =
                severities.entries.toTable(
                    Column("WINDOW(H)") { it.key },
                    Column("SEVERITY") { it.value.severity.name },
                    Column("AVG/HR") { it.value.average },
                    Column("TOTAL") { it.value.total },
                )
            appendLine(severityTable.indent("  "))
        }
    }

    @VisibleForTesting
    fun getCurrentSeverities(): Map<Int, SeverityReport> {
        val currentHour = getCurrentUptimeHour()
        val severities = mutableMapOf<Int, SeverityReport>()

        for ((windowHours, thresholds) in thresholdRules) {
            var totalResultsInWindow = 0
            val cutoffHour = currentHour - windowHours + 1

            for (stats in resultsPerHour.asReversed()) {
                if (stats.hour < cutoffHour) break
                totalResultsInWindow += stats.screenOff
            }

            val averagePerHour = totalResultsInWindow / windowHours
            severities[windowHours] =
                SeverityReport(
                    severity = thresholds.getSeverity(averagePerHour),
                    average = averagePerHour,
                    total = totalResultsInWindow,
                )
        }
        return severities
    }

    private fun getCurrentUptimeHour() = (timeProvider.elapsedRealtime() / MILLIS_PER_HOUR).toInt()

    companion object {
        private val MILLIS_PER_HOUR = 1.hours.inWholeMilliseconds
        private const val MAX_HOURS_TO_KEEP = 8

        private val thresholdRules =
            mapOf(
                1 to SeverityThresholds(low = 480, medium = 640, high = 800),
                4 to SeverityThresholds(low = 320, medium = 480, high = 640),
                8 to SeverityThresholds(low = 160, medium = 320, high = 480),
            )
    }
}
