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

package com.android.bluetooth.vcp

const val MIN_VOLUME = 0
const val MAX_VOLUME = 255
const val DEFAULT_VOLUME_STEP_SIZE = 10

enum class MuteState(val value: Int) {
    NOT_MUTED(0),
    MUTED(1);

    companion object {
        @JvmStatic
        fun fromInt(value: Int): MuteState? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class VolumeSettingPersisted(val value: Int) {
    RESET_VOLUME_SETTING(0),
    USER_SET_VOLUME_SETTING(1);

    companion object {
        @JvmStatic
        fun fromInt(value: Int): VolumeSettingPersisted? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

data class VcpRendererConfig(
    val initialVolume: Int,
    val initialMuteState: MuteState,
    val initialVolumeSettingPersisted: VolumeSettingPersisted,
    val volumeStepSize: Int,
) {
    init {
        require(initialVolume in MIN_VOLUME..MAX_VOLUME) {
            "initialVolume must be in range $MIN_VOLUME-$MAX_VOLUME"
        }
        require(volumeStepSize in MIN_VOLUME..MAX_VOLUME) {
            "volumeStepSize must be in range $MIN_VOLUME-$MAX_VOLUME"
        }
    }
}
