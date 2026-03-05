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
import com.android.internal.util.State
import com.android.internal.util.StateMachine
import java.time.Duration
import java.util.HashMap

class McpClientStateMachine(
    private val service: McpClientService,
    private val device: BluetoothDevice,
    private val nativeInterface: McpClientNativeInterface,
    looper: Looper,
) : StateMachine(TAG, looper) {

    private val disconnected = Disconnected()
    private val connecting = Connecting()
    private val disconnecting = Disconnecting()
    private val connected = Connected()

    private var connectionState = BluetoothProfile.STATE_DISCONNECTED
    private val mediaPlayers = HashMap<Int, MediaPlayerState>()

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
        }

        override fun exit() {
            logi("Exit: ${messageWhatToString(currentMessage?.what)}")
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
}
