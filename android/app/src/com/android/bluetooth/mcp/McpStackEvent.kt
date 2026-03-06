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

class McpStackEvent(val type: Int) {
    var device: BluetoothDevice? = null
    var valueInt1: Int = 0
    var valueInt2: Int = 0
    var valueInt3: Int = 0
    var valueString1: String? = null

    companion object {
        const val EVENT_TYPE_DISCOVERED = 1
        const val EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED = 2
        const val EVENT_TYPE_TRACK_CHANGED = 3
        const val EVENT_TYPE_TRACK_TITLE_CHANGED = 4
        const val EVENT_TYPE_TRACK_DURATION_CHANGED = 5
        const val EVENT_TYPE_TRACK_POSITION_CHANGED = 6
        const val EVENT_TYPE_PLAYBACK_SPEED_CHANGED = 7
        const val EVENT_TYPE_PLAYING_ORDER_CHANGED = 8
        const val EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED = 9
        const val EVENT_TYPE_SEEKING_SPEED_CHANGED = 10
        const val EVENT_TYPE_MEDIA_STATE_CHANGED = 11
        const val EVENT_TYPE_MEDIA_CONTROL_RESULT = 12
        const val EVENT_TYPE_OPCODES_SUPPORTED_CHANGED = 13
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("McpStackEvent {type:").append(eventTypeToString(type))
        result.append(", device:").append(device)
        result.append(", value1:").append(eventTypeValue1ToString(type, valueInt1))
        result.append(", value2:").append(eventTypeValue2ToString(type, valueInt2))
        result.append(", value3:").append(eventTypeValue3ToString(type, valueInt3))
        result.append(", string1:").append(eventTypeString1ToString(type, valueString1))
        result.append("}")
        return result.toString()
    }

    private fun eventTypeToString(type: Int): String {
        return when (type) {
            EVENT_TYPE_DISCOVERED -> "EVENT_TYPE_DISCOVERED"
            EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED -> "EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED"
            EVENT_TYPE_TRACK_CHANGED -> "EVENT_TYPE_TRACK_CHANGED"
            EVENT_TYPE_TRACK_TITLE_CHANGED -> "EVENT_TYPE_TRACK_TITLE_CHANGED"
            EVENT_TYPE_TRACK_DURATION_CHANGED -> "EVENT_TYPE_TRACK_DURATION_CHANGED"
            EVENT_TYPE_TRACK_POSITION_CHANGED -> "EVENT_TYPE_TRACK_POSITION_CHANGED"
            EVENT_TYPE_PLAYBACK_SPEED_CHANGED -> "EVENT_TYPE_PLAYBACK_SPEED_CHANGED"
            EVENT_TYPE_PLAYING_ORDER_CHANGED -> "EVENT_TYPE_PLAYING_ORDER_CHANGED"
            EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED ->
                "EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED"
            EVENT_TYPE_SEEKING_SPEED_CHANGED -> "EVENT_TYPE_SEEKING_SPEED_CHANGED"
            EVENT_TYPE_MEDIA_STATE_CHANGED -> "EVENT_TYPE_MEDIA_STATE_CHANGED"
            EVENT_TYPE_MEDIA_CONTROL_RESULT -> "EVENT_TYPE_MEDIA_CONTROL_RESULT"
            EVENT_TYPE_OPCODES_SUPPORTED_CHANGED -> "EVENT_TYPE_OPCODES_SUPPORTED_CHANGED"
            else -> "UNKNOWN"
        }
    }

    private fun eventTypeValue1ToString(type: Int, value: Int): String {
        return when (type) {
            EVENT_TYPE_DISCOVERED -> value.toString()
            else -> "{mediaControllerId: $value}"
        }
    }

    private fun eventTypeValue2ToString(type: Int, value: Int): String {
        return when (type) {
            EVENT_TYPE_TRACK_DURATION_CHANGED -> "{duration: $value}"
            EVENT_TYPE_TRACK_POSITION_CHANGED -> "{position: $value}"
            EVENT_TYPE_PLAYBACK_SPEED_CHANGED -> "{speed: $value}"
            EVENT_TYPE_PLAYING_ORDER_CHANGED ->
                "{playingOrder: " + (PlayingOrder.fromInt(value)?.name ?: value) + "}"
            EVENT_TYPE_PLAYING_ORDERS_SUPPORTED_CHANGED -> "{playingOrders: $value}"
            EVENT_TYPE_SEEKING_SPEED_CHANGED -> "{speed: $value}"
            EVENT_TYPE_MEDIA_STATE_CHANGED -> "{state: " + MediaState.toString(value) + "}"
            EVENT_TYPE_MEDIA_CONTROL_RESULT -> "{opcode: $value}"
            EVENT_TYPE_OPCODES_SUPPORTED_CHANGED -> "{opcodes: $value}"
            else -> value.toString()
        }
    }

    private fun eventTypeValue3ToString(type: Int, value: Int): String {
        return when (type) {
            EVENT_TYPE_MEDIA_CONTROL_RESULT ->
                "{result: " + (MediaControlResult.fromInt(value)?.name ?: value) + "}"
            else -> value.toString()
        }
    }

    private fun eventTypeString1ToString(type: Int, value: String?): String? {
        return when (type) {
            EVENT_TYPE_MEDIA_PLAYER_NAME_CHANGED -> "{name: $value}"
            EVENT_TYPE_TRACK_TITLE_CHANGED -> "{title: $value}"
            else -> value
        }
    }
}
