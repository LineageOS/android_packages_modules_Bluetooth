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

import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

/**
 * Callback interface for the MCP Client Native Interface. These methods are called from the native
 * layer.
 */
class McpClientNativeCallback(
    adapterService: AdapterService,
    private val service: McpClientService,
) : NativeCallback(adapterService) {

    fun onConnectionStateChanged(address: ByteArray, state: Int) {
        val device = getDevice(address)
        Log.d(TAG, "onConnectionStateChanged: device=$device, state=$state")
        service.post { service.onConnectionStateChanged(device, state) }
    }

    fun onDiscovered(address: ByteArray) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_DISCOVERED)
        event.device = getDevice(address)
        Log.d(TAG, "onDiscovered: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onMediaPlayerNameChanged(address: ByteArray, mediaControllerId: Int, name: String) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueString1 = name
        Log.d(TAG, "onMediaPlayerNameChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onTrackChanged(address: ByteArray, mediaControllerId: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        Log.d(TAG, "onTrackChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onTrackTitleChanged(address: ByteArray, mediaControllerId: Int, title: String) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_TITLE_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueString1 = title
        Log.d(TAG, "onTrackTitleChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onTrackDurationChanged(address: ByteArray, mediaControllerId: Int, duration: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_DURATION_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = duration
        Log.d(TAG, "onTrackDurationChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onTrackPositionChanged(address: ByteArray, mediaControllerId: Int, position: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_TRACK_POSITION_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = position
        Log.d(TAG, "onTrackPositionChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onPlaybackSpeedChanged(address: ByteArray, mediaControllerId: Int, speed: Byte) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_PLAYBACK_SPEED_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = speed.toInt()
        Log.d(TAG, "onPlaybackSpeedChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onPlayingOrderChanged(address: ByteArray, mediaControllerId: Int, playingOrder: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_PLAYING_ORDER_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = playingOrder
        Log.d(TAG, "onPlayingOrderChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onPlayingOrdersSupportedChanged(
        address: ByteArray,
        mediaControllerId: Int,
        playingOrders: Int,
    ) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = playingOrders
        Log.d(TAG, "onPlayingOrdersSupportedChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onSeekingSpeedChanged(address: ByteArray, mediaControllerId: Int, speed: Byte) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_SEEKING_SPEED_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = speed.toInt()
        Log.d(TAG, "onSeekingSpeedChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onMediaStateChanged(address: ByteArray, mediaControllerId: Int, state: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_MEDIA_STATE_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = state
        Log.d(TAG, "onMediaStateChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onMediaControlResult(address: ByteArray, mediaControllerId: Int, opcode: Int, result: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_MEDIA_CONTROL_RESULT)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = opcode
        event.valueInt3 = result
        Log.d(TAG, "onMediaControlResult: $event")
        service.post { service.messageFromNative(event) }
    }

    fun onOpcodesSupportedChanged(address: ByteArray, mediaControllerId: Int, opcodes: Int) {
        val event = McpStackEvent(McpStackEvent.EVENT_TYPE_OPCODES_SUPPORTED_CHANGED)
        event.device = getDevice(address)
        event.valueInt1 = mediaControllerId
        event.valueInt2 = opcodes
        Log.d(TAG, "onOpcodesSupportedChanged: $event")
        service.post { service.messageFromNative(event) }
    }

    companion object {
        private val TAG = McpClientNativeCallback::class.java.simpleName
    }
}
