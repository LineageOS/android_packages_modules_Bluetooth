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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.sysprop.BluetoothProperties
import com.android.bluetooth.util.registerReceiver
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
            buildSet(4) {
                val prefix = "com.android.bluetooth.opp.BluetoothOpp"
                add("${prefix}LauncherActivity")
                add("${prefix}BtEnableActivity")
                add("${prefix}BtEnablingActivity")
                add("${prefix}BtErrorActivity")
            }
    }

    private val receiver: BroadcastReceiver

    init {
        val filter = IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
        receiver =
            userContext.registerReceiver(looper, UserManager.ACTION_USER_RESTRICTIONS_CHANGED) {
                _,
                _ ->
                updateRestriction()
            }
        updateRestriction()
    }

    fun stop() {
        userContext.unregisterReceiver(receiver)
    }

    fun updateRestriction() {
        val oldState = sharingState
        sharingState = getSharingState()
        if (oldState == sharingState) {
            Log.v(TAG, "Nothing to do for $user. Sharing state is already $sharingState")
            return
        }
        Log.i(TAG, "Updating sharing state for $user: $oldState -> $sharingState")
        val bluetoothPackageName = bluetoothComponent.packageName

        // Disables Opp activities components, so the Bluetooth sharing option is not offered to the
        // user if Bluetooth or if the Sharing is not allowed.
        oppActivities.forEach { activityName ->
            userContext.packageManager.setComponentEnabledSetting(
                ComponentName(bluetoothPackageName, activityName),
                sharingState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun getSharingState(): Int {
        if (!BluetoothRestriction.isBluetoothAllowed) {
            Log.v(TAG, "Disabling sharing due to global user restriction")
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (
            userContext
                .getSystemService(UserManager::class.java)
                .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING)
        ) {
            Log.v(TAG, "Disabling sharing due to local sharing user restriction")
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (!BluetoothProperties.isProfileOppEnabled().orElse(false)) {
            Log.v(TAG, "Opp is not enabled. Setting sharing to 'default'.")
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        Log.v(TAG, "Enabling sharing")
        return PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
