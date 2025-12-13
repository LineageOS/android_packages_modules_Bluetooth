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

import android.app.ActivityManager
import android.bluetooth.State
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.UserHandle
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.bluetooth.flags.Flags
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.airplane.APM_BT_ENABLED_NOTIFICATION
import com.android.server.bluetooth.airplane.APM_BT_NOTIFICATION
import com.android.server.bluetooth.airplane.APM_BT_NOTIFICATION_DUE_TO_MEDIA
import com.android.server.bluetooth.airplane.APM_BT_NOTIFICATION_DUE_TO_WATCH
import com.android.server.bluetooth.airplane.APM_BT_NOTIFICATION_ON_WATCH
import com.android.server.bluetooth.airplane.APM_USER_TOGGLED_BLUETOOTH
import com.android.server.bluetooth.airplane.APM_WIFI_BT_NOTIFICATION
import com.android.server.bluetooth.airplane.AirplaneModeController
import com.android.server.bluetooth.airplane.BLUETOOTH_APM_STATE
import com.android.server.bluetooth.airplane.WIFI_APM_STATE
import com.android.server.bluetooth.airplane.test.ModeListenerTest as AirplaneModeListener
import com.android.server.bluetooth.test.BluetoothComponentTest
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf

@RunWith(ParameterizedRobolectricTestRunner::class)
@kotlin.time.ExperimentalTime
class AirplaneModeControllerTest(flags: FlagsWrapper) {
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val testName = TestName()

    private val looper = Looper.getMainLooper()
    private val state = BluetoothAdapterState()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver: ContentResolver = context.contentResolver

    private val userContext =
        context.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
    private val userResolver = userContext.contentResolver

    private lateinit var mode: ArrayList<Boolean>
    private lateinit var notification: ArrayList<String>
    private lateinit var controller: AirplaneModeController

    @Before
    fun setup() {
        Log.i("AirplaneModeControllerTest", "\t--> setup of ${testName.methodName}")
        BluetoothComponentTest.setup()
        AirplaneModeListener.setupAirplaneModeToOff(context.contentResolver, looper)

        mode = ArrayList()
        notification = ArrayList()
    }

    private fun createController(timesource: TimeSource = TimeSource.Monotonic) {
        controller =
            AirplaneModeController(
                userContext,
                state,
                this::callback,
                this::notificationCallback,
                timesource,
            )
    }

    private fun callback(newMode: Boolean) = mode.add(newMode)

    private fun notificationCallback(state: String) = notification.add(state)

    @Test
    fun initialize_whenOn_isOnForUser() {
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 1)

