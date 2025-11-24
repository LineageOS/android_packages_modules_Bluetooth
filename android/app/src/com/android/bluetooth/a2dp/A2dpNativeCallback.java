/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.a2dp;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.NativeCallback;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

class A2dpNativeCallback extends NativeCallback {
    private static final String TAG = A2dpNativeCallback.class.getSimpleName();

    // Match up with btav_audio_state_t enum of bt_av.h
    static final int AUDIO_STATE_REMOTE_SUSPEND = 0;
    static final int AUDIO_STATE_STOPPED = 1;
    static final int AUDIO_STATE_STARTED = 2;

    private final A2dpService mA2dpService;

    @VisibleForTesting
    A2dpNativeCallback(@NonNull AdapterService adapterService, @NonNull A2dpService a2dpService) {
        super(adapterService);
        mA2dpService = requireNonNull(a2dpService);
    }

    @VisibleForTesting
    void onConnectionStateChanged(byte[] address, int state, int reason) {
        mA2dpService.onConnectionStateChangedFromNative(getDevice(address), state, reason);
    }

    @VisibleForTesting
    void onAudioStateChanged(byte[] address, int state) {
        mA2dpService.onAudioStateChangedFromNative(getDevice(address), state);
    }

    @VisibleForTesting
    void onCodecConfigChanged(
            byte[] address,
            BluetoothCodecConfig newCodecConfig,
            BluetoothCodecConfig[] codecsLocalCapabilities,
            BluetoothCodecConfig[] codecsSelectableCapabilities) {
        mA2dpService.onCodecConfigChangedFromNative(
                getDevice(address),
                new BluetoothCodecStatus(
                        newCodecConfig,
                        Arrays.asList(codecsLocalCapabilities),
                        Arrays.asList(codecsSelectableCapabilities)));
    }

    void onAudioDelayReported(byte[] address, int audioDelay) {
        mA2dpService.onAudioDelayReportedFromNative(getDevice(address), audioDelay);
    }

    @VisibleForTesting
    boolean isMandatoryCodecPreferred(byte[] address) {
        int enabled = mA2dpService.getOptionalCodecsEnabled(getDevice(address));

        Log.d(TAG, "isMandatoryCodecPreferred: optional preference " + enabled);
        return enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED;
    }
}
