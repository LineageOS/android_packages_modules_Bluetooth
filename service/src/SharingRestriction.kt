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

package com.android.server.bluetooth

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.sysprop.BluetoothProperties
import com.android.internal.annotations.VisibleForTesting

private const val TAG = "SharingRestriction"

class SharingRestriction
internal constructor(
    private val userContext: Context,
    private val looper: Looper,
    private val bluetoothComponent: BluetoothComponent,
    private val user: UserHandle,
) {
    @VisibleForTesting internal var sharingState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    companion object {
        @JvmStatic
        val oppActivities =
            listOf(
                    "LauncherActivity", // Base sharing activity
                    "BtEnableActivity",
                    "BtEnablingActivity",
                    "BtErrorActivity",
                )
                .map { "com.android.bluetooth.opp.BluetoothOpp$it" }
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == UserManager.ACTION_USER_RESTRICTIONS_CHANGED) {
                    Log.d(TAG, "Received user restriction changed event for user $user")
                    updateOppLauncherComponentState()
                }
            }
        }

    init {
        val filter = IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
        userContext.registerReceiver(receiver, filter, null, Handler(looper))
        updateOppLauncherComponentState()
    }

    fun stop() {
        userContext.unregisterReceiver(receiver)
    }

    private fun updateOppLauncherComponentState() {
        val previousSharingState = sharingState
        sharingState = getBluetoothSharingState()
        if (previousSharingState == sharingState) {
            Log.v(TAG, "Bluetooth sharing state is already $sharingState")
            return
        }
        Log.i(TAG, "updateOppLauncherComponentState for user $user, sharing state: $sharingState")
        val bluetoothPackageName = bluetoothComponent.packageName

        oppActivities.forEach { activityName ->
            userContext.packageManager.setComponentEnabledSetting(
                ComponentName(bluetoothPackageName, activityName),
                sharingState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun getBluetoothSharingState(): Int {
        if (!BluetoothRestriction.isBluetoothAllowed) {
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (
            userContext
                .getSystemService(UserManager::class.java)
                .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING)
        ) {
            Log.v(TAG, "Sharing is disallowed due to user restriction")
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (!BluetoothProperties.isProfileOppEnabled().orElse(false)) {
            Log.v(TAG, "Sharing is set to default due to Opp profile not enabled")
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        Log.v(TAG, "Sharing is allowed")
        return PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
