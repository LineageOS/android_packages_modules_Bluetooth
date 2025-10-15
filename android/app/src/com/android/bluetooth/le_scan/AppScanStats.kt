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

private const val TAG = "AppScanStats"

/** ScanStats class helps keep track of information about scans on a per application basis. */
class AppScanStats(
    val appName: String,
    source: WorkSource?,
    uid: Int,
    private val adapterService: AdapterService,
    val timeProvider: TimeProvider,
) {

    class LastScan(
        startTimestamp: Long,
        scannerId: Int,
        scanMode: Int,
        scanCallbackType: Int,
        reportDelayMillis: Long,
        isBackgroundScan: Boolean,
        isBatchScan: Boolean,
        isCallbackScan: Boolean,
        isFilterScan: Boolean,
        isOpportunisticScan: Boolean,
        appImportanceOnStart: Int,
        attributionTag: String?,
    ) {
        val mFilterString: StringBuilder = StringBuilder()
        val mScannerId: Int
        val mScanMode: Int
        val mScanCallbackType: Int
        val mIsBackgroundScan: Boolean
        val mIsBatchScan: Boolean
        val mIsCallbackScan: Boolean
        val mIsFilterScan: Boolean
        val mIsOpportunisticScan: Boolean
        val mReportDelayMillis: Long
        val mAppImportanceOnStart: Int
        val mAttributionTag: String?

        val mStartTimestamp: Long
        var mEndTimestamp: Long = 0

        var mSuspendDuration: Long = 0
        var mSuspendStartTime: Long = 0
        var mIsSuspended = false
        var mIsTimeout = false
        var mIsDowngraded = false
        var mIsAutoBatchScan = false
        var mResultsScreenOn = 0
        var mResultsScreenOff = 0

        init {
            mStartTimestamp = startTimestamp
            mScannerId = scannerId
            mScanMode = scanMode
            mScanCallbackType = scanCallbackType
            mReportDelayMillis = reportDelayMillis
            mIsBackgroundScan = isBackgroundScan
            mIsBatchScan = isBatchScan
            mIsCallbackScan = isCallbackScan
            mIsFilterScan = isFilterScan
            mIsOpportunisticScan = isOpportunisticScan
            mAppImportanceOnStart = appImportanceOnStart
            mAttributionTag = attributionTag
        }
    }

    val mLastScans: MutableList<LastScan> = ArrayList()
    val mOngoingScans: MutableMap<Int, LastScan> = HashMap()

    @JvmField val mWorkSourceUtil: WorkSourceUtil
    private val mScanMetricsReporter: ScanMetricsReporter

    @JvmField var mIsAppDead = false
    var mIsRegistered = false
    @JvmField var mAppImportance = IMPORTANCE_CACHED
    var mScansStarted = 0
    var mScansStopped = 0
    private var mScanStartTimestamp: Long = 0
    var mTotalActiveTime: Long = 0
    var mTotalSuspendTime: Long = 0
    var mTotalScanTime: Long = 0
    var mOppScanTime: Long = 0
    var mLowPowerScanTime: Long = 0
    var mBalancedScanTime: Long = 0
    var mLowLatencyScanTime: Long = 0
    var mAmbientDiscoveryScanTime: Long = 0
    var mOppScan = 0
    var mLowPowerScan = 0
    var mBalancedScan = 0
    var mLowLatencyScan = 0
    var mAmbientDiscoveryScan = 0
    var mResultsScreenOn = 0
    var mResultsScreenOff = 0
    var mScheduledBatchAlarmCount = 0

    init {
        // Bill the caller uid if the work source isn't passed through
        val workSource = source ?: WorkSource(uid, appName)
        mWorkSourceUtil = WorkSourceUtil(workSource)
        val batteryStatsManager = adapterService.getSystemService(BatteryStatsManager::class.java)
        mScanMetricsReporter = ScanMetricsReporter(workSource, mWorkSourceUtil, batteryStatsManager)
    }

    @Synchronized fun getScanFromScannerId(scannerId: Int) = mOngoingScans[scannerId]

    @Synchronized
    fun addResult(scannerId: Int) {
        val isScreenOn = sIsScreenOn.get()
        if (isScreenOn) {
            mResultsScreenOn++
        } else {
            mResultsScreenOff++
        }

        val scan = getScanFromScannerId(scannerId) ?: return
        if (isScreenOn) {
            scan.mResultsScreenOn++
        } else {
            scan.mResultsScreenOff++
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.mResultsScreenOn + scan.mResultsScreenOff) % 100 == 0) {
            mScanMetricsReporter.reportScanResults(100)
        }
    }

    @Synchronized
    fun addResults(scannerId: Int, numberOfNewResults: Int) {
        val isScreenOn = sIsScreenOn.get()
        if (isScreenOn) {
            mResultsScreenOn += numberOfNewResults
        } else {
            mResultsScreenOff += numberOfNewResults
        }

        val scan = getScanFromScannerId(scannerId) ?: return

        val resultsBeforeUpdate = scan.mResultsScreenOn + scan.mResultsScreenOff
        if (isScreenOn) {
            scan.mResultsScreenOn += numberOfNewResults
        } else {
            scan.mResultsScreenOff += numberOfNewResults
        }

        // Only update battery stats every 100 results to lower the high-cost of binder transactions
        if ((scan.mResultsScreenOn + scan.mResultsScreenOff) / 100 > resultsBeforeUpdate / 100) {
            mScanMetricsReporter.reportScanResults(100)
        }
    }

    @Synchronized fun isScanning() = mOngoingScans.isNotEmpty()

    @Synchronized
    fun isScanTimeout(scannerId: Int) = getScanFromScannerId(scannerId)?.mIsTimeout ?: false

    @Synchronized
    fun isScanDowngraded(scannerId: Int) = getScanFromScannerId(scannerId)?.mIsDowngraded ?: false

    @Synchronized
    fun isAutoBatchScan(scannerId: Int) = getScanFromScannerId(scannerId)?.mIsAutoBatchScan ?: false

    @Synchronized
    fun setAppImportance(importance: Int) {
        mAppImportance = importance
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
        mScansStarted++
        val startTimestamp = timeProvider.elapsedRealtime()
        val scan =
            LastScan(
                startTimestamp,
                scannerId,
                settings.scanMode,
                settings.callbackType,
                settings.reportDelayMillis,
                isBackgroundScan(settings),
                isBatchScan(settings),
                isCallbackScan,
                isFilterScan,
                isOpportunisticScan(settings),
                mAppImportance,
                attributionTag,
            )
        when (scan.mScanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> mOppScan++
            ScanSettings.SCAN_MODE_LOW_POWER -> mLowPowerScan++
            ScanSettings.SCAN_MODE_BALANCED -> mBalancedScan++
            ScanSettings.SCAN_MODE_LOW_LATENCY -> mLowLatencyScan++
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> mAmbientDiscoveryScan++
        }

        if (isFilterScan) {
            filters.forEach { filter ->
                scan.mFilterString
                    .append("\n        └ ")
                    .append(scanFilterToStringWithoutNullParam(filter))
            }
        }

        if (!isScanning()) {
            mScanStartTimestamp = startTimestamp
        }

        mScanMetricsReporter.recordScanStart(
            scan,
            mOngoingScans.size,
            sIsScreenOn.get(),
            mIsAppDead,
            mAppImportance,
        )

        mOngoingScans[scannerId] = scan
    }

    @Synchronized
    fun recordScanStop(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId) ?: return
        mScansStopped++
        val stopTime = timeProvider.elapsedRealtime()
        scan.mEndTimestamp = stopTime
        if (scan.mIsSuspended) {
            val suspendDuration = stopTime - scan.mSuspendStartTime
            scan.mSuspendDuration += suspendDuration
            mTotalSuspendTime += suspendDuration
        }
        mOngoingScans.remove(scannerId)
        if (mLastScans.size >= adapterService.scanQuotaCount) {
            mLastScans.removeFirst()
        }
        mLastScans.add(scan)

        val scanDuration = scan.mEndTimestamp - scan.mStartTimestamp
        mTotalScanTime += scanDuration
        val activeDuration = scanDuration - scan.mSuspendDuration
        mTotalActiveTime += activeDuration
        when (scan.mScanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> mOppScanTime += activeDuration
            ScanSettings.SCAN_MODE_LOW_POWER -> mLowPowerScanTime += activeDuration
            ScanSettings.SCAN_MODE_BALANCED -> mBalancedScanTime += activeDuration
            ScanSettings.SCAN_MODE_LOW_LATENCY -> mLowLatencyScanTime += activeDuration
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> mAmbientDiscoveryScanTime += activeDuration
        }

        mScanMetricsReporter.recordScanStop(
            scan,
            scanDuration,
            mOngoingScans.size,
            sIsScreenOn.get(),
            mIsAppDead,
            mAppImportance,
        )
    }

    @Synchronized
    fun recordScanTimeoutCountMetrics(scannerId: Int, scanTimeoutMillis: Long) {
        val scan = getScanFromScannerId(scannerId)
        mScanMetricsReporter.recordScanTimeoutCountMetrics(scan, scanTimeoutMillis)
    }

    @Synchronized
    fun recordHwFilterNotAvailableCountMetrics(scannerId: Int, numOfFilterSupported: Long) {
        val scan = getScanFromScannerId(scannerId)
        mScanMetricsReporter.recordHwFilterNotAvailableCountMetrics(scan, numOfFilterSupported)
    }

    @Synchronized
    fun recordScanSuspend(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId)
        if (scan == null || scan.mIsSuspended) {
            return
        }
        scan.mSuspendStartTime = timeProvider.elapsedRealtime()
        scan.mIsSuspended = true
    }

    @Synchronized
    fun recordScanResume(scannerId: Int) {
        val scan = getScanFromScannerId(scannerId)
        if (scan == null || !scan.mIsSuspended) {
            return
        }
        scan.mIsSuspended = false
        val stopTime = timeProvider.elapsedRealtime()
        val suspendDuration = stopTime - scan.mSuspendStartTime
        scan.mSuspendDuration += suspendDuration
        mTotalSuspendTime += suspendDuration
    }

    @Synchronized
    fun setScanTimeout(scannerId: Int) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.mIsTimeout = true
    }

    @Synchronized
    fun setScanDowngrade(scannerId: Int, isDowngrade: Boolean) {
        if (!isScanning()) {
            return
        }
        getScanFromScannerId(scannerId)?.mIsDowngraded = isDowngrade
    }

    @Synchronized
    fun setAutoBatchScan(scannerId: Int, isBatchScan: Boolean) {
        getScanFromScannerId(scannerId)?.mIsAutoBatchScan = isBatchScan
    }

    @Synchronized
    fun isScanningTooFrequently(): Boolean {
        if (mLastScans.size < adapterService.scanQuotaCount) {
            return false
        }
        val oldestLastScanStartTimestamp = mLastScans.first().mStartTimestamp
        return Duration.ofMillis(timeProvider.elapsedRealtime() - oldestLastScanStartTimestamp)
            .compareTo(adapterService.scanQuotaWindow) < 0
    }

    @Synchronized
    fun isScanningTooLong(): Boolean {
        if (!isScanning()) {
            return false
        }
        return Duration.ofMillis(timeProvider.elapsedRealtime() - mScanStartTimestamp)
            .compareTo(adapterService.scanTimeout) >= 0
    }

    @Synchronized
    fun hasRecentScan(): Boolean {
        if (!isScanning() || mLastScans.isEmpty()) {
            return false
        }
        val lastScan = mLastScans.last()
        return (timeProvider.elapsedRealtime() - lastScan.mEndTimestamp) < LARGE_SCAN_TIME_GAP_MS
    }

    @Synchronized
    fun recordBatchAlarmScheduled() {
        mScheduledBatchAlarmCount++
    }

    fun getAttributionTagFromScannerId(scannerId: Int): String =
        getScanFromScannerId(scannerId)?.mAttributionTag ?: ""

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
