/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.vaps;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;
import com.android.bluetooth.profile.NativeInterface;

/** Voice Assistant Profile Server Native Interface to/from JNI. */
public class VapsServerNativeInterface extends NativeInterface<VapsServerNativeCallback> {
    private static final String TAG = VapsServerNativeInterface.class.getSimpleName();

    public VapsServerNativeInterface(VapsServerNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    void init() {
        initNative();
    }

    void setCcid(int ccid) {
        setCcidNative(ccid);
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    void setVaeName(String vaeName) {
        setVaeNameNative(vaeName);
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void setCcidNative(int ccid);

    private native void cleanupNative();

    private native void setVaeNameNative(String vaeName);
}
