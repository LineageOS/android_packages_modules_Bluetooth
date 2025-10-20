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
import android.os.BatteryStatsManager
import android.os.WorkSource
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_AMBIENT_DISCOVERY
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_BALANCED
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_LOW_LATENCY
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_LOW_POWER
import com.android.bluetooth.le_scan.ScanUtil.WEIGHT_OPPORTUNISTIC
import com.android.bluetooth.le_scan.ScanUtil.callbackTypeToString
import com.android.bluetooth.le_scan.ScanUtil.isBackgroundScan
import com.android.bluetooth.le_scan.ScanUtil.isBatchScan
import com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScan
import com.android.bluetooth.le_scan.ScanUtil.scanFilterToStringWithoutNullParam
import com.android.bluetooth.le_scan.ScanUtil.scanModeToString
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
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
 * - Counting scan results received while the screen is on vs. off ([addResult], [addResults])
 * - Enforcing scan quotas by checking [isScanningTooFrequently] and [isScanningTooLong]
 * - Reporting scan activity and results to [ScanMetricsReporter]
 * - Storing application state like [appImportance] and [isRegistered]
 */
class AppScanStats(
    val uid: Int,
    val pid: Int,
    val name: String,
    source: WorkSource?,
    private val adapterService: AdapterService,
    private val timeProvider: TimeProvider,
) {

    class LastScan(
        internal val startTimestamp: Long,
        internal var endTimestamp: Long = 0,
        internal val scannerId: Int,
        val scanMode: Int,
        val scanCallbackType: Int,
        val reportDelayMillis: Long,
        val isBackgroundScan: Boolean,
        val isBatchScan: Boolean,
        val isCallbackScan: Boolean,
        val isFilterScan: Boolean,
        val isOpportunisticScan: Boolean,
        internal val appImportanceOnStart: Int,
        val attributionTag: String?,
        val filterStringBuilder: StringBuilder = StringBuilder(),
        internal var suspendDuration: Long = 0L,
        internal var suspendStartTime: Long = 0L,
        internal var isSuspended: Boolean = false,
        internal var isTimeout: Boolean = false,
        internal var isDowngraded: Boolean = false,
        var isAutoBatchScan: Boolean = false,
        var resultsScreenOn: Int = 0,
        var resultsScreenOff: Int = 0,
    )

    private val lastScans: MutableList<LastScan> = ArrayList()
    private val ongoingScans: MutableMap<Int, LastScan> = HashMap()

    val workSourceUtil: WorkSourceUtil
    private val scanMetricsReporter: ScanMetricsReporter

    var isAppDead = false
    var isRegistered = false
    var appImportance = IMPORTANCE_CACHED
        @Synchronized get
        @Synchronized set

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

    init {
        // Bill the caller uid if the work source isn't passed through
        val workSource = source ?: WorkSource(uid, name)
        workSourceUtil = WorkSourceUtil(workSource)
        val batteryStatsManager = adapterService.getSystemService(BatteryStatsManager::class.java)
        scanMetricsReporter = ScanMetricsReporter(workSource, workSourceUtil, batteryStatsManager)
    }

    override fun toString() = "AppScanStats(uid=$uid, name=$name)"

    @Synchronized fun getScanFromScannerId(scannerId: Int) = ongoingScans[scannerId]

    @Synchronized
    fun addResult(scannerId: Int) {
        val isScreenOn = sIsScreenOn.get()
        if (isScreenOn) {
            resultsScreenOn++
        } else {
            resultsScreenOff++
        }

        val scan = getScanFromScannerId(scannerId) ?: return
        if (isScreenOn) {
            scan.resultsScreenOn++
        } else {
            scan.resultsScreenOff++
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.resultsScreenOn + scan.resultsScreenOff) % 100 == 0) {
            scanMetricsReporter.reportScanResults(100)
        }
    }

    @Synchronized
    fun addResults(scannerId: Int, numberOfNewResults: Int) {
        val isScreenOn = sIsScreenOn.get()
        if (isScreenOn) {
            resultsScreenOn += numberOfNewResults
        } else {
            resultsScreenOff += numberOfNewResults
        }

        val scan = getScanFromScannerId(scannerId) ?: return

        val resultsBeforeUpdate = scan.resultsScreenOn + scan.resultsScreenOff
        if (isScreenOn) {
            scan.resultsScreenOn += numberOfNewResults
        } else {
            scan.resultsScreenOff += numberOfNewResults
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.resultsScreenOn + scan.resultsScreenOff) / 100 > resultsBeforeUpdate / 100) {
            scanMetricsReporter.reportScanResults(100)
        }
    }

    @Synchronized fun isScanning() = ongoingScans.isNotEmpty()

    @Synchronized
    fun isScanTimeout(scannerId: Int) = getScanFromScannerId(scannerId)?.isTimeout ?: false

    @Synchronized
    fun isScanDowngraded(scannerId: Int) = getScanFromScannerId(scannerId)?.isDowngraded ?: false

    @Synchronized
    fun isAutoBatchScan(scannerId: Int) = getScanFromScannerId(scannerId)?.isAutoBatchScan ?: false

    @Synchronized
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
                scanMode = settings.scanMode,
                scanCallbackType = settings.callbackType,
                reportDelayMillis = settings.reportDelayMillis,
                isBackgroundScan = isBackgroundScan(settings),
                isBatchScan = isBatchScan(settings),
                isCallbackScan = isCallbackScan,
                isFilterScan = isFilterScan,
                isOpportunisticScan = isOpportunisticScan(settings),
                appImportanceOnStart = appImportance,
                attributionTag = attributionTag,
            )
        when (scan.scanMode) {
            SCAN_MODE_OPPORTUNISTIC -> oppScan++
            SCAN_MODE_LOW_POWER -> lowPowerScan++
            SCAN_MODE_BALANCED -> balancedScan++
            SCAN_MODE_LOW_LATENCY -> lowLatencyScan++
            SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScan++
        }

        if (isFilterScan) {
            filters.forEach { filter ->
                scan.filterStringBuilder
                    .append("\n        └ ")
                    .append(scanFilterToStringWithoutNullParam(filter))
            }
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

    @Synchronized
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
        when (scan.scanMode) {
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

    @Synchronized
    fun recordScanTimeoutCountMetrics(scannerId: Int, scanTimeoutMillis: Long) {
        val scan = getScanFromScannerId(scannerId)
        scanMetricsReporter.recordScanTimeoutCountMetrics(scan, scanTimeoutMillis)
    }

    @Synchronized
    fun recordHwFilterNotAvailableCountMetrics(scannerId: Int, numOfFilterSupported: Long) {
        val scan = getScanFromScannerId(scannerId)
        scanMetricsReporter.recordHwFilterNotAvailableCountMetrics(scan, numOfFilterSupported)
    }

    @Synchronized
    fun recordScanSuspend(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId)
        if (scan == null || scan.isSuspended) {
            return
        }
        scan.suspendStartTime = timeProvider.elapsedRealtime()
        scan.isSuspended = true
    }

    @Synchronized
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

    @Synchronized
    fun setScanTimeout(scannerId: Int) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.isTimeout = true
    }

    @Synchronized
    fun setScanDowngrade(scannerId: Int, isDowngrade: Boolean) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.isDowngraded = isDowngrade
    }

    @Synchronized
    fun setAutoBatchScan(scannerId: Int, isBatchScan: Boolean) {
        getScanFromScannerId(scannerId)?.isAutoBatchScan = isBatchScan
    }

    @Synchronized
    fun isScanningTooFrequently(): Boolean {
        if (lastScans.size < adapterService.scanQuotaCount) {
            return false
        }
        val oldestLastScanStartTimestamp = lastScans.first().startTimestamp
        return Duration.ofMillis(timeProvider.elapsedRealtime() - oldestLastScanStartTimestamp) <
            adapterService.scanQuotaWindow
    }

    @Synchronized
    fun isScanningTooLong(): Boolean {
        if (!isScanning()) {
            return false
        }
        return Duration.ofMillis(timeProvider.elapsedRealtime() - scanStartTimestamp) >=
            adapterService.scanTimeout
    }

    @Synchronized
    fun hasRecentScan(): Boolean {
        if (!isScanning() || lastScans.isEmpty()) {
            return false
        }
        val lastScan = lastScans.last()
        return (timeProvider.elapsedRealtime() - lastScan.endTimestamp) < LARGE_SCAN_TIME_GAP_MS
    }

    @Synchronized
    fun recordBatchAlarmScheduled() {
        scheduledBatchAlarmCount++
    }

    fun getAttributionTagFromScannerId(scannerId: Int): String =
        getScanFromScannerId(scannerId)?.attributionTag ?: ""

    @Synchronized
    fun dump(sb: StringBuilder, apps: List<ScannerApp>) {
        val currentTimeMs = System.currentTimeMillis()
        val elapsedRealtimeMs = timeProvider.elapsedRealtime()
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
            val scanDuration = elapsedRealtimeMs - ongoingScan.startTimestamp
            val suspendDuration =
                if (ongoingScan.isSuspended) {
                    elapsedRealtimeMs - ongoingScan.suspendStartTime
                } else {
                    0
                }
            val activeDuration = scanDuration - ongoingScan.suspendDuration - suspendDuration
            totalScanTime += scanDuration
            totalSuspendTime += suspendDuration
            totalActiveTime += activeDuration
            when (ongoingScan.scanMode) {
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

        sb.append("  $name")
        sb.append(if (isRegistered) " (Registered):" else ":")

        if (isRegistered) {
            for (app in apps) {
                sb.append("\n    Application ID: ${app.id}, UUID: ${app.uuid}")
                app.attributionTag?.let { sb.append(", Tag: $it") }
            }
        }

        sb.append("\n    LE scans               ")
            .append("(Started/Stopped)                                   : ")
        sb.append("$scansStarted / $scansStopped")

        sb.append("\n    Scan time(ms)          ")
            .append("(Active/Suspend/Total)                              : ")
        sb.append("$totalActiveTime / $totalSuspendTime / $totalScanTime")

        sb.append("\n    Scan time per mode(ms) ")
            .append("(Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ")
        sb.append("$opportunisticScanTime / $lowPowerScanTime / $balancedScanTime / ")
            .append("$lowLatencyScanTime / $ambientDiscoveryScanTime")

        sb.append("\n    Scan mode counter ")
            .append("     (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery) : ")
        sb.append("$opportunisticScan / $lowPowerScan / $balancedScan / ")
            .append("$lowLatencyScan / $ambientDiscoveryScan")

        sb.append("\n    Score ")
            .append("                                                                     : $score")

        val results = resultsScreenOff + resultsScreenOn
        sb.append("\n    Number of results      (ScreenOff/ScreenOn/Total)")
            .append("                          : $resultsScreenOff / $resultsScreenOn / $results")

        if (scheduledBatchAlarmCount > 0) {
            sb.append("\n    Number of batch alarms scheduled")
                .append("                                           : $scheduledBatchAlarmCount")
        }

        if (lastScans.isNotEmpty()) {
            sb.append("\n    Last ${lastScans.size} scans:")
            lastScans.forEach { it.appendDetails(sb, currentTimeMs, elapsedRealtimeMs, false) }
        }

        if (ongoingScans.isNotEmpty()) {
            sb.append("\n    Ongoing ${ongoingScans.size} scans:")
            ongoingScans.forEach { it.appendDetails(sb, currentTimeMs, elapsedRealtimeMs, true) }
        }

        sb.appendLine()
    }

    private fun LastScan.appendDetails(
        sb: StringBuilder,
        currentTimeMs: Long,
        elapsedRealtimeMs: Long,
        ongoing: Boolean,
    ) {
        val bootEpochMs = currentTimeMs - elapsedRealtimeMs

        val start = Instant.ofEpochMilli(bootEpochMs + startTimestamp)
        sb.append("\n      [${Utils.formatInstant(start)}")
        if (!ongoing) {
            val end = Instant.ofEpochMilli(bootEpochMs + endTimestamp)
            sb.append(" --> ${Utils.formatInstant(end)}")
        }
        sb.append("]  (")

        val duration: Long
        if (ongoing) {
            duration = elapsedRealtimeMs - startTimestamp
            sb.append("Elapsed: ${duration}ms")
        } else {
            duration = endTimestamp - startTimestamp
            sb.append("Duration: ${duration}ms")
        }

        sb.append(")\n        └ Info: ")

        if (isOpportunisticScan) sb.append("(Opp) ")
        if (isBackgroundScan) sb.append("(Back) ")
        if (isTimeout) sb.append("(Forced) ")
        if (isFilterScan) sb.append("(Filter) ")
        if (ongoing && isSuspended) sb.append("(Suspended) ")

        val results = resultsScreenOff + resultsScreenOn
        sb.append("Results: ($resultsScreenOff / $resultsScreenOn / $results) | ")
            .append("id: ($scannerId) | ")

        attributionTag?.let { sb.append("[$it] | ") }

        sb.append(if (isCallbackScan) "CB " else "PI ")
        when {
            isBatchScan -> sb.append("Batch Scan")
            isAutoBatchScan -> sb.append("Auto Batch Scan")
            else -> sb.append("Regular Scan")
        }

        if (!ongoing) {
            val importanceText =
                when {
                    appImportanceOnStart < IMPORTANCE_FOREGROUND_SERVICE -> " Higher than"
                    appImportanceOnStart > IMPORTANCE_FOREGROUND_SERVICE -> " Lower than"
                    else -> ""
                }
            sb.append("\n        └ App Importance:$importanceText Foreground Service")
        }

        if (suspendStartTime != 0L) {
            val suspendDuration =
                if (ongoing && isSuspended) {
                    (elapsedRealtimeMs - suspendStartTime) + suspendDuration
                } else {
                    suspendDuration
                }
            val activeDuration = duration - suspendDuration

            sb.append("\n        └ ")
            sb.append("Active Time: ${activeDuration}ms, Suspended Time: ${suspendDuration}ms")
        }

        sb.append("\n        └ Config: [ScanMode=${scanModeToString(scanMode)}")
        sb.append(", callbackType=${callbackTypeToString(scanCallbackType)}]")

        if (isFilterScan) sb.append(filterStringBuilder)
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
