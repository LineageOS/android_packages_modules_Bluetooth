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

package com.android.server.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings

private const val TAG = "BluetoothSystemBroadcastReceiver"

// Due to test compilation issues, we need to re-declared existing API from Intent:
internal val ACTION_SETTING_RESTORED = "android.os.action.SETTING_RESTORED"
internal val EXTRA_SETTING_NAME = "setting_name"
internal val EXTRA_SETTING_PREVIOUS_VALUE = "previous_value"
internal val EXTRA_SETTING_NEW_VALUE = "new_value"

class BluetoothSystemBroadcastReceiver(
    private val context: Context,
    private val looper: Looper,
    private val onShutdown: () -> Unit,
    private val onSettingsRestored: (Boolean) -> Unit,
) {

    init {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SHUTDOWN -> handleShutdown()
                        ACTION_SETTING_RESTORED -> handleSettingRestored(intent)
                        else -> Log.w(TAG, "Unknown action received: ${intent.action}")
                    }
                }
            }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SHUTDOWN)
        filter.addAction(ACTION_SETTING_RESTORED)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        context.registerReceiver(receiver, filter, null, Handler(looper))
    }

    private fun handleShutdown() {
        Log.i(TAG, "Device is shutting down.")
        onShutdown()
    }

    private fun handleSettingRestored(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_SETTING_NAME)
        if (Settings.Global.BLUETOOTH_ON == name) {
            val prevValue = intent.getStringExtra(EXTRA_SETTING_PREVIOUS_VALUE)
            val newValue = intent.getStringExtra(EXTRA_SETTING_NEW_VALUE)

            Log.d(TAG, "ACTION_SETTING_RESTORED: $prevValue -> $newValue")

            if (newValue != null && newValue != prevValue) {
                onSettingsRestored(newValue != "0")
            }
        }
    }
}
