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

import android.app.PendingIntent
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.os.BatteryStatsManager
import android.os.UserHandle
import android.os.WorkSource
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
import com.android.bluetooth.util.getLastAttributionTag
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = ScanUtil.TAG_PREFIX + "ScannerMap"

/** List of our registered scanners. */
class ScannerMap {

    /** Internal map to keep track of logging information by app uid */
    private val appScanStatsMap = mutableMapOf<Int, AppScanStats>()
    private val apps = ConcurrentLinkedQueue<ScannerApp>()

    fun addWithCallback(
        appUid: Int,
        appPid: Int,
        appName: String,
        uuid: UUID,
        source: AttributionSource,
        workSource: WorkSource?,
        callback: IScannerCallback,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        adapterService: AdapterService,
        batteryStatsManager: BatteryStatsManager,
        isInternal: Boolean = false,
    ): ScannerApp =
        add(
            appUid = appUid,
            appPid = appPid,
            appName = appName,
            uuid = uuid,
            userHandle = null,
            source = source,
            workSource = workSource,
            callback = callback,
            settings = settings,
            filters = filters,
            piInfo = null,
            adapterService = adapterService,
            batteryStatsManager = batteryStatsManager,
            isInternal = isInternal,
        )

    fun addWithPendingIntent(
        appName: String,
        uuid: UUID,
        userHandle: UserHandle,
        source: AttributionSource,
        piInfo: ScanController.PendingIntentInfo,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        adapterService: AdapterService,
        batteryStatsManager: BatteryStatsManager,
    ): ScannerApp =
        add(
            appUid = piInfo.callingUid(),
            appPid = piInfo.callingPid(),
            appName = appName,
            uuid = uuid,
            userHandle = userHandle,
            source = source,
            workSource = null,
            callback = null,
            settings = settings,
            filters = filters,
            piInfo = piInfo,
            adapterService = adapterService,
            batteryStatsManager = batteryStatsManager,
            isInternal = false,
        )

    private fun add(
        appUid: Int,
        appPid: Int,
        appName: String,
        uuid: UUID,
        userHandle: UserHandle?,
        source: AttributionSource,
        workSource: WorkSource?,
        callback: IScannerCallback?,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        piInfo: ScanController.PendingIntentInfo?,
        adapterService: AdapterService,
        batteryStatsManager: BatteryStatsManager,
        isInternal: Boolean,
    ): ScannerApp {
        val appScanStats =
            appScanStatsMap.getOrPut(appUid) {
                // Bill the caller uid if the work source isn't passed through
                val workSource = workSource ?: WorkSource(appUid, appName)
                val workSourceUtil = WorkSourceUtil(workSource)
                AppScanStats(
                    appUid,
                    appPid,
                    appName,
                    workSourceUtil,
                    adapterService,
                    ScanMetricsReporter(workSource, workSourceUtil, batteryStatsManager),
                    TimeProvider.systemClock,
                )
            }
        val app =
            ScannerApp(
                appScanStats,
                uuid,
                userHandle,
                source.getLastAttributionTag(),
                callback,
                settings,
                filters,
                source,
                piInfo,
                isInternal,
            )
        apps.add(app)
        appScanStats.isRegistered = true
        return app
    }

    fun remove(id: Int) = removeBy("id=$id") { it.scannerId == id }

    fun remove(uuid: UUID) = removeBy("UUID=$uuid") { it.uuid == uuid }

    private fun removeBy(removalContext: String, predicate: (ScannerApp) -> Boolean) {
        Log.d(TAG, "remove(): By $removalContext")
        val iterator = apps.iterator()
        while (iterator.hasNext()) {
            val app = iterator.next()
            if (predicate(app)) {
                app.cleanup()
                iterator.remove()
                break
            }
        }
    }

    fun clear() {
        apps.forEach(ScannerApp::cleanup)
        apps.clear()
    }

    fun getAppScanStatsByUid(uid: Int): AppScanStats? = appScanStatsMap[uid]

    fun getAppScanStatsById(id: Int): AppScanStats? = getById(id)?.appScanStats

    fun getById(id: Int) = findBy("ID=$id") { it.scannerId == id }

    fun getByUuid(uuid: UUID) = findBy("UUID=$uuid") { it.uuid == uuid }

    fun getByPendingIntentInfo(intent: PendingIntent) =
        findBy("intent=$intent") { it.info?.intent() == intent }

    private fun findBy(criteria: String, predicate: (ScannerApp) -> Boolean): ScannerApp? {
        val app = apps.find(predicate)
        if (app == null) {
            Log.e(TAG, "Context not found for $criteria")
        }
        return app
    }

    fun dump(sb: StringBuilder, settingsMap: Map<Int, ScanSettings>) {
        sb.appendLine("LE Scanner:")
        if (apps.isNotEmpty()) {
            val columns =
                mutableListOf<Column<ScannerApp>>(
                    Column("UID", width = 5) { it.uid },
                    Column("PID", width = 5) { it.pid },
                    Column("ID", width = 2) { it.scannerId },
                    Column("PACKAGE") { it.name },
                )

            if (apps.any { !it.attributionTag.isNullOrEmpty() }) {
                columns.add(Column("TAG") { it.attributionTag ?: "" })
            }

            if (settingsMap.values.any { it.reportDelayMillis > 0 }) {
                columns.add(
                    Column("REPORT_DELAY_MS", width = 15) { app ->
                        val delay = settingsMap[app.scannerId]?.reportDelayMillis ?: 0
                        if (delay > 0) delay.toString() else ""
                    }
                )
            }

            sb.appendLine(apps.toTable(columns).indent("  "))
        }
        sb.appendLine()

        sb.appendLine("LE Scanner Map:")
        sb.appendLine("  Entries: ${appScanStatsMap.size}")
        for (appScanStats in appScanStatsMap.values) {
            val scannerApps = apps.filter { it.name == appScanStats.name }
            sb.appendLine(appScanStats.dump(scannerApps).indent("  "))
        }
    }
}
