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
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.os.UserHandle
import android.os.WorkSource
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanUtil.appNameOrUnknown
import com.android.bluetooth.util.TimeProvider
import com.android.bluetooth.util.getLastAttributionTag
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "ScannerMap"

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
        adapterService: AdapterService,
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
            piInfo = null,
            adapterService = adapterService,
        )

    fun addWithPendingIntent(
        uuid: UUID,
        userHandle: UserHandle,
        source: AttributionSource,
        piInfo: ScanController.PendingIntentInfo,
        adapterService: AdapterService,
    ): ScannerApp =
        add(
            appUid = piInfo.callingUid(),
            appPid = piInfo.callingPid(),
            appName = appNameOrUnknown(piInfo.callingPackage(), piInfo.callingUid()),
            uuid = uuid,
            userHandle = userHandle,
            source = source,
            workSource = null,
            callback = null,
            piInfo = piInfo,
            adapterService = adapterService,
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
        piInfo: ScanController.PendingIntentInfo?,
        adapterService: AdapterService,
    ): ScannerApp {
        val appScanStats =
            appScanStatsMap.getOrPut(appUid) {
                AppScanStats(
                    appUid,
                    appPid,
                    appName,
                    workSource,
                    adapterService,
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
                piInfo,
            )
        apps.add(app)
        appScanStats.isRegistered = true
        return app
    }

    /** Remove the context for a given application ID. */
    fun remove(id: Int) = removeBy("id=$id") { it.id == id }

    /** Remove the context for a given UUID */
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

    /** Erases all application context entries. */
    fun clear() {
        apps.forEach(ScannerApp::cleanup)
        apps.clear()
    }

    /** Get Logging info by application UID */
    fun getAppScanStatsByUid(uid: Int): AppScanStats? = appScanStatsMap[uid]

    /** Get Logging info by ID */
    fun getAppScanStatsById(id: Int): AppScanStats? = getById(id)?.appScanStats

    /** Get an application context by ID. */
    fun getById(id: Int) = findBy("ID=$id") { it.id == id }

    /** Get an application context by UUID. */
    fun getByUuid(uuid: UUID) = findBy("UUID=$uuid") { it.uuid == uuid }

    /** Get an application context by the pending intent info object's intent. */
    fun getByPendingIntentInfo(intent: PendingIntent) =
        findBy("intent=$intent") { it.info?.intent() == intent }

    private fun findBy(searchContext: String, predicate: (ScannerApp) -> Boolean): ScannerApp? {
        val app = apps.find(predicate)
        if (app == null) {
            Log.e(TAG, "Context not found for $searchContext")
        }
        return app
    }

    /** Logs debug information for registered apps and their scan statistics. */
    fun dump(sb: StringBuilder, settingsMap: Map<Int, ScanSettings>) {
        sb.appendLine("\nLE Scanner:")

        if (apps.isNotEmpty()) {
            val colWidthUid = 5 // "10300"
            val colWidthPid = 5 // "10300"
            val colWidthId = 2 // Longest: "32"
            val colWidthPackage = apps.maxOfOrNull { it.name.length } ?: 30
            val colWidthTag = apps.maxOfOrNull { it.attributionTag?.length ?: 0 } ?: 0
            val colTagExists = colWidthTag != 0
            val reportDelayMsColWidth =
                if (settingsMap.values.any { it.reportDelayMillis > 0 }) 15 else 0
            val colReportDelayExists = reportDelayMsColWidth != 0

            // Headers
            val headerUid = "UID".padEnd(colWidthUid)
            val headerPid = "PID".padEnd(colWidthPid)
            val headerId = "ID".padEnd(colWidthId)
            val headerPackage = "PACKAGE".padEnd(colWidthPackage)
            val headerTag = "TAG".padEnd(colWidthTag)
            val headerReportDelayMs = "REPORT_DELAY_MS" // Last column doesn't need padding
            sb.append("  $headerUid $headerPid $headerId $headerPackage")
            if (colTagExists) sb.append(" $headerTag")
            if (colReportDelayExists) sb.append(" $headerReportDelayMs")
            sb.append("\n")

            // Separators
            val separatorUid = "-".repeat(colWidthUid)
            val separatorPid = "-".repeat(colWidthPid)
            val separatorId = "-".repeat(colWidthId)
            val separatorPackage = "-".repeat(colWidthPackage)
            val separatorTag = "-".repeat(colWidthTag)
            val separatorReportDelayMs = "-".repeat(reportDelayMsColWidth)
            sb.append("  $separatorUid $separatorPid $separatorId $separatorPackage")
            if (colTagExists) sb.append(" $separatorTag")
            if (colReportDelayExists) sb.append(" $separatorReportDelayMs")
            sb.append("\n")

            // Values
            apps.forEach { app ->
                val uid = app.uid.toString().padEnd(colWidthUid)
                val pid = app.pid.toString().padEnd(colWidthPid)
                val id = app.id.toString().padEnd(colWidthId)
                val name = app.name.padEnd(colWidthPackage)
                sb.append("  $uid $pid $id $name")
                if (colTagExists) {
                    val tag = (app.attributionTag ?: "").padEnd(colWidthTag)
                    sb.append(" $tag")
                }
                if (colReportDelayExists) {
                    val reportDelayMs = settingsMap[app.id]?.reportDelayMillis ?: 0
                    val reportDelayString = if (reportDelayMs > 0) reportDelayMs.toString() else ""
                    sb.append(" ${reportDelayString.padEnd(reportDelayMsColWidth)}")
                }
                sb.append("\n")
            }
        }

        sb.appendLine("\nLE Scanner Map:")
        sb.appendLine("  Entries: ${appScanStatsMap.size}")
        for (appScanStats in appScanStatsMap.values) {
            val scannerApps = apps.filter { it.name == appScanStats.name }
            appScanStats.dump(sb, scannerApps)
        }
    }
}
