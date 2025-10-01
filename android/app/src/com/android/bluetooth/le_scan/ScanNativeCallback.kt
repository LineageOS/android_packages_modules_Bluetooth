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
import com.android.bluetooth.flags.Flags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "ScanNativeCallback"

class ScanNativeCallback(private val scanController: ScanController) {

    private var latch = CountDownLatch(1)

    fun callbackDone() = latch.countDown()

    fun resetCountDownLatch() {
        latch = CountDownLatch(1)
    }

    // Returns true if [latch] reaches 0, false if timeout or interrupted
    fun waitForCallback(timeoutMs: Long): Boolean =
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }

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
    ) {
        doOnScanThread {
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
    }

    fun onScannerRegistered(status: Int, scannerId: Int, uuidLsb: Long, uuidMsb: Long) {
        doOnScanThread { onScannerRegistered(status, scannerId, uuidLsb, uuidMsb) }
    }

    fun onScanFilterEnableDisabled(action: Int, status: Int, clientIf: Int) {
        Log.d(
            TAG,
            "onScanFilterEnableDisabled(): action=$action, status=$status, clientIf=$clientIf",
        )
        callbackDone()
    }

    fun onScanFilterParamsConfigured(action: Int, status: Int, clientIf: Int, availableSpace: Int) {
        Log.d(
            TAG,
            "onScanFilterParamsConfigured(): action=$action, status=$status, clientIf=$clientIf," +
                " availableSpace=$availableSpace",
        )
        callbackDone()
    }

    fun onScanFilterConfig(
        action: Int,
        status: Int,
        clientIf: Int,
        filterType: Int,
        availableSpace: Int,
    ) {
        Log.d(
            TAG,
            "onScanFilterConfig(): action=$action, status=$status, clientIf=$clientIf," +
                " filterType=$filterType, availableSpace=$availableSpace",
        )
        callbackDone()
    }

    fun onBatchScanStorageConfigured(status: Int, clientIf: Int) {
        Log.d(TAG, "onBatchScanStorageConfigured(): status=$status, clientIf=$clientIf")
        callbackDone()
    }

    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped
    fun onBatchScanStartStopped(startStopAction: Int, status: Int, clientIf: Int) {
        Log.d(
            TAG,
            "onBatchScanStartStopped(): startStopAction=$startStopAction, status=$status," +
                " clientIf=$clientIf",
        )
        callbackDone()
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
        if (Flags.scanControllerThread()) {
            callbackDone()
        }
        doOnScanThread { onBatchScanReports(status, scannerId, reportType, numRecords, recordData) }
    }

    fun onBatchScanThresholdCrossed(clientIf: Int) {
        doOnScanThread { onBatchScanThresholdCrossed(clientIf) }
    }

    fun createOnTrackAdvFoundLostObject(
        clientIf: Int,
        advPacketLen: Int,
        advPacket: ByteArray?,
        scanResponseLen: Int,
        scanResponse: ByteArray?,
        filtIndex: Int,
        advState: Int,
        advInfoPresent: Int,
        address: String?,
        addrType: Int,
        txPower: Int,
        rssiValue: Int,
        timeStamp: Int,
    ): AdvtFilterOnFoundOnLostInfo? {
        return scanController.fetchOnScanThread<AdvtFilterOnFoundOnLostInfo>(
            {
                scanController.createOnTrackAdvFoundLostObject(
                    clientIf,
                    advPacketLen,
                    advPacket,
                    scanResponseLen,
                    scanResponse,
                    filtIndex,
                    advState,
                    advInfoPresent,
                    address,
                    addrType,
                    txPower,
                    rssiValue,
                    timeStamp,
                )
            },
            null,
        )
    }

    fun onTrackAdvFoundLost(trackingInfo: AdvtFilterOnFoundOnLostInfo) {
        doOnScanThread { onTrackAdvFoundLost(trackingInfo) }
    }

    fun onScanParamSetupCompleted(status: Int, scannerId: Int) {
        doOnScanThread { onScanParamSetupCompleted(status, scannerId) }
    }

    fun onMsftAdvMonitorAdd(filter_index: Int, monitor_handle: Int, status: Int) {
        doOnScanThread { onMsftAdvMonitorAdd(filter_index, monitor_handle, status) }
    }

    fun onMsftAdvMonitorRemove(filter_index: Int, status: Int) {
        doOnScanThread { onMsftAdvMonitorRemove(filter_index, status) }
    }

    fun onMsftAdvMonitorEnable(enable: Boolean, status: Int) {
        doOnScanThread { onMsftAdvMonitorEnable(enable, status) }
    }

    private fun doOnScanThread(block: ScanController.() -> Unit) {
        scanController.doOnScanThread { scanController.block() }
    }
}
