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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.bass_client.LtvData.AudioActiveState
import com.android.bluetooth.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [LtvData]. */
@RunWith(AndroidJUnit4::class)
class LtvDataTest {

    companion object {
        private const val AUDIO_ACTIVE_STATE_TYPE = 0x08
        private const val AUDIO_ACTIVE_STATE_LENGTH = 0x02
        private const val AUDIO_ACTIVE_STATE_TRUE = 0x01
        private const val AUDIO_ACTIVE_STATE_FALSE = 0x00

        private const val STREAMING_AUDIO_CONTEXTS_TYPE = 0x02
        private const val STREAMING_AUDIO_CONTEXTS_LENGTH = 0x03
    }

    @Test
    fun parse_nullMetadata() {
        val data = LtvData.parse(null)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_emptyMetadata() {
        val data = LtvData.parse(byteArrayOf())
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_audioActiveStateTrue() {
        val metadata =
            byteArrayOf(
                AUDIO_ACTIVE_STATE_LENGTH.toByte(),
                AUDIO_ACTIVE_STATE_TYPE.toByte(),
                AUDIO_ACTIVE_STATE_TRUE.toByte(),
            )
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.TRUE)
    }

    @Test
    fun parse_audioActiveStateFalse() {
        val metadata =
            byteArrayOf(
                AUDIO_ACTIVE_STATE_LENGTH.toByte(),
                AUDIO_ACTIVE_STATE_TYPE.toByte(),
                AUDIO_ACTIVE_STATE_FALSE.toByte(),
            )
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.FALSE)
    }

    @Test
    fun parse_unknownType() {
        val metadata = byteArrayOf(0x02.toByte(), 0xFE.toByte(), 0x01.toByte()) // Unknown type
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_invalidLength() {
        val metadata = byteArrayOf(0x03.toByte(), AUDIO_ACTIVE_STATE_TYPE.toByte(), 0x01.toByte())
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_truncatedMetadata() {
        val metadata =
            byteArrayOf(AUDIO_ACTIVE_STATE_LENGTH.toByte(), AUDIO_ACTIVE_STATE_TYPE.toByte())
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_streamingAudioContexts() {
        val contexts = 0x0401 // Example context
        val metadata =
            byteArrayOf(
                STREAMING_AUDIO_CONTEXTS_LENGTH.toByte(),
                STREAMING_AUDIO_CONTEXTS_TYPE.toByte(),
                0x01.toByte(), // LSB
                0x04.toByte(), // MSB
            )
        val data = LtvData.parse(metadata)
        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            assertThat(data.streamingAudioContexts).isEqualTo(contexts)
        } else {
            assertThat(data.streamingAudioContexts).isNull()
        }
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.NONE)
    }

    @Test
    fun parse_mixedMetadata() {
        val contexts = 0x0004 // Media
        val metadata =
            byteArrayOf(
                AUDIO_ACTIVE_STATE_LENGTH.toByte(),
                AUDIO_ACTIVE_STATE_TYPE.toByte(),
                AUDIO_ACTIVE_STATE_TRUE.toByte(),
                STREAMING_AUDIO_CONTEXTS_LENGTH.toByte(),
                STREAMING_AUDIO_CONTEXTS_TYPE.toByte(),
                0x04.toByte(), // LSB
                0x00.toByte(), // MSB
            )
        val data = LtvData.parse(metadata)
        assertThat(data.audioActiveState).isEqualTo(AudioActiveState.TRUE)
        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            assertThat(data.streamingAudioContexts).isEqualTo(contexts)
        } else {
            assertThat(data.streamingAudioContexts).isNull()
        }
    }
}
