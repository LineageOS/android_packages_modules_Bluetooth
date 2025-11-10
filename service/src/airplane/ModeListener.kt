/*
 * Copyright (C) 2023 The Android Open Source Project
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
@file:JvmName("AirplaneModeListener")

package com.android.server.bluetooth.airplane

import android.content.ContentResolver
import android.os.Looper
import android.provider.Settings
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.initializeRadioModeListener

private const val TAG = "AirplaneModeListener"

/**
 * @return true if airplane is ON on the device.
 *
 * This need to be used instead of reading the settings properties to avoid race condition from
 * within the BluetoothManagerService thread
 */
var isOn = false
    private set

var isEnhancementEnabled = false
    private set

/** Listen on satellite mode and trigger the callback if it has changed */
fun initialize(looper: Looper, resolver: ContentResolver, callback: (m: Boolean) -> Unit) {
    // Wifi got support for "Airplane Enhancement Mode" prior to Bluetooth.
    // In order for Wifi to be aware that Bluetooth also support the feature, Bluetooth need to set
    // the APM_ENHANCEMENT settings to `1`.
    // Value will be set to DEFAULT_APM_ENHANCEMENT_STATE only if the APM_ENHANCEMENT is not set.
    // Any modification to the value require a reboot to take effect
    val apmEnhancement =
        Settings.Global.getInt(resolver, APM_ENHANCEMENT, DEFAULT_APM_ENHANCEMENT_STATE)
    isEnhancementEnabled = apmEnhancement == 1
    Settings.Global.putInt(resolver, APM_ENHANCEMENT, apmEnhancement)

    isOn =
        initializeRadioModeListener(
            looper,
            resolver,
            Settings.Global.AIRPLANE_MODE_RADIOS,
            Settings.Global.AIRPLANE_MODE_ON,
            fun(newMode: Boolean) {
                val previousMode = isOn
                isOn = newMode
                if (previousMode == isOn) {
                    Log.d(TAG, "Ignore satellite mode change because is already: $isOn")
                    return
                }
                Log.i(TAG, "Trigger callback with state: $isOn")
                callback(isOn)
            },
        )
    Log.i(TAG, "Initialized successfully with state: $isOn")
}

// Whether the "Airplane Enhancement Mode" is enabled
internal const val APM_ENHANCEMENT = "apm_enhancement_enabled"

// Define if the "Airplane Enhancement Mode" feature is enabled by default. `0` == disabled
private const val DEFAULT_APM_ENHANCEMENT_STATE = 1
