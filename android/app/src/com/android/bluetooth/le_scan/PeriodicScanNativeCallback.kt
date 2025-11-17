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

import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

private const val TAG = ScanUtil.TAG_PREFIX + "PeriodicScanNativeCallback"

class PeriodicScanNativeCallback(
    adapterService: AdapterService,
    private val manager: PeriodicScanManager,
) : NativeCallback(adapterService) {

    fun onSyncStarted(
        regId: Int,
        syncHandle: Int,
        sid: Int,
        addressType: Int,
        address: String,
        phy: Int,
        interval: Int,
        status: Int,
    ) {
        Log.d(TAG, "onSyncStarted(): regId=$regId syncHandle=$syncHandle status=$status")
        doOnScanThread {
            onSyncStarted(regId, syncHandle, sid, addressType, address, phy, interval, status)
        }
    }

    fun onSyncReport(syncHandle: Int, txPower: Int, rssi: Int, dataStatus: Int, data: ByteArray) {
        Log.d(TAG, "onSyncReport(): syncHandle=$syncHandle")
        doOnScanThread { onSyncReport(syncHandle, txPower, rssi, dataStatus, data) }
    }

    fun onSyncLost(syncHandle: Int) {
        Log.d(TAG, "onSyncLost(): syncHandle=$syncHandle")
        doOnScanThread { onSyncLost(syncHandle) }
    }

    fun onSyncTransferredCallback(paSource: Int, status: Int, bda: String) {
        Log.d(TAG, "onSyncTransferredCallback()")
        doOnScanThread { onSyncTransferredCallback(paSource, status, bda) }
    }

    fun onBigInfoReport(syncHandle: Int, encrypted: Boolean) {
        Log.d(TAG, "onBigInfoReport(): syncHandle=$syncHandle encrypted=$encrypted")
        doOnScanThread { onBigInfoReport(syncHandle, encrypted) }
    }

    private fun doOnScanThread(block: PeriodicScanManager.() -> Unit) {
        manager.doOnScanThread { manager.block() }
    }
}
