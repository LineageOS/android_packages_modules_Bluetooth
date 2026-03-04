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

package com.android.bluetooth.media_audio.sink

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAudioUtilsTest {
    /* Test: audioFocusToString can handle all current AudioManager.AUDIOFOCUS_* constants */
    @Test
    fun audioFocusToString_validAudioFocusConstants_returnsFriendlyName() {
        assertThat(MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_NONE))
            .isEqualTo("NONE")
        assertThat(MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_GAIN))
            .isEqualTo("GAIN")
        assertThat(MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
            .isEqualTo("GAIN_TRANSIENT")
        assertThat(
                MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            )
            .isEqualTo("GAIN_TRANSIENT_MAY_DUCK")
        assertThat(
                MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            )
            .isEqualTo("GAIN_TRANSIENT_EXCLUSIVE")
        assertThat(MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_LOSS))
            .isEqualTo("LOSS")
        assertThat(MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
            .isEqualTo("LOSS_TRANSIENT")
        assertThat(
                MediaAudioUtils.audioFocusToString(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            )
            .isEqualTo("LOSS_TRANSIENT_CAN_DUCK")
    }

    /* Test: audioFocusToString can handle all values outside of the current
     * AudioManager.AUDIOFOCUS_* constants, returning UNKNOWN (<value>)
     */
    @Test
    fun audioFocusToString_invalidAudioFocusConstant_returnsFriendlyUnknownString() {
        assertThat(MediaAudioUtils.audioFocusToString(-100)).isEqualTo("UNKNOWN (-100)")
    }
}
