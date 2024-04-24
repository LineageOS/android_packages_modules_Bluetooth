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

@file:JvmName("AutoOnFeature")

package com.android.server.bluetooth

import android.app.AlarmManager
import android.app.BroadcastOptions
import android.bluetooth.BluetoothAdapter.ACTION_AUTO_ON_STATE_CHANGED
import android.bluetooth.BluetoothAdapter.AUTO_ON_STATE_DISABLED
import android.bluetooth.BluetoothAdapter.AUTO_ON_STATE_ENABLED
import android.bluetooth.BluetoothAdapter.EXTRA_AUTO_ON_STATE
import android.bluetooth.BluetoothAdapter.STATE_ON
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.android.modules.expresslog.Counter
import com.android.server.bluetooth.airplane.hasUserToggledApm as hasUserToggledApm
import com.android.server.bluetooth.airplane.isOn as isAirplaneModeOn
import com.android.server.bluetooth.satellite.isOn as isSatelliteModeOn
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val TAG = "AutoOnFeature"

public fun resetAutoOnTimerForUser(
    looper: Looper,
    context: Context,
    state: BluetoothAdapterState,
    callback_on: () -> Unit
) {
    // Remove any previous timer
    timer?.cancel()
    timer = null

    if (!isFeatureEnabledForUser(context.contentResolver)) {
        Log.d(TAG, "Not Enabled for current user: ${context.getUser()}")
        return
    }
    if (state.oneOf(STATE_ON)) {
        Log.d(TAG, "Bluetooth already in ${state}, no need for timer")
        return
    }
    if (isSatelliteModeOn) {
        Log.d(TAG, "Satellite prevent feature activation")
        return
    }
    if (isAirplaneModeOn) {
        if (!hasUserToggledApm(context)) {
            Log.d(TAG, "Airplane prevent feature activation")
            return
        }
        Log.d(TAG, "Airplane bypassed as airplane enhanced mode has been activated previously")
    }

    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.i(TAG, "Received ${intent.action} that trigger a new alarm scheduling")
                pause()
                resetAutoOnTimerForUser(looper, context, state, callback_on)
            }
        }

    timer = Timer.start(looper, context, receiver, callback_on)
}

