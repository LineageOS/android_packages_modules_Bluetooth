/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.app.AlarmManager
import android.bluetooth.IBluetoothManager.ACTION_AUTO_ON_STATE_CHANGED
import android.bluetooth.IBluetoothManager.AUTO_ON_STATE_DISABLED
import android.bluetooth.IBluetoothManager.AUTO_ON_STATE_ENABLED
import android.bluetooth.IBluetoothManager.EXTRA_AUTO_ON_STATE
import android.bluetooth.State
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.truth.content.IntentSubject.assertThat
import com.android.server.bluetooth.AutoOn
import com.android.server.bluetooth.AutoOn.Companion.AUTO_ON_KEY
import com.android.server.bluetooth.AutoOn.Companion.STORAGE_KEY
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.airplane.APM_USER_TOGGLED_BLUETOOTH
import com.android.server.bluetooth.airplane.AirplaneModeController
import com.android.server.bluetooth.airplane.test.ModeListenerTest as AirplaneModeListener
import com.android.server.bluetooth.satellite.isOn as isSatelliteModeOn
import com.android.server.bluetooth.satellite.test.ModeListenerTest as SatelliteListener
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
@kotlin.time.ExperimentalTime
class AutoOnTest {

    @get:Rule val testName = TestName()
    @get:Rule val expect = Expect.create()

    private val looper = Looper.getMainLooper()
    private val state = BluetoothAdapterState()
    private val user = UserHandle.of(ActivityManager.getCurrentUser())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userContext =
        context.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
    private val userResolver = userContext.contentResolver
    private val now = LocalDateTime.now()
    private val timerTarget = LocalDateTime.of(now.toLocalDate(), LocalTime.of(5, 0)).plusDays(1)
    private lateinit var airplaneController: AirplaneModeController
    private lateinit var autoOn: AutoOn

    private var callback_count = 0

    @Before
    fun setUp() {

        Log.i("AutoOnTest", "\t--> setUp(${testName.methodName})")
        BluetoothRestrictionTest.setup()

        callback_count = 0
        AirplaneModeListener.setupAirplaneModeToOff(context.contentResolver, looper)
        airplaneController =
            AirplaneModeController(userContext, state, {}, {}, TimeSource.Monotonic)
        autoOn = AutoOn(looper, userContext, user, state, this::callback_on, airplaneController)
        enableSetting()
        BluetoothComponentTest.setup()
    }

    @After
    fun tearDown() {
        autoOn.timer?.cancel()
        resetSavedTimer()
    }

    private fun setupTimer() {
        autoOn.resetAutoOnTimer()
    }

    private fun setEnabled(status: Boolean) {
        autoOn.setEnabled(status)
    }

    private fun enableSetting() {
        Settings.Secure.putInt(userResolver, AUTO_ON_KEY, 1)
        shadowOf(looper).idle()
    }

    private fun disableSettings() {
        Settings.Secure.putInt(userResolver, AUTO_ON_KEY, 0)
        shadowOf(looper).idle()
    }

    private fun restoreSettings() {
        Settings.Secure.putString(userResolver, AUTO_ON_KEY, null)
        shadowOf(looper).idle()
    }

    private fun resetSavedTimer() {
        Settings.Secure.putString(userResolver, STORAGE_KEY, null)
        shadowOf(looper).idle()
    }

    private fun expectStorageTime() {
        shadowOf(looper).idle()
        expect
            .that(Settings.Secure.getString(userResolver, STORAGE_KEY))
            .isEqualTo(timerTarget.toString())
    }

    private fun expectNoStorageTime() {
        shadowOf(looper).idle()
        expect.that(Settings.Secure.getString(userResolver, STORAGE_KEY)).isNull()
    }

    private fun callback_on() {
        callback_count++
    }

