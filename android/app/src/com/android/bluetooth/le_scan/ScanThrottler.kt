/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.le_scan

import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.ScanUtil.convertAllowanceToRemainingTime
import com.android.bluetooth.le_scan.ScanUtil.getScanAllowance
import com.android.bluetooth.le_scan.ScanUtil.isDowngradedScanClient
import com.android.bluetooth.le_scan.ScanUtil.isForceDowngradedScanClient
import com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScanClient
import com.android.bluetooth.le_scan.ScanUtil.minScanMode
import com.android.bluetooth.le_scan.ScanUtil.scanModeToString
import com.android.internal.annotations.VisibleForTesting
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private const val TAG = ScanUtil.TAG_PREFIX + "ScanThrottler"

/**
 * Throttler to adjusts scan settings based on system state, like screen status, app visibility
 * (foreground vs. background) and hardware resource contention (connecting state).
 */
class ScanThrottler(
    private val scanManager: ScanManager,
    private val adapterService: AdapterService,
    private val handler: Handler,
) {

    // When scan mode is throttled using scan allowance, a delayed record usage job will
    // be scheduled which records any scan allowance usage during the job run and lower all
    // scan clients scan mode when out of allowance. Record usage
    // job also schedules the refill job, which refills the scan allowance during the job run
    // and lift all scan clients scan mode when new allowance is available.
    // The refill job is per uid, so we can enforce hourly scan allowance for the app.
    // The recording job is per scan client, the earliest recording job will schedule the
    // refill job and cancel any pending recording jobs.
    @VisibleForTesting val recordUsageRunnables = HashMap<ScanClient, Runnable>()
    @VisibleForTesting val refillRunnables = HashMap<Int, Runnable>()
    @VisibleForTesting val backgroundUidThrottleRunnables = HashMap<Int, Runnable>()
    @VisibleForTesting var pendingScreenOffThrottleTask: Runnable? = null

    class ScanAllowanceLedger {
        var spentScanAllowance = Duration.ZERO
    }

    fun isScanAllowanceThrottlingEnabled(): Boolean {
        return Flags.scanAllowanceThrottlingEnabled()
    }

    fun throttleScanMode(client: ScanClient, targetMode: Int, isScreenOn: Boolean): Boolean {

        var targetScanMode = targetMode
        if (isOpportunisticScanClient(client)) {
            return false
        }

        // background throttling
        if (!scanManager.isAppForeground(client) || isForceDowngradedScanClient(client)) {
            val limitMode =
                if (!isScreenOn) {
                    ScanSettings.SCAN_MODE_SCREEN_OFF
                } else {
                    ScanSettings.SCAN_MODE_LOW_POWER
                }
            targetScanMode = minScanMode(limitMode, targetScanMode)
        }
        // screen off throttling
        else if (!isScreenOn) {
            targetScanMode =
                when (targetScanMode) {
                    ScanSettings.SCAN_MODE_LOW_POWER -> ScanSettings.SCAN_MODE_SCREEN_OFF
                    ScanSettings.SCAN_MODE_BALANCED,
                    ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                        ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED
                    ScanSettings.SCAN_MODE_LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    else -> return false
                }
        }

        if (isScanAllowanceThrottlingEnabled()) {
            return applyAllowanceThrottling(client, targetScanMode)
        } else {
            return client.updateScanMode(targetScanMode)
        }
    }

    fun throttleScanModeScreenOff(client: ScanClient): Boolean {
        val targetScanMode = client.scanModeApp
        if (throttleScanMode(client, targetScanMode, isScreenOn = false)) {
            Log.d(
                TAG,
                "throttleScanModeScreenOff(): for $client from=${scanModeToString(targetScanMode)} " +
                    "to=${scanModeToString(client.settings.scanMode)}",
            )
            return true
        }
        return false
    }

    private fun <K> postDelayedTask(
        key: K,
        registry: MutableMap<K, Runnable>,
        delay: Duration,
        action: () -> Unit,
    ) {
        val task = Runnable {
            registry.remove(key)
            action()
        }

        registry[key] = task
        handler.postDelayed(task, delay.inWholeMilliseconds)
    }

    fun throttleAllScanModeScreenOffDelayed(
        clients: Set<ScanClient>,
        callback: java.util.function.Consumer<Boolean>,
    ) {
        pendingScreenOffThrottleTask?.let { handler.removeCallbacks(it) }

        val task = Runnable {
            val updatedCount = clients.count { throttleScanModeScreenOff(it) }
            callback.accept(updatedCount > 0)
            pendingScreenOffThrottleTask = null
        }

        pendingScreenOffThrottleTask = task
        handler.postDelayed(task, ScanUtil.DEFAULT_SCAN_THROTTLE_DELAY.inWholeMilliseconds)
    }

    fun throttleScanModeScreenOn(client: ScanClient): Boolean {
        // use the scan_allowance_throttling_enabled flag to gate the screen off delayed
        // throttling
        if (isScanAllowanceThrottlingEnabled()) {
            // cancel any pending delayed screen off throttle job
            pendingScreenOffThrottleTask?.let { handler.removeCallbacks(it) }
            pendingScreenOffThrottleTask = null
        }

        val targetScanMode = client.scanModeApp
        if (throttleScanMode(client, targetScanMode, isScreenOn = true)) {
            Log.d(
                TAG,
                "throttleScanModeScreenOn(): for $client from=${scanModeToString(targetScanMode)} " +
                    "to=${scanModeToString(client.settings.scanMode)}",
            )
            return true
        }
        return false
    }

    fun throttleScanModeForegroundUid(client: ScanClient, uid: Int, isScreenOn: Boolean): Boolean {
        // use the scan_allowance_throttling_enabled flag to gate the background uid delayed
        // throttling
        if (isScanAllowanceThrottlingEnabled()) {
            // cancel any pending delayed background uid throttle job
            backgroundUidThrottleRunnables.remove(uid)?.let { handler.removeCallbacks(it) }
        }
        var targetScanMode = client.scanModeApp
        if (isForceDowngradedScanClient(client)) {
            targetScanMode = minScanMode(ScanSettings.SCAN_MODE_LOW_POWER, targetScanMode)
        }
        val isUpdated =
            if (isScanAllowanceThrottlingEnabled()) {
                applyAllowanceThrottling(client, targetScanMode)
            } else {
                client.updateScanMode(targetScanMode)
            }
        if (isUpdated) {
            Log.d(
                TAG,
                "throttleScanModeForegroundUid(): for $client uid=$uid " +
                    "isForeground=${scanManager.isAppForeground(client)} " +
                    "scanMode=${scanModeToString(client.settings.scanMode)}",
            )
            return true
        }
        return false
    }

    fun throttleScanModeBackgroundUid(client: ScanClient, uid: Int, isScreenOn: Boolean): Boolean {
        // TODO(b/478349128): Implement Event Debouncing for background uid
        var scanMode = client.settings.scanMode
        val targetScanMode =
            if (isScreenOn) {
                minScanMode(ScanSettings.SCAN_MODE_LOW_POWER, scanMode)
            } else {
                minScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF, scanMode)
            }
        val isUpdated =
            if (isScanAllowanceThrottlingEnabled()) {
                applyAllowanceThrottling(client, targetScanMode)
            } else {
                client.updateScanMode(targetScanMode)
            }
        if (isUpdated) {
            Log.d(
                TAG,
                "throttleScanModeBackgroundUid(): for $client uid=$uid " +
                    "isForeground=${scanManager.isAppForeground(client)} " +
                    "scanMode=${scanModeToString(client.settings.scanMode)}",
            )
            return true
        }
        return false
    }

    fun throttleAllScanModeBackgroundUidDelayed(
        uid: Int,
        isScreenOn: Boolean,
        clients: Set<ScanClient>,
        callback: java.util.function.Consumer<Boolean>,
    ) =
        postDelayedTask(uid, backgroundUidThrottleRunnables, ScanUtil.DEFAULT_SCAN_THROTTLE_DELAY) {
            val updatedCount = clients.count { throttleScanModeBackgroundUid(it, uid, isScreenOn) }
            callback.accept(updatedCount > 0)
        }

    fun downgradeScanModeFromMaxDuty(client: ScanClient): Boolean {
        if (adapterService.scanDowngradeDuration == java.time.Duration.ZERO) {
            return false
        }

        val updatedScanMode = minScanMode(client.settings.scanMode, ScanSettings.SCAN_MODE_BALANCED)

        val isUpdated =
            if (isScanAllowanceThrottlingEnabled())
                applyAllowanceThrottling(client, updatedScanMode)
            else client.updateScanMode(updatedScanMode)
        if (isUpdated) {
            client.appScanStats.setScanDowngrade(client.scannerId, true)
            Log.d(
                TAG,
                "downgradeScanModeFromMaxDuty(): for $client to=${scanModeToString(updatedScanMode)}",
            )
            return true
        }

        return false
    }

    fun revertDowngradeScanModeFromMaxDuty(client: ScanClient, isScreenOn: Boolean): Boolean {
        if (!isDowngradedScanClient(client)) {
            return false
        }
        client.appScanStats.setScanDowngrade(client.scannerId, false)
        Log.d(TAG, "revertDowngradeScanModeFromMaxDuty() for $client")
        return if (isScreenOn) {
            throttleScanModeScreenOn(client)
        } else {
            throttleScanModeScreenOff(client)
        }
    }

    fun applyAllowanceThrottling(client: ScanClient, targetScanMode: Int): Boolean {
        val ledger = client.appScanStats.scanAllowanceLedger
        if (scanManager.hasPrivilegedPermission(client) || scanManager.isAppForegroundUi(client)) {
            return client.updateScanMode(targetScanMode)
        }
        if (
            targetScanMode != ScanSettings.SCAN_MODE_SCREEN_OFF &&
                ledger.spentScanAllowance < getScanAllowance()
        ) {
            val remainingAllowance = getScanAllowance() - ledger.spentScanAllowance
            Log.d(
                TAG,
                "Apply scan mode $targetScanMode with available scan allowance $remainingAllowance",
            )
            return applyModeAndScheduleNextJob(client, targetScanMode, ledger, remainingAllowance)
        }
        recordUsageRunnables.remove(client)?.let { handler.removeCallbacks(it) }
        return client.updateScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF)
    }

    private fun scheduleAllowanceRefill(
        client: ScanClient,
        ledger: ScanAllowanceLedger,
        delay: Duration,
    ) {
        if (refillRunnables.containsKey(client.appUid)) {
            return // skip if the next refill job is pending
        }
        if (ledger.spentScanAllowance == Duration.ZERO) {
            return // skip if allowance is full
        }
        // Schedule the refill
        postDelayedTask(client.appUid, refillRunnables, delay) {
            refillAllowance(client, ledger)
            scanManager.onScanAllowanceChanged()
        }
        Log.d(
            TAG,
            "Scheduled allowance refill in $delay for app ${client.appUid} scanner ${client.scannerId}",
        )
    }

    private fun applyModeAndScheduleNextJob(
        client: ScanClient,
        targetScanMode: Int,
        ledger: ScanAllowanceLedger,
        allowance: Duration,
    ): Boolean {
        val remainingTime = convertAllowanceToRemainingTime(allowance, targetScanMode)

        // Replace it if the next record usage job is pending
        recordUsageRunnables.remove(client)?.let { handler.removeCallbacks(it) }
        postDelayedTask(client, recordUsageRunnables, remainingTime) {
            recordUsage(client, ledger, allowance) // Lock in the time spent
            scanManager.onScanAllowanceChanged()
            scheduleAllowanceRefill(
                client,
                ledger,
                maxOf(ALLOWANCE_REFILL_WINDOW - remainingTime, Duration.ZERO),
            )
        }
        Log.d(
            TAG,
            "Scheduled the next allowance check in $remainingTime for app ${client.appUid} scanner ${client.scannerId}",
        )
        return client.updateScanMode(targetScanMode)
    }

    private fun recordUsage(client: ScanClient, ledger: ScanAllowanceLedger, allowance: Duration) {
        ledger.spentScanAllowance += allowance
        Log.d(
            TAG,
            "Record Allowance Spent $allowance for app ${client.appUid} scanner ${client.scannerId}, " +
                "total spent: ${ledger.spentScanAllowance}",
        )
    }

    private fun refillAllowance(client: ScanClient, ledger: ScanAllowanceLedger) {
        ledger.spentScanAllowance = Duration.ZERO
        Log.d(TAG, "Scan Allowance Refilled for app ${client.appUid} scanner ${client.scannerId}")
    }

    fun removeRecordUsageRunnable(client: ScanClient) {
        recordUsageRunnables.remove(client)?.let { handler.removeCallbacks(it) }
    }

    companion object {
        @VisibleForTesting val ALLOWANCE_REFILL_WINDOW = 1.hours
    }
}
