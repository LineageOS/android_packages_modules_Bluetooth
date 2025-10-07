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

package com.android.bluetooth.le_scan;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import java.lang.annotation.Native;

public class PeriodicScanNativeInterface {
    private static final int PA_SOURCE_LOCAL = 1;
    private static final int PA_SOURCE_REMOTE = 2;

    @Native private final PeriodicScanNativeCallback mNativeCallback;

    PeriodicScanNativeInterface(PeriodicScanNativeCallback nativeCallback) {
        mNativeCallback = requireNonNull(nativeCallback);
    }

    void init() {
        initializeNative();
    }

    void cleanup() {
        cleanupNative();
    }

    void startSync(int sid, String address, int skip, int timeout, int regId) {
        startSyncNative(sid, address, skip, timeout, regId);
    }

    void stopSync(int syncHandle) {
        stopSyncNative(syncHandle);
    }

    void cancelSync(int sid, String address) {
        cancelSyncNative(sid, address);
    }

    void syncTransfer(BluetoothDevice bda, int serviceData, int syncHandle) {
        syncTransferNative(PA_SOURCE_REMOTE, bda.getAddress(), serviceData, syncHandle);
    }

    void transferSetInfo(BluetoothDevice bda, int serviceData, int advHandle) {
        transferSetInfoNative(PA_SOURCE_LOCAL, bda.getAddress(), serviceData, advHandle);
    }

    private native void initializeNative();

    private native void cleanupNative();

    private native void startSyncNative(int sid, String address, int skip, int timeout, int regId);

    private native void stopSyncNative(int syncHandle);

    private native void cancelSyncNative(int sid, String address);

    private native void syncTransferNative(
            int paSource, String address, int serviceData, int syncHandle);

    private native void transferSetInfoNative(
            int paSource, String address, int serviceData, int advHandle);
}
