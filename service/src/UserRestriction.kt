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
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.sysprop.BluetoothProperties
import com.android.internal.annotations.VisibleForTesting

private const val TAG = "UserRestriction"

object UserRestriction {

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

    /** Listen on User restriction and trigger the callback when Bluetooth is not allowed */
    @JvmStatic
    fun initialize(context: Context, looper: Looper, callback: () -> Unit) {
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

        isBluetoothAllowed = isBluetoothAllowed(context)
    }

    @JvmStatic
    @VisibleForTesting
    fun handleRestrictionChange(context: Context, fromUser: UserHandle, callback: () -> Unit) {
        isBluetoothAllowed = isBluetoothAllowed(context)
        val userContext = context.createContextAsUser(fromUser, 0)
        val isBluetoothSharingAllowed = isBluetoothAllowed && isBluetoothSharingAllowed(userContext)

        updateOppLauncherComponentState(userContext, isBluetoothSharingAllowed)

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
        val isBluetoothSharingAllowed = isBluetoothAllowed && isBluetoothSharingAllowed(userContext)

        updateOppLauncherComponentState(userContext, isBluetoothSharingAllowed)
    }

    /**
     * Disables BluetoothOppLauncherActivity component, so the Bluetooth sharing option is not
     * offered to the user if Bluetooth or sharing is disallowed. Puts the component to its default
     * state if Bluetooth is not disallowed.
     */
    private fun updateOppLauncherComponentState(userContext: Context, sharingAllowed: Boolean) {
        val newState =
            if (!sharingAllowed) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else if (BluetoothProperties.isProfileOppEnabled().orElse(false)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }

        val bluetoothPackageName = getBluetoothPackageName(userContext)

        oppActivities.forEach { activityName ->
            userContext
                .getPackageManager()
                .setComponentEnabledSetting(
                    ComponentName(bluetoothPackageName, activityName),
                    newState,
                    PackageManager.DONT_KILL_APP,
                )
        }
    }

    private fun getBluetoothPackageName(context: Context): String {
        val systemPackageManager = context.getPackageManager()
        return systemPackageManager
            .getPackagesForUid(Process.BLUETOOTH_UID)!!
            .asSequence()
            .onEach { Log.v(TAG, "getBluetoothPackageName searching within package $it") }
            .map { pkg ->
                try {
                    systemPackageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_ACTIVITIES.toLong() or
                                PackageManager.MATCH_ANY_USER.toLong() or
                                PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or
                                PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
                        ),
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "getBluetoothPackageName: Could not find package $pkg")
                    null
                }
            }
            .filterNotNull()
            .filter { packageInfo -> packageInfo.activities != null }
            .flatMap { packageInfo -> packageInfo.activities!!.asSequence() }
            .onEach { Log.v(TAG, "getBluetoothPackageName: Checking activity ${it.name}") }
            .filter { activity -> oppActivities.contains(activity.name) }
            .first()
            .packageName
    }

    private fun isBluetoothAllowed(systemContext: Context): Boolean =
        !systemContext
            .getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH)

    private fun isBluetoothSharingAllowed(userContext: Context): Boolean =
        !userContext
            .getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING)
}
