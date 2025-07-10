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

import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.RolePermissionListener.BLUETOOTH_ROLE
import com.android.server.bluetooth.RolePermissionListener.registerForUser
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
class RolePermissionListenerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val looper: Looper = Looper.getMainLooper()
    private val currentUser = UserHandle.of(ActivityManager.getCurrentUser())

    private var callbackTriggered: Boolean = false

    @Before
    fun setup() {
        callbackTriggered = false
    }

    private fun startListener() {
        registerForUser(looper, context, currentUser) { callbackTriggered = true }
    }

    private fun addRoleAndWait(
        roleName: String = BLUETOOTH_ROLE,
        user: UserHandle = currentUser,
        holder: String = "com.android.bluetooth",
    ) {
        ShadowRoleManager.addRoleHolder(roleName, holder, user)
        shadowOf(looper).idle()
    }

    private fun callback() {
        callbackTriggered = true
    }

    @Test
    fun start_whenNoRole_callbackNotTriggered() {
        startListener()

        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun start_whenRoleAlreadyGiven_callbackTriggered() {
        addRoleAndWait()
        startListener()

        assertThat(callbackTriggered).isTrue()
    }

    @Test
    fun provideRole_whenWaiting_callbackTriggered() {
        startListener()
        addRoleAndWait()

        assertThat(callbackTriggered).isTrue()
    }

    @Test
    fun provideRoleForAnotherUser_whenWaiting_callbackNotTriggered() {
        val otherUser = UserHandle.of(ActivityManager.getCurrentUser() + 1)
        startListener()
        addRoleAndWait(user = otherUser)

        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun provideRoleMultipleTimes_whenWaiting_callbackTriggeredOnlyOnce() {
        startListener()

        addRoleAndWait()

        assertThat(callbackTriggered).isTrue()

        callbackTriggered = false
        addRoleAndWait()

        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun provideRoleToEmptyHolder_whenWaiting_callbackNotTriggered() {
        startListener()

        addRoleAndWait(holder = "")

        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun provideAnotherRole_whenWaiting_callbackNotTriggered() {
        startListener()

        addRoleAndWait(roleName = "This is not the role you are looking for")

        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun removeRoleBeforeCallbackIsCalled_whenWaiting_callbackNotTriggered() {
        startListener()

        ShadowRoleManager.addRoleHolder(BLUETOOTH_ROLE, "foo", currentUser)
        ShadowRoleManager.removeRoleHolder(BLUETOOTH_ROLE, "foo", currentUser)
        shadowOf(looper).idle()

        assertThat(callbackTriggered).isFalse()
    }
}
