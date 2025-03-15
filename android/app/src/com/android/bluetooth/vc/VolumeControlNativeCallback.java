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

package com.android.bluetooth.vc;

import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED;
import static com.android.bluetooth.vc.VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED;

import static java.util.Objects.requireNonNull;

import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.function.Consumer;

class VolumeControlNativeCallback {
    private static final String TAG = VolumeControlNativeCallback.class.getSimpleName();

    private final AdapterService mAdapterService;
    private final VolumeControlService mVolumeControlService;

    VolumeControlNativeCallback(
            AdapterService adapterService, VolumeControlService volumeControlService) {
        mAdapterService = requireNonNull(adapterService);
        mVolumeControlService = requireNonNull(volumeControlService);
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapterService.getDeviceFromByte(address);
    }

    private void sendMessageToService(Consumer<VolumeControlService> action) {
        if (!mVolumeControlService.isAvailable()) {
            StringBuilder sb = new StringBuilder();
            Arrays.stream(new Throwable().getStackTrace())
                    .skip(1) // skip the inlineStackTrace method in the outputted stack trace
                    .forEach(trace -> sb.append(" [at ").append(trace).append("]"));
            Log.e(TAG, "Action ignored, service not available: " + sb.toString());
            return;
        }
        action.accept(mVolumeControlService);
    }

    @VisibleForTesting
    void onConnectionStateChanged(int state, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = state;

        Log.d(TAG, "onConnectionStateChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onVolumeStateChanged(
            int volume, boolean mute, int flags, byte[] address, boolean isAutonomous) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_VOLUME_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = -1;
        event.valueInt2 = volume;
        event.valueInt3 = flags;
        event.valueBool1 = mute;
        event.valueBool2 = isAutonomous;

        Log.d(TAG, "onVolumeStateChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onGroupVolumeStateChanged(int volume, boolean mute, int groupId, boolean isAutonomous) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_VOLUME_STATE_CHANGED);
        event.device = null;
        event.valueInt1 = groupId;
        event.valueInt2 = volume;
        event.valueBool1 = mute;
        event.valueBool2 = isAutonomous;

        Log.d(TAG, "onGroupVolumeStateChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onDeviceAvailable(int numOfExternalOutputs, int numOfExternalInputs, byte[] address) {
        VolumeControlStackEvent event = new VolumeControlStackEvent(EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = getDevice(address);
        event.valueInt1 = numOfExternalOutputs;
        event.valueInt2 = numOfExternalInputs;

        Log.d(TAG, "onDeviceAvailable: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onExtAudioOutVolumeOffsetChanged(int externalOutputId, int offset, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueInt2 = offset;

        Log.d(TAG, "onExtAudioOutVolumeOffsetChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onExtAudioOutLocationChanged(int externalOutputId, int location, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueInt2 = location;

        Log.d(TAG, "onExtAudioOutLocationChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onExtAudioOutDescriptionChanged(int externalOutputId, String descr, byte[] address) {
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = externalOutputId;
        event.valueString1 = descr;

        Log.d(TAG, "onExtAudioOutLocationChanged: " + event);
        mVolumeControlService.messageFromNative(event);
    }

    @VisibleForTesting
    void onExtAudioInStateChanged(
            int id, int gainSetting, @Mute int mute, @GainMode int gainMode, byte[] address) {
        sendMessageToService(
                s ->
                        s.onExtAudioInStateChanged(
                                getDevice(address), id, gainSetting, mute, gainMode));
    }

    @VisibleForTesting
    void onExtAudioInSetGainSettingFailed(int id, byte[] address) {
        sendMessageToService(s -> s.onExtAudioInSetGainSettingFailed(getDevice(address), id));
    }

    @VisibleForTesting
    void onExtAudioInSetMuteFailed(int id, byte[] address) {
        sendMessageToService(s -> s.onExtAudioInSetMuteFailed(getDevice(address), id));
    }

    @VisibleForTesting
    void onExtAudioInSetGainModeFailed(int id, byte[] address) {
        sendMessageToService(s -> s.onExtAudioInSetGainModeFailed(getDevice(address), id));
    }

    @VisibleForTesting
    void onExtAudioInStatusChanged(int id, @AudioInputStatus int status, byte[] address) {
        sendMessageToService(s -> s.onExtAudioInStatusChanged(getDevice(address), id, status));
    }

    @VisibleForTesting
    void onExtAudioInTypeChanged(int id, @AudioInputType int type, byte[] address) {
        sendMessageToService(s -> s.onExtAudioInTypeChanged(getDevice(address), id, type));
    }

    @VisibleForTesting
    void onExtAudioInDescriptionChanged(
            int id, String description, boolean isWritable, byte[] address) {
        sendMessageToService(
                s ->
                        s.onExtAudioInDescriptionChanged(
                                getDevice(address), id, description, isWritable));
    }

    @VisibleForTesting
    void onExtAudioInGainSettingPropertiesChanged(
            int id, int unit, int min, int max, byte[] address) {
        sendMessageToService(
                s ->
                        s.onExtAudioInGainSettingPropertiesChanged(
                                getDevice(address), id, unit, min, max));
    }
}
