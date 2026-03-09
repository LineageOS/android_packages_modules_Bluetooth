/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.hearingaid;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;
import com.android.bluetooth.profile.NativeInterface;
import com.android.internal.annotations.VisibleForTesting;

public class HearingAidNativeInterface extends NativeInterface<HearingAidNativeCallback> {

    HearingAidNativeInterface(HearingAidNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    void init() {
        initNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    /**
     * Initiates HearingAid connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    boolean connectHearingAid(BluetoothDevice device) {
        return connectHearingAidNative(getByteAddress(device));
    }

    /**
     * Disconnects HearingAid from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    boolean disconnectHearingAid(BluetoothDevice device) {
        return disconnectHearingAidNative(getByteAddress(device));
    }

    /**
     * Add a hearing aid device to acceptlist.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    boolean addToAcceptlist(BluetoothDevice device) {
        return addToAcceptlistNative(getByteAddress(device));
    }

    /** Sets the HearingAid volume */
    void setVolume(int volume) {
        setVolumeNative(volume);
    }

    @VisibleForTesting
    byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private native void initNative();

    private native void cleanupNative();

    private native boolean connectHearingAidNative(byte[] address);

    private native boolean disconnectHearingAidNative(byte[] address);

    private native boolean addToAcceptlistNative(byte[] address);

    private native void setVolumeNative(int volume);
}
