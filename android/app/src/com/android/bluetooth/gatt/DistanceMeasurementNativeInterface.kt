/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.gatt

import com.android.bluetooth.profile.NativeInterface

class DistanceMeasurementNativeInterface(nativeCallback: DistanceMeasurementNativeCallback) :
    NativeInterface<DistanceMeasurementNativeCallback>(nativeCallback) {

    fun init() {
        initializeNative()
    }

    override fun cleanup() {
        cleanupNative()
    }

    fun startDistanceMeasurement(
        appUid: Int,
        address: String,
        interval: Int,
        method: Int,
        sightType: Int,
        locationType: Int,
    ) {
        startDistanceMeasurementNative(appUid, address, interval, method, sightType, locationType)
    }

    fun stopDistanceMeasurement(address: String, method: Int) {
        stopDistanceMeasurementNative(address, method)
    }

    private external fun initializeNative()

    private external fun cleanupNative()

    private external fun startDistanceMeasurementNative(
        appUid: Int,
        address: String,
        interval: Int,
        method: Int,
        sightType: Int,
        locationType: Int,
    )

    private external fun stopDistanceMeasurementNative(address: String, method: Int)
}
