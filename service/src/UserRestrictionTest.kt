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
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
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
        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, true)
        start()
        assertThat(isBluetoothAllowed).isFalse()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun disallowUser_whenAllowed_triggerCallback() {
        start()

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, true)
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isFalse()
        assertThat(callback_count).isEqualTo(1)
    }

    @Test
    fun allowUser_whenDisallowed_doNotTriggerCallback() {
        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, true)

        start()

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, false)
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun disallowUserSharing_whenAllowed_doNotTriggerCallback() {
        start()

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, true)
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }

    @Test
    fun allowUserSharing_whenDisallowed_doNotTriggerCallback() {
        ShadowSystemProperties.override("bluetooth.profile.opp.enabled", "true")

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, true)

        start()

        setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, false)
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        shadowOf(looper).idle()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(0)
    }
}
