/*
 * Copyright 2024 The Android Open Source Project
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

// @file:JvmName("BleScanSettingListener")

package com.android.server.bluetooth

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

private const val TAG = "BleScanSettingListener"

object BleScanSettingListener {
    @JvmStatic
    var isScanAllowed = false
        private set

    /**
     * Listen on Ble Scan setting and trigger the callback when scanning is no longer enabled
     *
     * @param callback: The callback to trigger when there is a mode change, pass new mode as
     *   parameter
     * @return The initial value of the radio
     */
    @JvmStatic
    fun initialize(looper: Looper, resolver: ContentResolver, callback: () -> Unit) {
        val notifyForDescendants = false

        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE),
            notifyForDescendants,
            object : ContentObserver(Handler(looper)) {
                override fun onChange(selfChange: Boolean) {
                    val previousValue = isScanAllowed
                    isScanAllowed = getScanSettingValue(resolver)
                    if (isScanAllowed) {
                        Log.i(TAG, "Ble Scan mode is now allowed. Nothing to do")
                        return
                    } else if (previousValue == isScanAllowed) {
                        Log.i(TAG, "Ble Scan mode was already considered as false. Discarding")
                        return
                    } else {
                        Log.i(TAG, "Trigger callback to disable BLE_ONLY mode")
                        callback()
                    }
                }
            }
        )
        isScanAllowed = getScanSettingValue(resolver)
    }

    /**
     * Check if Bluetooth is impacted by the radio and fetch global mode status
     *
     * @return whether Bluetooth should consider this radio or not
     */
    private fun getScanSettingValue(resolver: ContentResolver): Boolean {
        try {
            return Settings.Global.getInt(resolver, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE) != 0
        } catch (e: Settings.SettingNotFoundException) {
            Log.i(TAG, "Settings not found. Default to false")
            return false
        }
    }
}
