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
import com.google.protobuf.ByteString
import java.util.UUID

private const val TAG = ScanUtil.TAG_PREFIX + "ScanNativeCallback"

class ScanNativeCallback(
    adapterService: AdapterService,
    private val scanController: ScanController,
) : NativeCallback(adapterService) {

    fun onScanResult(
        eventType: Int,
        addressType: Int,
        address: String?,
        primaryPhy: Int,
        secondaryPhy: Int,
        advertisingSid: Int,
        txPower: Int,
        rssi: Int,
        periodicAdvInt: Int,
        advData: ByteArray?,
        originalAddress: String?,
    ) = doOnScanThread {
        onScanResult(
            eventType,
            addressType,
            address,
            primaryPhy,
            secondaryPhy,
            advertisingSid,
            txPower,
            rssi,
            periodicAdvInt,
            advData,
            originalAddress,
        )
    }

    fun onScannerRegistered(status: Int, scannerId: Int, uuidMsb: Long, uuidLsb: Long) =
        doOnScanThread {
            onScannerRegistered(status, scannerId, UUID(uuidMsb, uuidLsb))
        }

    fun onScanFilterEnableDisabled(action: Int, status: Int, scannerId: Int) {
        Log.d(
            TAG,
            "onScanFilterEnableDisabled(): action=$action, status=$status, scannerId=$scannerId",
        )
    }

    fun onScanFilterParamsConfigured(
        action: Int,
        status: Int,
        scannerId: Int,
        availableSpace: Int,
    ) {
        Log.d(
            TAG,
            "onScanFilterParamsConfigured(): action=$action, status=$status, scannerId=$scannerId," +
                " availableSpace=$availableSpace",
        )
    }

    fun onScanFilterConfig(
        action: Int,
        status: Int,
        scannerId: Int,
        filterType: Int,
        availableSpace: Int,
    ) {
        Log.d(
            TAG,
            "onScanFilterConfig(): action=$action, status=$status, scannerId=$scannerId," +
                " filterType=$filterType, availableSpace=$availableSpace",
        )
    }

    fun onBatchScanStorageConfigured(status: Int, scannerId: Int) {
        Log.d(TAG, "onBatchScanStorageConfigured(): status=$status, scannerId=$scannerId")
    }

    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped
    fun onBatchScanStartStopped(startStopAction: Int, status: Int, scannerId: Int) {
        Log.d(
            TAG,
            "onBatchScanStartStopped(): startStopAction=$startStopAction, status=$status," +
                " scannerId=$scannerId",
        )
    }

    fun onBatchScanReports(
        status: Int,
        scannerId: Int,
        reportType: Int,
        numRecords: Int,
        recordData: ByteArray?,
    ) {
        Log.d(
            TAG,
            "onBatchScanReports(): status=$status, scannerId=$scannerId, reportType=$reportType," +
                " reportType=$reportType",
        )
        doOnScanThread { onBatchScanReports(status, scannerId, reportType, numRecords, recordData) }
    }

    fun onBatchScanThresholdCrossed(scannerId: Int) = doOnScanThread {
        onBatchScanThresholdCrossed(scannerId)
    }

    fun createOnTrackAdvFoundLostObject(
        scannerId: Int,
        advPacketLen: Int,
        advPacket: ByteArray?,
        scanResponseLen: Int,
        scanResponse: ByteArray?,
        filtIndex: Int,
        advState: Int,
        advInfoPresent: Int,
        address: String,
        addrType: Int,
        txPower: Int,
        rssiValue: Int,
        timeStamp: Int,
    ) =
        AdvtFilterOnFoundOnLostInfo(
            scannerId,
            advPacketLen,
            ByteString.copyFrom(advPacket),
            scanResponseLen,
            ByteString.copyFrom(scanResponse),
            filtIndex,
            advState,
            advInfoPresent,
            address,
            addrType,
            txPower,
            rssiValue,
            timeStamp,
        )

    fun onTrackAdvFoundLost(trackingInfo: AdvtFilterOnFoundOnLostInfo) = doOnScanThread {
        onTrackAdvFoundLost(trackingInfo)
    }

    fun onScanParamSetupCompleted(status: Int, scannerId: Int) = doOnScanThread {
        onScanParamSetupCompleted(status, scannerId)
    }

    fun onMsftAdvMonitorAdd(filter_index: Int, monitor_handle: Int, status: Int) = doOnScanThread {
        onMsftAdvMonitorAdd(filter_index, monitor_handle, status)
    }

    fun onMsftAdvMonitorRemove(filter_index: Int, status: Int) = doOnScanThread {
        onMsftAdvMonitorRemove(filter_index, status)
    }

    fun onMsftAdvMonitorEnable(enable: Boolean, status: Int) = doOnScanThread {
        onMsftAdvMonitorEnable(enable, status)
    }

    private fun doOnScanThread(block: ScanController.() -> Unit) = scanController.doOnScanThread {
        scanController.block()
    }
}
