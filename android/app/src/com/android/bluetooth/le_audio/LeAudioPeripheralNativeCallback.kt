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

package com.android.bluetooth.le_audio

import android.util.Log
import com.android.bluetooth.btservice.AdapterService

internal class LeAudioPeripheralNativeCallback(
    private val adapterService: AdapterService,
    private val service: LeAudioPeripheralService,
) {

    fun onInitialized() {
        Log.d(TAG, "onInitialized")
        service.post { it.handleNativeEventNativeInitialized() }
    }

    fun onConnectionStateChanged(address: ByteArray, state: Int) {
        val device = adapterService.getDeviceFromByte(address)
        Log.d(
            TAG,
            "onConnectionStateChanged: state=${connectionStateToString(state)}, device=$device",
        )
        service.post { it.onConnectionStateChanged(device, state) }
    }

    fun onStreamStartRequest(address: ByteArray, requests: List<StreamStartRequestInfo>) {
        val device = adapterService.getDeviceFromByte(address)
        Log.d(TAG, "onStreamEnableRequest: device=$device, requests=$requests")
        service.post { service.onStreamEnableRequest(device, requests) }
    }

    fun onStreamStarted(address: ByteArray, streamId: Int, audioContextType: Int) {
        Log.d(TAG, "onStreamStarted")
        val device = adapterService.getDeviceFromByte(address)
        service.post { it.onStreamStarted(device, streamId, audioContextType) }
    }

    fun onStreamMetadataUpdated(address: ByteArray, streamId: Int, audioContextType: Int) {
        Log.d(TAG, "onStreamMetadataUpdated")
        val device = adapterService.getDeviceFromByte(address)
        service.post { it.onStreamMetadataUpdated(device, streamId, audioContextType) }
    }

    fun onSinkStreamReady(address: ByteArray) {
        Log.d(TAG, "onSinkStreamReady")
        val device = adapterService.getDeviceFromByte(address)
        service.post { it.onSinkStreamReady(device) }
    }

    fun onSourceStreamReady(address: ByteArray) {
        Log.d(TAG, "onSourceStreamReady")
        val device = adapterService.getDeviceFromByte(address)
        service.post { it.onSourceStreamReady(device) }
    }

    fun onStreamStopped(address: ByteArray, streamId: Int) {
        Log.d(TAG, "onStreamStopped")
        val device = adapterService.getDeviceFromByte(address)
        service.post { it.onStreamStopped(device, streamId) }
    }

    private fun connectionStateToString(state: Int): String =
        when (state) {
            CONNECTION_STATE_DISCONNECTED -> "CONNECTION_STATE_DISCONNECTED"
            CONNECTION_STATE_CONNECTED -> "CONNECTION_STATE_CONNECTED"
            else -> "UNKNOWN"
        }

    companion object {
        // Do not modify without updating the HAL bt_le_server_audio.h files.
        const val CONNECTION_STATE_DISCONNECTED = 0
        const val CONNECTION_STATE_CONNECTED = 1
        private val TAG = LeAudioPeripheralNativeCallback::class.java.simpleName
    }
}
