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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.bluetooth.Util.isAutomotive
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.util.registerReceiver
import kotlin.math.roundToInt

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
 *   locally and independently. No volume changed events are sent back to the Target.
 */
class AvrcpControllerVolumeHandler(
    private val context: Context,
    private val device: BluetoothDevice,
    private val callback: Callback,
    looper: Looper,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /**
     * For sending volume changed events back to the object owner. Listens for
     * [AudioManager.ACTION_VOLUME_CHANGED] events and posts [onIntentReceived] to [handler].
     */
    // TODO when cleaning avrcpControllerAbsVolChangedNotification make it NonNull
    private val receiver: BroadcastReceiver?

    // To serialize the processing of volume events involving cachedStreamVolume
    // Only used with STRATEGY_ABSOLUTE
    private val handler = Handler(looper)

    // For distinguishing external volume changed events from setAbsoluteVolume calls
    // This volume is a local index, not absolute volume
    // Only used with STRATEGY_ABSOLUTE
    private var cachedStreamVolume = VOLUME_VALUE_MISSING

    /** The volume strategy in use by our device. */
    val volumeStrategy: Int = getDesiredVolumeStrategy(context)

    private val isLoud: Boolean
        get() = volumeStrategy == STRATEGY_LOUD

    private val isAbsolute: Boolean
        get() = volumeStrategy == STRATEGY_ABSOLUTE

    /**
     * Registers a broadcast receiver for volume changed events. Initializes [cachedStreamVolume] to
     * the current stream volume.
     */
    init {
        debug("Initializing volume handler")
        receiver =
            if (Flags.avrcpControllerAbsVolChangedNotification()) {
                context.registerReceiver(
                    handler,
                    AudioManager.ACTION_VOLUME_CHANGED,
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY,
                ) { _, intent ->
                    onIntentReceived(intent)
                }
            } else {
                null
            }
        cachedStreamVolume = getStreamVolume()
    }

    /**
     * Unregisters the broadcast receiver used for volume changed events. Clears the handler's
     * message queue. Resets [cachedStreamVolume].
     *
     * This object should no longer be used. Further invocations may result in undefined behavior,
     * including exceptions.
     */
    fun stop() {
        debug("Stopping volume handler")
        if (Flags.avrcpControllerAbsVolChangedNotification()) {
            context.unregisterReceiver(receiver)
            handler.removeCallbacksAndMessages(null)
        }
        cachedStreamVolume = VOLUME_VALUE_MISSING
    }

    val absoluteVolume: Int
        /**
         * Gets the current absolute volume level.
         *
         * @return A volume level based on a domain of [0, ABS_VOL_MAX]
         */
        get() {
            if (isLoud) {
                return ABS_VOL_MAX
            }
            val localVolume: Int =
                if (Flags.avrcpControllerAbsVolChangedNotification()) {
                    cachedStreamVolume
                } else {
                    getStreamVolume()
                }
            return localToAbsoluteVolume(localVolume)
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
        debug("setAbsoluteVolume: absVol=$absVol, label=$label")
        if (isLoud) {
            debug(
                "Volume strategy is ${strategyToString(STRATEGY_LOUD)}, responding with max volume"
            )
            return ABS_VOL_MAX
        } else {
            setAbsoluteVolumeInternal(absVol)
            return absVol
        }
    }

    /**
     * Align our volume with a requested absolute volume level
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     */
    private fun setAbsoluteVolumeInternal(absVol: Int) {
        if (!isAbsolute) {
            error(
                "setAbsoluteVolumeInternal: Unsupported volume strategy: ${strategyToString(volumeStrategy)}"
            )
            return
        }

        val reqLocalVolume = absoluteToLocalVolume(absVol)
        val curLocalVolume: Int =
            if (Flags.avrcpControllerAbsVolChangedNotification()) {
                cachedStreamVolume
            } else {
                getStreamVolume()
            }
        debug(
            "setAbsoluteVolumeInternal: absVol=$absVol, " +
                "reqLocal=$reqLocalVolume, " +
                "curLocal=$curLocalVolume"
        )

        if (Flags.avrcpControllerAbsVolChangedNotification()) {
            postSetStreamVolume(reqLocalVolume, curLocalVolume)
        } else {
            setStreamVolume(reqLocalVolume, curLocalVolume)
        }
    }

    /**
     * Changes the stream volume. Only used with [STRATEGY_ABSOLUTE]. To be removed with
     * [Flags.avrcpControllerAbsVolChangedNotification].
     */
    private fun setStreamVolume(reqLocalVolume: Int, curLocalVolume: Int) {
        if (reqLocalVolume == curLocalVolume) {
            /*
             * In some cases a change in percentage is not sufficient to warrant a change in index
             * value, which is in the range of 0-15. For such cases no action is required.
             */
            return
        }
        debug("Changing local stream volume from $curLocalVolume to $reqLocalVolume")
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            reqLocalVolume,
            AudioManager.FLAG_SHOW_UI,
        )
        cachedStreamVolume = reqLocalVolume
    }

    /**
     * Posts a runnable to [handler] to change the stream volume. Only used with
     * [STRATEGY_ABSOLUTE].
     */
    private fun postSetStreamVolume(reqLocalVolume: Int, curLocalVolume: Int) {
        handler.post { setStreamVolume(reqLocalVolume, curLocalVolume) }
    }

    /** Handle volume changed events by triggering the [callback]. */
    private fun volumeChanged(newLocalVolume: Int) {
        if (cachedStreamVolume == newLocalVolume) {
            // Volume is unchanged since the last set absolute volume command or volume changed
            // event
            return
        }

        val newAbsoluteVolume = localToAbsoluteVolume(newLocalVolume)
        debug("Stream volume changed to $newLocalVolume (local), $newAbsoluteVolume (absolute)")
        cachedStreamVolume = newLocalVolume

        if (!isAbsolute) {
            debug(
                "Dropping volume changed event because we are using " +
                    "${strategyToString(volumeStrategy)}, not " +
                    "${strategyToString(STRATEGY_ABSOLUTE)}."
            )
            return
        }

        callback.onAbsoluteVolumeChanged(newAbsoluteVolume)
    }

    /**
     * A Callback interface so the owning state machine can receive volume changed events from this
     * handler.
     */
    interface Callback {
        /**
         * Receive absolute volume level updates
         *
         * @param absVol The new absolute volume level
         */
        fun onAbsoluteVolumeChanged(absVol: Int)
    }

    /** Handles the [AudioManager.ACTION_VOLUME_CHANGED] intents [receiver] listens for. */
    @VisibleForTesting
    fun onIntentReceived(intent: Intent) {
        if (intent.action != AudioManager.ACTION_VOLUME_CHANGED) {
            return
        }
        val streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
        if (streamType != AudioManager.STREAM_MUSIC) {
            return
        }
        if (cachedStreamVolume == VOLUME_VALUE_MISSING) {
            // We ignore volume changed events before our initial caching at instantiation
            return
        }

        // This volume is a local index, not absolute volume
        val newLocalVolume =
            intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, VOLUME_VALUE_MISSING)
        if (newLocalVolume == VOLUME_VALUE_MISSING) {
            return
        }
        volumeChanged(newLocalVolume)
    }

    private fun getStreamVolume(): Int {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun getStreamMaxVolume(): Int {
        return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /**
     * Translate to an absolute volume level
     *
     * @param localVolume A local volume level based on the device's audio manager
     * @return An absolute volume level based on a domain of [0, ABS_VOL_MAX]
     */
    @VisibleForTesting
    fun localToAbsoluteVolume(localVolume: Int): Int {
        val maxLocalVolume = getStreamMaxVolume()
        return ((ABS_VOL_MAX * localVolume).toDouble() / maxLocalVolume).roundToInt()
    }

    /**
     * Translate to a local volume level
     *
     * @param absoluteVolume An absolute volume level based on a domain of [0, ABS_VOL_MAX]
     * @return A local volume level based on the device's audio manager
     */
    @VisibleForTesting
    fun absoluteToLocalVolume(absoluteVolume: Int): Int {
        val maxLocalVolume = getStreamMaxVolume()
        return ((maxLocalVolume * absoluteVolume).toDouble() / ABS_VOL_MAX).roundToInt()
    }

    /**
     * Get a minimal string representation of this handler.
     *
     * @return The output string
     */
    override fun toString(): String {
        return "Device: $device" +
            ", Volume Strategy: ${strategyToString(volumeStrategy)}" +
            ", Cached Stream Volume: $cachedStreamVolume"
    }

    private fun debug(message: String) {
        Log.d(TAG, "[$device]: $message")
    }

    private fun error(message: String) {
        Log.e(TAG, "[$device]: $message")
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

        private const val VOLUME_VALUE_MISSING = -1

        @JvmStatic
        fun strategyToString(strategy: Int): String {
            return when (strategy) {
                STRATEGY_NONE -> "STRATEGY_NONE"
                STRATEGY_RELATIVE -> "STRATEGY_RELATIVE"
                STRATEGY_ABSOLUTE -> "STRATEGY_ABSOLUTE"
                STRATEGY_LOUD -> "STRATEGY_LOUD"
                else -> "UNKNOWN_STRATEGY_ID_$strategy"
            }
        }

        @JvmStatic
        fun getDesiredVolumeStrategy(context: Context): Int {
            val audioManager = context.getSystemService(AudioManager::class.java)
            return if (audioManager.isVolumeFixed() || context.isAutomotive()) STRATEGY_LOUD
            else STRATEGY_ABSOLUTE
        }
    }
}
