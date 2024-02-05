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
package com.android.server.bluetooth.test

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.USER_SETTINGS_KEY
import com.android.server.bluetooth.notifyBluetoothOn
import com.android.server.bluetooth.resetAutoOnTimerForUser
import com.android.server.bluetooth.timer
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class AutoOnFeatureTest {
    private val looper = Looper.getMainLooper()
    private val state = BluetoothAdapterState()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    private var callback_count = 0

    @JvmField @Rule val testName = TestName()
    @JvmField @Rule val expect = Expect.create()

    @Before
    fun setUp() {
        Log.i("AutoOnFeatureTest", "\t--> setUp(${testName.getMethodName()})")

        enableUserSettings()
    }

    @After
    fun tearDown() {
        callback_count = 0
        timer?.cancel()
        timer = null
    }

    private fun setupTimer() {
        resetAutoOnTimerForUser(looper, context, state, this::callback_on)
    }

    private fun enableUserSettings() {
        Settings.Secure.putInt(resolver, USER_SETTINGS_KEY, 1)
        shadowOf(looper).idle()
    }

    private fun disableUserSettings() {
        Settings.Secure.putInt(resolver, USER_SETTINGS_KEY, 0)
        shadowOf(looper).idle()
    }

    private fun restoreSettings() {
        Settings.Secure.putString(resolver, USER_SETTINGS_KEY, null)
        shadowOf(looper).idle()
    }

    private fun callback_on() {
        callback_count++
    }

    @Test
    fun setupTimer_whenItWasNeverUsed_isNotScheduled() {
        restoreSettings()

        setupTimer()

        expect.that(timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun setupTimer_whenBtOn_isNotScheduled() {
        state.set(BluetoothAdapter.STATE_ON)

        setupTimer()

        state.set(BluetoothAdapter.STATE_OFF)
        expect.that(timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun setupTimer_whenBtOffAndUserEnabled_isScheduled() {
        setupTimer()

        expect.that(timer).isNotNull()
    }

    @Test
    fun setupTimer_whenBtOffAndUserEnabled_triggerCallback() {
        setupTimer()

        shadowOf(looper).runToEndOfTasks()
        expect.that(callback_count).isEqualTo(1)
        expect.that(timer).isNull()
    }

    @Test
    fun setupTimer_whenAlreadySetup_triggerCallbackOnce() {
        setupTimer()
        setupTimer()
        setupTimer()

        shadowOf(looper).runToEndOfTasks()
        expect.that(callback_count).isEqualTo(1)
        expect.that(timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenNoTimer_noCrash() {
        notifyBluetoothOn(resolver)

        assertThat(timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenTimer_isNotScheduled() {
        setupTimer()
        notifyBluetoothOn(resolver)

        shadowOf(looper).runToEndOfTasks()
        expect.that(callback_count).isEqualTo(0)
        expect.that(timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenItWasNeverUsed_enableSettings() {
        restoreSettings()

        notifyBluetoothOn(resolver)

        assertThat(Settings.Secure.getInt(resolver, USER_SETTINGS_KEY, 42)).isEqualTo(1)
    }
}
