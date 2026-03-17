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

import com.android.bluetooth.Util.getByteAddress

class LeAudioPeripheralNativeInterface
internal constructor(internal val nativeCallback: LeAudioPeripheralNativeCallback) {

    fun init() {
        initNative()
    }

    fun cleanup() {
        cleanupNative()
    }

    fun confirmStreamStartRequest(device: android.bluetooth.BluetoothDevice, allowed: Boolean) {
        confirmStreamStartRequestNative(device.getByteAddress(), allowed)
    }

    fun stopStream(device: android.bluetooth.BluetoothDevice, streamId: Int) {
        stopStreamNative(device.getByteAddress(), streamId)
    }

    // Native methods that call into the JNI interface
    private external fun initNative()

    private external fun cleanupNative()

    private external fun confirmStreamStartRequestNative(address: ByteArray, allowed: Boolean)

    private external fun stopStreamNative(address: ByteArray, streamId: Int)
}
