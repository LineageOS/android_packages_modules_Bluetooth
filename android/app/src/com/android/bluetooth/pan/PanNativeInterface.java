/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.pan;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothPan;

import com.android.bluetooth.profile.NativeInterface;

/** Provides Bluetooth Pan native interface for the Pan service */
public class PanNativeInterface extends NativeInterface<PanNativeCallback> {

    PanNativeInterface(PanNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    void init() {
        initializeNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    boolean connect(byte[] identityAddress) {
        requireNonNull(identityAddress);
        return connectPanNative(
                identityAddress, BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE);
    }

    boolean disconnect(byte[] identityAddress) {
        requireNonNull(identityAddress);
        return disconnectPanNative(identityAddress);
    }

    private native void initializeNative();

    private native void cleanupNative();

    private native boolean connectPanNative(byte[] btAddress, int localRole, int remoteRole);

    private native boolean disconnectPanNative(byte[] btAddress);
}
