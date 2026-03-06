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

import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

/**
 * Callback interface for the VCP Renderer Native Interface. These methods are called from the
 * native layer.
 */
class VcpRendererNativeCallback(
    adapterService: AdapterService,
    private val service: VcpRendererService,
) : NativeCallback(adapterService) {

    fun onInitialized() {
        Log.d(TAG, "onInitialized")
        service.post { service.onInitialized() }
    }

    fun onConnectionStateChanged(address: ByteArray, state: Int) {
        val device = getDevice(address)
        Log.d(TAG, "onConnectionStateChanged: device=$device, state=$state")
        service.post { service.onConnectionStateChanged(device, state) }
    }

    fun onVolumeStateChangeRequest(volume: Int, muteState: Int) {
        Log.d(TAG, "onVolumeStateChangeRequest: volume=$volume, muteState=$muteState")
        service.post { service.onVolumeStateChangeRequest(volume, muteState) }
    }

    companion object {
        private val TAG = VcpRendererNativeCallback::class.java.simpleName
    }
}
