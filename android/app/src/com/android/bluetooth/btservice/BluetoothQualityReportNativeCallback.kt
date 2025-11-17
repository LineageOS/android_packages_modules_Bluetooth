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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothQualityReport
import android.bluetooth.BluetoothStatusCodes
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.profile.NativeCallback

private const val TAG = "BluetoothQualityReportNativeCallback"

class BluetoothQualityReportNativeCallback(adapterService: AdapterService) :
    NativeCallback(adapterService) {

    private fun bqrDeliver(
        remoteAddr: ByteArray,
        lmpVer: Int,
        lmpSubVer: Int,
        manufacturerId: Int,
        bqrRawData: ByteArray,
    ) {
        val remoteAddress = Utils.getAddressStringFromByte(remoteAddr)
        if (remoteAddress == null) {
            Log.e(TAG, "bqrDeliver(): Failed. remoteAddress is null")
            return
        }

        val device = adapterService.getRemoteDevice(remoteAddress)
        val remoteClass = BluetoothClass(adapterService.getRemoteClass(device))
        val bqr: BluetoothQualityReport
        try {
            bqr =
                BluetoothQualityReport.Builder(bqrRawData)
                    .setRemoteAddress(remoteAddress)
                    .setLmpVersion(lmpVer)
                    .setLmpSubVersion(lmpSubVer)
                    .setManufacturerId(manufacturerId)
                    .setRemoteName(adapterService.getRemoteName(device))
                    .setBluetoothClass(remoteClass)
                    .build()
            Log.i(TAG, bqr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "bqrDeliver(): Failed to create BluetoothQualityReport", e)
            return
        }

        try {
            val status = adapterService.bluetoothQualityReportReadyCallback(device, bqr)
            if (status != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "bluetoothQualityReportReadyCallback failed, status=$status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "bqrDeliver(): Failed. bluetoothQualityReportReadyCallback error", e)
        }
    }
}