public fun pause() {
    timer?.pause()
    timer = null
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public fun notifyBluetoothOn(context: Context) {
    timer?.cancel()
    timer = null

    if (!isFeatureSupportedForUser(context.contentResolver)) {
        val defaultFeatureValue = true
        if (!setFeatureEnabledForUserUnchecked(context, defaultFeatureValue)) {
            Log.e(TAG, "Failed to set feature to its default value ${defaultFeatureValue}")
        } else {
            Log.i(TAG, "Feature was set to its default value ${defaultFeatureValue}")
        }
    } else {
        // When Bluetooth turned on state, any saved time will be obsolete.
        // This happen only when the phone reboot while Bluetooth is ON
        Timer.resetStorage(context.contentResolver)
    }
}

public fun isUserSupported(resolver: ContentResolver) = isFeatureSupportedForUser(resolver)

public fun isUserEnabled(context: Context): Boolean {
    if (!isUserSupported(context.contentResolver)) {
        throw IllegalStateException("AutoOnFeature not supported for user: ${context.getUser()}")
    }
    return isFeatureEnabledForUser(context.contentResolver)
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public fun setUserEnabled(
    looper: Looper,
    context: Context,
    state: BluetoothAdapterState,
    status: Boolean,
    callback_on: () -> Unit,
) {
    if (!isUserSupported(context.contentResolver)) {
        throw IllegalStateException("AutoOnFeature not supported for user: ${context.getUser()}")
    }
    if (!setFeatureEnabledForUserUnchecked(context, status)) {
        throw IllegalStateException("AutoOnFeature database failure for user: ${context.getUser()}")
    }
    Counter.logIncrement(
        if (status) "bluetooth.value_auto_on_enabled" else "bluetooth.value_auto_on_disabled"
    )
    Timer.resetStorage(context.contentResolver)
    resetAutoOnTimerForUser(looper, context, state, callback_on)
}

////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////// PRIVATE METHODS /////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////

@VisibleForTesting internal var timer: Timer? = null

@VisibleForTesting
internal class Timer
private constructor(
    looper: Looper,
    private val context: Context,
    private val receiver: BroadcastReceiver,
    private val callback_on: () -> Unit,
    private val now: LocalDateTime,
    private val target: LocalDateTime,
    private val timeToSleep: Duration
) : AlarmManager.OnAlarmListener {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)!!

    private val handler = Handler(looper)

    init {
        writeDateToStorage(target, context.contentResolver)
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + timeToSleep.inWholeMilliseconds,
            "Bluetooth AutoOnFeature",
            this,
            handler
        )
        Log.i(TAG, "[${this}]: Scheduling next Bluetooth restart")

        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            null,
            handler
        )
    }

    override fun onAlarm() {
        Log.i(TAG, "[${this}]: Bluetooth restarting now")
        callback_on()
        cancel()
        timer = null
    }

    companion object {
        @VisibleForTesting internal val STORAGE_KEY = "bluetooth_internal_automatic_turn_on_timer"

        private fun writeDateToStorage(date: LocalDateTime, resolver: ContentResolver): Boolean {
            return Settings.Secure.putString(resolver, STORAGE_KEY, date.toString())
        }

        private fun getDateFromStorage(resolver: ContentResolver): LocalDateTime? {
            val date = Settings.Secure.getString(resolver, STORAGE_KEY)
            return date?.let { LocalDateTime.parse(it) }
        }

        fun resetStorage(resolver: ContentResolver) {
            Settings.Secure.putString(resolver, STORAGE_KEY, null)
        }

        fun start(
            looper: Looper,
            context: Context,
            receiver: BroadcastReceiver,
            callback_on: () -> Unit
        ): Timer? {
            val now = LocalDateTime.now()
            val target = getDateFromStorage(context.contentResolver) ?: nextTimeout(now)
            val timeToSleep =
                now.until(target, ChronoUnit.NANOS).toDuration(DurationUnit.NANOSECONDS)

            if (timeToSleep.isNegative()) {
                Log.i(TAG, "Starting now (${now}) as it was scheduled for ${target}")
                callback_on()
                resetStorage(context.contentResolver)
                return null
            }

            return Timer(looper, context, receiver, callback_on, now, target, timeToSleep)
        }

        /** Return a LocalDateTime for tomorrow 5 am */
        private fun nextTimeout(now: LocalDateTime) =
            LocalDateTime.of(now.toLocalDate(), LocalTime.of(5, 0)).plusDays(1)
    }

    /** Save timer to storage and stop it */
    internal fun pause() {
        Log.i(TAG, "[${this}]: Pausing timer")
        context.unregisterReceiver(receiver)
        alarmManager.cancel(this)
        handler.removeCallbacksAndMessages(null)
    }

    /** Stop timer and reset storage */
    @VisibleForTesting
    internal fun cancel() {
        Log.i(TAG, "[${this}]: Cancelling timer")
        context.unregisterReceiver(receiver)
        alarmManager.cancel(this)
        handler.removeCallbacksAndMessages(null)
        resetStorage(context.contentResolver)
    }

    override fun toString() =
        "Timer was scheduled at ${now} and should expire at ${target}. (sleep for ${timeToSleep})."
}

@VisibleForTesting internal val USER_SETTINGS_KEY = "bluetooth_automatic_turn_on"

/**
 * *Do not use outside of this file to avoid async issues*
 *
 * @return whether the auto on feature is enabled for this user
 */
private fun isFeatureEnabledForUser(resolver: ContentResolver): Boolean {
    return Settings.Secure.getInt(resolver, USER_SETTINGS_KEY, 0) == 1
}

/**
 * *Do not use outside of this file to avoid async issues*
 *
 * @return whether the auto on feature is supported for the user
 */
private fun isFeatureSupportedForUser(resolver: ContentResolver): Boolean {
    return Settings.Secure.getInt(resolver, USER_SETTINGS_KEY, -1) != -1
}

/**
 * *Do not use outside of this file to avoid async issues*
 *
 * @return whether the auto on feature is enabled for this user
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun setFeatureEnabledForUserUnchecked(context: Context, status: Boolean): Boolean {
    val ret =
        Settings.Secure.putInt(context.contentResolver, USER_SETTINGS_KEY, if (status) 1 else 0)
    if (ret) {
        context.sendBroadcast(
            Intent(ACTION_AUTO_ON_STATE_CHANGED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(
                    EXTRA_AUTO_ON_STATE,
                    if (status) AUTO_ON_STATE_ENABLED else AUTO_ON_STATE_DISABLED
                ),
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle(),
        )
    }
    return ret
}
