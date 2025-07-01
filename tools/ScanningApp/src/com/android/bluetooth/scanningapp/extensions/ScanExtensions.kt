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

package com.android.bluetooth.scanningapp.extensions

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings

fun Int.toScanErrorMessage(): String {
    return when (this) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
        else -> "Unknown error ($this)"
    }
}

fun Int.toScanModeString(): String {
    return when (this) {
        ScanSettings.SCAN_MODE_LOW_POWER -> "Low Power"
        ScanSettings.SCAN_MODE_BALANCED -> "Balanced"
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "Low Latency"
        else -> "Unknown scan mode ($this)"
    }
}
