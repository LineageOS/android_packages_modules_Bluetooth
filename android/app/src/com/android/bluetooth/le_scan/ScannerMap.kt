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
import com.android.bluetooth.Util.appNameOrUnknown
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.WorkSourceUtil
import com.android.bluetooth.util.getLastAttributionTag
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import java.util.UUID

private const val TAG = ScanUtil.TAG_PREFIX + "ScannerMap"

/** List of our registered scanners. */
class ScannerMap(
    private val adapterService: AdapterService,
    private val batteryStatsManager: BatteryStatsManager,
) {

    /** Internal map to keep track of logging information by app uid */
    private val appScanStatsMap = mutableMapOf<Int, AppScanStats>()
    private val apps = ArrayDeque<ScannerApp>()

    fun addWithCallback(
        source: AttributionSource,
        workSource: WorkSource?,
        callback: IScannerCallback,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        isInternal: Boolean = false,
    ) =
        add(
            userHandle = null,
            source = source,
            workSource = workSource,
            callback = callback,
            settings = settings,
            filters = filters,
            pendingIntent = null,
            isInternal = isInternal,
        )

    fun addWithPendingIntent(
        source: AttributionSource,
        pendingIntent: PendingIntent,
        settings: ScanSettings,
        filters: List<ScanFilter>,
    ) =
        add(
            userHandle = UserHandle.getUserHandleForUid(source.uid),
            source = source,
            workSource = null,
            callback = null,
            settings = settings,
            filters = filters,
            pendingIntent = pendingIntent,
            isInternal = false,
        )

    private fun add(
        userHandle: UserHandle?,
        source: AttributionSource,
        workSource: WorkSource?,
        callback: IScannerCallback?,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        pendingIntent: PendingIntent?,
        isInternal: Boolean,
    ): ScannerApp {
        val appScanStats =
            appScanStatsMap.getOrPut(source.uid) {
                val appName = adapterService.appNameOrUnknown(source.uid)
                // Bill the caller uid if the work source isn't passed through
                val workSource = workSource ?: WorkSource(source.uid, appName)
                val workSourceUtil = WorkSourceUtil(workSource)
                AppScanStats(
                    source.uid,
                    source.pid,
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
                UUID.randomUUID(),
                userHandle,
                source.getLastAttributionTag(),
                callback,
                settings,
                filters,
                source,
                pendingIntent,
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

    fun getByPendingIntent(pendingIntent: PendingIntent) =
        findBy("pendingIntent=$pendingIntent") { it.pendingIntent == pendingIntent }

    private fun findBy(criteria: String, predicate: (ScannerApp) -> Boolean): ScannerApp? {
        val app = apps.find(predicate)
        if (app == null) {
            Log.e(TAG, "Context not found for $criteria")
        }
        return app
    }

    fun dump(sb: StringBuilder) {
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
