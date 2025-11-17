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

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.profile.NativeInterface

class PeriodicScanNativeInterface(nativeCallback: PeriodicScanNativeCallback) :
    NativeInterface<PeriodicScanNativeCallback>(nativeCallback) {

    fun init() {
        initializeNative()
    }

    override fun cleanup() {
        cleanupNative()
    }

    fun startSync(
        sid: Int,
        address: String,
        addressType: Int,
        skip: Int,
        timeout: Int,
        regId: Int,
    ) {
        startSyncNative(sid, address, addressType, skip, timeout, regId)
    }

    fun stopSync(syncHandle: Int) {
        stopSyncNative(syncHandle)
    }

    fun cancelSync(sid: Int, address: String) {
        cancelSyncNative(sid, address)
    }

    fun syncTransfer(bda: BluetoothDevice, serviceData: Int, syncHandle: Int) {
        syncTransferNative(PA_SOURCE_REMOTE, bda.address, serviceData, syncHandle)
    }

    fun transferSetInfo(bda: BluetoothDevice, serviceData: Int, advHandle: Int) {
        transferSetInfoNative(PA_SOURCE_LOCAL, bda.address, serviceData, advHandle)
    }

    private external fun initializeNative()

    private external fun cleanupNative()

    private external fun startSyncNative(
        sid: Int,
        address: String,
        addressType: Int,
        skip: Int,
        timeout: Int,
        regId: Int,
    )

    private external fun stopSyncNative(syncHandle: Int)

    private external fun cancelSyncNative(sid: Int, address: String)

    private external fun syncTransferNative(
        paSource: Int,
        address: String,
        serviceData: Int,
        syncHandle: Int,
    )

    private external fun transferSetInfoNative(
        paSource: Int,
        address: String,
        serviceData: Int,
        advHandle: Int,
    )

    companion object {
        private const val PA_SOURCE_LOCAL = 1
        private const val PA_SOURCE_REMOTE = 2
    }
}
