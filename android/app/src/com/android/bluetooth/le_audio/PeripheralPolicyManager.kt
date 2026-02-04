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
import android.bluetooth.BluetoothLeAudioPeripheral
import android.os.Handler
import android.util.Log

/**
 * Manages LE Audio peripheral policy, device connections, and stream states.
 *
 * This class is the central "brain" of the LE Audio peripheral service. It is responsible for
 * making all policy decisions regarding audio streams, including:
 * - Arbitration: Deciding whether to accept or reject incoming stream requests.
 * - Preemption: Stopping an active stream to yield to a higher-priority request.
 * - State Management: Tracking the state of each peer device and its associated streams.
 * - Validation: Ensuring that stream requests are valid for the current device and context.
 */
class PeripheralPolicyManager(
    private val service: LeAudioPeripheralService,
    private val nativeInterface: LeAudioPeripheralNativeInterface,
    private val handler: Handler,
) {
    @Volatile var activeSinkDevice: BluetoothDevice? = null
    @Volatile var activeSourceDevice: BluetoothDevice? = null
    val peerStreams: MutableMap<BluetoothDevice, MutableMap<Int, LeAudioStream>> = mutableMapOf()
    private val allowedStreamTypes: MutableMap<BluetoothDevice, Int> = mutableMapOf()
    private var audioProxy: AudioProxy? = null

    fun setStreamTypesEnabled(device: BluetoothDevice, streamTypes: Int, enabled: Boolean) {
        val currentStreamTypes = allowedStreamTypes.getOrDefault(device, 0)
        val newStreamTypes =
            if (enabled) {
                currentStreamTypes or streamTypes
            } else {
                currentStreamTypes and streamTypes.inv()
            }
        allowedStreamTypes[device] = newStreamTypes
    }

    fun getEnabledStreamTypes(device: BluetoothDevice): Int {
        return allowedStreamTypes.getOrDefault(device, 0)
    }

    fun setAudioProxy(proxy: AudioProxy) {
        audioProxy = proxy
    }

    fun cleanup() {
        peerStreams.clear()
        allowedStreamTypes.clear()
        activeSinkDevice = null
        activeSourceDevice = null
    }

    fun handleDeviceConnected(device: BluetoothDevice) {
        peerStreams[device] = mutableMapOf()
    }

    fun handleDeviceDisconnected(device: BluetoothDevice) {
        if (device == activeSinkDevice) {
            val previous = activeSinkDevice
            activeSinkDevice = null
            service.updateActiveSinkDevice(null, previous)
        }
        if (device == activeSourceDevice) {
            val previous = activeSourceDevice
            activeSourceDevice = null
            service.updateActiveSourceDevice(null, previous)
        }

        val sessions = peerStreams.remove(device)
        Log.d(TAG, "Device $device disconnected, cleaning up ${sessions?.size ?: 0} sessions.")
        audioProxy?.handleDeviceDisconnected(device)
    }

    /**
     * Handles an incoming stream enable request from a peer device.
     *
     * This method contains the core arbitration logic for the peripheral service. It decides
     * whether to accept a new stream request based on the following policy:
     * 1. If there is no currently active stream, the request is accepted and the requesting device
     *    becomes the new active audio device.
     * 2. If the request is from the currently active device, the request is accepted.
     * 3. If the request is from a different device, its context priority is compared to the
     *    priority of the currently active stream. If the new request has a higher priority, the
     *    active stream is preempted (stopped) and the new stream is accepted. Otherwise, the new
     *    stream is rejected.
     */
    fun onStreamEnableRequest(device: BluetoothDevice, requests: List<StreamStartRequestInfo>) {
        Log.d(TAG, "onStreamEnableRequest for device $device, num_requests=${requests.size}")

        val requestPriority =
            requests.map { getContextPriority(it.audioContextType) }.maxOrNull() ?: 0
        val highestPriorityContext =
            requests.map { getHighestPriorityContext(it.audioContextType) }.maxOrNull() ?: 0

        if (!isStreamTypeAllowed(device, highestPriorityContext)) {
            Log.e(
                TAG,
                "onStreamEnableRequest: REJECTED - Stream type not allowed for device $device, context $highestPriorityContext",
            )
            nativeInterface.confirmStreamStartRequest(device, false)
            return
        }

        Log.d(
            TAG,
            "onStreamEnableRequest: Stream type allowed. Processing ${requests.size} requests.",
        )
        // Create stream objects on-demand
        requests.forEach {
            val sessions = peerStreams.getOrPut(device) { mutableMapOf() }
            val stream =
                sessions.getOrPut(it.streamId) { LeAudioStream(device, it.streamId, it.direction) }
            stream.processEvent(StreamEvent.EnableRequested)
        }

        var newSinkDevice: BluetoothDevice? = null
        var newSourceDevice: BluetoothDevice? = null
        var shouldConfirm = true

        requests.forEach {
            if (it.direction == StreamDirection.SINK) {
                val activeDevice = activeSinkDevice
                if (activeDevice == null) {
                    Log.d(TAG, "onStreamEnableRequest: No active SINK device. Activating $device")
                    newSinkDevice = device
                } else if (activeDevice == device) {
                    Log.d(
                        TAG,
                        "onStreamEnableRequest: Request for already-active SINK device $device.",
                    )
                    newSinkDevice = device
                } else {
                    Log.d(
                        TAG,
                        "onStreamEnableRequest: Request for new SINK device $device. Current active is $activeDevice. Checking priority.",
                    )
                    val activeStreamPriority = getActiveStreamContext(activeDevice)
                    if (requestPriority > activeStreamPriority) {
                        Log.d(TAG, "onStreamEnableRequest: Preempting active SINK device.")
                        preemptActiveStream(activeDevice)
                        newSinkDevice = device
                    } else {
                        Log.d(
                            TAG,
                            "onStreamEnableRequest: REJECTED - New SINK request has lower priority.",
                        )
                        shouldConfirm = false
                    }
                }
            } else { // StreamDirection.SOURCE
                val activeDevice = activeSourceDevice
                if (activeDevice == null) {
                    Log.d(TAG, "onStreamEnableRequest: No active SOURCE device. Activating $device")
                    newSourceDevice = device
                } else if (activeDevice == device) {
                    Log.d(
                        TAG,
                        "onStreamEnableRequest: Request for already-active SOURCE device $device.",
                    )
                    newSourceDevice = device
                } else {
                    Log.d(
                        TAG,
                        "onStreamEnableRequest: Request for new SOURCE device $device. Current active is $activeDevice. Checking priority.",
                    )
                    val activeStreamPriority = getActiveStreamContext(activeDevice)
                    if (requestPriority > activeStreamPriority) {
                        Log.d(TAG, "onStreamEnableRequest: Preempting active SOURCE device.")
                        preemptActiveStream(activeDevice)
                        newSourceDevice = device
                    } else {
                        Log.d(
                            TAG,
                            "onStreamEnableRequest: REJECTED - New SOURCE request has lower priority.",
                        )
                        shouldConfirm = false
                    }
                }
            }
        }

        if (shouldConfirm) {
            newSinkDevice?.let {
                val previous = activeSinkDevice
                activeSinkDevice = it
                service.updateActiveSinkDevice(it, previous)
            }
            newSourceDevice?.let {
                val previous = activeSourceDevice
                activeSourceDevice = it
                service.updateActiveSourceDevice(it, previous)
            }
            nativeInterface.confirmStreamStartRequest(device, true)
            requests.forEach {
                peerStreams[device]?.get(it.streamId)?.audioContextType = highestPriorityContext
            }
        } else {
            nativeInterface.confirmStreamStartRequest(device, false)
        }
    }

    fun onStreamDisableRequest(device: BluetoothDevice, streamId: Int) {
        Log.d(TAG, "onStreamDisableRequest for device $device, streamId $streamId")
        peerStreams[device]?.get(streamId)?.processEvent(StreamEvent.DisableRequested)
            ?: Log.e(TAG, "No stream found for onStreamDisableRequest: $device, $streamId")
    }

    fun onStreamStarted(device: BluetoothDevice, streamId: Int, audioContextType: Int) {
        peerStreams[device]?.get(streamId)?.processEvent(StreamEvent.StreamStarted)
    }

    fun onStreamStopped(device: BluetoothDevice, streamId: Int) {
        val stream = peerStreams[device]?.get(streamId)
        stream?.processEvent(StreamEvent.StreamStopped)

        if (stream?.direction == StreamDirection.SINK && device == activeSinkDevice) {
            val previous = activeSinkDevice
            activeSinkDevice = null
            service.updateActiveSinkDevice(null, previous)
        }
        if (stream?.direction == StreamDirection.SOURCE && device == activeSourceDevice) {
            val previous = activeSourceDevice
            activeSourceDevice = null
            service.updateActiveSourceDevice(null, previous)
        }
    }

    fun onStreamMetadataUpdated(device: BluetoothDevice, streamId: Int, audioContextType: Int) {
        val highestPriorityContext = getHighestPriorityContext(audioContextType)
        if (!isStreamTypeAllowed(device, highestPriorityContext)) {
            Log.e(
                TAG,
                "Stream type not allowed for device $device, context $highestPriorityContext. Stopping the Stream.",
            )
            nativeInterface.stopStream(device, streamId)
            return
        }

        peerStreams[device]?.get(streamId)?.run { this.audioContextType = highestPriorityContext }
    }

    private fun getHighestPriorityContext(contextType: Int): Int {
        var highestPriority = 0
        var highestPriorityContext = CONTEXT_TYPE_UNSPECIFIED
        CONTEXT_PRIORITIES.forEach { (type, priority) ->
            if ((contextType and type) != 0) {
                if (priority > highestPriority) {
                    highestPriority = priority
                    highestPriorityContext = type
                }
            }
        }
        return highestPriorityContext
    }

    private fun getContextPriority(contextType: Int): Int {
        var highestPriority = 0
        CONTEXT_PRIORITIES.forEach { (type, priority) ->
            if ((contextType and type) != 0) {
                if (priority > highestPriority) {
                    highestPriority = priority
                }
            }
        }
        return highestPriority
    }

    private fun getActiveStreamContext(device: BluetoothDevice): Int {
        val sessions = peerStreams[device] ?: return 0
        return sessions.values
            .filter { it.currentState == LeAudioStream.State.ACTIVE }
            .map { getContextPriority(it.audioContextType) }
            .maxOrNull() ?: 0
    }

    private fun preemptActiveStream(device: BluetoothDevice) {
        val sessions = peerStreams[device] ?: return
        sessions.values
            .filter { it.currentState == LeAudioStream.State.ACTIVE }
            .forEach { nativeInterface.stopStream(device, it.streamId) }
    }

    private fun isStreamTypeAllowed(device: BluetoothDevice, audioContextType: Int): Boolean {
        val allowedTypes = getEnabledStreamTypes(device)
        if (allowedTypes == 0) {
            return true
        }

        return STREAM_TYPE_CONTEXT_MAP.any { (streamType, contextTypes) ->
            (allowedTypes and streamType) != 0 && contextTypes.contains(audioContextType)
        }
    }

    companion object {
        private val TAG = PeripheralPolicyManager::class.java.simpleName

        private val CONTEXT_TYPE_UNSPECIFIED = 0x0001
        private val CONTEXT_TYPE_CONVERSATIONAL = 0x0002
        private val CONTEXT_TYPE_MEDIA = 0x0004
        private val CONTEXT_TYPE_GAME = 0x0008
        private val CONTEXT_TYPE_INSTRUCTIONAL = 0x0010
        private val CONTEXT_TYPE_VOICE_ASSISTANTS = 0x0020
        private val CONTEXT_TYPE_LIVE = 0x0040
        private val CONTEXT_TYPE_SOUND_EFFECTS = 0x0080
        private val CONTEXT_TYPE_NOTIFICATIONS = 0x0100
        private val CONTEXT_TYPE_RINGTONE = 0x0200
        private val CONTEXT_TYPE_ALERTS = 0x0400
        private val CONTEXT_TYPE_EMERGENCY_ALARM = 0x0800

        private val CONTEXT_PRIORITIES =
            mapOf(
                CONTEXT_TYPE_UNSPECIFIED to 0,
                CONTEXT_TYPE_MEDIA to 1,
                CONTEXT_TYPE_INSTRUCTIONAL to 1,
                CONTEXT_TYPE_GAME to 2,
                CONTEXT_TYPE_VOICE_ASSISTANTS to 2,
                CONTEXT_TYPE_LIVE to 2,
                CONTEXT_TYPE_SOUND_EFFECTS to 2,
                CONTEXT_TYPE_NOTIFICATIONS to 3,
                CONTEXT_TYPE_RINGTONE to 3,
                CONTEXT_TYPE_ALERTS to 3,
                CONTEXT_TYPE_EMERGENCY_ALARM to 3,
                CONTEXT_TYPE_CONVERSATIONAL to 4,
            )

        private val STREAM_TYPE_CONTEXT_MAP: Map<Int, List<Int>> =
            mapOf(
                BluetoothLeAudioPeripheral.STREAM_TYPE_CALL to
                    listOf(CONTEXT_TYPE_CONVERSATIONAL, CONTEXT_TYPE_RINGTONE),
                BluetoothLeAudioPeripheral.STREAM_TYPE_MEDIA to
                    listOf(
                        CONTEXT_TYPE_MEDIA,
                        CONTEXT_TYPE_INSTRUCTIONAL,
                        CONTEXT_TYPE_SOUND_EFFECTS,
                        CONTEXT_TYPE_NOTIFICATIONS,
                        CONTEXT_TYPE_ALERTS,
                        CONTEXT_TYPE_EMERGENCY_ALARM,
                    ),
                BluetoothLeAudioPeripheral.STREAM_TYPE_GAME to listOf(CONTEXT_TYPE_GAME),
                BluetoothLeAudioPeripheral.STREAM_TYPE_VOICE_ASSISTANT to
                    listOf(CONTEXT_TYPE_VOICE_ASSISTANTS),
                BluetoothLeAudioPeripheral.STREAM_TYPE_RECORDING to listOf(CONTEXT_TYPE_LIVE),
            )
    }
}

