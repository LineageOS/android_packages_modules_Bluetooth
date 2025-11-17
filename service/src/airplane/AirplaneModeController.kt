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

package com.android.server.bluetooth.airplane

import android.bluetooth.State
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.android.bluetooth.BluetoothStatsLog
import com.android.server.bluetooth.BackupHelper
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.Log
import com.android.server.bluetooth.airplane.isOn as isSystemAirplaneModeOn
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "AirplaneModeController"

/**
 * The {@link AirplaneModeController} handles per user override of the system airplane mode
 *
 * The information of airplane mode being turns on would not be passed when Bluetooth is on and one
 * of the following situations is met:
 * - "Enhancement Mode" is enabled and the user asked for Bluetooth to be on previously
 * - A media profile is connected to a remote device (one of A2DP | Hearing Aid | Le Audio)
 * - A remote device identified as a watch is connected
 * - The local device is a watch and any device is connected
 *
 *   See {@link AirplaneModeController#airplaneModeValueOverride} for commented detail of the logic
 */
@kotlin.time.ExperimentalTime
class AirplaneModeController(
    private val userContext: Context,
    private val state: BluetoothAdapterState,
    private val triggerModeCallback: (m: Boolean) -> Unit,
    private val sendNotification: (state: String) -> Unit,
    private val timeSource: TimeSource,
) {

    /** @return true if Bluetooth state is currently impacted by airplane mode */
    var isOnForUser = false
        private set

    // True if a media profile is connected
    private var isMediaProfileConnected = false

    // True if a watch is connected, or if we are a watch and have any connection to a remote
    private var watchConnectionState = false

    private var session: AirplaneMetricSession?

    init {
        isOnForUser = airplaneModeValueOverride(isSystemAirplaneModeOn, null)
        session =
            if (isSystemAirplaneModeOn) {
                AirplaneMetricSession(false, timeSource.markNow())
            } else {
                null
            }
        Log.i(TAG, "Init completed. isOnForUser=$isOnForUser")
    }

    fun onAirplaneModeChanged(newMode: Boolean) {
        val previous = isOnForUser
        val isBluetoothOn = state.oneOf(State.ON, State.TURNING_ON, State.TURNING_OFF)

        isOnForUser = airplaneModeValueOverride(newMode, isBluetoothOn)

        session =
            if (newMode) {
                AirplaneMetricSession(isBluetoothOn, timeSource.markNow())
            } else {
                session?.close(isBluetoothOn)
                null
            }

        val description = "previous=$previous, isOnForUser=$isOnForUser"

        if (previous == isOnForUser) {
            Log.d(TAG, "Ignore mode change to same state. $description")
            return
        } else if (isOnForUser == false && state.oneOf(State.ON)) {
            Log.d(TAG, "Ignore mode change as Bluetooth is ON. $description")
            return
        }

        Log.i(TAG, "Trigger callback. $description")
        triggerModeCallback(isOnForUser)
    }

    @kotlin.time.ExperimentalTime
    fun notifyUserToggledBluetooth(isBluetoothOn: Boolean) {
        session?.notifyUserToggledBluetooth(isBluetoothOn)
    }

    fun setIsMediaProfileConnected(connected: Boolean) {
        isMediaProfileConnected = connected
    }

    fun setWatchConnectionState(connected: Boolean) {
        watchConnectionState = connected
    }

    fun factoryReset() {
        userContext.setSettingsSecure(BLUETOOTH_APM_STATE, 0)
        userContext.setSettingsSecure(APM_USER_TOGGLED_BLUETOOTH, 0)
    }

    private fun airplaneModeValueOverride(systemMode: Boolean, isBluetoothOn: Boolean?): Boolean {
        // Airplane mode is being disabled or bluetooth was not ON: no override
        if (!systemMode || isBluetoothOn == false) {
            return systemMode
        }
        // If "Enhancement Mode" is ON and the user already used the feature …
        if (isEnhancementEnabled && userContext.hasAirplaneModeEnhanced()) {
            // … Staying ON only depend on its last action in airplane mode
            if (isBluetoothOnAPM()) {
                if (isBluetoothOn == true) { // Avoid edge case when APM is already enabled at boot
                    val isWifiOn = isWifiOnApm()
                    sendNotification.invoke(
                        if (isWifiOn) APM_WIFI_BT_NOTIFICATION else APM_BT_NOTIFICATION
                    )
                }
                Log.i(TAG, "Enhancement Mode: override and stays ON")
                return false
            }
            Log.i(TAG, "Enhancement Mode: override and turns OFF")
            return true
        }
        // … Else, staying ON only depend on a media profile or a watch being connected or not
        //
        // Note: Once the "Enhancement Mode" has been used, media override no longer apply
        //       This has been done on purpose to avoid complex scenario like:
        //           1. User wants Bluetooth OFF according to "Enhancement Mode"
        //           2. User switches airplane while there is media => so Bluetooth stays ON
        //           3. User turns airplane OFF, stops media and toggles airplane back ON
        //       Should we turn Bluetooth OFF like asked initially ? Or keep it ON like the toggle ?
        if (isMediaProfileConnected) {
            Log.i(TAG, "Legacy Mode: media override -> stays ON")
            sendNotification.invoke(APM_BT_NOTIFICATION_DUE_TO_MEDIA)
            return false
        }
        if (watchConnectionState) {
            val isWatch = userContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
            Log.i(TAG, "Legacy Mode: watch isWatch=$isWatch override -> stays ON")
            sendNotification.invoke(
                if (isWatch) APM_BT_NOTIFICATION_ON_WATCH else APM_BT_NOTIFICATION_DUE_TO_WATCH
            )
            return false
        }
        Log.i(TAG, "Legacy Mode: no override, request to turns OFF")
        return true
    }

    @kotlin.time.ExperimentalTime
    private inner class AirplaneMetricSession(
        private val isBluetoothOnBeforeApmToggle: Boolean,
        private val sessionStartTime: TimeMark,
    ) {

        private val isMediaProfileConnectedBeforeApmToggle = isMediaProfileConnected
        private val isBluetoothOnAfterApmToggle = !isOnForUser
        private var userToggledBluetoothDuringApm = false
        private var userToggledBluetoothDuringApmWithinMinute = false

        fun notifyUserToggledBluetooth(isBluetoothOn: Boolean) {
            val isFirstToggle = !userToggledBluetoothDuringApm
            userToggledBluetoothDuringApm = true

            if (isFirstToggle) {
                val oneMinute = sessionStartTime + 1.minutes
                userToggledBluetoothDuringApmWithinMinute = !oneMinute.hasPassedNow()
            }

            if (isEnhancementEnabled) {
                // Set "Enhancement Mode" settings for a specific user
                userContext.setSettingsSecure(BLUETOOTH_APM_STATE, if (isBluetoothOn) 1 else 0)
                userContext.setSettingsSecure(APM_USER_TOGGLED_BLUETOOTH, 1)

                if (isBluetoothOn) {
                    Log.i(TAG, "Enhancement Mode will keep Bluetooth ON when toggling Airplane")
                    sendNotification(APM_BT_ENABLED_NOTIFICATION)
                } else {
                    Log.i(TAG, "Enhancement Mode will turn Bluetooth OFF when toggling Airplane")
                }
            }

            Log.i(TAG, "Triggering data backup")
            BackupHelper.sendBroadcast(userContext)
        }

        /** Log current airplaneSession. Session cannot be re-use */
        fun close(isBluetoothOn: Boolean) {
            BluetoothStatsLog.write(
                BluetoothStatsLog.AIRPLANE_MODE_SESSION_REPORTED,
                BluetoothStatsLog.AIRPLANE_MODE_SESSION_REPORTED__PACKAGE_NAME__BLUETOOTH,
                isBluetoothOnBeforeApmToggle,
                isBluetoothOnAfterApmToggle,
                isBluetoothOn,
                userContext.hasAirplaneModeEnhanced(),
                userToggledBluetoothDuringApm,
                userToggledBluetoothDuringApmWithinMinute,
                isMediaProfileConnectedBeforeApmToggle,
            )
        }
    }

    fun hasUserToggledApm() = userContext.hasAirplaneModeEnhanced()

    /** Enhancement Mode: Return true if the wifi should stays on during airplane mode */
    private fun isWifiOnApm() =
        Settings.Global.getInt(userContext.contentResolver, Settings.Global.WIFI_ON, 0) != 0 &&
            Settings.Secure.getInt(userContext.contentResolver, WIFI_APM_STATE, 0) == 1

    /** Enhancement Mode: Return true if the bluetooth should stays on during airplane mode */
    private fun isBluetoothOnAPM() =
        Settings.Secure.getInt(userContext.contentResolver, BLUETOOTH_APM_STATE, 0) == 1
}

