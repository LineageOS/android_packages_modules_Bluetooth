/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.bass_client

import android.util.Log
import com.android.bluetooth.flags.Flags

/**
 * Represents and parses Length-Type-Value (LTV) structured data from broadcast metadata.
 *
 * Use [LtvData.parse] to create an instance from a raw byte array.
 */
class LtvData
private constructor(val audioActiveState: AudioActiveState, val streamingAudioContexts: Int?) {
    override fun toString(): String =
        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            "LtvData: audioActiveState: $audioActiveState, streamingAudioContexts: $streamingAudioContexts"
        } else {
            "LtvData: audioActiveState: $audioActiveState"
        }

    /** Represents the audio active state in the LTV data. */
    enum class AudioActiveState {
        NONE, // No AudioActiveState information
        FALSE, // No audio data is being transmitted
        TRUE, // Audio data is being transmitted
    }

    companion object {
        private val TAG = BassClientService.TAG + "." + LtvData::class.java.simpleName

        private const val METADATA_LEN_MIN = 2
        private const val AUDIO_ACTIVE_STATE_TYPE = 0x08
        private const val AUDIO_ACTIVE_STATE_LENGTH = 0x02
        private const val AUDIO_ACTIVE_STATE_TRUE = 0x01

        private const val STREAMING_AUDIO_CONTEXTS_TYPE = 0x02
        private const val STREAMING_AUDIO_CONTEXTS_LENGTH = 0x03

        @JvmStatic
        fun parse(metadata: ByteArray?): LtvData {
            var audioActiveState = AudioActiveState.NONE
            var streamingAudioContexts: Int? = null
            if (metadata == null) {
                return LtvData(audioActiveState, streamingAudioContexts)
            }
            var offset = 0
            while (offset + METADATA_LEN_MIN <= metadata.size) {
                val length = metadata[offset++].toInt() and 0xFF
                if (length == 0 || offset + length > metadata.size) {
                    Log.e(TAG, "Invalid metadata entry length")
                    break
                }
                val type = metadata[offset++].toInt() and 0xFF
                if (type == AUDIO_ACTIVE_STATE_TYPE && length == AUDIO_ACTIVE_STATE_LENGTH) {
                    val value = metadata[offset].toInt() and 0xFF
                    audioActiveState =
                        if (value == AUDIO_ACTIVE_STATE_TRUE) AudioActiveState.TRUE
                        else AudioActiveState.FALSE
                } else if (
                    Flags.leaudioBroadcastAutoSwitchAnnouncement() &&
                        type == STREAMING_AUDIO_CONTEXTS_TYPE &&
                        length == STREAMING_AUDIO_CONTEXTS_LENGTH
                ) {
                    streamingAudioContexts =
                        (metadata[offset].toInt() and 0xFF) or
                            ((metadata[offset + 1].toInt() and 0xFF) shl 8)
                }
                offset += length - 1
            }
            return LtvData(audioActiveState, streamingAudioContexts)
        }
    }
}
