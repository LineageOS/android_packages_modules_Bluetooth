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
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED
import android.bluetooth.IBluetoothScan
import android.bluetooth.State
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.os.WorkSource
import android.util.Log
import com.android.bluetooth.Util
import com.android.bluetooth.Util.enforceScanPermissionForDataDelivery
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_scan.ScanUtil.toStringShort

private const val TAG = ScanUtil.TAG_PREFIX + "ScanBinder"

class ScanBinder(
    private val adapterService: AdapterService,
    private val scanController: ScanController,
) : IBluetoothScan.Stub() {

    @Volatile private var isAvailable = true

    fun cleanup() {
        isAvailable = false
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    private fun withControllerRunOnScanThread(
        source: AttributionSource,
        method: String,
        block: ScanController.() -> Unit,
    ) = getController(source, method)?.let { it.runOrDoOnScanThread(it, block) }

    @RequiresPermission(BLUETOOTH_SCAN)
    private fun getController(source: AttributionSource, method: String): ScanController? {
        if (!isAvailable) return null
        if (!enforceScanPermissionForDataDelivery(adapterService, source, TAG, method)) return null
        return scanController
    }

    override fun registerAndStartScan(
        callback: IScannerCallback,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        workSource: WorkSource?,
        source: AttributionSource,
    ) {
        enforceTransportBlockFilterSupported(filters)
        enforcePrivilegedPermissionIfNeeded(settings, filters)
        if (workSource != null) {
            adapterService.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, null)
        }
        val hasPrivilegedPermission = Util.checkCallerHasPrivilegedPermission(adapterService)
        withControllerRunOnScanThread(source, "registerAndStartScan") {
            registerAndStartScan(
                callback,
                workSource,
                source,
                hasPrivilegedPermission,
                settings,
                filters,
            )
        } ?: run { callback.onScannerRegistered(SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, -1) }
    }

    override fun unregisterScanner(scannerId: Int, source: AttributionSource) {
        withControllerRunOnScanThread(source, "unregisterScanner") { unregisterScanner(scannerId) }
    }

    override fun registerPiAndStartScan(
        intent: PendingIntent,
        settings: ScanSettings,
        filters: List<ScanFilter>,
        source: AttributionSource,
    ) {
        enforceTransportBlockFilterSupported(filters)
        enforcePrivilegedPermissionIfNeeded(settings, filters)
        withControllerRunOnScanThread(source, "registerPiAndStartScan") {
            registerPiAndStartScan(intent, settings, filters, source)
        }
    }

    override fun stopScan(scannerId: Int, source: AttributionSource) {
        withControllerRunOnScanThread(source, "stopScan") { stopScan(scannerId) }
    }

    override fun stopScanForIntent(intent: PendingIntent, source: AttributionSource) {
        withControllerRunOnScanThread(source, "stopScanForIntent") { stopScan(intent) }
    }

    override fun flushPendingBatchResults(scannerId: Int, source: AttributionSource) {
        withControllerRunOnScanThread(source, "flushPendingBatchResults") {
            flushPendingBatchResults(scannerId)
        }
    }

    override fun registerSync(
        scanResult: ScanResult,
        skip: Int,
        timeout: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        withControllerRunOnScanThread(source, "registerSync") {
            registerSync(scanResult, skip, timeout, callback)
        }
    }

    override fun unregisterSync(callback: IPeriodicAdvertisingCallback, source: AttributionSource) {
        withControllerRunOnScanThread(source, "unregisterSync") { unregisterSync(callback) }
    }

    override fun transferSync(
        device: BluetoothDevice,
        serviceData: Int,
        syncHandle: Int,
        source: AttributionSource,
    ) {
        withControllerRunOnScanThread(source, "transferSync") {
            transferSync(device, serviceData, syncHandle)
        }
    }

    override fun transferSetInfo(
        device: BluetoothDevice,
        serviceData: Int,
        advHandle: Int,
        callback: IPeriodicAdvertisingCallback,
        source: AttributionSource,
    ) {
        withControllerRunOnScanThread(source, "transferSetInfo") {
            transferSetInfo(device, serviceData, advHandle, callback)
        }
    }

    override fun numHwTrackFiltersAvailable(source: AttributionSource): Int {
        val scan = getController(source, "numHwTrackFiltersAvailable") ?: return 0
        return scan.runOrFetchOnScanThread(scan, 0) { scan.numHwTrackFiltersAvailable() }
    }

    private fun enforceTransportBlockFilterSupported(filters: List<ScanFilter>) {
        val hasTdsFilter = filters.any { it.transportBlockFilter != null }
        if (hasTdsFilter) {
            if (adapterService.offloadedTransportDiscoveryDataScanSupported != FEATURE_SUPPORTED) {
                throw IllegalArgumentException("Transport Discovery Data filter is not supported")
            }
        }
    }

    @RequiresPermission(value = BLUETOOTH_PRIVILEGED, conditional = true)
    private fun enforcePrivilegedPermissionIfNeeded(
        settings: ScanSettings,
        filters: List<ScanFilter>,
    ) {
        Log.d(TAG, "enforcePrivilegedPermissionIfNeeded(${settings.toStringShort()}, $filters")

        fun needsPrivilegedPermissionForScan(settings: ScanSettings): Boolean {
            // BLE scan only mode needs special permission.
            if (adapterService.getState() != State.ON) {
                return true
            }

            return when {
                // Ambient discovery mode, needs privileged permission.
                settings.scanMode == ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> true
                // Regular scan, no special permission.
                settings.reportDelayMillis == 0L -> false
                // Batch scan, truncated mode needs permission.
                else -> settings.scanResultType == ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED
            }
        }

        /**
         * The ScanFilter#setDeviceAddress API overloads are @SystemApi access methods. This
         * requires that the permissions be BLUETOOTH_PRIVILEGED.
         */
        fun enforcePrivilegedPermissionIfNeeded(filters: List<ScanFilter>) =
            filters.forEach { filter ->
                // The only case to enforce here is if there is an address. If there is an address,
                // enforce if the correct combination criteria is met.
                if (filter.deviceAddress != null) {
                    // At this point we have an address, that means a caller used the
                    // setDeviceAddress(address) public API for the ScanFilter. We don't want to
                    // enforce if the type is PUBLIC and the IRK is null. However, if we have a
                    // different type that means the caller used a new @SystemApi such as
                    // setDeviceAddress(address, type) or setDeviceAddress(address, type, irk) which
                    // are both @SystemApi and require permissions to be enforced
                    if (
                        filter.addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC &&
                            filter.irk == null
                    ) {
                        // Do not enforce
                    } else {
                        adapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
                        return
                    }
                }
            }

        if (needsPrivilegedPermissionForScan(settings)) {
            adapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
            return
        }

        enforcePrivilegedPermissionIfNeeded(filters)
    }

    // TODO(b/444010402) Delete on Flags.leaudioBroadcastImproveSourceOperations() cleanup
    private fun <T> ScanController.runOrDoOnScanThread(target: T, block: T.() -> Unit) {
        if (isOnScanThread) {
            target.block()
        } else {
            doOnScanThread { target.block() }
        }
    }

    // TODO(b/444010402) Delete on Flags.leaudioBroadcastImproveSourceOperations() cleanup
    private fun <T, R> ScanController.runOrFetchOnScanThread(
        target: T,
        defaultValue: R,
        block: T.() -> R,
    ): R {
        return if (isOnScanThread) {
            target.block()
        } else {
            fetchOnScanThread<R>({ target.block() }, defaultValue)
        }
    }
}
