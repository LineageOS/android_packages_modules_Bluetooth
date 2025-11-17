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

package com.android.bluetooth.hearingaid

import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

private const val TAG = "HearingAidNativeCallback"

class HearingAidNativeCallback(
    adapterService: AdapterService,
    private val service: HearingAidService,
) : NativeCallback(adapterService) {

    fun onConnectionStateChanged(state: Int, address: ByteArray) {
        val device = getDevice(address)
        Log.d(TAG, "onConnectionStateChanged(): device=$device, state=$state")
        service.onConnectionStateChangedFromNative(device, state)
    }

    fun onDeviceAvailable(capabilities: Byte, hiSyncId: Long, address: ByteArray) {
        val device = getDevice(address)
        Log.d(
            TAG,
            ("onDeviceAvailable(): device=$device, capabilities=${capabilities.toInt()}") +
                ", hiSyncId=$hiSyncId",
        )
        service.onDeviceAvailableFromNative(device, capabilities.toInt(), hiSyncId)
    }
}
