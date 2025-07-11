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

// @file:JvmName("UserRestriction")

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

private const val TAG = "UserRestriction"

object UserRestriction {
    private lateinit var bluetoothComponent: BluetoothComponent

    // OPP activities should be enabled even when Bluetooth is OFF.
    @JvmStatic
    val oppActivities =
        listOf(
                "LauncherActivity", // Base sharing activity
                "BtEnableActivity",
                "BtEnablingActivity",
                "BtErrorActivity",
            )
            .map { "com.android.bluetooth.opp.BluetoothOpp$it" }

    @JvmStatic
    var isBluetoothAllowed = false
        private set

    var bluetoothSharingState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        private set

    /** Listen on User restriction and trigger the callback when Bluetooth is not allowed */
    @JvmStatic
    fun initialize(
        context: Context,
        looper: Looper,
        component: BluetoothComponent,
        callback: () -> Unit,
    ) {
        bluetoothComponent = component
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleRestrictionChange(context, getSendingUser(), callback)
                }
            }

        context.registerReceiverForAllUsers(
            receiver,
            IntentFilter().apply {
                addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
                setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY)
            },
            null,
            Handler(looper),
        )

        isBluetoothAllowed = !hasBluetoothRestriction(context)
    }

    @JvmStatic
    @VisibleForTesting
    fun handleRestrictionChange(context: Context, fromUser: UserHandle, callback: () -> Unit) {
        val wasBluetoothAllowed = isBluetoothAllowed
        isBluetoothAllowed = !hasBluetoothRestriction(context)

        if (!isBluetoothAllowed && !wasBluetoothAllowed) {
            // When Bluetooth was not allowed and is still not allowed, there will be nothing else
            // to do as both Bluetooth and Sharing are not allowed.
            // But if Bluetooth was allowed and is still allowed, we need to check for an eventual
            // Sharing restriction.
            // This is why the check is `&&` and not `==`
            Log.v(TAG, "Bluetooth was already not allowed. Nothing more to do")
            return
        }

        updateOppLauncherComponentState(context.createContextAsUser(fromUser, 0))

        Log.i(TAG, "handleRestrictionChange for user $fromUser, is allowed: $isBluetoothAllowed")

        // DISALLOW_BLUETOOTH can only be set by DO or PO on the system user.
        // Only trigger once instead of for all users
        if (UserHandle.SYSTEM.equals(fromUser) && !isBluetoothAllowed) {
            Log.i(TAG, "Bluetooth is not allowed")
            callback()
        }
    }

    @JvmStatic
    fun initializeUser(userContext: Context) {
        updateOppLauncherComponentState(userContext)
    }

    /**
     * Manages Opp Activity components, so the Bluetooth sharing option is not offered to the user
     * if Bluetooth or sharing is disallowed. Puts the component to its default state if Bluetooth
     * is not disallowed.
     */
    private fun updateOppLauncherComponentState(userContext: Context) {
        val previousBluetoothSharingState = bluetoothSharingState
        bluetoothSharingState = getBluetoothSharingState(userContext)
        if (previousBluetoothSharingState == bluetoothSharingState) {
            Log.v(TAG, "Bluetooth sharing state is already $bluetoothSharingState")
            return
        }
        val bluetoothPackageName = bluetoothComponent.packageName

        oppActivities.forEach { activityName ->
            userContext.packageManager.setComponentEnabledSetting(
                ComponentName(bluetoothPackageName, activityName),
                bluetoothSharingState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun getBluetoothSharingState(userContext: Context): Int {
        if (!isBluetoothAllowed) {
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

    private fun hasBluetoothRestriction(systemContext: Context): Boolean =
        systemContext
            .getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH)
}