/** Enhancement Mode: Return true if this user already toggled (aka used) the feature */
fun Context.hasAirplaneModeEnhanced() =
    Settings.Secure.getInt(this.contentResolver, APM_USER_TOGGLED_BLUETOOTH, 0) == 1

// Notification Id for when the airplane mode is turn on but Bluetooth stay on
internal const val APM_BT_NOTIFICATION = "apm_bt_notification"

// Notification Id for when the airplane mode is turn on but a device is connected (on a watch)
internal const val APM_BT_NOTIFICATION_ON_WATCH = "apm_bt_notification_on_watch"

// Notification Id for when the airplane mode is turn on but a watch is connected (not on a watch)
internal const val APM_BT_NOTIFICATION_DUE_TO_WATCH = "apm_bt_notification_due_to_watch"

// Notification Id for when the airplane mode is turn on but a media profile is connected
internal const val APM_BT_NOTIFICATION_DUE_TO_MEDIA = "apm_bt_notification_due_to_media"

// Notification Id for when the airplane mode is turn on but Bluetooth and Wifi stay on
internal const val APM_WIFI_BT_NOTIFICATION = "apm_wifi_bt_notification"

// Notification Id for when the Bluetooth is turned back on during airplane mode
internal const val APM_BT_ENABLED_NOTIFICATION = "apm_bt_enabled_notification"

// Whether the user has already toggled and used the "Enhancement Mode" feature
internal const val APM_USER_TOGGLED_BLUETOOTH = "apm_user_toggled_bluetooth"

// Whether Bluetooth should remain on in airplane mode
internal const val BLUETOOTH_APM_STATE = "bluetooth_apm_state"

// Whether Wifi should remain on in airplane mode
internal const val WIFI_APM_STATE = "wifi_apm_state"

private fun Context.setSettingsSecure(name: String, value: Int) =
    Settings.Secure.putInt(this.contentResolver, name, value)
