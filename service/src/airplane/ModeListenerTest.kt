/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.bluetooth.airplane.test

import android.content.ContentResolver
import android.content.Context
import android.os.Looper
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.airplane.APM_ENHANCEMENT
import com.android.server.bluetooth.airplane.initialize
import com.android.server.bluetooth.airplane.isOn
import com.android.server.bluetooth.test.disableMode
import com.android.server.bluetooth.test.disableSensitive
import com.android.server.bluetooth.test.enableMode
import com.android.server.bluetooth.test.enableSensitive
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters

@RunWith(ParameterizedRobolectricTestRunner::class)
class ModeListenerTest(flags: FlagsWrapper) {
    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val testName = TestName()

    private val looper: Looper = Looper.getMainLooper()
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    private lateinit var mode: ArrayList<Boolean>

    @Before
    fun setup() {
        Log.i("AirplaneModeListenerTest", "\t--> setup of ${testName.methodName}")

        // Most test will expect the system to be sensitive + off
        enableSensitive()
        disableMode()

        mode = ArrayList()
    }

    private fun enableSensitive() {
        enableSensitive(resolver, looper, Settings.Global.AIRPLANE_MODE_RADIOS)
    }

    private fun disableSensitive() {
        disableSensitive(resolver, looper, Settings.Global.AIRPLANE_MODE_RADIOS)
    }

    private fun disableMode() {
        disableMode(resolver, looper, Settings.Global.AIRPLANE_MODE_ON)
    }

    private fun enableMode() {
        enableMode(resolver, looper, Settings.Global.AIRPLANE_MODE_ON)
    }

    private fun callback(newMode: Boolean) = mode.add(newMode)

    @Test
    fun initialize_whenNullSensitive_isOff() {
        Settings.Global.putString(resolver, Settings.Global.AIRPLANE_MODE_RADIOS, null)
        enableMode()

        initialize(looper, resolver, this::callback)

        assertThat(isOn).isFalse()
        assertThat(mode).isEmpty()
    }

    @Test
    fun initialize_whenNotSensitive_isOff() {
        disableSensitive()
        enableMode()

        initialize(looper, resolver, this::callback)

        assertThat(isOn).isFalse()
        assertThat(mode).isEmpty()
    }

    @Test
    fun enable_whenNotSensitive_isOff() {
        disableSensitive()
        disableMode()

        initialize(looper, resolver, this::callback)

        enableMode()

        assertThat(isOn).isFalse()
        assertThat(mode).isEmpty()
    }

    @Test
    fun initialize_whenSensitiveAndDisabled_isOff() {
        initialize(looper, resolver, this::callback)

        assertThat(isOn).isFalse()
        assertThat(mode).isEmpty()
    }

    @Test
    fun initialize_whenSensitiveAndEnable_isOn() {
        enableSensitive()
        enableMode()

        initialize(looper, resolver, this::callback)

        assertThat(isOn).isTrue()
        assertThat(mode).isEmpty()
    }

    @Test
    fun toggleSensitive_whenEnabled_isOnOffOn() {
        enableSensitive()
        enableMode()

        initialize(looper, resolver, this::callback)

        disableSensitive()
        enableSensitive()

        assertThat(isOn).isTrue()
        assertThat(mode).containsExactly(false, true)
    }

    @Test
    fun toggleEnable_whenSensitive_isOffOnOff() {
        initialize(looper, resolver, this::callback)

        enableMode()
        disableMode()

        assertThat(isOn).isFalse()
        assertThat(mode).containsExactly(true, false)
    }

    @Test
    fun disable_whenDisabled_discardUpdate() {
        initialize(looper, resolver, this::callback)

        disableMode()

        assertThat(isOn).isFalse()
        assertThat(mode).isEmpty()
    }

    @Test
    fun enabled_whenEnabled_discardOnChange() {
        enableSensitive()
        enableMode()

        initialize(looper, resolver, this::callback)

        enableMode()

        assertThat(isOn).isTrue()
        assertThat(mode).isEmpty()
    }

    @Test
    fun changeContent_whenDisabled_discard() {
        initialize(looper, resolver, this::callback)

        disableSensitive()
        enableMode()

        assertThat(isOn).isFalse()
        // As opposed to the bare RadioModeListener, similar consecutive event are discarded
        assertThat(mode).isEmpty()
    }

    @Test
    fun initialize_firstTime_apmSettingIsSet() {
        initialize(looper, resolver, this::callback)
        assertThat(Settings.Global.getInt(resolver, APM_ENHANCEMENT, 0)).isEqualTo(1)
    }

    @Test
    fun initialize_secondTime_apmSettingIsNotOverride() {
        val settingValue = 42
        Settings.Global.putInt(resolver, APM_ENHANCEMENT, settingValue)

        initialize(looper, resolver, this::callback)

        assertThat(Settings.Global.getInt(resolver, APM_ENHANCEMENT, 0)).isEqualTo(settingValue)
    }

    companion object {
        internal fun setupAirplaneModeToOn(resolver: ContentResolver, looper: Looper) {
            enableSensitive(resolver, looper, Settings.Global.AIRPLANE_MODE_RADIOS)
            enableMode(resolver, looper, Settings.Global.AIRPLANE_MODE_ON)

            initialize(looper, resolver) {}
        }

        internal fun setupAirplaneModeToOff(resolver: ContentResolver, looper: Looper) {
            disableSensitive(resolver, looper, Settings.Global.AIRPLANE_MODE_RADIOS)
            disableMode(resolver, looper, Settings.Global.AIRPLANE_MODE_ON)

            initialize(looper, resolver) {}
        }

        internal fun disableEnhancementMode(resolver: ContentResolver, looper: Looper) {
            Settings.Global.putInt(resolver, APM_ENHANCEMENT, 0)
            initialize(looper, resolver) {}
        }

        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
