/*
 * Copyright 2023 The Android Open Source Project
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

import android.content.ContentResolver
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BleScanSettingListener.initialize
import com.android.server.bluetooth.BleScanSettingListener.isScanAllowed
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BleScanSettingListenerTest {
    private val resolver: ContentResolver =
        ApplicationProvider.getApplicationContext<Context>().getContentResolver()

    private val looper: Looper = Looper.getMainLooper()

    private var callbackTriggered: Boolean = false

    @Before
    public fun setup() {
        callbackTriggered = false
    }

    private fun startListener() {
        initialize(looper, resolver, this::callback)
    }

    private fun enableSetting() {
        Settings.Global.putInt(resolver, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 1)
        shadowOf(looper).idle()
    }

    private fun disableSetting() {
        Settings.Global.putInt(resolver, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 0)
        shadowOf(looper).idle()
    }

    private fun callback() {
        callbackTriggered = true
    }

    @Test
    fun initialize_whenNoSettings_isOff() {
        startListener()

        assertThat(isScanAllowed).isFalse()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun initialize_whenSettingsOff_isOff() {
        disableSetting()

        startListener()

        assertThat(isScanAllowed).isFalse()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun initialize_whenSettingsOn_isScanAllowed() {
        enableSetting()
        startListener()

        assertThat(isScanAllowed).isTrue()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun changeModeToOn_whenSettingsOn_isScanAllowedAndEventDiscarded() {
        enableSetting()
        startListener()

        enableSetting()

        assertThat(isScanAllowed).isTrue()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun changeModeToOff_whenSettingsOff_isOffAndEventDiscarded() {
        startListener()

        disableSetting()

        assertThat(isScanAllowed).isFalse()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun changeModeToOn_whenSettingsOff_isScanAllowedWithoutCallback() {
        startListener()

        enableSetting()

        assertThat(isScanAllowed).isTrue()
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun changeModeToOff_whenSettingsOn_isOffAndCallbackTriggered() {
        enableSetting()
        startListener()

        disableSetting()

        assertThat(isScanAllowed).isFalse()
        assertThat(callbackTriggered).isTrue()
    }
}
