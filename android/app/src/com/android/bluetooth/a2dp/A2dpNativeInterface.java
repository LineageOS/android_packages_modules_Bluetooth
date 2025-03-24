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

/*
 * Defines the native interface that is used by state machine/service to
 * send or receive messages from the native stack. This file is registered
 * for the native methods in the corresponding JNI C++ file.
 */
package com.android.bluetooth.a2dp;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Native;
import java.util.Arrays;
import java.util.List;

/** A2DP Native Interface to/from JNI. */
public class A2dpNativeInterface {
    private static final String TAG = A2dpNativeInterface.class.getSimpleName();

    @Native private final A2dpNativeCallback mNativeCallback;

    private BluetoothCodecType[] mSupportedCodecTypes;

    @VisibleForTesting
    A2dpNativeInterface(@NonNull A2dpNativeCallback nativeCallback) {
        mNativeCallback = requireNonNull(nativeCallback);
    }

    /**
     * Initializes the native interface.
     *
     * @param maxConnectedAudioDevices maximum number of A2DP Sink devices that can be connected
     *     simultaneously
     * @param codecConfigPriorities an array with the codec configuration priorities to configure.
     */
    public void init(
            int maxConnectedAudioDevices,
            BluetoothCodecConfig[] codecConfigPriorities,
            BluetoothCodecConfig[] codecConfigOffloading) {
        initNative(maxConnectedAudioDevices, codecConfigPriorities, codecConfigOffloading);
    }

    /** Cleanup the native interface. */
    public void cleanup() {
        cleanupNative();
    }

    /** Returns the list of locally supported codec types. */
    public List<BluetoothCodecType> getSupportedCodecTypes() {
        if (mSupportedCodecTypes == null) {
            mSupportedCodecTypes = getSupportedCodecTypesNative();
        }
        return Arrays.asList(mSupportedCodecTypes);
    }

    /**
     * Initiates A2DP connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean connectA2dp(BluetoothDevice device) {
        return connectA2dpNative(getByteAddress(device));
    }

    /**
     * Disconnects A2DP from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean disconnectA2dp(BluetoothDevice device) {
        return disconnectA2dpNative(getByteAddress(device));
    }

    /**
     * Sets a connected A2DP remote device to silence mode.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean setSilenceDevice(BluetoothDevice device, boolean silence) {
        return setSilenceDeviceNative(getByteAddress(device), silence);
    }

    /**
     * Sets a connected A2DP remote device as active.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        return setActiveDeviceNative(getByteAddress(device));
    }

    /**
     * Sets the codec configuration preferences.
     *
     * @param device the remote Bluetooth device
     * @param codecConfigArray an array with the codec configurations to configure.
     * @return true on success, otherwise false.
     */
    public boolean setCodecConfigPreference(
            BluetoothDevice device, BluetoothCodecConfig[] codecConfigArray) {
        return setCodecConfigPreferenceNative(getByteAddress(device), codecConfigArray);
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getByteBrEdrAddress(device);
    }

    private native void initNative(
            int maxConnectedAudioDevices,
            BluetoothCodecConfig[] codecConfigPriorities,
            BluetoothCodecConfig[] codecConfigOffloading);

    private native void cleanupNative();

    private native BluetoothCodecType[] getSupportedCodecTypesNative();

    private native boolean connectA2dpNative(byte[] address);

    private native boolean disconnectA2dpNative(byte[] address);

    private native boolean setSilenceDeviceNative(byte[] address, boolean silence);

    private native boolean setActiveDeviceNative(byte[] address);

    private native boolean setCodecConfigPreferenceNative(
            byte[] address, BluetoothCodecConfig[] codecConfigArray);
}
