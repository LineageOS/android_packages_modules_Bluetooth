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

package com.android.bluetooth.hearingaid

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.Util
import com.android.bluetooth.profile.NativeInterface
import com.android.internal.annotations.VisibleForTesting

class HearingAidNativeInterface(nativeCallback: HearingAidNativeCallback) :
    NativeInterface<HearingAidNativeCallback>(nativeCallback) {

    fun init() {
        initNative()
    }

    override fun cleanup() {
        cleanupNative()
    }

    /**
     * Initiates HearingAid connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun connectHearingAid(device: BluetoothDevice?): Boolean {
        return connectHearingAidNative(getByteAddress(device))
    }

    /**
     * Disconnects HearingAid from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun disconnectHearingAid(device: BluetoothDevice?): Boolean {
        return disconnectHearingAidNative(getByteAddress(device))
    }

    /**
     * Add a hearing aid device to acceptlist.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun addToAcceptlist(device: BluetoothDevice?): Boolean {
        return addToAcceptlistNative(getByteAddress(device))
    }

    /** Sets the HearingAid volume */
    fun setVolume(volume: Int) {
        setVolumeNative(volume)
    }

    @VisibleForTesting
    fun getByteAddress(device: BluetoothDevice?): ByteArray {
        if (device == null) {
            return Util.getBytesFromAddress("00:00:00:00:00:00")
        }
        return Util.getBytesFromAddress(device.address)
    }

    private external fun initNative()

    private external fun cleanupNative()

    private external fun connectHearingAidNative(address: ByteArray): Boolean

    private external fun disconnectHearingAidNative(address: ByteArray): Boolean

    private external fun addToAcceptlistNative(address: ByteArray): Boolean

    private external fun setVolumeNative(volume: Int)
}
