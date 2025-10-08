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

    /** Internal map to keep track of logging information by app name */
    private val appScanStatsMap = mutableMapOf<Int, AppScanStats>()
    private val apps = ConcurrentLinkedQueue<ScannerApp>()

    fun addWithCallback(
        appUid: Int,
        appName: String,
        uuid: UUID,
        source: AttributionSource,
        workSource: WorkSource?,
        callback: IScannerCallback,
        adapterService: AdapterService,
    ): ScannerApp =
        add(
            appUid = appUid,
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
                AppScanStats(appName, workSource, appUid, adapterService, TimeProvider.systemClock)
            }
        val app =
            ScannerApp(
                uuid,
                userHandle,
                source.getLastAttributionTag(),
                callback,
                piInfo,
                appName,
                appScanStats,
            )
        apps.add(app)
        appScanStats.mIsRegistered = true
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
        sb.append("LE Scanner:\n")
        for (entry in apps) {
            sb.append("  app_if: ${entry.id}, appName: ${entry.name}")

            entry.attributionTag?.let { tag -> sb.append(", tag: $tag") }

            settingsMap[entry.id]?.let { settings ->
                if (settings.reportDelayMillis > 0) {
                    sb.append(", reportDelayMillis: ${settings.reportDelayMillis}")
                }
            }
            sb.append("\n")
        }

        sb.append("\nLE Scanner Map:\n")
        sb.append("  Entries: ${appScanStatsMap.size}\n\n")
        for (appScanStats in appScanStatsMap.values) {
            val scannerApps = apps.filter { it.name == appScanStats.mAppName }
            appScanStats.dump(sb, scannerApps)
        }
    }
}
