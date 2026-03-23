/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.BluetoothProfileConnectionInfo
import android.media.HwAudioSource
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService

/**
 * Abstracts away all interactions with the Android Audio framework.
 *
 * This class uses [HwAudioSource] for both playback and recording paths. This architecture relies
 * on system-level audio policy routing to connect the `HwAudioSource` for the recording path to the
 * actual microphone input.
 *
 * ### Rendezvous Pattern for Stream Startup
 * For the active playback path, there is a critical race condition between two asynchronous events
 * required to start streaming:
 * 1. `onSinkStreamReady()`: A callback from the native stack, indicating the Bluetooth transport
 *    (CIS) is ready.
 * 2. `onAudioDeviceAdded()`: A callback from the `AudioManager`, providing the `AudioDeviceInfo`
 *    needed to create the `HwAudioSource` player.
 *
 * The arrival order of these two events is not guaranteed. To handle this, a "rendezvous" pattern
 * is implemented using the [DeviceAudioState] class. The first event to arrive sets a flag. The
 * second event to arrive sees the flag is set, creates the player, and then triggers the final
 * action (`player.start()`). This ensures the player is only started after both the transport is
 * ready and the player object has been created.
 */
open class AudioProxy(
    private val context: Context,
    private val handler: Handler,
    private val adapterService: AdapterService,
    private val policyManager: PeripheralPolicyManager,
) {
    private data class DeviceAudioState(
        var sinkPlayer: HwAudioSource? = null,
        var sourcePlayer: HwAudioSource? = null,
        var isSinkStreamReady: Boolean = false,
        var isSourceStreamReady: Boolean = false,
    )

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)!!
    private val audioManagerAudioDeviceCallback = AudioManagerAudioDeviceCallback()
    private val deviceAudioStates: MutableMap<BluetoothDevice, DeviceAudioState> = mutableMapOf()

    fun init() {
        audioManager.registerAudioDeviceCallback(audioManagerAudioDeviceCallback, handler)
    }

    fun cleanup() {
        audioManager.unregisterAudioDeviceCallback(audioManagerAudioDeviceCallback)
        deviceAudioStates.keys.forEach { handleDeviceDisconnected(it) }
        deviceAudioStates.clear()
    }

    fun handleDeviceDisconnected(device: BluetoothDevice) {
        val state = deviceAudioStates.remove(device) ?: return
        state.sinkPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
        }
        state.sourcePlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
        }
    }

    /**
     * Notifies AudioManager of the active LE Audio device for a given stream direction.
     *
     * ### PITFALL: The `isLeOutput` parameter has inverted logic for the Peripheral role.
     * The `BluetoothProfileConnectionInfo.createLeAudioInfo` method and the underlying
     * `AudioManager` APIs were designed from the perspective of a Central device (like a phone).
     * This leads to counter-intuitive behavior when implemented in a Peripheral role.
     *
     * The `isLeOutput` parameter should be interpreted as "is the *remote* device an output sink?".
     * - **For Local Playback (SINK):** The remote device is a SOURCE. Therefore, we must pass
     *   `isLeOutput = false` to correctly configure the audio framework for playback on the
     *   peripheral.
     * - **For Local Recording (SOURCE):** The remote device is a SINK. Therefore, we must pass
     *   `isLeOutput = true` to correctly configure the audio framework for capturing audio from the
     *   peripheral.
     *
     * **WARNING:** Do not "correct" this logic. It is a necessary workaround to adapt the
     * Central-centric APIs for the Peripheral role.
     *
     * @param newDevice The new device to be set as active, or `null` to deactivate.
     * @param previousDevice The previously active device.
     * @param direction The direction of the stream being activated/deactivated.
     */
    fun updateActiveAudioDevice(
        newDevice: BluetoothDevice?,
        previousDevice: BluetoothDevice?,
        direction: StreamDirection,
    ) {
        Log.d(TAG, "Update active audio device for $direction: $previousDevice -> $newDevice")

        val suppressNoisyIntent = false
        val isLeOutput = (direction == StreamDirection.SOURCE)

        Log.i(
            TAG,
            "$ACTIVE_AUDIO_DEVICE_DEBUG_TAG Notifying AudioManager of active LE Audio device." +
                " isLeOutput=$isLeOutput, newDevice=$newDevice, previousDevice=$previousDevice",
        )
        audioManager.handleBluetoothActiveDeviceChanged(
            newDevice,
            previousDevice,
            BluetoothProfileConnectionInfo.createLeAudioInfo(suppressNoisyIntent, isLeOutput),
        )
    }

    fun onSinkStreamReady(device: BluetoothDevice) {
        if (policyManager.activeSinkDevice != device) {
            Log.w(TAG, "onSinkStreamReady for non-active sink device: $device")
            return
        }

        val state = deviceAudioStates.getOrPut(device) { DeviceAudioState() }

        state.sinkPlayer?.let {
            if (!it.isPlaying) {
                Log.d(TAG, "Stream is ready, starting sink player for device $device")
                it.start()
            }
        }
            ?: run {
                Log.d(
                    TAG,
                    "Stream is ready for $device but sink player not yet created. Setting flag.",
                )
                state.isSinkStreamReady = true
            }
    }

    fun onSourceStreamReady(device: BluetoothDevice) {
        if (policyManager.activeSourceDevice != device) {
            Log.w(TAG, "onSourceStreamReady for non-active source device: $device")
            return
        }

        val state = deviceAudioStates.getOrPut(device) { DeviceAudioState() }

        state.sourcePlayer?.let {
            if (!it.isPlaying) {
                Log.d(TAG, "Stream is ready, starting source player for device $device")
                it.start()
            }
        }
            ?: run {
                Log.d(
                    TAG,
                    "Stream is ready for $device but source player not yet created. Setting flag.",
                )
                state.isSourceStreamReady = true
            }
    }

    fun onStreamStopped(device: BluetoothDevice, streamId: Int) {
        Log.d(TAG, "onStreamStopped for device $device, streamId $streamId")
        val state = deviceAudioStates[device] ?: return
        val stream = policyManager.peerStreams[device]?.get(streamId) ?: return

        when (stream.direction) {
            StreamDirection.SINK -> {
                state.sinkPlayer?.let {
                    if (it.isPlaying) {
                        Log.i(TAG, "Stopping sink player for device $device")
                        it.stop()
                    }
                }
            }
            StreamDirection.SOURCE -> {
                state.sourcePlayer?.let {
                    if (it.isPlaying) {
                        Log.i(TAG, "Stopping source player for device $device")
                        it.stop()
                    }
                }
            }
        }
    }

    private inner class AudioManagerAudioDeviceCallback : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (deviceInfo in addedDevices) {
                onAudioDeviceAdded(deviceInfo)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (deviceInfo in removedDevices) {
                onAudioDeviceRemoved(deviceInfo)
            }
        }
    }

    private fun onAudioDeviceAdded(deviceInfo: AudioDeviceInfo) {
        if (
            deviceInfo.type != AudioDeviceInfo.TYPE_BLE_HEADSET &&
                deviceInfo.type != AudioDeviceInfo.TYPE_BLE_HEARING_AID
        ) {
            return
        }

        val deviceAddress = deviceInfo.address
        if (deviceAddress.isNullOrEmpty()) {
            Log.e(TAG, "Device address is null or empty")
            return
        }
        val device = adapterService.getDeviceFromByte(Util.getBytesFromAddress(deviceAddress))
        Log.i(
            TAG,
            "$ACTIVE_AUDIO_DEVICE_DEBUG_TAG Audio device added: $device, " +
                "isSink=${deviceInfo.isSink}, isSource=${deviceInfo.isSource}",
        )

        val state = deviceAudioStates.getOrPut(device) { DeviceAudioState() }

        // Note: deviceInfo describes the remote device's role.
        if (deviceInfo.isSource) {
            // Remote is a source, so this is our local SINK (playback) path.
            if (state.sinkPlayer != null) {
                Log.w(TAG, "Sink player already exists for device $device")
                return
            }

            val player = createHwAudioSource(deviceInfo, true)
            Log.d(TAG, "LE Audio Server playback (DECODING) device added: $device")
            state.sinkPlayer = player

            if (state.isSinkStreamReady) {
                Log.d(
                    TAG,
                    "Player created for already-ready sink stream. Starting player for $device",
                )
                player.start()
                state.isSinkStreamReady = false
            }
        } else if (deviceInfo.isSink) {
            // Remote is a sink, so this is our local SOURCE (recording) path.
            if (state.sourcePlayer != null) {
                Log.w(TAG, "Source player already exists for device $device")
                return
            }

            // For the recording path, we ask the audio framework which input device it would
            // use by default, and then we create our HwAudioSource on that device.
            // PITFALL: This uses a fragile "probe-and-hook" mechanism. See the warning in the
            // `discoverActiveInputDevice` method documentation for details on the risks.
            val micDevice = discoverActiveInputDevice()
            if (micDevice == null) {
                Log.e(
                    TAG,
                    "Could not find a valid input device to create HwAudioSource for recording!",
                )
                return
            }

            val player = createHwAudioSource(micDevice, false)
            Log.d(
                TAG,
                "LE Audio Server recording (ENCODING) device added: $device, using $micDevice",
            )
            state.sourcePlayer = player

            if (state.isSourceStreamReady) {
                Log.d(
                    TAG,
                    "Player created for already-ready source stream. Starting player for $device",
                )
                player.start()
                state.isSourceStreamReady = false
            }
        }
    }

    /**
     * Discovers the audio device that would be used for recording by the system.
     *
     * ### PITFALL: This mechanism is fragile and relies on internal system behavior.
     * This function works by creating a temporary `AudioRecord` instance and checking which device
     * the audio framework routes it to. This is a "probe-and-hook" mechanism, not a stable,
     * documented API contract for this purpose.
     *
     * **WARNING:** This implementation is tightly coupled to the internal routing logic of the
     * Android audio framework. It may fail or return an incorrect device if the default audio
     * policy or `AudioRecord`'s routing behavior changes in a future Android version. This is a
     * known technical debt.
     *
     * @return The discovered [AudioDeviceInfo] for the active input, or `null` if one could not be
     *   found.
     */
    private fun discoverActiveInputDevice(): AudioDeviceInfo? {
        val micDevice =
            try {
                // Create a temporary recorder to discover the preferred device
                val tempRecorder = createTempAudioRecord()
                val routedDevice = tempRecorder.routedDevice
                tempRecorder.release()
                if (routedDevice == null) {
                    throw IllegalStateException("Could not discover a routed input device.")
                }
                routedDevice
            } catch (e: Exception) {
                Log.e(TAG, "Failed to discover active microphone via AudioRecord: $e")
                // Fallback to querying devices if discovery fails
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                }
            }
        return micDevice
    }

    protected open fun createHwAudioSource(
        deviceInfo: AudioDeviceInfo,
        isPlayback: Boolean,
    ): HwAudioSource {
        val usage =
            if (isPlayback) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_VOICE_COMMUNICATION
        return HwAudioSource.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setAudioDeviceInfo(deviceInfo)
            .build()
    }

    private fun createTempAudioRecord(): AudioRecord {
        // TODO(b/481255899): Align with the BAP stream parameters in native
        val sampleRate = 16000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, format)

        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelMask,
            format,
            bufferSize,
        )
    }

    private fun onAudioDeviceRemoved(deviceInfo: AudioDeviceInfo) {
        if (
            deviceInfo.type != AudioDeviceInfo.TYPE_BLE_HEADSET &&
                deviceInfo.type != AudioDeviceInfo.TYPE_BLE_HEARING_AID
        ) {
            return
        }

        val deviceAddress = deviceInfo.address
        if (deviceAddress.isNullOrEmpty()) {
            Log.e(TAG, "Device address is null or empty for removed device")
            return
        }
        val device = adapterService.getDeviceFromByte(Util.getBytesFromAddress(deviceAddress))
        val state = deviceAudioStates[device] ?: return

        // Note: deviceInfo describes the remote device's role.
        if (deviceInfo.isSource) {
            // Remote is a source, so this is our local SINK (playback) path.
            state.sinkPlayer?.let {
                Log.d(TAG, "LE Audio Server playback (DECODING) device removed: $device")
                if (it.isPlaying) {
                    it.stop()
                }
                state.sinkPlayer = null
            }
        } else if (deviceInfo.isSink) {
            // Remote is a sink, so this is our local SOURCE (recording) path.
            state.sourcePlayer?.let {
                Log.d(TAG, "LE Audio Server recording (ENCODING) device removed: $device")
                if (it.isPlaying) {
                    it.stop()
                }
                state.sourcePlayer = null
            }
        }
    }

    companion object {
        private val TAG = AudioProxy::class.java.simpleName
        private const val ACTIVE_AUDIO_DEVICE_DEBUG_TAG = "[DEVICE_INFO_DEBUG]"
    }
}
