/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.util.Log

private const val TAG = ScanUtil.TAG_PREFIX + "ScanSuspendManager"

/** Class that handles Bluetooth LE scan related operations when the system suspends. */
internal class ScanSuspendManager(private val scanManager: ScanManager) {
    @get:JvmName("isSystemSuspended") var systemSuspended = false

    fun onSystemSuspendChanged(suspended: Boolean) =
        if (suspended) handleSystemSuspend() else handleSystemResume()

    private fun handleSystemSuspend() {
        if (systemSuspended) {
            return
        }
        systemSuspended = true
        Log.d(TAG, "handleSystemSuspend()")
        handleSuspendAllScans()
    }

    private fun handleSystemResume() {
        Log.d(TAG, "handleSystemResume(): Scan will be resumed when screen is on")
        systemSuspended = false
    }

    private fun handleSuspendAllScans() {
        fun suspendScan(client: ScanClient) {
            client.appScanStats.recordScanSuspend(client.scannerId)
            Log.d(TAG, "Suspend scan for $client")
            scanManager.stopScan(client.scannerId)
            scanManager.suspendedScanQueue.add(client)
        }
        scanManager.regularScanQueue.forEach(::suspendScan)
        scanManager.batchScanQueue.forEach(::suspendScan)
    }
}