    @Test
    fun setupTimer_whenItWasNeverUsed_isNotScheduled() {
        restoreSettings()

        setupTimer()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun setupTimer_whenUserDisallowed_isNotScheduled() {
        BluetoothRestrictionTest.disallowBluetooth()
        setupTimer()

        expect.that(autoOn.timer).isNull()
    }

    @Test
    fun setupTimer_whenBtOn_isNotScheduled() {
        state.set(State.ON)

        setupTimer()

        state.set(State.OFF)
        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun setupTimer_whenBtOffAndEnabled_isScheduled() {
        setupTimer()

        expect.that(autoOn.timer).isNotNull()
    }

    @Test
    fun setupTimer_whenBtOffAndUserEnabled_triggerCallback() {
        setupTimer()

        val shadowAlarmManager = shadowOf(userContext.getSystemService(AlarmManager::class.java))
        shadowAlarmManager.fireAlarm(shadowAlarmManager.peekNextScheduledAlarm())

        shadowOf(looper).runOneTask()

        expect.that(callback_count).isEqualTo(1)
        expect.that(autoOn.timer).isNull()
    }

    @Test
    fun setupTimer_whenAlreadySetup_triggerCallbackOnce() {
        setupTimer()
        setupTimer()
        setupTimer()

        val shadowAlarmManager = shadowOf(userContext.getSystemService(AlarmManager::class.java))
        shadowAlarmManager.fireAlarm(shadowAlarmManager.peekNextScheduledAlarm())

        shadowOf(looper).runOneTask()

        expect.that(callback_count).isEqualTo(1)
        expect.that(autoOn.timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenNoTimer_noCrash() {
        autoOn.notifyBluetoothOn()

        assertThat(autoOn.timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenTimer_isNotScheduled() {
        setupTimer()
        autoOn.notifyBluetoothOn()

        shadowOf(looper).runToEndOfTasks()
        expect.that(callback_count).isEqualTo(0)
        expect.that(autoOn.timer).isNull()
    }

    @Test
    fun notifyBluetoothOn_whenItWasNeverUsed_enableSettings() {
        restoreSettings()

        autoOn.notifyBluetoothOn()

        assertThat(autoOn.isSupported()).isTrue()
    }

    @Test
    fun notifyBluetoothOn_whenStorage_resetStorage() {
        Settings.Secure.putString(userResolver, STORAGE_KEY, timerTarget.toString())
        shadowOf(looper).idle()

        autoOn.notifyBluetoothOn()

        expectNoStorageTime()
    }

    @Test
    fun apiIsEnable_whenItWasNeverUsed_throwException() {
        restoreSettings()

        assertFailsWith<IllegalStateException> { autoOn.isEnabled() }
    }

    @Test
    fun apiSetEnabled_whenItWasNeverUsed_throwException() {
        restoreSettings()

        assertFailsWith<IllegalStateException> { setEnabled(true) }
    }

    @Test
    fun apiIsEnable_whenEnabled_isTrue() {
        assertThat(autoOn.isEnabled()).isTrue()
    }

    @Test
    fun apiIsEnable_whenDisabled_isFalse() {
        disableSettings()
        assertThat(autoOn.isEnabled()).isFalse()
    }

    @Test
    fun apiSetEnableToFalse_whenScheduled_isNotScheduled() {
        setupTimer()

        setEnabled(false)

        assertThat(autoOn.isEnabled()).isFalse()
        assertThat(callback_count).isEqualTo(0)
        assertThat(autoOn.timer).isNull()
    }

    @Test
    fun apiSetEnableToFalse_whenIdle_isNotScheduled() {
        setEnabled(false)

        assertThat(autoOn.isEnabled()).isFalse()
        assertThat(callback_count).isEqualTo(0)
        assertThat(autoOn.timer).isNull()
    }

    @Test
    fun apiSetEnableToTrue_whenIdle_canSchedule() {
        disableSettings()

        setEnabled(true)

        assertThat(autoOn.timer).isNotNull()
    }

    @Test
    fun apiSetEnableToggle_whenScheduled_isRescheduled() {
        val pastTime = timerTarget.minusDays(3)
        Settings.Secure.putString(userResolver, STORAGE_KEY, pastTime.toString())
        shadowOf(looper).idle()

        setEnabled(false)
        expectNoStorageTime()

        setEnabled(true)
        expectStorageTime()

        assertThat(autoOn.timer).isNotNull()
    }

    @Test
    fun apiSetEnableToFalse_whenEnabled_broadcastIntent() {
        setEnabled(false)

        assertThat(shadowOf(context as ContextWrapper).getBroadcastIntents().get(0)).run {
            hasAction(ACTION_AUTO_ON_STATE_CHANGED)
            hasFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            extras().integer(EXTRA_AUTO_ON_STATE).isEqualTo(AUTO_ON_STATE_DISABLED)
        }
    }

    @Test
    fun apiSetEnableToTrue_whenDisabled_broadcastIntent() {
        disableSettings()
        setEnabled(true)

        assertThat(shadowOf(context as ContextWrapper).getBroadcastIntents().get(0)).run {
            hasAction(ACTION_AUTO_ON_STATE_CHANGED)
            hasFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            extras().integer(EXTRA_AUTO_ON_STATE).isEqualTo(AUTO_ON_STATE_ENABLED)
        }
    }

    @Test
    fun apiSetEnableToTrue_whenAlreadyEnabled_doNothing() {
        setEnabled(true)

        assertThat(shadowOf(context as ContextWrapper).getBroadcastIntents().size).isEqualTo(0)
    }

    @Test
    fun pause_whenIdle_noTimeSave() {
        autoOn.pause()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
        expectNoStorageTime()
    }

    @Test
    fun pause_whenTimer_timeIsSaved() {
        setupTimer()

        autoOn.pause()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
        expectStorageTime()
    }

    @Test
    fun setupTimer_whenIdle_timeIsSave() {
        setupTimer()

        expect.that(autoOn.timer).isNotNull()
        expect.that(callback_count).isEqualTo(0)
        expectStorageTime()
    }

    @Test
    fun setupTimer_whenPaused_isResumed() {
        val now = LocalDateTime.now()
        val alarmTime = LocalDateTime.of(now.toLocalDate(), LocalTime.of(5, 0)).plusDays(1)
        Settings.Secure.putString(userResolver, STORAGE_KEY, alarmTime.toString())
        shadowOf(looper).idle()

        setupTimer()

        expect.that(autoOn.timer).isNotNull()
        expect.that(callback_count).isEqualTo(0)
        expectStorageTime()
    }

    @Test
    fun setupTimer_whenSaveTimerIsExpired_triggerCallback() {
        val pastTime = timerTarget.minusDays(3)
        Settings.Secure.putString(userResolver, STORAGE_KEY, pastTime.toString())
        shadowOf(looper).idle()

        setupTimer()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(1)
        expectNoStorageTime()
    }

    @Test
    fun setupTimer_whenSatelliteIsOn_isNotScheduled() {
        SatelliteListener.setupSatelliteModeToOn(context.contentResolver, looper)
        assertThat(isSatelliteModeOn).isTrue()

        setupTimer()

        SatelliteListener.setupSatelliteModeToOff(context.contentResolver, looper)
        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
        expectNoStorageTime()
    }

    @Test
    fun updateTimezone_whenTimerSchedule_isReScheduled() {
        setupTimer()

        // Fake storage time so when receiving the intent, the test think we jump in the future
        val pastTime = timerTarget.minusDays(3)
        Settings.Secure.putString(userResolver, STORAGE_KEY, pastTime.toString())

        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(looper).idle()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(1)
        expectNoStorageTime()
    }

    @Test
    fun updateTime_whenTimerSchedule_isReScheduled() {
        setupTimer()

        // Fake stored time so when receiving the intent, the test think we jumped in the future
        val pastTime = timerTarget.minusDays(3)
        Settings.Secure.putString(userResolver, STORAGE_KEY, pastTime.toString())

        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(looper).idle()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(1)
        expectNoStorageTime()
    }

    @Test
    fun updateDate_whenTimerSchedule_isReScheduled() {
        setupTimer()

        // Fake stored time so when receiving the intent, the test think we jumped in the future
        val pastTime = timerTarget.minusDays(3)
        Settings.Secure.putString(userResolver, STORAGE_KEY, pastTime.toString())

        context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        shadowOf(looper).idle()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(1)
        expectNoStorageTime()
    }

    @Test
    @kotlin.time.ExperimentalTime
    fun setupTimer_whenLegacyAirplaneIsOn_isNotSchedule() {
        AirplaneModeListener.disableEnhancementMode(context.contentResolver, looper)
        airplaneController.onAirplaneModeChanged(true)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)

        setupTimer()

        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
        expectNoStorageTime()
    }

    @Test
    @kotlin.time.ExperimentalTime
    fun setupTimer_whenApmAirplaneIsOn_isSchedule() {
        airplaneController.onAirplaneModeChanged(true)
        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 1)

        setupTimer()

        Settings.Secure.putInt(userResolver, APM_USER_TOGGLED_BLUETOOTH, 0)
        expect.that(autoOn.timer).isNotNull()
        expect.that(callback_count).isEqualTo(0)
        expectStorageTime()
    }

    @Test
    fun factoryReset_whenTimerIsRunning_isCancelledAndOff() {
        setupTimer()

        autoOn.factoryReset()

        expectNoStorageTime()
        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun factoryReset_whenNoTimer_isCancelledAndOff() {
        autoOn.factoryReset()

        expectNoStorageTime()
        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
    }

    @Test
    fun factoryReset_whenTimerDisabled_isCancelledAndOff() {
        disableSettings()
        autoOn.factoryReset()

        assertThat(autoOn.isEnabled()).isFalse()
        expectNoStorageTime()
        expect.that(autoOn.timer).isNull()
        expect.that(callback_count).isEqualTo(0)
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
    }
}
