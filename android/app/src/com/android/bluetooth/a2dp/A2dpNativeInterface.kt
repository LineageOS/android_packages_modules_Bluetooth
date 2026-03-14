/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecType
import android.bluetooth.BluetoothDevice
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeInterface

/** A2DP Native Interface to/from JNI. */
class A2dpNativeInterface(
    private val adapterService: AdapterService,
    nativeCallback: A2dpNativeCallback,
) : NativeInterface<A2dpNativeCallback>(nativeCallback) {
    private var supportedCodecTypes: Array<BluetoothCodecType>? = null

    /**
     * Initializes the native interface.
     *
     * @param maxConnectedAudioDevices maximum number of A2DP Sink devices that can be connected
     *   simultaneously
     * @param codecConfigPriorities an array with the codec configuration priorities to configure.
     */
    fun init(
        maxConnectedAudioDevices: Int,
        codecConfigPriorities: Array<BluetoothCodecConfig>,
        codecConfigOffloading: Array<BluetoothCodecConfig>,
    ) {
        initNative(maxConnectedAudioDevices, codecConfigPriorities, codecConfigOffloading)
    }

    override fun cleanup() {
        cleanupNative()
    }

    /** Returns the list of locally supported codec types. */
    fun getSupportedCodecTypes(): List<BluetoothCodecType> {
        if (supportedCodecTypes == null) {
            supportedCodecTypes = getSupportedCodecTypesNative()
        }
        return supportedCodecTypes?.toList() ?: emptyList()
    }

    /**
     * Initiates A2DP connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun connectA2dp(device: BluetoothDevice): Boolean {
        return connectA2dpNative(getByteAddress(device))
    }

    /**
     * Disconnects A2DP from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun disconnectA2dp(device: BluetoothDevice): Boolean {
        return disconnectA2dpNative(getByteAddress(device))
    }

    /**
     * Sets a connected A2DP remote device to silence mode.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun setSilenceDevice(device: BluetoothDevice, silence: Boolean): Boolean {
        return setSilenceDeviceNative(getByteAddress(device), silence)
    }

    /**
     * Sets a connected A2DP remote device as active.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    fun setActiveDevice(device: BluetoothDevice?): Boolean {
        return setActiveDeviceNative(getByteAddress(device))
    }

    /**
     * Sets the codec configuration preferences.
     *
     * @param device the remote Bluetooth device
     * @param codecConfigArray an array with the codec configurations to configure.
     * @return true on success, otherwise false.
     */
    fun setCodecConfigPreference(
        device: BluetoothDevice?,
        codecConfigArray: Array<BluetoothCodecConfig>,
    ): Boolean {
        return setCodecConfigPreferenceNative(getByteAddress(device), codecConfigArray)
    }

    private fun getByteAddress(device: BluetoothDevice?): ByteArray {
        if (device == null) {
            return Util.getBytesFromAddress("00:00:00:00:00:00")
        }
        return adapterService.getByteBrEdrAddress(device)
    }

    private external fun initNative(
        maxConnectedAudioDevices: Int,
        codecConfigPriorities: Array<BluetoothCodecConfig>,
        codecConfigOffloading: Array<BluetoothCodecConfig>,
    )

    private external fun cleanupNative()

    private external fun getSupportedCodecTypesNative(): Array<BluetoothCodecType>

    private external fun connectA2dpNative(address: ByteArray): Boolean

    private external fun disconnectA2dpNative(address: ByteArray): Boolean

    private external fun setSilenceDeviceNative(address: ByteArray, silence: Boolean): Boolean

    private external fun setActiveDeviceNative(address: ByteArray): Boolean

    private external fun setCodecConfigPreferenceNative(
        address: ByteArray,
        codecConfigArray: Array<BluetoothCodecConfig>,
    ): Boolean
}
