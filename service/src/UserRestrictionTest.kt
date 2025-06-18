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

package com.android.server.bluetooth.test

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.UserRestriction.bluetoothSharingState
import com.android.server.bluetooth.UserRestriction.handleRestrictionChange
import com.android.server.bluetooth.UserRestriction.initialize
import com.android.server.bluetooth.UserRestriction.initializeUser
import com.android.server.bluetooth.UserRestriction.isBluetoothAllowed
import com.android.server.bluetooth.UserRestriction.oppActivities
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class UserRestrictionTest {
    private val looper = Looper.getMainLooper()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userManager = context.getSystemService(UserManager::class.java)

    private var callback_count = 0
    private val info =
        PackageInfo().apply {
            packageName = "my_package"
            activities = arrayOf(ActivityInfo().apply { name = oppActivities[0] })
        }
    private val emptyInfo = PackageInfo().apply { packageName = "my_empty_package" }

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        callback_count = 0
        shadowOf(context.packageManager)
            .setPackagesForUid(
                Process.BLUETOOTH_UID,
                "not_a_package",
                emptyInfo.packageName,
                info.packageName,
            )
        shadowOf(context.packageManager).addPackage(info)
        shadowOf(context.packageManager).addPackage(emptyInfo)
    }

    @Suppress("DEPRECATION")
    private fun setUserRestriction(
        restriction: String,
        status: Boolean,
        user: UserHandle = UserHandle.SYSTEM,
    ) {
        shadowOf(userManager).setUserRestriction(user, restriction, status)
    }

    private fun disallowBluetooth() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH, true)

    private fun allowBluetooth() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH, false)

    private fun disallowSharing() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, true)

    private fun allowSharing() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, false)

    private fun start() {
        initialize(context, looper) { callback_count++ }
        initializeUser(context)
    }

    @Test
    fun initialize_whenAllowed_isAllowed() {
        start()
        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun initialize_whenDisallowed_isDisallowed() {
        disallowBluetooth()
        start()
        assertThat(isBluetoothAllowed).isFalse()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun disallowUser_whenAllowed_triggerCallback() {
        start()

        disallowBluetooth()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isFalse()
        assertThat(callback_count).isEqualTo(1)
    }

    @Test
    fun disallowNonMainUser_whenAllowed_doNotTriggerCallback() {
        val user = UserHandle.of(42)
        start()

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, true, user)
        handleRestrictionChange(context, user) { callback_count++ }

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun allowUser_whenDisallowed_doNotTriggerCallback() {
        disallowBluetooth()

        start()

        allowBluetooth()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun disallowUserSharing_whenAllowed_sharingIsDisableAndNoCallback() {
        start()

        disallowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(bluetoothSharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun disallowUserSharing_whenDisallowed_doNothing() {
        disallowBluetooth()

        start()

        disallowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isFalse()
        assertThat(bluetoothSharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun allowUserSharing_whenSharingDisallowed_sharingAllowedAndNoCallback() {
        ShadowSystemProperties.override("bluetooth.profile.opp.enabled", "true")
        disallowSharing()

        start()

        allowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(bluetoothSharingState).isEqualTo(COMPONENT_ENABLED_STATE_ENABLED)
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun allowUserSharing_whenDisallowed_sharingStayDisableAndNoCallback() {
        ShadowSystemProperties.override("bluetooth.profile.opp.enabled", "true")
        disallowSharing()
        disallowBluetooth()

        start()

        allowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isFalse()
        assertThat(bluetoothSharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(callback_count).isEqualTo(0)
    }
}
