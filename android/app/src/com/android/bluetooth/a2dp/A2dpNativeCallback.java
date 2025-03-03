/*
 * Copyright 2024 The Android Open Source Project
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
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

class A2dpNativeCallback {
    private static final String TAG = A2dpNativeCallback.class.getSimpleName();

    private final AdapterService mAdapterService;
    private final A2dpService mA2dpService;

    @VisibleForTesting
    A2dpNativeCallback(@NonNull AdapterService adapterService, @NonNull A2dpService a2dpService) {
        mAdapterService = requireNonNull(adapterService);
        mA2dpService = requireNonNull(a2dpService);
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapterService.getDeviceFromByte(address);
    }

    @VisibleForTesting
    void onConnectionStateChanged(byte[] address, int state) {
        A2dpStackEvent event =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt = state;

        Log.d(TAG, "onConnectionStateChanged: " + event);
        mA2dpService.messageFromNative(event);
    }

    @VisibleForTesting
    void onAudioStateChanged(byte[] address, int state) {
        A2dpStackEvent event = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt = state;

        Log.d(TAG, "onAudioStateChanged: " + event);
        mA2dpService.messageFromNative(event);
    }

    @VisibleForTesting
    void onCodecConfigChanged(
            byte[] address,
            BluetoothCodecConfig newCodecConfig,
            BluetoothCodecConfig[] codecsLocalCapabilities,
            BluetoothCodecConfig[] codecsSelectableCapabilities) {
        A2dpStackEvent event = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED);
        event.device = getDevice(address);
        event.codecStatus =
                new BluetoothCodecStatus(
                        newCodecConfig,
                        Arrays.asList(codecsLocalCapabilities),
                        Arrays.asList(codecsSelectableCapabilities));

        Log.d(TAG, "onCodecConfigChanged: " + event);
        mA2dpService.messageFromNative(event);
    }

    @VisibleForTesting
    boolean isMandatoryCodecPreferred(byte[] address) {
        int enabled = mA2dpService.getOptionalCodecsEnabled(getDevice(address));

        Log.d(TAG, "isMandatoryCodecPreferred: optional preference " + enabled);
        return enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED;
    }
}
