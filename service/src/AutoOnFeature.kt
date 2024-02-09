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

import android.bluetooth.BluetoothAdapter.STATE_ON
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.VisibleForTesting
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

    timer = Timer.start(looper, callback_on)
}

public fun notifyBluetoothOn() {
    timer?.cancel()
    timer = null
}

////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////// PRIVATE METHODS /////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////

@VisibleForTesting internal var timer: Timer? = null

@VisibleForTesting
internal class Timer
private constructor(
    looper: Looper,
    callback_on: () -> Unit,
    private val now: LocalDateTime,
    private val target: LocalDateTime,
    private val timeToSleep: Duration
) {
    private val handler = Handler(looper)

    init {
        handler.postDelayed(
            {
                Log.i(TAG, "[${this}]: Bluetooth restarting now")
                callback_on()
                cancel()
                // Set global instance to null to prevent further action. Job is done here
                timer = null
            },
            timeToSleep.inWholeMilliseconds
        )
        Log.i(TAG, "[${this}]: Scheduling next Bluetooth restart")
    }

    companion object {

        fun start(looper: Looper, callback_on: () -> Unit): Timer {
            val now = LocalDateTime.now()
            val target = nextTimeout(now)
            val timeToSleep =
                now.until(target, ChronoUnit.NANOS).toDuration(DurationUnit.NANOSECONDS)

            return Timer(looper, callback_on, now, target, timeToSleep)
        }

        /** Return a LocalDateTime for tomorrow 5 am */
        private fun nextTimeout(now: LocalDateTime) =
            LocalDateTime.of(now.toLocalDate(), LocalTime.of(5, 0)).plusDays(1)
    }

    /** Stop timer and reset storage */
    @VisibleForTesting
    internal fun cancel() {
        Log.i(TAG, "[${this}]: Cancelling timer")
        handler.removeCallbacksAndMessages(null)
    }

    override fun toString() = "Timer scheduled ${now} for target=${target} (=${timeToSleep} delay)."
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
