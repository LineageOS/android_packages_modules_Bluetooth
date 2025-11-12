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

package com.android.bluetooth.avrcpcontroller

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.android.bluetooth.Util

/**
 * Handler for [AvrcpControllerStateMachine] volume operations, which are handled differently
 * depending on volume strategy.
 *
 * Volume Strategies:
 * * **Relative:** This is the assumed volume strategy in the case no other volume features are
 *   supported, where the Controller and Target devices maintain their own independent volume level
 *   and stream gain/attenuation. The Controller can send simple
 *   [AvrcpControllerService.PASS_THRU_CMD_ID_VOL_UP] or
 *   [AvrcpControllerService.PASS_THRU_CMD_ID_VOL_DOWN] commands to the Target (which we do not
 *   support), but there is no synchronization of the exact volume level between the devices. Each
 *   device applies its own gain to the audio, with the resulting output level being the product of
 *   the two gains. Thus, unless one side is set to 100%, the result will always be quieter.
 * * **Absolute:** Introduced in AVRCP 1.4, this strategy allows the Controller and Target to
 *   synchronize on a single, shared volume level in the domain of [0, 127]. A change in volume on
 *   one device is expected to be reflected on the other. To facilitate this, the Target device
 *   should send the audio stream un-attenuated (at line level) to the Controller, which then
 *   applies the agreed-upon volume level.
 * * **Loud:** A specialized adaptation of the Absolute Volume strategy, primarily for Controller
 *   devices (like vehicle head units) that need to maintain independent control over their final
 *   output volume, often for safety reasons (to prevent dangerously loud levels). While a
 *   mini-Target record is created to signal support for Absolute Volume (ensuring line-level audio
 *   from the Target), the Controller in this strategy does not truly synchronize its volume. It
 *   typically "spoofs" its volume range, for example, by always reporting the volume as set to the
 *   maximum (127) in response to [AvrcpControllerStateMachine.MESSAGE_PROCESS_SET_ABS_VOL_CMD]
 *   commands from the Target, regardless of the Target's request. This allows the Controller to
 *   receive a consistent line-level input signal while managing its actual speaker output volume
 *   locally and independently. No volume change events are sent back to the Target.
 */
class AvrcpControllerVolumeHandler(
    private val mContext: Context,
    private val mDevice: BluetoothDevice?,
) {
    private val mAudioManager: AudioManager = mContext.getSystemService(AudioManager::class.java)

    /** The volume strategy in use by our device. */
    val volumeStrategy: Int =
        if (mAudioManager.isVolumeFixed() || Util.isAutomotive(mContext)) STRATEGY_LOUD
        else STRATEGY_ABSOLUTE

    private val isLoud: Boolean
        get() = this.volumeStrategy == STRATEGY_LOUD

    val absoluteVolume: Int
        /**
         * Gets the current absolute volume level.
         *
         * @return A volume level based on a domain of [0, ABS_VOL_MAX]
         */
        get() {
            if (this.isLoud) {
                return ABS_VOL_MAX
            }
            val maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            return (index * ABS_VOL_MAX) / maxVolume
        }

    /**
     * Set a requested absolute volume level according to our volume strategy. If the volume
     * strategy is STRATEGY_LOUD, then a response of ABS_VOL_MAX will always be sent and no volume
     * adjustment action will be taken on the sink side.
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     * @param label Volume notification label
     * @return The volume level to set according to strategy, based on a domain of [0, ABS_VOL_MAX].
     *   Does not have to be the same as the input.
     */
    fun setAbsoluteVolume(absVol: Int, label: Int): Int {
        var absVolToSet = absVol
        debug("setAbsoluteVolume: absVol=$absVolToSet, label=$label")
        if (this.isLoud) {
            debug(
                ("Volume strategy is " +
                    strategyToString(STRATEGY_LOUD) +
                    ", responding with max volume")
            )
            absVolToSet = ABS_VOL_MAX
        } else {
            setAbsVolume(absVolToSet)
        }
        return absVolToSet
    }

    /**
     * Align our volume with a requested absolute volume level
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     */
    private fun setAbsVolume(absVol: Int) {
        val maxLocalVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curLocalVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val reqLocalVolume: Int = (maxLocalVolume * absVol) / ABS_VOL_MAX
        debug(
            "setAbsVolume: absVol=" +
                absVol +
                ", reqLocal=" +
                reqLocalVolume +
                ", curLocal=" +
                curLocalVolume +
                ", maxLocal=" +
                maxLocalVolume
        )

        /*
         * In some cases change in percentage is not sufficient enough to warrant
         * change in index values which are in range of 0-15. For such cases
         * no action is required
         */
        if (reqLocalVolume != curLocalVolume) {
            mAudioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                reqLocalVolume,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    /**
     * Get a minimal string representation of this handler.
     *
     * @return The output string
     */
    override fun toString(): String {
        return "Device: $mDevice, Strategy: " + strategyToString(this.volumeStrategy)
    }

    private fun debug(message: String?) {
        Log.d(TAG, "[$mDevice]: $message")
    }

    companion object {
        private val TAG: String = AvrcpControllerVolumeHandler::class.java.getSimpleName()

        // Volume Strategies
        const val STRATEGY_NONE: Int = 1
        const val STRATEGY_RELATIVE: Int = 2
        const val STRATEGY_ABSOLUTE: Int = 3
        const val STRATEGY_LOUD: Int = 4

        // Absolute Volume domain is [0, 127], with 0 -> 0% and 127 -> 100%. See Bluetooth AVRCP
        // specification, section 6.13.1, "Absolute Volume"
        private const val ABS_VOL_MAX = 127

        private fun strategyToString(strategy: Int): String {
            return when (strategy) {
                STRATEGY_NONE -> "STRATEGY_NONE"
                STRATEGY_RELATIVE -> "STRATEGY_RELATIVE"
                STRATEGY_ABSOLUTE -> "STRATEGY_ABSOLUTE"
                STRATEGY_LOUD -> "STRATEGY_LOUD"
                else -> "UNKNOWN_STRATEGY_ID_$strategy"
            }
        }
    }
}
