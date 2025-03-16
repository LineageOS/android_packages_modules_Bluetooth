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

package com.android.bluetooth.hap;

import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO_ERROR;
import static com.android.bluetooth.hap.HapClientStackEvent.EVENT_TYPE_ON_PRESET_NAME_SET_ERROR;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapPresetInfo;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;

/** Hearing Access Profile Client Native Callback (from native to Java). */
public class HapClientNativeCallback {
    private static final String TAG = HapClientNativeCallback.class.getSimpleName();

    private final AdapterService mAdapterService;
    private final HapClientService mHapClientService;

    HapClientNativeCallback(AdapterService adapterService, HapClientService hapClientService) {
        mAdapterService = requireNonNull(adapterService);
        mHapClientService = requireNonNull(hapClientService);
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapterService.getDeviceFromByte(address);
    }

    @VisibleForTesting
    void onConnectionStateChanged(int state, byte[] address) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = state;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onDeviceAvailable(byte[] address, int features) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = getDevice(address);
        event.valueInt1 = features;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onFeaturesUpdate(byte[] address, int features) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_DEVICE_FEATURES);
        event.device = getDevice(address);
        event.valueInt1 = features;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onActivePresetSelected(byte[] address, int presetIndex) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        event.device = getDevice(address);
        event.valueInt1 = presetIndex;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onActivePresetGroupSelected(int groupId, int presetIndex) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        event.valueInt1 = presetIndex;
        event.valueInt2 = groupId;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onActivePresetSelectError(byte[] address, int resultCode) {
        HapClientStackEvent event =
                new HapClientStackEvent(EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR);
        event.device = getDevice(address);
        event.valueInt1 = resultCode;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onActivePresetGroupSelectError(int groupId, int resultCode) {
        HapClientStackEvent event =
                new HapClientStackEvent(EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR);
        event.valueInt1 = resultCode;
        event.valueInt2 = groupId;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onPresetInfo(byte[] address, int infoReason, BluetoothHapPresetInfo[] presets) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_INFO);
        event.device = getDevice(address);
        event.valueInt2 = infoReason;
        event.valueList = new ArrayList<>(Arrays.asList(presets));

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onGroupPresetInfo(int groupId, int infoReason, BluetoothHapPresetInfo[] presets) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_INFO);
        event.valueInt2 = infoReason;
        event.valueInt3 = groupId;
        event.valueList = new ArrayList<>(Arrays.asList(presets));

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onPresetNameSetError(byte[] address, int presetIndex, int resultCode) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_NAME_SET_ERROR);
        event.device = getDevice(address);
        event.valueInt1 = resultCode;
        event.valueInt2 = presetIndex;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onGroupPresetNameSetError(int groupId, int presetIndex, int resultCode) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_NAME_SET_ERROR);
        event.valueInt1 = resultCode;
        event.valueInt2 = presetIndex;
        event.valueInt3 = groupId;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onPresetInfoError(byte[] address, int presetIndex, int resultCode) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_INFO_ERROR);
        event.device = getDevice(address);
        event.valueInt1 = resultCode;
        event.valueInt2 = presetIndex;

        mHapClientService.messageFromNative(event);
    }

    @VisibleForTesting
    void onGroupPresetInfoError(int groupId, int presetIndex, int resultCode) {
        HapClientStackEvent event = new HapClientStackEvent(EVENT_TYPE_ON_PRESET_INFO_ERROR);
        event.valueInt1 = resultCode;
        event.valueInt2 = presetIndex;
        event.valueInt3 = groupId;

        mHapClientService.messageFromNative(event);
    }
}
