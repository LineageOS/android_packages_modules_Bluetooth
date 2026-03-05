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

import com.android.bluetooth.profile.NativeInterface

/** Native interface for the VCP Renderer. */
class VcpRendererNativeInterface(nativeCallback: VcpRendererNativeCallback) :
    NativeInterface<VcpRendererNativeCallback>(nativeCallback) {

    fun init(config: VcpRendererConfig) {
        initNative(config)
    }

    override fun cleanup() {
        cleanupNative()
    }

    fun updateVolumeState(volume: Int, muteState: MuteState) {
        updateVolumeStateNative(volume, muteState.value)
    }

    fun updateVolumeFlags(volumeSettingPersisted: VolumeSettingPersisted) {
        updateVolumeFlagsNative(volumeSettingPersisted.value)
    }

    private external fun initNative(config: VcpRendererConfig)

    private external fun cleanupNative()

    private external fun updateVolumeStateNative(volume: Int, muteState: Int)

    private external fun updateVolumeFlagsNative(volumeSettingPersisted: Int)
}
