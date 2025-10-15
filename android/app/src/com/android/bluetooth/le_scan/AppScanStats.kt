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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.BatteryStatsManager
import android.os.WorkSource
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanUtil.dumpExt
import com.android.bluetooth.le_scan.ScanUtil.isBackgroundScan
import com.android.bluetooth.le_scan.ScanUtil.isBatchScan
import com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScan
import com.android.bluetooth.le_scan.ScanUtil.scanFilterToStringWithoutNullParam
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** ScanStats class helps keep track of information about scans on a per application basis. */
class AppScanStats(
    val appName: String,
    source: WorkSource?,
    uid: Int,
    private val adapterService: AdapterService,
    val timeProvider: TimeProvider,
) {

    class LastScan(
        val startTimestamp: Long,
        var endTimestamp: Long = 0,
        val scannerId: Int,
        val scanMode: Int,
        val scanCallbackType: Int,
        val reportDelayMillis: Long,
        val isBackgroundScan: Boolean,
        val isBatchScan: Boolean,
        val isCallbackScan: Boolean,
        val isFilterScan: Boolean,
        val isOpportunisticScan: Boolean,
        val appImportanceOnStart: Int,
        val attributionTag: String?,
        val filterStringBuilder: StringBuilder = StringBuilder(),
        var suspendDuration: Long = 0L,
        var suspendStartTime: Long = 0L,
        var isSuspended: Boolean = false,
        var isTimeout: Boolean = false,
        var isDowngraded: Boolean = false,
        var isAutoBatchScan: Boolean = false,
        var resultsScreenOn: Int = 0,
        var resultsScreenOff: Int = 0,
    )

    val lastScans: MutableList<LastScan> = ArrayList()
    val ongoingScans: MutableMap<Int, LastScan> = HashMap()

    val workSourceUtil: WorkSourceUtil
    private val scanMetricsReporter: ScanMetricsReporter

    var isAppDead = false
    var isRegistered = false
    private var appImportance = IMPORTANCE_CACHED
    var scansStarted = 0
    var scansStopped = 0
    private var scanStartTimestamp = 0L
    var totalActiveTime = 0L
    var totalSuspendTime = 0L
    var totalScanTime = 0L
    var oppScanTime = 0L
    var lowPowerScanTime = 0L
    var balancedScanTime = 0L
    var lowLatencyScanTime = 0L
    var ambientDiscoveryScanTime = 0L
    var oppScan = 0
    var lowPowerScan = 0
    var balancedScan = 0
    var lowLatencyScan = 0
    var ambientDiscoveryScan = 0
    var resultsScreenOn = 0
    var resultsScreenOff = 0
    var scheduledBatchAlarmCount = 0

    init {
        // Bill the caller uid if the work source isn't passed through
        val workSource = source ?: WorkSource(uid, appName)
        workSourceUtil = WorkSourceUtil(workSource)
        val batteryStatsManager = adapterService.getSystemService(BatteryStatsManager::class.java)
        scanMetricsReporter = ScanMetricsReporter(workSource, workSourceUtil, batteryStatsManager)
    }

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

    @Synchronized fun getAppImportance() = appImportance

    @Synchronized
    fun setAppImportance(importance: Int) {
        appImportance = importance
    }

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
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> oppScan++
            ScanSettings.SCAN_MODE_LOW_POWER -> lowPowerScan++
            ScanSettings.SCAN_MODE_BALANCED -> balancedScan++
            ScanSettings.SCAN_MODE_LOW_LATENCY -> lowLatencyScan++
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScan++
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
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> oppScanTime += activeDuration
            ScanSettings.SCAN_MODE_LOW_POWER -> lowPowerScanTime += activeDuration
            ScanSettings.SCAN_MODE_BALANCED -> balancedScanTime += activeDuration
            ScanSettings.SCAN_MODE_LOW_LATENCY -> lowLatencyScanTime += activeDuration
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> ambientDiscoveryScanTime += activeDuration
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
        // TODO(b/397863857) Inline this on `Flags.scanControllerThread()` cleanup within ScannerMap
        dumpExt(sb, apps)
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
