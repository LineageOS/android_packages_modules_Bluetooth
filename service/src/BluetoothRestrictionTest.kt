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
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BluetoothRestriction
import com.android.server.bluetooth.BluetoothRestriction.isBluetoothAllowed
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class BluetoothRestrictionTest {
    private val looper = Looper.getMainLooper()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userManager = context.getSystemService(UserManager::class.java)

    private var callback_count = 0

    private fun start() {
        BluetoothRestriction.initialize(context, looper) { callback_count++ }
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

        assertThat(isBluetoothAllowed).isFalse()
        assertThat(callback_count).isEqualTo(1)
    }

    @Test
    fun allowUser_whenDisallowed_triggerCallback() {
        disallowBluetooth()
        start()

        allowBluetooth()

        assertThat(isBluetoothAllowed).isTrue()
        assertThat(callback_count).isEqualTo(1)
    }

    companion object {
        internal fun setup() {
            val looper = Looper.getMainLooper()
            val context = ApplicationProvider.getApplicationContext<Context>()

            allowBluetooth()

            BluetoothRestriction.initialize(context, looper) {}
        }

        @Suppress("DEPRECATION")
        private fun setUserRestriction(
            context: Context,
            restriction: String,
            status: Boolean,
            user: UserHandle = UserHandle.SYSTEM,
        ) {
            val userManager = context.getSystemService(UserManager::class.java)

            shadowOf(userManager).setUserRestriction(user, restriction, status)
        }

        internal fun disallowBluetooth() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            setUserRestriction(context, UserManager.DISALLOW_BLUETOOTH, true)
            context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(context as ContextWrapper).clearBroadcastIntents()
        }

        internal fun allowBluetooth() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            setUserRestriction(context, UserManager.DISALLOW_BLUETOOTH, false)
            context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(context as ContextWrapper).clearBroadcastIntents()
        }
    }
}
