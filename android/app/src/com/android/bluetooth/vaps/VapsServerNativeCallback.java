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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.NativeCallback;
import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Consumer;

/** Voice Assistant Profile Server Native Callback (from native to Java). */
public class VapsServerNativeCallback extends NativeCallback {
    private static final String TAG = VapsServerNativeCallback.class.getSimpleName();

    private final VapsServerService mVapsServerService;
    private final Handler mHandler;

    VapsServerNativeCallback(AdapterService adapterService, VapsServerService vapsServerService) {
        super(adapterService);
        mVapsServerService = requireNonNull(vapsServerService);
        mHandler = new Handler(Looper.getMainLooper());
    }

    private void sendMessageToService(Consumer<VapsServerService> action) {
        mHandler.post(
                () -> {
                    if (!mVapsServerService.isAvailable()) {
                        Log.e(TAG, "Action ignored, service not available.");
                        return;
                    }
                    action.accept(mVapsServerService);
                });
    }

    void onInitialized() {
        Log.d(TAG, "onInitialized");
        sendMessageToService(service -> service.onInitialized());
    }

    @VisibleForTesting
    void onStartVaSession(byte[] address) {
        BluetoothDevice device = getDevice(address);
        Log.d(TAG, "onStartVaSession: device=" + device);
        sendMessageToService(service -> service.onStartVaSession(device));
    }

    @VisibleForTesting
    void onStopVaSession(byte[] address) {
        BluetoothDevice device = getDevice(address);
        Log.d(TAG, "onStopVaSession: device=" + device);
        sendMessageToService(service -> service.onStopVaSession(device));
    }
}
