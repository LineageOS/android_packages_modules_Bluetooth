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
import com.android.bluetooth.profile.NativeInterface
import java.util.UUID

private const val TAG = ScanUtil.TAG_PREFIX + "ScanNativeInterface"

class ScanNativeInterface(nativeCallback: ScanNativeCallback) :
    NativeInterface<ScanNativeCallback>(nativeCallback) {

    fun init() {
        initializeNative()
    }

    override fun cleanup() {
        cleanupNative()
    }

    private external fun initializeNative()

    private external fun cleanupNative()

    /** ************************ Regular scan related native methods ************************* */
    private external fun registerScannerNative(appUuidMsb: Long, appUuidLsb: Long)

    private external fun unregisterScannerNative(scannerId: Int)

    private external fun scanNative(start: Boolean)

    private external fun setScanParametersNative(
        scannerId1m: Int,
        scanInterval1m: Int,
        scanWindow1m: Int,
        scannerIdCoded: Int,
        scanIntervalCoded: Int,
        scanWindowCoded: Int,
        scanPhy: Int,
    )

    /** ************************ Filter related native methods ******************************* */
    private external fun scanFilterAddNative(
        clientId: Int,
        entries: Array<ScanFilterQueue.Entry>,
        filterIndex: Int,
    )

    private external fun scanFilterParamAddNative(filtValue: FilterParams)

    private external fun scanFilterParamDeleteNative(scannerId: Int, filtIndex: Int)

    private external fun scanFilterClearNative(scannerId: Int, filterIndex: Int)

    private external fun scanFilterEnableNative(scannerId: Int, enable: Boolean)

    /** ************************ MSFT scan related native methods **************************** */
    private external fun isMsftSupportedNative(): Boolean

    private external fun msftAdvMonitorAddNative(
        msft_adv_monitor: MsftAdvMonitor.Monitor,
        msft_adv_monitor_patterns: Array<MsftAdvMonitor.Pattern>,
        msft_adv_monitor_uuid: MsftAdvMonitor.Uuid,
        msft_adv_monitor_address: MsftAdvMonitor.Address,
        filter_index: Int,
    )

    private external fun msftAdvMonitorRemoveNative(filter_index: Int, monitor_handle: Int)

    private external fun msftAdvMonitorEnableNative(enable: Boolean)

    /** ************************ Batch related native methods ******************************** */
    private external fun configBatchScanStorageNative(
        scannerId: Int,
        maxFullReportsPercent: Int,
        maxTruncatedReportsPercent: Int,
        notifyThresholdPercent: Int,
    )

    private external fun startBatchScanNative(
        scannerId: Int,
        scanMode: Int,
        scanIntervalUnit: Int,
        scanWindowUnit: Int,
        addressType: Int,
        discardRule: Int,
    )

    private external fun stopBatchScanNative(scannerId: Int)

    private external fun readScanReportsNative(scannerId: Int, scanType: Int)

    fun registerScanner(uuid: UUID) {
        registerScannerNative(uuid.mostSignificantBits, uuid.leastSignificantBits)
    }

    fun unregisterScanner(scannerId: Int) {
        unregisterScannerNative(scannerId)
    }

    fun scan(start: Boolean, caller: String) {
        Log.d(TAG, "Scan=${if (start) "START" else "STOP"}, caller=$caller")
        scanNative(start)
    }

    /** Configure BLE scan parameters */
    fun setScanParameters(
        scannerId1m: Int,
        scanInterval1m: Int,
        scanWindow1m: Int,
        scannerIdCoded: Int,
        scanIntervalCoded: Int,
        scanWindowCoded: Int,
        scanPhy: Int,
    ) {
        setScanParametersNative(
            scannerId1m,
            scanInterval1m,
            scanWindow1m,
            scannerIdCoded,
            scanIntervalCoded,
            scanWindowCoded,
            scanPhy,
        )
    }

    /** Add BLE scan filter */
    fun scanFilterAdd(clientId: Int, entries: Array<ScanFilterQueue.Entry>, filterIndex: Int) {
        scanFilterAddNative(clientId, entries, filterIndex)
    }

    /** Add BLE scan filter parameters */
    fun scanFilterParamAdd(filtValue: FilterParams) {
        scanFilterParamAddNative(filtValue)
    }

    /** Delete BLE scan filter parameters */
    fun scanFilterParamDelete(scannerId: Int, filtIndex: Int) {
        scanFilterParamDeleteNative(scannerId, filtIndex)
    }

    /** Clear BLE scan filter */
    fun scanFilterClear(scannerId: Int, filterIndex: Int) {
        scanFilterClearNative(scannerId, filterIndex)
    }

    /** Enable/disable BLE scan filter */
    fun scanFilterEnable(scannerId: Int, enable: Boolean) {
        scanFilterEnableNative(scannerId, enable)
    }

    /** Check if MSFT HCI extension is supported */
    fun isMsftSupported(): Boolean {
        return isMsftSupportedNative()
    }

    /** Add a MSFT Advertisement Monitor */
    fun msftAdvMonitorAdd(
        msft_adv_monitor: MsftAdvMonitor.Monitor,
        msft_adv_monitor_patterns: Array<MsftAdvMonitor.Pattern>,
        msft_adv_monitor_uuid: MsftAdvMonitor.Uuid,
        msft_adv_monitor_address: MsftAdvMonitor.Address,
        filter_index: Int,
    ) {
        msftAdvMonitorAddNative(
            msft_adv_monitor,
            msft_adv_monitor_patterns,
            msft_adv_monitor_uuid,
            msft_adv_monitor_address,
            filter_index,
        )
    }

    /** Remove a MSFT Advertisement Monitor */
    fun msftAdvMonitorRemove(filterIndex: Int, monitorHandle: Int) {
        msftAdvMonitorRemoveNative(filterIndex, monitorHandle)
    }

    /** Enable a MSFT Advertisement Monitor */
    fun msftAdvMonitorEnable(enable: Boolean) {
        msftAdvMonitorEnableNative(enable)
    }

    /** Configure BLE batch scan storage */
    fun configBatchScanStorage(
        scannerId: Int,
        maxFullReportsPercent: Int,
        maxTruncatedReportsPercent: Int,
        notifyThresholdPercent: Int,
    ) {
        configBatchScanStorageNative(
            scannerId,
            maxFullReportsPercent,
            maxTruncatedReportsPercent,
            notifyThresholdPercent,
        )
    }

    /** Enable BLE batch scan with the parameters */
    fun startBatchScan(
        scannerId: Int,
        scanMode: Int,
        scanIntervalUnit: Int,
        scanWindowUnit: Int,
        addressType: Int,
        discardRule: Int,
    ) {
        startBatchScanNative(
            scannerId,
            scanMode,
            scanIntervalUnit,
            scanWindowUnit,
            addressType,
            discardRule,
        )
    }

    /** Disable BLE batch scan */
    fun stopBatchScan(scannerId: Int) {
        stopBatchScanNative(scannerId)
    }

    /** Read BLE batch scan reports */
    fun readScanReports(scannerId: Int, scanType: Int) {
        readScanReportsNative(scannerId, scanType)
    }
}
