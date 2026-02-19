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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_audio.LeAudioPeripheralService
import com.android.bluetooth.profile.ProfileService

/** Service for the Volume Control Profile (VCP) Renderer. */
class VcpRendererService
@JvmOverloads
constructor(
    adapterService: AdapterService,
    looper: Looper = Looper.getMainLooper(),
    initNativeInterface: VcpRendererNativeInterface? = null,
) : ProfileService(BluetoothProfile.VCP_RENDERER, adapterService) {

    private val handler: Handler = Handler(looper)
    private val nativeCallback = VcpRendererNativeCallback(adapterService, this)
    private var nativeInterface: VcpRendererNativeInterface =
        initNativeInterface ?: VcpRendererNativeInterface(nativeCallback)

    init {
        Log.d(TAG, "init()")
        val rendererConfig =
            VcpRendererConfig(
                initialVolume = MIN_VOLUME,
                initialMuteState = MuteState.NOT_MUTED,
                initialVolumeSettingPersisted = VolumeSettingPersisted.RESET_VOLUME_SETTING,
                volumeStepSize = DEFAULT_VOLUME_STEP_SIZE,
            )
        nativeInterface.init(rendererConfig)
    }

    override fun cleanup() {
        Log.d(TAG, "cleanup()")
        nativeInterface.cleanup()
    }

    override fun initBinder(): IProfileServiceBinder? {
        return null
    }

    fun post(consumer: (VcpRendererService) -> Unit) {
        handler.post {
            if (!isAvailable) {
                Log.e(TAG, "Service is no longer available")
                return@post
            }
            consumer.invoke(this)
        }
    }

    fun onInitialized() {
        Log.d(TAG, "onInitialized")
    }

    fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        Log.d(TAG, "onConnectionStateChanged: device=$device, state=$state")
    }

    fun onVolumeStateChangeRequest(volume: Int, muteState: Int) {
        Log.d(TAG, "onVolumeStateChangeRequest: volume=$volume, muteState=$muteState")
    }

    fun updateVolumeState(volume: Int, muteState: MuteState) {
        nativeInterface.updateVolumeState(volume, muteState)
    }

    fun updateVolumeFlags(volumeSettingPersisted: VolumeSettingPersisted) {
        nativeInterface.updateVolumeFlags(volumeSettingPersisted)
    }

    override fun dump(sb: StringBuilder) {
        super.dump(sb)
    }

    companion object {
        private val TAG = VcpRendererService::class.java.simpleName

        @JvmStatic
        fun isEnabled(): Boolean {
            return Flags.leaudioPeripheralVcpLinkAbstractionLayer() &&
                LeAudioPeripheralService.isEnabled()
        }
    }
}
