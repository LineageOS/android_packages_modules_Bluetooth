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

import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.UPDATE_DEVICE_STATS
import android.annotation.RequiresPermission
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
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
import com.android.bluetooth.Utils.checkScanPermissionForDataDelivery
import com.android.bluetooth.btservice.AdapterService

class ScanBinder(
    private val adapterService: AdapterService,
    private val scanController: ScanController,
) : IBluetoothScan.Stub() {

    companion object {
        private val TAG = ScanBinder::class.java.simpleName
    }

    @Volatile private var isAvailable = true

    fun cleanup() {
        isAvailable = false
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    private fun getController(source: AttributionSource, method: String): ScanController? {
        if (
            !isAvailable || !checkScanPermissionForDataDelivery(adapterService, source, TAG, method)
        ) {
            return null
        }

        return scanController
    }

    override fun registerScanner(
        callback: IScannerCallback,
        workSource: WorkSource?,
        source: AttributionSource,
    ) {
        if (workSource != null) {
            adapterService.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, null)
        }
        getController(source, "registerScanner")?.registerScanner(callback, workSource, source)
    }

    override fun unregisterScanner(scannerId: Int, source: AttributionSource) {
        getController(source, "unregisterScanner")?.unregisterScanner(scannerId)
    }

    override fun startScan(
        scannerId: Int,
        settings: ScanSettings?,
        filters: List<ScanFilter>?,
        source: AttributionSource,
    ) {
        enforcePrivilegedPermissionIfNeeded(settings)
        enforcePrivilegedPermissionIfNeeded(filters)
        getController(source, "startScan")?.startScan(scannerId, settings, filters, source)
    }

    override fun registerPiAndStartScan(
        intent: PendingIntent,
        settings: ScanSettings?,
        filters: List<ScanFilter>?,
        source: AttributionSource,
    ) {
        enforcePrivilegedPermissionIfNeeded(settings)
        enforcePrivilegedPermissionIfNeeded(filters)
        getController(source, "registerPiAndStartScan")
            ?.registerPiAndStartScan(intent, settings, filters, source)
    }

    override fun stopScan(scannerId: Int, source: AttributionSource) {
        getController(source, "stopScan")?.stopScan(scannerId)
    }

    override fun stopScanForIntent(intent: PendingIntent, source: AttributionSource) {
        getController(source, "stopScanForIntent")?.stopScan(intent)
    }

    override fun flushPendingBatchResults(scannerId: Int, source: AttributionSource) {
        getController(source, "flushPendingBatchResults")?.flushPendingBatchResults(scannerId)
    }

    override fun registerSync(
        scanResult: ScanResult,
        skip: Int,
        timeout: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        getController(source, "registerSync")
            ?.registerSync(scanResult, skip, timeout, callback, source)
    }

    override fun unregisterSync(callback: IPeriodicAdvertisingCallback, source: AttributionSource) {
        getController(source, "unregisterSync")?.unregisterSync(callback, source)
    }

    override fun transferSync(
        device: BluetoothDevice,
        serviceData: Int,
        syncHandle: Int,
        source: AttributionSource,
    ) {
        getController(source, "transferSync")?.transferSync(device, serviceData, syncHandle, source)
    }

    override fun transferSetInfo(
        device: BluetoothDevice,
        serviceData: Int,
        advHandle: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        getController(source, "transferSetInfo")
            ?.transferSetInfo(device, serviceData, advHandle, callback, source)
    }

    override fun numHwTrackFiltersAvailable(source: AttributionSource): Int {
        return getController(source, "numHwTrackFiltersAvailable")
            ?.numHwTrackFiltersAvailable(source) ?: 0
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private fun enforcePrivilegedPermissionIfNeeded(settings: ScanSettings?) {
        if (needsPrivilegedPermissionForScan(settings)) {
            adapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        }
    }

    private fun needsPrivilegedPermissionForScan(settings: ScanSettings?): Boolean {
        // BLE scan only mode needs special permission.
        if (adapterService.getState() != BluetoothAdapter.STATE_ON) {
            return true
        }

        // Regular scan, no special permission.
        if (settings == null) {
            return false
        }

        // Ambient discovery mode, needs privileged permission.
        if (settings.getScanMode() == ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY) {
            return true
        }

        // Regular scan, no special permission.
        if (settings.getReportDelayMillis() == 0L) {
            return false
        }

        // Batch scan, truncated mode needs permission.
        return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED
    }

    /**
     * The ScanFilter#setDeviceAddress API overloads are @SystemApi access methods. This requires
     * that the permissions be BLUETOOTH_PRIVILEGED.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private fun enforcePrivilegedPermissionIfNeeded(filters: List<ScanFilter>?) {
        Log.d(TAG, "enforcePrivilegedPermissionIfNeeded($filters))")
        // Some 3p API cases may have null filters, need to allow
        if (filters == null) return
        for (filter in filters) {
            // The only case to enforce here is if there is an address. If there is an address,
            // enforce if the correct combination criteria is met.
            if (filter.getDeviceAddress() != null) {
                // At this point we have an address, that means a caller used the
                // setDeviceAddress(address) public API for the ScanFilter. We don't want to enforce
                // if the type is PUBLIC and the IRK is null. However, if we have a different type
                // that means the caller used a new @SystemApi such as setDeviceAddress(address,
                // type) or setDeviceAddress(address, type, irk) which are both @SystemApi and
                // require permissions to be enforced
                if (
                    filter.getAddressType() == BluetoothDevice.ADDRESS_TYPE_PUBLIC &&
                        filter.getIrk() == null
                ) {
                    // Do not enforce
                } else {
                    adapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
                    return
                }
            }
        }
    }
}
