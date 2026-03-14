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

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY
import android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
import android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
import android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
import android.bluetooth.le.ScanSettings.SCAN_MODE_OPPORTUNISTIC
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanThrottler.ScanAllowanceLedger
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_AMBIENT_DISCOVERY
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_BALANCED
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_LOW_LATENCY
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_LOW_POWER
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_OPPORTUNISTIC
import com.android.bluetooth.le_scan.ScanUtil.isBackgroundScan
import com.android.bluetooth.le_scan.ScanUtil.isBatchScan
import com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScan
import com.android.bluetooth.le_scan.ScanUtil.toStringWithoutNullParam
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import com.android.internal.annotations.VisibleForTesting
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helps keep track of all scan-related information on a per-application basis.
 *
 * This class is the central owner of an application's identity ([uid], [name]) and all its
 * associated scanning statistics. It maintains a list of currently [ongoingScans] (keyed by
 * scannerId) and a historical log of [lastScans] (up to a system-defined limit).
 *
 * Key responsibilities:
 * - Recording scan starts ([recordScanStart]) and stops ([recordScanStop])
 * - Tracking scan suspensions ([recordScanSuspend]) and resumes ([recordScanResume])
 * - Aggregating total scan time, active time, and time spent in each scan mode (e.g. `oppScanTime`)
 * - Counting scan results received while the screen is on vs. off ([addResults])
 * - Enforcing scan quotas by checking [isScanningTooFrequently] and [isScanningTooLong]
 * - Reporting scan activity and results to [ScanMetricsReporter]
 * - Storing application state like [appImportance] and [isRegistered]
 */
