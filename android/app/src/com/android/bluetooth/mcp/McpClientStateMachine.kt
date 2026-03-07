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

package com.android.bluetooth.mcp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Looper
import android.os.Message
import android.util.Log
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.media_audio.sink.MediaAudioServer
import com.android.bluetooth.media_audio.sink.MediaSource
import com.android.internal.util.State
import com.android.internal.util.StateMachine
import java.time.Duration
import java.util.HashMap

class McpClientStateMachine(
    private val service: McpClientService,
    private val device: BluetoothDevice,
    private val nativeInterface: McpClientNativeInterface,
    // TODO(Flags.mediaAudioServer): Remove ? when flag is cleaned up
    private val mediaAudioServer: MediaAudioServer?,
    looper: Looper,
) : StateMachine(TAG, looper) {

    private val disconnected = Disconnected()
    private val connecting = Connecting()
    private val disconnecting = Disconnecting()
    private val connected = Connected()

    private var connectionState = BluetoothProfile.STATE_DISCONNECTED
    private val mediaPlayers = HashMap<Int, MediaPlayerState>()
    private val mediaSource = McpClientMediaSource()

    init {
        addState(disconnected)
        addState(connecting)
        addState(disconnecting)
        addState(connected)
        setInitialState(disconnected)
        start()
    }

    fun doQuit() {
        quitNow()
    }

    fun cleanup() {
        // No cleanup required
    }

    fun getConnectionState(): Int {
        return connectionState
    }

    private inner class Disconnected : State() {
        override fun enter() {
            setMostRecentState(BluetoothProfile.STATE_DISCONNECTED)
            logi("Enter: ${messageWhatToString(currentMessage?.what)}")
            removeDeferredMessages(MESSAGE_DISCONNECT)
        }

        override fun exit() {
            logi("Exit: ${messageWhatToString(currentMessage?.what)}")
        }

        override fun processMessage(message: Message): Boolean {
            logd("Process message: ${messageWhatToString(message.what)}")
            when (message.what) {
                MESSAGE_CONNECT -> {
                    logi("Connecting")
                    nativeInterface.connect(device)
                    transitionTo(connecting)
                }
                MESSAGE_DISCONNECT -> {
                    logw("DISCONNECT ignored")
                }
                MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1)
                }
                MESSAGE_STACK_EVENT -> {
                    val event = message.obj as McpStackEvent
                    logw("Stack event not handled in Disconnected state: $event")
                }
                else -> return NOT_HANDLED
            }
            return HANDLED
        }

        private fun processConnectionEvent(state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (service.okToConnect(device)) {
                        logi("Incoming Connecting request accepted")
                        transitionTo(connecting)
                    } else {
                        logw("Incoming Connecting request rejected")
                        nativeInterface.disconnect(device)
                    }
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    logw("Connected from Disconnected state")
                    if (service.okToConnect(device)) {
                        logi("Incoming Connected request accepted")
                        setMostRecentState(BluetoothProfile.STATE_CONNECTING)
                        transitionTo(connected)
                    } else {
                        logw("Incoming Connected request rejected")
                        nativeInterface.disconnect(device)
                    }
                }
            }
        }
    }

    private inner class Connecting : State() {
        override fun enter() {
            setMostRecentState(BluetoothProfile.STATE_CONNECTING)
            logi("Enter: ${messageWhatToString(currentMessage?.what)}")
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis())
        }

        override fun exit() {
            logi("Exit: ${messageWhatToString(currentMessage?.what)}")
            removeMessages(MESSAGE_CONNECT_TIMEOUT)
        }

        override fun processMessage(message: Message): Boolean {
            logd("Process message: ${messageWhatToString(message.what)}")
            when (message.what) {
                MESSAGE_CONNECT -> logw("CONNECT ignored")
                MESSAGE_CONNECT_TIMEOUT -> {
                    logw("Connection timeout")
                    nativeInterface.disconnect(device)
                    transitionTo(disconnected)
                }
                MESSAGE_DISCONNECT -> {
                    logi("Connection canceled")
                    nativeInterface.disconnect(device)
                    transitionTo(disconnected)
                }
                MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1)
                }
                MESSAGE_STACK_EVENT -> {
                    val event = message.obj as McpStackEvent
                    logw("Stack event not handled in Connecting state: $event")
                }
                else -> return NOT_HANDLED
            }
            return HANDLED
        }

        private fun processConnectionEvent(state: Int) {
            when (state) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logw("Device disconnected")
                    transitionTo(disconnected)
                }
                BluetoothProfile.STATE_CONNECTED -> transitionTo(connected)
                BluetoothProfile.STATE_DISCONNECTING -> {
                    logw("Interrupted: device is disconnecting")
                    transitionTo(disconnecting)
                }
            }
        }
    }

    private inner class Connected : State() {
        override fun enter() {
            setMostRecentState(BluetoothProfile.STATE_CONNECTED)
            logi("Enter: ${messageWhatToString(currentMessage?.what)}")
            removeDeferredMessages(MESSAGE_CONNECT)
            // TODO(Flags.mediaAudioServer): Remove ? when flag is cleaned up
            mediaAudioServer?.registerMediaSource(mediaSource)
        }

        override fun exit() {
            logi("Exit: ${messageWhatToString(currentMessage?.what)}")
            // TODO(Flags.mediaAudioServer): Remove ? when flag is cleaned up
            mediaAudioServer?.unregisterMediaSource(mediaSource)
        }

        override fun processMessage(message: Message): Boolean {
            logd("Process message: ${messageWhatToString(message.what)}")
            when (message.what) {
                MESSAGE_CONNECT -> logw("CONNECT ignored")
                MESSAGE_DISCONNECT -> {
                    logi("Disconnecting")
                    nativeInterface.disconnect(device)
                    transitionTo(disconnecting)
                }
                MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1)
                }
                MESSAGE_STACK_EVENT -> {
                    val event = message.obj as McpStackEvent
                    logd("Stack event: $event")
                    processMcpEvent(event)
                }
                else -> return NOT_HANDLED
            }
            return HANDLED
        }

        private fun processConnectionEvent(state: Int) {
            when (state) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logi("Disconnected")
                    setMostRecentState(BluetoothProfile.STATE_DISCONNECTING)
                    transitionTo(disconnected)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    logi("Disconnecting")
                    transitionTo(disconnecting)
                }
            }
        }
    }

    private inner class Disconnecting : State() {
        override fun enter() {
            setMostRecentState(BluetoothProfile.STATE_DISCONNECTING)
            logi("Enter: ${messageWhatToString(currentMessage?.what)}")
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis())
        }

        override fun exit() {
            logi("Exit: ${messageWhatToString(currentMessage?.what)}")
            removeMessages(MESSAGE_CONNECT_TIMEOUT)
        }

        override fun processMessage(message: Message): Boolean {
            logd("Process message: ${messageWhatToString(message.what)}")
            when (message.what) {
                MESSAGE_CONNECT -> {
                    if (!hasDeferredMessages(MESSAGE_CONNECT)) {
                        deferMessage(message)
                    } else {
                        logd("Connect already scheduled")
                    }
                }
                MESSAGE_DISCONNECT -> {
                    logi("Disconnect is ongoing")
                    if (hasDeferredMessages(MESSAGE_CONNECT)) {
                        logd("Removing scheduled connect")
                        removeDeferredMessages(MESSAGE_CONNECT)
                    }
                }
                MESSAGE_CONNECT_TIMEOUT -> {
                    logw("Connection timeout")
                    nativeInterface.disconnect(device)
                    transitionTo(disconnected)
                }
                MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1)
                }
                MESSAGE_STACK_EVENT -> {
                    val event = message.obj as McpStackEvent
                    logw("Stack event not handled in Disconnecting state: $event")
                }
                else -> return NOT_HANDLED
            }
            return HANDLED
        }

        private fun processConnectionEvent(state: Int) {
            when (state) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logi("Disconnected")
                    transitionTo(disconnected)
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    if (service.okToConnect(device)) {
                        logw("Interrupted: device is connected")
                        transitionTo(connected)
                    } else {
                        logw("Incoming Connected request rejected")
                        nativeInterface.disconnect(device)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    if (service.okToConnect(device)) {
                        logi("Interrupted: try to reconnect")
                        transitionTo(connecting)
                    } else {
                        logw("Incoming Connecting request rejected")
                        nativeInterface.disconnect(device)
                    }
                }
            }
        }
    }

    private fun processMcpEvent(event: McpStackEvent) {
        if (event.type == McpStackEvent.EVENT_TYPE_DISCOVERED) {
            mediaPlayers.clear()
            return
        }

        val id = event.valueInt1
        val state = mediaPlayers.getOrPut(id) { MediaPlayerState(id) }

        when (event.type) {
            McpStackEvent.EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED -> state.name = event.valueString1
            McpStackEvent.EVENT_TYPE_TRACK_TITLE_CHANGED -> state.trackTitle = event.valueString1
            McpStackEvent.EVENT_TYPE_TRACK_DURATION_CHANGED -> state.trackDuration = event.valueInt2
            McpStackEvent.EVENT_TYPE_TRACK_POSITION_CHANGED -> state.trackPosition = event.valueInt2
            McpStackEvent.EVENT_TYPE_PLAYBACK_SPEED_CHANGED -> state.playbackSpeed = event.valueInt2
            McpStackEvent.EVENT_TYPE_PLAYING_ORDER_CHANGED ->
                state.playingOrder = PlayingOrder.fromInt(event.valueInt2)
            McpStackEvent.EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED ->
                state.supportedPlayingOrders = event.valueInt2
            McpStackEvent.EVENT_TYPE_SEEKING_SPEED_CHANGED -> state.seekingSpeed = event.valueInt2
            McpStackEvent.EVENT_TYPE_MEDIA_STATE_CHANGED ->
                state.state = MediaState.fromInt(event.valueInt2)
            McpStackEvent.EVENT_TYPE_OPCODES_SUPPORTED_CHANGED ->
                state.supportedOpcodes = event.valueInt2
        }

        if (id == GMCS_ID) {
            updateMediaSource(state)
        }
    }

    private fun setMostRecentState(newState: Int) {
        if (connectionState != newState) {
            val oldState = connectionState
            connectionState = newState
            logd(
                "Connection state changed: ${BluetoothProfile.getConnectionStateName(oldState)}" +
                    " -> ${BluetoothProfile.getConnectionStateName(newState)}"
            )
            service.connectionStateChanged(device, oldState, newState)
        }
    }

    private fun getLogMessage(message: String): String {
        return "[$device] ${BluetoothProfile.getConnectionStateName(connectionState)}: $message"
    }

    override fun logv(message: String) {
        Log.v(TAG, getLogMessage(message))
    }

    override fun logd(message: String) {
        Log.d(TAG, getLogMessage(message))
    }

    override fun logi(message: String) {
        Log.i(TAG, getLogMessage(message))
    }

    override fun logw(message: String) {
        Log.w(TAG, getLogMessage(message))
    }

    override fun loge(message: String) {
        Log.e(TAG, getLogMessage(message))
    }

    fun dump(sb: StringBuilder) {
        sb.appendLine("  Device: $device")
        sb.appendLine("  Connection State: $connectionState")
        sb.appendLine("  Media Players: ${mediaPlayers.size}")
        for ((id, player) in mediaPlayers) {
            sb.appendLine("    Player ID: $id")
            sb.appendLine("      Name: ${player.name}")
            sb.appendLine("      State: ${player.state}")
        }
    }

    companion object {
        private val TAG = McpClientStateMachine::class.java.simpleName

        const val MESSAGE_CONNECT = 1
        const val MESSAGE_DISCONNECT = 2
        const val MESSAGE_CONNECTION_STATE_CHANGED = 101
        const val MESSAGE_STACK_EVENT = 102
        const val MESSAGE_CONNECT_TIMEOUT = 201

        private val CONNECT_TIMEOUT = Duration.ofSeconds(30)

        private fun messageWhatToString(what: Int?): String {
            return when (what) {
                MESSAGE_CONNECT -> "CONNECT"
                MESSAGE_DISCONNECT -> "DISCONNECT"
                MESSAGE_CONNECTION_STATE_CHANGED -> "CONNECTION_STATE_CHANGED"
                MESSAGE_STACK_EVENT -> "STACK_EVENT"
                MESSAGE_CONNECT_TIMEOUT -> "CONNECT_TIMEOUT"
                null -> "null"
                else -> what.toString()
            }
        }

        private data class MediaPlayerState(
            val id: Int,
            var name: String? = null,
            var trackTitle: String? = null,
            var trackDuration: Int? = null,
            var trackPosition: Int? = null,
            var playbackSpeed: Int? = null,
            var playingOrder: PlayingOrder? = null,
            var supportedPlayingOrders: Int? = null,
            var seekingSpeed: Int? = null,
            var state: MediaState? = null,
            var supportedOpcodes: Int? = null,
        )
    }

    private fun updateMediaSource(state: MediaPlayerState) {
        if (!Flags.mediaAudioServer()) {
            return
        }

        val metadata =
            MediaSource.Metadata(
                title = state.trackTitle,
                artist = null,
                album = null,
                trackNumber = 0,
                totalNumberOfTracks = 0,
                genre = null,
                duration = state.trackDuration?.toLong() ?: 0L,
                imageUri = null,
            )
        if (metadata != mediaSource.getMetadata()) {
            mediaSource.setMetadata(metadata)
        }

        val playbackStatus =
            MediaSource.PlaybackStatus(
                state =
                    state.state?.let { mediaStateToPlaybackState(it) }
                        ?: MediaSource.PlaybackState.UNKNOWN,
                playbackPosition = state.trackPosition?.toLong() ?: 0L,
                playbackSpeed = (state.playbackSpeed?.toFloat() ?: 100f) / 100f,
                activeQueueId = -1L,
                availableActions = getAvailableActions(state),
                shuffleMode =
                    state.playingOrder?.let { playingOrderToShuffleMode(it) }
                        ?: MediaSource.ShuffleMode.OFF,
                repeatMode =
                    state.playingOrder?.let { playingOrderToRepeatMode(it) }
                        ?: MediaSource.RepeatMode.OFF,
            )
        if (playbackStatus != mediaSource.getPlaybackStatus()) {
            mediaSource.setPlaybackStatus(playbackStatus)
        }
    }

    private fun mediaStateToPlaybackState(state: MediaState): MediaSource.PlaybackState {
        return when (state) {
            MediaState.INACTIVE -> MediaSource.PlaybackState.STOPPED
            MediaState.PLAYING -> MediaSource.PlaybackState.PLAYING
            MediaState.PAUSED -> MediaSource.PlaybackState.PAUSED
            MediaState.SEEKING -> MediaSource.PlaybackState.FAST_FORWARDING
        }
    }

    private fun getAvailableActions(state: MediaPlayerState): List<MediaSource.PlayerAction> {
        val actions = mutableListOf<MediaSource.PlayerAction>()
        val opcodes = state.supportedOpcodes ?: 0
        if (opcodes and (1 shl McpOpcode.PLAY.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.PLAY)
        if (opcodes and (1 shl McpOpcode.PAUSE.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.PAUSE)
        if (opcodes and (1 shl McpOpcode.FAST_REWIND.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.REWIND)
        if (opcodes and (1 shl McpOpcode.FAST_FORWARD.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.FAST_FORWARD)
        if (opcodes and (1 shl McpOpcode.STOP.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.STOP)
        if (opcodes and (1 shl McpOpcode.NEXT_TRACK.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.NEXT)
        if (opcodes and (1 shl McpOpcode.PREVIOUS_TRACK.supportedOpcodeBit) != 0)
            actions.add(MediaSource.PlayerAction.PREVIOUS)

        val playingOrders = state.supportedPlayingOrders ?: 0
        val repeatMask =
            (1 shl (PlayingOrder.SINGLE_REPEAT.value - 1)) or
                (1 shl (PlayingOrder.IN_ORDER_REPEAT.value - 1)) or
                (1 shl (PlayingOrder.OLDEST_REPEAT.value - 1)) or
                (1 shl (PlayingOrder.NEWEST_REPEAT.value - 1)) or
                (1 shl (PlayingOrder.SHUFFLE_REPEAT.value - 1))
        if (playingOrders and repeatMask != 0) {
            actions.add(MediaSource.PlayerAction.REPEAT)
        }
        val shuffleMask =
            (1 shl (PlayingOrder.SHUFFLE_ONCE.value - 1)) or
                (1 shl (PlayingOrder.SHUFFLE_REPEAT.value - 1))
        if (playingOrders and shuffleMask != 0) {
            actions.add(MediaSource.PlayerAction.SHUFFLE)
        }
        return actions
    }

    private fun playingOrderToShuffleMode(order: PlayingOrder): MediaSource.ShuffleMode {
        return when (order) {
            PlayingOrder.SHUFFLE_ONCE,
            PlayingOrder.SHUFFLE_REPEAT -> MediaSource.ShuffleMode.ALL
            else -> MediaSource.ShuffleMode.OFF
        }
    }

    private fun playingOrderToRepeatMode(order: PlayingOrder): MediaSource.RepeatMode {
        return when (order) {
            PlayingOrder.SINGLE_REPEAT -> MediaSource.RepeatMode.ONE
            PlayingOrder.IN_ORDER_REPEAT,
            PlayingOrder.OLDEST_REPEAT,
            PlayingOrder.NEWEST_REPEAT,
            PlayingOrder.SHUFFLE_REPEAT -> MediaSource.RepeatMode.ALL
            else -> MediaSource.RepeatMode.OFF
        }
    }

    private fun resolvePlayingOrder(
        current: PlayingOrder,
        reqRepeat: MediaSource.RepeatMode?,
        reqShuffle: MediaSource.ShuffleMode?,
    ): PlayingOrder {
        var isRepeatOne = false
        var isRepeatAll = false
        var isShuffle = false

        if (reqRepeat != null) {
            isRepeatOne = (reqRepeat == MediaSource.RepeatMode.ONE)
            isRepeatAll =
                (reqRepeat == MediaSource.RepeatMode.ALL ||
                    reqRepeat == MediaSource.RepeatMode.GROUP)

            isShuffle =
                when (current) {
                    PlayingOrder.SHUFFLE_ONCE,
                    PlayingOrder.SHUFFLE_REPEAT -> true
                    else -> false
                }
        } else if (reqShuffle != null) {
            isShuffle =
                (reqShuffle == MediaSource.ShuffleMode.ALL ||
                    reqShuffle == MediaSource.ShuffleMode.GROUP)

            isRepeatOne = current == PlayingOrder.SINGLE_REPEAT
            isRepeatAll =
                when (current) {
                    PlayingOrder.IN_ORDER_REPEAT,
                    PlayingOrder.OLDEST_REPEAT,
                    PlayingOrder.NEWEST_REPEAT,
                    PlayingOrder.SHUFFLE_REPEAT -> true
                    else -> false
                }
        }

        if (isRepeatOne) return PlayingOrder.SINGLE_REPEAT
        if (isShuffle) {
            return if (isRepeatAll) PlayingOrder.SHUFFLE_REPEAT else PlayingOrder.SHUFFLE_ONCE
        }
        return if (isRepeatAll) PlayingOrder.IN_ORDER_REPEAT else PlayingOrder.IN_ORDER_ONCE
    }

    private inner class McpClientMediaSource :
        MediaSource(device, MediaSource.Protocol.MCP_CLIENT) {
        override fun onPrepare() {
            logd("MediaSource: onPrepare; not used")
        }

        override fun onPlay() {
            logd("MediaSource: onPlay")
            nativeInterface.play(device, GMCS_ID)
        }

        override fun onPause() {
            logd("MediaSource: onPause")
            nativeInterface.pause(device, GMCS_ID)
        }

        override fun onSkipToNext() {
            logd("MediaSource: onSkipToNext")
            nativeInterface.nextTrack(device, GMCS_ID)
        }

        override fun onSkipToPrevious() {
            logd("MediaSource: onSkipToPrevious")
            nativeInterface.previousTrack(device, GMCS_ID)
        }

        override fun onSkipToQueueItem(id: Long) {
            logd("MediaSource: onSkipToQueueItem id=$id; not supported")
            // Not supported by McpClientNativeInterface
        }

        override fun onStop() {
            logd("MediaSource: onStop")
            nativeInterface.stop(device, GMCS_ID)
        }

        override fun onFastForward() {
            logd("MediaSource: onFastForward")
            nativeInterface.fastForward(device, GMCS_ID)
        }

        override fun onRewind() {
            logd("MediaSource: onRewind")
            nativeInterface.fastRewind(device, GMCS_ID)
        }

        override fun onPlayFromMediaId(id: String) {
            logd("MediaSource: onPlayFromMediaId id=$id; not supported")
            // Not supported by McpClientNativeInterface
        }

        override fun onSetRepeatMode(mode: MediaSource.RepeatMode) {
            logd("MediaSource: onSetRepeatMode mode=$mode")
            val currentOrder = mediaPlayers[GMCS_ID]?.playingOrder ?: PlayingOrder.IN_ORDER_ONCE
            val newOrder = resolvePlayingOrder(currentOrder, mode, null)
            nativeInterface.setPlayingOrder(device, GMCS_ID, newOrder)
        }

        override fun onSetShuffleMode(mode: MediaSource.ShuffleMode) {
            logd("MediaSource: onSetShuffleMode mode=$mode")
            val currentOrder = mediaPlayers[GMCS_ID]?.playingOrder ?: PlayingOrder.IN_ORDER_ONCE
            val newOrder = resolvePlayingOrder(currentOrder, null, mode)
            nativeInterface.setPlayingOrder(device, GMCS_ID, newOrder)
        }
    }
}
