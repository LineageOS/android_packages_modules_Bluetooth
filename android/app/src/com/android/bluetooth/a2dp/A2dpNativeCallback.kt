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

package com.android.bluetooth.a2dp

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback
import com.android.internal.annotations.VisibleForTesting

class A2dpNativeCallback(adapterService: AdapterService, private val a2dpService: A2dpService) :
    NativeCallback(adapterService) {

    companion object {
        private val TAG: String = A2dpNativeCallback::class.java.simpleName

        // Match up with btav_audio_state_t enum of bt_av.h
        const val AUDIO_STATE_REMOTE_SUSPEND = 0
        const val AUDIO_STATE_STOPPED = 1
        const val AUDIO_STATE_STARTED = 2
    }

    @VisibleForTesting
    fun onConnectionStateChanged(address: ByteArray, state: Int, reason: Int) {
        a2dpService.onConnectionStateChangedFromNative(getDevice(address), state, reason)
    }

    @VisibleForTesting
    fun onAudioStateChanged(address: ByteArray, state: Int) {
        a2dpService.onAudioStateChangedFromNative(getDevice(address), state)
    }

    @VisibleForTesting
    fun onCodecConfigChanged(
        address: ByteArray,
        newCodecConfig: BluetoothCodecConfig,
        codecsLocalCapabilities: Array<BluetoothCodecConfig>,
        codecsSelectableCapabilities: Array<BluetoothCodecConfig>,
    ) {
        a2dpService.onCodecConfigChangedFromNative(
            getDevice(address),
            BluetoothCodecStatus(
                newCodecConfig,
                codecsLocalCapabilities.toList(),
                codecsSelectableCapabilities.toList(),
            ),
        )
    }

    fun onAudioDelayReported(address: ByteArray, audioDelay: Int) {
        a2dpService.onAudioDelayReportedFromNative(getDevice(address), audioDelay)
    }

    @VisibleForTesting
    fun isMandatoryCodecPreferred(address: ByteArray): Boolean {
        val enabled = a2dpService.getOptionalCodecsEnabled(getDevice(address))

        Log.d(TAG, "isMandatoryCodecPreferred: optional preference \$enabled")
        return enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
    }
}
