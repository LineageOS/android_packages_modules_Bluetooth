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
import com.android.bluetooth.Util
import com.android.bluetooth.flags.Flags
import com.android.internal.annotations.GuardedBy

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
    private val mContext: Context,
    private val mDevice: BluetoothDevice,
    private val mCallback: Callback,
    mLooper: Looper,
) {
    /** For synchronizing [start] and [stop]. */
    private val mLock = Any()
    @GuardedBy("mLock") private var mStarted = false

    private val mAudioManager: AudioManager = mContext.getSystemService(AudioManager::class.java)

    // For sending volume changed events back to the object owner
    private val mReceiver = VolumeHandlerBroadcastReceiver()

    // To serialize the processing of volume events involving mCachedStreamVolume
    // Only used with STRATEGY_ABSOLUTE
    private val mHandler: Handler = Handler(mLooper)

    // For distinguishing external volume changed events from setAbsoluteVolume calls
    // This volume is a local index, not absolute volume
    // Only used with STRATEGY_ABSOLUTE
    private var mCachedStreamVolume = VOLUME_VALUE_MISSING

    /** The volume strategy in use by our device. */
    val mVolumeStrategy: Int =
        if (mAudioManager.isVolumeFixed() || Util.isAutomotive(mContext)) STRATEGY_LOUD
        else STRATEGY_ABSOLUTE

    private val isLoud: Boolean
        get() = mVolumeStrategy == STRATEGY_LOUD

    private val isAbsolute: Boolean
        get() = mVolumeStrategy == STRATEGY_ABSOLUTE

    /**
     * Registers the [VolumeHandlerBroadcastReceiver]. Initializes [mCachedStreamVolume] to the
     * current stream volume.
     */
    fun start() {
        synchronized(mLock) {
            if (mStarted) {
                error("Calling start() when already started")
                return
            }
            debug("Starting volume handler")
            if (Flags.avrcpControllerAbsVolChangedNotification()) {
                val filter = IntentFilter()
                filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                filter.addAction(AudioManager.ACTION_VOLUME_CHANGED)
                mContext.registerReceiver(mReceiver, filter)
            }
            mCachedStreamVolume = getStreamVolume()
            mStarted = true
        }
    }

    /**
     * Unregisters the [VolumeHandlerBroadcastReceiver]. Clears the handler's message queue. Resets
     * [mCachedStreamVolume].
     */
    fun stop() {
        synchronized(mLock) {
            if (!mStarted) {
                error("Calling stop() when already stopped")
                return
            }
            debug("Stopping volume handler")
            if (Flags.avrcpControllerAbsVolChangedNotification()) {
                mContext.unregisterReceiver(mReceiver)
                mHandler.removeCallbacksAndMessages(null)
            }
            mCachedStreamVolume = VOLUME_VALUE_MISSING
            mStarted = false
        }
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
                    mCachedStreamVolume
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
        var absVolToSet = absVol
        debug("setAbsoluteVolume: absVol=$absVolToSet, label=$label")
        if (isLoud) {
            debug(
                ("Volume strategy is " +
                    strategyToString(STRATEGY_LOUD) +
                    ", responding with max volume")
            )
            absVolToSet = ABS_VOL_MAX
        } else {
            setAbsoluteVolumeInternal(absVolToSet)
        }
        return absVolToSet
    }

    /**
     * Align our volume with a requested absolute volume level
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     */
    private fun setAbsoluteVolumeInternal(absVol: Int) {
        if (!isAbsolute) {
            error("setAbsoluteVolumeInternal: Unsupported volume strategy: $mVolumeStrategy")
            return
        }

        val reqLocalVolume = absoluteToLocalVolume(absVol)
        val curLocalVolume: Int =
            if (Flags.avrcpControllerAbsVolChangedNotification()) {
                mCachedStreamVolume
            } else {
                getStreamVolume()
            }
        debug(
            "setAbsoluteVolumeInternal: absVol=" +
                absVol +
                ", reqLocal=" +
                reqLocalVolume +
                ", curLocal=" +
                curLocalVolume
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
        /*
         * In some cases change in percentage is not sufficient enough to warrant
         * change in index values which are in range of 0-15. For such cases
         * no action is required
         */
        if (reqLocalVolume == curLocalVolume) {
            return
        }
        debug("Changing local stream volume from $curLocalVolume to $reqLocalVolume")
        mAudioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            reqLocalVolume,
            AudioManager.FLAG_SHOW_UI,
        )
        mCachedStreamVolume = reqLocalVolume
    }

    /**
     * Posts a runnable to [mHandler] to change the stream volume. Only used with
     * [STRATEGY_ABSOLUTE].
     */
    private fun postSetStreamVolume(reqLocalVolume: Int, curLocalVolume: Int) {
        mHandler.post { setStreamVolume(reqLocalVolume, curLocalVolume) }
    }

    /** Handle volume changed events by triggering the [Callback]. */
    private fun volumeChanged(newLocalVolume: Int) {
        if (mCachedStreamVolume == newLocalVolume) {
            // Volume is unchanged since the last set absolute volume command or volume changed
            // event
            return
        }

        val newAbsoluteVolume = localToAbsoluteVolume(newLocalVolume)
        debug("Stream volume changed to $newLocalVolume (local), $newAbsoluteVolume (absolute)")
        mCachedStreamVolume = newLocalVolume

        if (!isAbsolute) {
            debug(
                "Dropping volume changed event because we are using " +
                    "${strategyToString(mVolumeStrategy)}, not " +
                    "${strategyToString(STRATEGY_ABSOLUTE)}."
            )
            return
        }

        mCallback.onAbsoluteVolumeChanged(newAbsoluteVolume)
    }

    /** Posts a runnable to [mHandler] to handle volume changed events. */
    private fun postVolumeChanged(newLocalVolume: Int) {
        mHandler.post { volumeChanged(newLocalVolume) }
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

    /**
     * If using absolute volume, listens for [AudioManager.ACTION_VOLUME_CHANGED] events to trigger
     * the [Callback].
     */
    private inner class VolumeHandlerBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_VOLUME_CHANGED) {
                return
            }
            val streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType != AudioManager.STREAM_MUSIC) {
                return
            }
            if (mCachedStreamVolume == VOLUME_VALUE_MISSING) {
                // We ignore volume changed events before our initial caching in start()
                return
            }

            // This volume is a local index, not absolute volume
            val newLocalVolume =
                intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, VOLUME_VALUE_MISSING)
            if (newLocalVolume == VOLUME_VALUE_MISSING) {
                return
            }
            postVolumeChanged(newLocalVolume)
        }
    }

    private fun getStreamVolume(): Int {
        return mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun getStreamMaxVolume(): Int {
        return mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /**
     * Translate to an absolute volume level
     *
     * @param localVolume A local volume level based on the device's audio manager
     * @return An absolute volume level based on a domain of [0, ABS_VOL_MAX]
     */
    private fun localToAbsoluteVolume(localVolume: Int): Int {
        val maxLocalVolume = getStreamMaxVolume()
        return (ABS_VOL_MAX * localVolume) / maxLocalVolume
    }

    /**
     * Translate to a local volume level
     *
     * @param absoluteVolume An absolute volume level based on a domain of [0, ABS_VOL_MAX]
     * @return A local volume level based on the device's audio manager
     */
    private fun absoluteToLocalVolume(absoluteVolume: Int): Int {
        val maxLocalVolume = getStreamMaxVolume()
        return (maxLocalVolume * absoluteVolume) / ABS_VOL_MAX
    }

    /**
     * Get a minimal string representation of this handler.
     *
     * @return The output string
     */
    override fun toString(): String {
        return "Device: $mDevice" +
            ", Volume Strategy: ${strategyToString(mVolumeStrategy)}" +
            ", Cached Stream Volume: $mCachedStreamVolume"
    }

    private fun debug(message: String) {
        Log.d(TAG, "[$mDevice]: $message")
    }

    private fun error(message: String) {
        Log.e(TAG, "[$mDevice]: $message")
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