        createController()

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).isEmpty()
    }

    @Test
    fun initialize_afterFactoryReset_apmSettingIsReset() {
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 1)
        createController()

        controller.factoryReset()

        assertThat(controller.hasUserToggledApm()).isFalse()
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(0)
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(0)
    }

    @Test
    fun disable_whenBluetoothOn_isDiscarded() {
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()
        state.set(State.ON)

        controller.onAirplaneModeChanged(false)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).containsExactly()
    }

    @Test
    fun enable_whenLegacy_triggerCallback() {
        createController()
        state.set(State.ON)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly(true)
    }

    @Test
    fun disableTwice_whenLegacy_triggerCallbackOnce() {
        // Note this test scenario should be impossible in real code
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()

        controller.onAirplaneModeChanged(false)
        controller.onAirplaneModeChanged(false)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).containsExactly(false)
    }

    @Test
    fun enable_whenMediaConnected_isDicardedWithNotification() {
        createController()
        state.set(State.ON)
        controller.setIsMediaProfileConnected(true)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_BT_NOTIFICATION_DUE_TO_MEDIA)
    }

    @Test
    fun enable_whenWatchConnected_isDiscardedWithNotification() {
        createController()
        state.set(State.ON)
        controller.setWatchConnectionState(true)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_BT_NOTIFICATION_DUE_TO_WATCH)
    }

    @Test
    fun enable_asWatchDevice_whenConnected_isDiscardedWithNotification() {
        shadowOf(userContext.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, true)
        createController()
        state.set(State.ON)
        controller.setWatchConnectionState(true)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_BT_NOTIFICATION_ON_WATCH)
    }

    @Test
    fun enable_whenNoEnhancement_triggerCallback() {
        createController()
        state.set(State.ON)
        AirplaneModeListener.disableEnhancementMode(context.contentResolver, looper)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly(true)
    }

    @Test
    fun enable_whenEnhancementToOff_triggerCallback() {
        createController()
        state.set(State.ON)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 0)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly(true)
    }

    @Test
    fun enable_whenUntoggledEnhancement_triggerCallback() {
        createController()
        state.set(State.ON)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.hasUserToggledApm()).isFalse()
        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly(true)
    }

    @Test
    fun enable_whenEnhancement_isDiscardedWithNotification() {
        createController()
        state.set(State.ON)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 1)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(controller.hasUserToggledApm()).isTrue()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_BT_NOTIFICATION)
    }

    @Test
    fun enable_whenEnhancementAndWifi_isDiscardedWithNotification() {
        createController()

        state.set(State.ON)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 1)

        Settings.Global.putInt(resolver, Settings.Global.WIFI_ON, 1)
        Settings.Secure.putInt(userResolver, WIFI_APM_STATE, 1)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_WIFI_BT_NOTIFICATION)
    }

    @Test
    fun enable_whenEnhancementAndWifiOff_isDiscardedWIthNotification() {
        createController()

        state.set(State.ON)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)
        Settings.Secure.putInt(userResolver, BLUETOOTH_APM_STATE, 1)

        Settings.Global.putInt(resolver, Settings.Global.WIFI_ON, 1)

        controller.onAirplaneModeChanged(true)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).containsExactly(APM_BT_NOTIFICATION)
    }

    @Test
    fun toggleBluetooth_whenNoSession_nothingHappen() {
        createController()

        controller.notifyUserToggledBluetooth(false)

        assertThat(controller.isOnForUser).isFalse()
        assertThat(mode).isEmpty()
        assertThat(notification).isEmpty()
    }

    @Test
    fun toggleBluetooth_whenLegacySession_nothingHappen() {
        AirplaneModeListener.disableEnhancementMode(context.contentResolver, looper)
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()

        controller.notifyUserToggledBluetooth(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly()
        assertThat(notification).isEmpty()
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(0)
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(0)
    }

    @Test
    fun toggleBluetooth_whenLegacySession_nothingHappen_skipTime() {
        AirplaneModeListener.disableEnhancementMode(context.contentResolver, looper)
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        val timeSource = TestTimeSource()
        createController(timeSource)

        timeSource += 2.minutes
        controller.notifyUserToggledBluetooth(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly()
        assertThat(notification).isEmpty()
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(0)
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(0)
    }

    @Test
    fun toggleBluetoothOff_whenEnhancementSession_noNotificationAndSettingSaved() {
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()

        controller.notifyUserToggledBluetooth(false)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly()
        assertThat(notification).isEmpty()
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(0)
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(1)
    }

    @Test
    fun toggleBluetoothOn_whenEnhancementSession_notificationAndSettingSaved() {
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()

        controller.notifyUserToggledBluetooth(true)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly()
        assertThat(notification).containsExactly(APM_BT_ENABLED_NOTIFICATION)
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(1)
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(1)
    }

    @Test
    fun toggleBluetoothOnOff_whenEnhancementSession_notificationAndSettingSaved() {
        AirplaneModeListener.setupAirplaneModeToOn(context.contentResolver, looper)
        createController()

        controller.notifyUserToggledBluetooth(true)
        controller.notifyUserToggledBluetooth(false)

        assertThat(controller.isOnForUser).isTrue()
        assertThat(mode).containsExactly()
        assertThat(notification).containsExactly(APM_BT_ENABLED_NOTIFICATION)
        assertThat(Settings.Secure.getInt(userResolver, BLUETOOTH_APM_STATE, 0)).isEqualTo(0)
        assertThat(Settings.Secure.getInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)).isEqualTo(1)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            BluetoothAdapterState.disableCacheForTesting = true
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            BluetoothAdapterState.disableCacheForTesting = false
        }

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() =
            FlagsWrapper.progressionOf(Flags.FLAG_VALIDATE_BLUETOOTH_NAME_IN_PLATFORM_CONFIG)
    }
}
