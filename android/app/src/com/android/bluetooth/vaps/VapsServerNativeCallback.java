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

import static com.android.bluetooth.vaps.VapsServerStackEvent.EVENT_TYPE_ON_INITIALIZED;
import static com.android.bluetooth.vaps.VapsServerStackEvent.EVENT_TYPE_ON_START_VA_SESSION;
import static com.android.bluetooth.vaps.VapsServerStackEvent.EVENT_TYPE_ON_STOP_VA_SESSION;

import static java.util.Objects.requireNonNull;

import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.NativeCallback;
import com.android.internal.annotations.VisibleForTesting;

/** Voice Assistant Profile Server Native Callback (from native to Java). */
public class VapsServerNativeCallback extends NativeCallback {
    private static final String TAG = VapsServerNativeCallback.class.getSimpleName();

    private final VapsServerService mVapsServerService;

    VapsServerNativeCallback(AdapterService adapterService, VapsServerService vapsServerService) {
        super(adapterService);
        mVapsServerService = requireNonNull(vapsServerService);
    }

    void onInitialized() {
        VapsServerStackEvent event = new VapsServerStackEvent(EVENT_TYPE_ON_INITIALIZED);
        Log.d(TAG, "onInitialized: " + event);
        mVapsServerService.messageFromNative(event);
    }

    @VisibleForTesting
    void onStartVaSession(byte[] address) {
        VapsServerStackEvent event = new VapsServerStackEvent(EVENT_TYPE_ON_START_VA_SESSION);
        event.device = getDevice(address);

        Log.d(TAG, "onStartVaSession: " + event);
        mVapsServerService.messageFromNative(event);
    }

    @VisibleForTesting
    void onStopVaSession(byte[] address) {
        VapsServerStackEvent event = new VapsServerStackEvent(EVENT_TYPE_ON_STOP_VA_SESSION);
        event.device = getDevice(address);

        Log.d(TAG, "onStopVaSession: " + event);
        mVapsServerService.messageFromNative(event);
    }
}
