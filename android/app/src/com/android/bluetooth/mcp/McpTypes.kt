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

enum class MediaState(val value: Int) {
    INACTIVE(0x00),
    PLAYING(0x01),
    PAUSED(0x02),
    SEEKING(0x03);

    companion object {
        @JvmField val STATE_MIN = INACTIVE
        @JvmField val STATE_MAX = SEEKING

        @JvmStatic
        fun fromInt(value: Int): MediaState {
            return entries.firstOrNull { it.value == value } ?: INACTIVE
        }

        @JvmStatic
        fun toString(value: Int): String {
            return when (value) {
                0x00 -> "INACTIVE(0x00)"
                0x01 -> "PLAYING(0x01)"
                0x02 -> "PAUSED(0x02)"
                0x03 -> "SEEKING(0x03)"
                else -> "UNKNOWN(0x" + Integer.toHexString(value) + ")"
            }
        }
    }
}

enum class PlayingOrder(val value: Int) {
    SINGLE_ONCE(0x01),
    SINGLE_REPEAT(0x02),
    IN_ORDER_ONCE(0x03),
    IN_ORDER_REPEAT(0x04),
    OLDEST_ONCE(0x05),
    OLDEST_REPEAT(0x06),
    NEWEST_ONCE(0x07),
    NEWEST_REPEAT(0x08),
    SHUFFLE_ONCE(0x09),
    SHUFFLE_REPEAT(0x0A);

    companion object {
        @JvmStatic
        fun fromInt(value: Int): PlayingOrder? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class MediaControlResult(val value: Int) {
    SUCCESS(0x01),
    OPCODE_NOT_SUPPORTED(0x02),
    MEDIA_PLAYER_INACTIVE(0x03),
    COMMAND_CANNOT_BE_COMPLETED(0x04);

    companion object {
        @JvmStatic
        fun fromInt(value: Int): MediaControlResult? {
            return entries.firstOrNull { it.value == value }
        }
    }
}
