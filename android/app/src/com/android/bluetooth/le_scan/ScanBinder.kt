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
import android.bluetooth.BluetoothDevice
import android.bluetooth.IBluetoothScan
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.os.WorkSource
import android.util.Log

class ScanBinder(private val mScanController: ScanController) : IBluetoothScan.Stub() {

    companion object {
        private val TAG = ScanBinder::class.java.simpleName
    }

    @Volatile private var mIsAvailable = true

    fun cleanup() {
        mIsAvailable = false
    }

    private fun getScanController(): ScanController? {
        if (mIsAvailable) {
            return mScanController
        } else {
            Log.e(TAG, "getScanController() - ScanController requested, but not available!")
            return null
        }
    }

    override fun registerScanner(
        callback: IScannerCallback,
        workSource: WorkSource?,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.registerScanner(callback, workSource, source)
        }
    }

    override fun unregisterScanner(scannerId: Int, source: AttributionSource) {
        getScanController()?.let { scanController ->
            scanController.unregisterScanner(scannerId, source)
        }
    }

    override fun startScan(
        scannerId: Int,
        settings: ScanSettings?,
        filters: List<ScanFilter>?,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.startScan(scannerId, settings, filters, source)
        }
    }

    override fun startScanForIntent(
        intent: PendingIntent,
        settings: ScanSettings?,
        filters: List<ScanFilter>?,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.registerPiAndStartScan(intent, settings, filters, source)
        }
    }

    override fun stopScan(scannerId: Int, source: AttributionSource) {
        getScanController()?.let { scanController -> scanController.stopScan(scannerId, source) }
    }

    override fun stopScanForIntent(intent: PendingIntent, source: AttributionSource) {
        getScanController()?.let { scanController -> scanController.stopScan(intent, source) }
    }

    override fun flushPendingBatchResults(scannerId: Int, source: AttributionSource) {
        getScanController()?.let { scanController ->
            scanController.flushPendingBatchResults(scannerId, source)
        }
    }

    override fun registerSync(
        scanResult: ScanResult,
        skip: Int,
        timeout: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.registerSync(scanResult, skip, timeout, callback, source)
        }
    }

    override fun unregisterSync(callback: IPeriodicAdvertisingCallback, source: AttributionSource) {
        getScanController()?.let { scanController ->
            scanController.unregisterSync(callback, source)
        }
    }

    override fun transferSync(
        device: BluetoothDevice,
        serviceData: Int,
        syncHandle: Int,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.transferSync(device, serviceData, syncHandle, source)
        }
    }

    override fun transferSetInfo(
        device: BluetoothDevice,
        serviceData: Int,
        advHandle: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        getScanController()?.let { scanController ->
            scanController.transferSetInfo(device, serviceData, advHandle, callback, source)
        }
    }

    override fun numHwTrackFiltersAvailable(source: AttributionSource): Int {
        return getScanController()?.let { scanController ->
            scanController.numHwTrackFiltersAvailable(source)
        } ?: 0
    }
}