class AppScanStats(
    val uid: Int,
    val pid: Int,
    val name: String,
    val workSourceUtil: WorkSourceUtil,
    private val adapterService: AdapterService,
    private val scanMetricsReporter: ScanMetricsReporter,
    private val timeProvider: TimeProvider,
) {

    class LastScan(
        internal val startTimestamp: Long,
        internal var endTimestamp: Long = 0,
        internal val scannerId: Int,
        internal val settings: ScanSettings,
        internal val scanMode: ScanMode,
        internal val callbackType: CallbackType,
        internal val reportDelayMillis: Long,
        internal val isBackgroundScan: Boolean,
        internal val isBatchScan: Boolean,
        internal val isCallbackScan: Boolean,
        internal val isFilterScan: Boolean,
        internal val isOpportunisticScan: Boolean,
        internal val appImportanceOnStart: Int,
        internal val attributionTag: String?,
        internal val filterStringBuilder: StringBuilder = StringBuilder(),
        internal var suspendDuration: Long = 0L,
        internal var suspendStartTime: Long = 0L,
        internal var isSuspended: Boolean = false,
        internal var isTimeout: Boolean = false,
        internal var isDowngraded: Boolean = false,
        internal var isAutoBatchScan: Boolean = false,
        internal var resultsScreenOn: Int = 0,
        internal var resultsScreenOff: Int = 0,
    ) {
        internal val resultsTotal: Int
            get() = resultsScreenOn + resultsScreenOff
    }

    private val lastScans: MutableList<LastScan> = ArrayList()
    private val ongoingScans: MutableMap<Int, LastScan> = HashMap()

    private val consumptionStats = AppCurrentConsumptionStats(timeProvider)

    var isAppDead = false
    var isRegistered = false
    var appImportance = IMPORTANCE_CACHED

    var scanAllowanceLedger = ScanAllowanceLedger()

    private var scansStarted = 0
    private var scansStopped = 0
    private var scanStartTimestamp = 0L
    private var totalActiveTime = 0L
    private var totalSuspendTime = 0L
    private var totalScanTime = 0L
    private var oppScanTime = 0L
    private var lowPowerScanTime = 0L
    private var balancedScanTime = 0L
    private var lowLatencyScanTime = 0L
    private var ambientDiscoveryScanTime = 0L
    private var oppScan = 0
    private var lowPowerScan = 0
    private var balancedScan = 0
    private var lowLatencyScan = 0
    private var ambientDiscoveryScan = 0
    private var resultsScreenOn = 0
    private var resultsScreenOff = 0
    private var scheduledBatchAlarmCount = 0

    @VisibleForTesting
    val results: Int
        get() = resultsScreenOn + resultsScreenOff

    override fun toString() = "AppScanStats(uid=$uid, name=$name)"

    fun getScanFromScannerId(scannerId: Int) = ongoingScans[scannerId]

    fun addResults(scannerId: Int, numberOfNewResults: Int, isBatch: Boolean) {
        val isScreenOn = sIsScreenOn.get()
        if (isScreenOn) {
            resultsScreenOn += numberOfNewResults
        } else {
            resultsScreenOff += numberOfNewResults
        }

        val scan = getScanFromScannerId(scannerId) ?: return
        val resultsBeforeUpdate = scan.resultsTotal
        if (isScreenOn) {
            scan.resultsScreenOn += numberOfNewResults
        } else {
            scan.resultsScreenOff += numberOfNewResults
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if (scan.resultsTotal / 100 > resultsBeforeUpdate / 100) {
            scanMetricsReporter.reportScanResults(100)
        }

        consumptionStats.addScanResults(numberOfNewResults, isScreenOn)
        // Check threshold violations every 40 results to be efficient and align with thresholds
        if (scan.resultsTotal / 40 > resultsBeforeUpdate / 40) {
            consumptionStats.checkThresholdViolation(name)
        }

        scanMetricsReporter.reportLeScanResult(
            isBatch,
            numberOfNewResults,
            isScreenOn,
            getAttributionTagFromScannerId(scannerId),
            scan,
        )
    }

    fun isScanning() = ongoingScans.isNotEmpty()

    fun isScanTimeout(scannerId: Int) = getScanFromScannerId(scannerId)?.isTimeout ?: false

    fun isScanDowngraded(scannerId: Int) = getScanFromScannerId(scannerId)?.isDowngraded ?: false

    fun isAutoBatchScan(scannerId: Int) = getScanFromScannerId(scannerId)?.isAutoBatchScan ?: false

    fun recordScanStart(
        settings: ScanSettings,
        filters: List<ScanFilter>,
        isFilterScan: Boolean,
        isCallbackScan: Boolean,
        scannerId: Int,
        attributionTag: String?,
    ) {
        val existingScan = getScanFromScannerId(scannerId)
        if (existingScan != null) return
        scansStarted++
        val startTimestamp = timeProvider.elapsedRealtime()
        val scan =
            LastScan(
                startTimestamp = startTimestamp,
                scannerId = scannerId,
                settings = settings,
                scanMode = ScanMode(settings.scanMode),
                callbackType = CallbackType(settings.callbackType),
                reportDelayMillis = settings.reportDelayMillis,
                isBackgroundScan = isBackgroundScan(settings),
                isBatchScan = isBatchScan(settings),
                isCallbackScan = isCallbackScan,
                isFilterScan = isFilterScan,
                isOpportunisticScan = isOpportunisticScan(settings),
                appImportanceOnStart = appImportance,
                attributionTag = attributionTag,
            )
        when (scan.scanMode.value) {
            SCAN_MODE_OPPORTUNISTIC -> oppScan++
            SCAN_MODE_LOW_POWER -> lowPowerScan++
            SCAN_MODE_BALANCED -> balancedScan++
            SCAN_MODE_LOW_LATENCY -> lowLatencyScan++
            SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScan++
        }

        if (isFilterScan) {
            filters.forEach { scan.filterStringBuilder.appendLine(it.toStringWithoutNullParam()) }
        }

        if (!isScanning()) {
            scanStartTimestamp = startTimestamp
        }

        scanMetricsReporter.recordScanStart(
            scan,
            ongoingScans.size,
            sIsScreenOn.get(),
            isAppDead,
            appImportance,
        )

        ongoingScans[scannerId] = scan
    }

    fun recordScanStop(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId) ?: return
        scansStopped++
        val stopTime = timeProvider.elapsedRealtime()
        scan.endTimestamp = stopTime
        if (scan.isSuspended) {
            val suspendDuration = stopTime - scan.suspendStartTime
            scan.suspendDuration += suspendDuration
            totalSuspendTime += suspendDuration
        }
        ongoingScans.remove(scannerId)
        if (lastScans.size >= adapterService.scanQuotaCount) {
            lastScans.removeFirst()
        }
        lastScans.add(scan)

        val scanDuration = scan.endTimestamp - scan.startTimestamp
        totalScanTime += scanDuration
        val activeDuration = scanDuration - scan.suspendDuration
        totalActiveTime += activeDuration
        when (scan.scanMode.value) {
            SCAN_MODE_OPPORTUNISTIC -> oppScanTime += activeDuration
            SCAN_MODE_LOW_POWER -> lowPowerScanTime += activeDuration
            SCAN_MODE_BALANCED -> balancedScanTime += activeDuration
            SCAN_MODE_LOW_LATENCY -> lowLatencyScanTime += activeDuration
            SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScanTime += activeDuration
        }

        scanMetricsReporter.recordScanStop(
            scan,
            scanDuration,
            ongoingScans.size,
            sIsScreenOn.get(),
            isAppDead,
            appImportance,
        )
    }

    fun recordScanTimeoutCountMetrics(scannerId: Int, scanTimeoutMillis: Long) {
        val scan = getScanFromScannerId(scannerId)
        scanMetricsReporter.recordScanTimeoutCount(scan, scanTimeoutMillis)
    }

    fun recordHwFilterNotAvailableCountMetrics(scannerId: Int, numOfFilterSupported: Long) {
        val scan = getScanFromScannerId(scannerId)
        scanMetricsReporter.recordHwFilterNotAvailableCount(scan, numOfFilterSupported)
    }

    fun recordScanSuspend(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId)
        if (scan == null || scan.isSuspended) {
            return
        }
        scan.suspendStartTime = timeProvider.elapsedRealtime()
        scan.isSuspended = true
    }

    fun recordScanResume(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId)
        if (scan == null || !scan.isSuspended) {
            return
        }
        scan.isSuspended = false
        val stopTime = timeProvider.elapsedRealtime()
        val suspendDuration = stopTime - scan.suspendStartTime
        scan.suspendDuration += suspendDuration
        totalSuspendTime += suspendDuration
    }

    fun setScanTimeout(scannerId: Int) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.isTimeout = true
    }

    fun setScanDowngrade(scannerId: Int, isDowngrade: Boolean) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.isDowngraded = isDowngrade
    }

    fun setAutoBatchScan(scannerId: Int, isBatchScan: Boolean) {
        getScanFromScannerId(scannerId)?.isAutoBatchScan = isBatchScan
    }

    fun isScanningTooFrequently(): Boolean {
        if (lastScans.size < adapterService.scanQuotaCount) {
            return false
        }
        val oldestLastScanStartTimestamp = lastScans.first().startTimestamp
        return Duration.ofMillis(timeProvider.elapsedRealtime() - oldestLastScanStartTimestamp) <
            adapterService.scanQuotaWindow
    }

    fun isScanningTooLong(): Boolean {
        if (!isScanning()) {
            return false
        }
        return Duration.ofMillis(timeProvider.elapsedRealtime() - scanStartTimestamp) >=
            adapterService.scanTimeout
    }

    fun hasRecentScan(): Boolean {
        if (!isScanning() || lastScans.isEmpty()) {
            return false
        }
        val lastScan = lastScans.last()
        return (timeProvider.elapsedRealtime() - lastScan.endTimestamp) < LARGE_SCAN_TIME_GAP_MS
    }

    fun recordBatchAlarmScheduled() {
        scheduledBatchAlarmCount++
    }

    fun getAttributionTagFromScannerId(scannerId: Int): String =
        getScanFromScannerId(scannerId)?.attributionTag ?: ""

    fun dump(apps: List<ScannerApp>) = buildString {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = timeProvider.elapsedRealtime()
        val opportunisticScan = oppScan
        val lowPowerScan = lowPowerScan
        val balancedScan = balancedScan
        val lowLatencyScan = lowLatencyScan
        val ambientDiscoveryScan = ambientDiscoveryScan
        var opportunisticScanTime = oppScanTime
        var lowPowerScanTime = lowPowerScanTime
        var balancedScanTime = balancedScanTime
        var lowLatencyScanTime = lowLatencyScanTime
        var ambientDiscoveryScanTime = ambientDiscoveryScanTime
        var totalActiveTime = totalActiveTime
        var totalSuspendTime = totalSuspendTime
        var totalScanTime = totalScanTime

        val ongoingScans = ongoingScans.values
        for (ongoingScan in ongoingScans) {
            val scanDuration = elapsedTime - ongoingScan.startTimestamp
            val suspendDuration =
                if (ongoingScan.isSuspended) {
                    elapsedTime - ongoingScan.suspendStartTime
                } else {
                    0
                }
            val activeDuration = scanDuration - ongoingScan.suspendDuration - suspendDuration
            totalScanTime += scanDuration
            totalSuspendTime += suspendDuration
            totalActiveTime += activeDuration
            when (ongoingScan.scanMode.value) {
                SCAN_MODE_OPPORTUNISTIC -> opportunisticScanTime += activeDuration
                SCAN_MODE_LOW_POWER -> lowPowerScanTime += activeDuration
                SCAN_MODE_BALANCED -> balancedScanTime += activeDuration
                SCAN_MODE_LOW_LATENCY -> lowLatencyScanTime += activeDuration
                SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScanTime += activeDuration
            }
        }

        val score =
            (opportunisticScanTime * WEIGHT_OPPORTUNISTIC +
                lowPowerScanTime * WEIGHT_LOW_POWER +
                balancedScanTime * WEIGHT_BALANCED +
                lowLatencyScanTime * WEIGHT_LOW_LATENCY +
                ambientDiscoveryScanTime * WEIGHT_AMBIENT_DISCOVERY) / 100

        appendLine("$name${if (isRegistered) " (Registered):" else ":"}")

        if (isRegistered) {
            for (app in apps) {
                fun tag() = app.attributionTag?.let { ", Tag: $it" } ?: ""
                appendLine("  Scanner ID: ${app.scannerId}, UUID: ${app.uuid}${tag()}")
            }
        }

        append("  LE scans               (Started/Stopped)                                   : ")
        appendLine("$scansStarted / $scansStopped")

        append("  Scan time(ms)          (Active/Suspend/Total)                              : ")
        appendLine("$totalActiveTime / $totalSuspendTime / $totalScanTime")

        append("  Scan time per mode(ms) (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ")
        append("$opportunisticScanTime / $lowPowerScanTime / $balancedScanTime / ")
            .appendLine("$lowLatencyScanTime / $ambientDiscoveryScanTime")

        append("  Scan mode counter      (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ")
        append("$opportunisticScan / $lowPowerScan / $balancedScan / ")
            .appendLine("$lowLatencyScan / $ambientDiscoveryScan")

        appendLine(
            "  Score                                                                      : $score"
        )

        val results = resultsScreenOff + resultsScreenOn
        append("  Number of results      (ScreenOff/ScreenOn/Total)                          : ")
            .appendLine("$resultsScreenOff / $resultsScreenOn / $results")

        if (scheduledBatchAlarmCount > 0) {
            append("  Number of batch alarms scheduled                                         ")
                .appendLine("  : $scheduledBatchAlarmCount")
        }

        appendLine(consumptionStats.dump().indent("  "))

        if (lastScans.isNotEmpty()) {
            appendLine("  Last ${lastScans.size} scans:")
            lastScans.forEach {
                appendLine(it.details(currentTime, elapsedTime, false).indent("    "))
            }
        }

        if (ongoingScans.isNotEmpty()) {
            appendLine("  Ongoing ${ongoingScans.size} scans:")
            ongoingScans.forEach {
                appendLine(it.details(currentTime, elapsedTime, true).indent("    "))
            }
        }
    }

    private fun LastScan.details(currTime: Long, elapsedTime: Long, active: Boolean) = buildString {
        val bootEpochMs = currTime - elapsedTime

        val start = Utils.formatInstant(Instant.ofEpochMilli(bootEpochMs + startTimestamp))
        val end =
            if (active) ""
            else " --> ${Utils.formatInstant(Instant.ofEpochMilli(bootEpochMs + endTimestamp))}"
        append("[$start$end] (")

        val duration: Long
        if (active) {
            duration = elapsedTime - startTimestamp
            appendLine("Elapsed: ${duration}ms)")
        } else {
            duration = endTimestamp - startTimestamp
            appendLine("Duration: ${duration}ms)")
        }

        append("  └ Info: ")

        if (isOpportunisticScan) append("(Opp) ")
        if (isBackgroundScan) append("(Back) ")
        if (isTimeout) append("(Forced) ")
        if (isFilterScan) append("(Filter) ")
        if (active && isSuspended) append("(Suspended) ")

        val results = resultsScreenOff + resultsScreenOn
        append("Results: ($resultsScreenOff / $resultsScreenOn / $results) | ")
            .append("id: ($scannerId) | ")

        attributionTag?.let { append("[$it] | ") }

        append(if (isCallbackScan) "CB " else "PI ")
        when {
            isBatchScan -> appendLine("Batch Scan")
            isAutoBatchScan -> appendLine("Auto Batch Scan")
            else -> appendLine("Regular Scan")
        }

        if (!active) {
            val importanceText =
                when {
                    appImportanceOnStart < IMPORTANCE_FOREGROUND_SERVICE -> " Higher than"
                    appImportanceOnStart > IMPORTANCE_FOREGROUND_SERVICE -> " Lower than"
                    else -> ""
                }
            appendLine("  └ App Importance:$importanceText Foreground Service")
        }

        if (suspendStartTime != 0L) {
            val suspendDuration =
                if (active && isSuspended) {
                    (elapsedTime - suspendStartTime) + suspendDuration
                } else {
                    suspendDuration
                }
            val activeDuration = duration - suspendDuration

            appendLine("  └ Active Time: ${activeDuration}ms, Suspended Time: ${suspendDuration}ms")
        }

        val settingsTable =
            listOf(settings)
                .toTable(
                    Column("SCAN_MODE") { ScanMode(it.scanMode) },
                    Column("CALLBACK_TYPE") { CallbackType(it.callbackType) },
                    Column("RESULT_TYPE") { ResultType(it.scanResultType) },
                    Column("DELAY_MS") { it.reportDelayMillis },
                    Column("MATCH_MODE") { MatchMode(it.matchMode) },
                    Column("NUM_MATCHES") { NumberOfMatches(it.numOfMatches) },
                    Column("LEGACY") { it.legacy },
                    Column("PHY") { Phy(it.phy) },
                    Column("RSSI") { it.rssiThreshold },
                    Column("SCAN_TYPE") { Type(it.scanType) },
                )
        appendLine(settingsTable.indent("    "))

        if (isFilterScan) append(filterStringBuilder.toString().indent("  └ "))
    }

    companion object {
        private const val LARGE_SCAN_TIME_GAP_MS = 24000
        private val sIsScreenOn = AtomicBoolean(false)

        @JvmStatic
        fun setScreenState(isScreenOn: Boolean) {
            sIsScreenOn.set(isScreenOn)
        }
    }
}
