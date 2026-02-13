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

package com.android.server.bluetooth

import android.app.AlarmManager
import android.app.BroadcastOptions
import android.bluetooth.IBluetoothManager.ACTION_AUTO_ON_STATE_CHANGED
import android.bluetooth.IBluetoothManager.AUTO_ON_STATE_DISABLED
import android.bluetooth.IBluetoothManager.AUTO_ON_STATE_ENABLED
import android.bluetooth.IBluetoothManager.EXTRA_AUTO_ON_STATE
import android.bluetooth.State
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.bluetooth.util.registerReceiver
import com.android.server.bluetooth.airplane.AirplaneModeController
import com.android.server.bluetooth.airplane.hasAirplaneModeEnhanced
import com.android.server.bluetooth.satellite.isOn as isSatelliteModeOn
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val TAG = "AutoOn"

@kotlin.time.ExperimentalTime
class AutoOn(
    private val looper: Looper,
    private val context: Context,
    private val user: UserHandle,
    private val state: BluetoothAdapterState,
    private val callback_on: () -> Unit,
    private val airplaneModeController: AirplaneModeController,
) {
    private val contentResolver: ContentResolver = context.contentResolver

    @VisibleForTesting internal var timer: Timer? = null

    fun resetAutoOnTimer() {
        // Remove any previous timer
        timer?.cancel()
        timer = null

        if (!isEnabledUnchecked()) {
            Log.d(TAG, "Not Enabled for $user")
            return
        }
        if (!BluetoothRestriction.isBluetoothAllowed) {
            Log.d(TAG, "Bluetooth is disallowed, no need for timer")
            return
        }
        if (state.oneOf(State.ON)) {
            Log.d(TAG, "Bluetooth already ON, no need for timer")
            return
        }
        if (isSatelliteModeOn) {
            Log.d(TAG, "Satellite prevent feature activation")
            return
        }
        if (airplaneModeController.isOnForUser) {
            if (!context.hasAirplaneModeEnhanced()) {
                Log.d(TAG, "Airplane prevent feature activation")
                return
            }
            Log.d(TAG, "Airplane bypassed as airplane enhanced mode has been activated previously")
        }

        val now = LocalDateTime.now()
        val target = getDateFromStorage(contentResolver) ?: nextTimeout(now)
        val timeToSleep = now.until(target, ChronoUnit.NANOS).toDuration(DurationUnit.NANOSECONDS)

        if (timeToSleep.isNegative()) {
            Log.i(TAG, "Starting immediately ($now). Previous timer was scheduled for $target")
            callback_on()
            resetStorage(contentResolver)
            return
        }

        timer = Timer(looper, context, callback_on, now, target, timeToSleep)
    }

    private fun onReceiveIntent(intent: Intent) {
        Log.i(TAG, "Received ${intent.action} that trigger a new alarm scheduling")
        pause()
        resetAutoOnTimer()
    }

    fun pause() {
        timer?.pause()
        timer = null
    }

    fun notifyBluetoothOn() {
        timer?.cancel()
        timer = null

        if (!isSupported()) {
            val defaultFeatureValue = true
            setEnabledUnchecked(defaultFeatureValue)
            Log.i(TAG, "Feature was set to its default value $defaultFeatureValue")
        } else {
            // When Bluetooth turned on state, any saved time will be obsolete.
            // This happen only when the phone reboot while Bluetooth is ON
            resetStorage(contentResolver)
        }
    }

    fun isSupported() = Settings.Secure.getInt(contentResolver, AUTO_ON_KEY, -1) != -1

    fun isEnabled(): Boolean {
        check(isSupported()) { "AutoOn not supported for $user" }
        return isEnabledUnchecked()
    }

    fun setEnabled(status: Boolean) {
        check(isSupported()) { "AutoOn not supported for $user" }
        if (isEnabledUnchecked() && status == true) {
            Log.i(TAG, "setEnabled: Nothing to do, feature is already enabled")
            return
        }
        setEnabledUnchecked(status)
        resetStorage(contentResolver)
        resetAutoOnTimer()
    }

    fun factoryReset() {
        Settings.Secure.putInt(contentResolver, AUTO_ON_KEY, 0)
        timer?.cancel()
        timer = null
    }

    @VisibleForTesting
    internal inner class Timer
    constructor(
        looper: Looper,
        private val context: Context,
        private val callback_on: () -> Unit,
        private val now: LocalDateTime,
        private val target: LocalDateTime,
        private val timeToSleep: Duration,
    ) : AlarmManager.OnAlarmListener {
        private val alarmManager: AlarmManager =
            context.getSystemService(AlarmManager::class.java)!!

        private val receiver: BroadcastReceiver
        private val handler = Handler(looper)

        init {
            writeDateToStorage(target, context.contentResolver)
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + timeToSleep.inWholeMilliseconds,
                "Bluetooth AutoOn",
                this,
                handler,
            )
            Log.i(TAG, "[$this]: Scheduling next Bluetooth restart")

            receiver =
                context.registerReceiver(
                    looper,
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                ) { _, intent ->
                    onReceiveIntent(intent)
                }
        }

        override fun onAlarm() {
            Log.i(TAG, "[$this]: Bluetooth restarting now")
            callback_on()
            cancel()
            this@AutoOn.timer = null
        }

        /** Save timer to storage and stop it */
        internal fun pause() {
            Log.i(TAG, "[$this]: Pausing timer")
            context.unregisterReceiver(receiver)
            alarmManager.cancel(this)
            handler.removeCallbacksAndMessages(null)
        }

        /** Stop timer and reset storage */
        @VisibleForTesting
        internal fun cancel() {
            Log.i(TAG, "[$this]: Cancelling timer")
            context.unregisterReceiver(receiver)
            alarmManager.cancel(this)
            handler.removeCallbacksAndMessages(null)
            resetStorage(contentResolver)
        }

        override fun toString() =
            "Timer: scheduled at $now. expire at $target. (sleep for $timeToSleep)."
    }

    private fun isEnabledUnchecked() = Settings.Secure.getInt(contentResolver, AUTO_ON_KEY, 0) == 1

    private fun setEnabledUnchecked(status: Boolean) {
        Settings.Secure.putInt(contentResolver, AUTO_ON_KEY, if (status) 1 else 0)
        context.sendBroadcast(
            Intent(ACTION_AUTO_ON_STATE_CHANGED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(
                    EXTRA_AUTO_ON_STATE,
                    if (status) AUTO_ON_STATE_ENABLED else AUTO_ON_STATE_DISABLED,
                ),
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle(),
        )

        Log.i(TAG, "Triggering data backup")
        BackupHelper.sendBroadcast(context)
    }

    companion object {
        @VisibleForTesting internal const val AUTO_ON_KEY = "bluetooth_automatic_turn_on"
        @VisibleForTesting
        internal const val STORAGE_KEY = "bluetooth_internal_automatic_turn_on_timer"

        private fun writeDateToStorage(date: LocalDateTime, resolver: ContentResolver): Boolean {
            return Settings.Secure.putString(resolver, STORAGE_KEY, date.toString())
        }

        private fun getDateFromStorage(resolver: ContentResolver): LocalDateTime? {
            val date = Settings.Secure.getString(resolver, STORAGE_KEY)
            return date?.let { LocalDateTime.parse(it) }
        }

        private fun resetStorage(resolver: ContentResolver) {
            Settings.Secure.putString(resolver, STORAGE_KEY, null)
        }

        /** Return a LocalDateTime for tomorrow 5 am */
        private fun nextTimeout(now: LocalDateTime) =
            LocalDateTime.of(now.toLocalDate(), LocalTime.of(5, 0)).plusDays(1)
    }
}