/** High-level representation of a single LE Audio stream. */
class LeAudioStream(
    val device: BluetoothDevice,
    val streamId: Int,
    val direction: StreamDirection,
    var audioContextType: Int = 0,
) {
    var currentState: State = State.INACTIVE
        private set

    fun processEvent(event: StreamEvent) {
        val nextState = currentState.processEvent(this, event)
        if (nextState != currentState) {
            Log.d(
                "LeAudioStream",
                "Stream $streamId for device $device transitioning from $currentState to $nextState",
            )
            currentState = nextState
        }
    }

    enum class State {
        INACTIVE,
        PENDING_ACTIVATION,
        ACTIVE,
        PENDING_DEACTIVATION;

        fun processEvent(stream: LeAudioStream, event: StreamEvent): State {
            return when (this) {
                INACTIVE ->
                    when (event) {
                        is StreamEvent.EnableRequested -> PENDING_ACTIVATION
                        else -> this
                    }
                PENDING_ACTIVATION ->
                    when (event) {
                        is StreamEvent.StreamStarted -> ACTIVE
                        else -> this
                    }
                ACTIVE ->
                    when (event) {
                        is StreamEvent.DisableRequested -> PENDING_DEACTIVATION
                        else -> this
                    }
                PENDING_DEACTIVATION ->
                    when (event) {
                        is StreamEvent.StreamStopped -> INACTIVE
                        else -> this
                    }
            }
        }
    }
}

/** Events that can drive the LeAudioStream state machine. */
sealed class StreamEvent {
    object EnableRequested : StreamEvent()

    object DisableRequested : StreamEvent()

    object StreamStarted : StreamEvent()

    object StreamStopped : StreamEvent()
}

/** Represents the direction of an LE Audio stream. */
enum class StreamDirection(val value: Int) {
    SINK(1),
    SOURCE(2);

    companion object {
        @JvmStatic
        fun from(findValue: Int): StreamDirection =
            StreamDirection.values().first { it.value == findValue }
    }
}

/** Represents the codec configuration for a stream. */
data class StreamConfig(val codecId: CodecId, val sampleRate: Int, val channelCount: Byte)

/** Represents a specific codec. */
data class CodecId(val codingFormat: Int, val vendorCompanyId: Int, val vendorCodecId: Int)
