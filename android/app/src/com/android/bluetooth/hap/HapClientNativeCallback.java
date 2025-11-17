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

import static android.bluetooth.BluetoothUtils.inlineStackTrace;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothStatusCodes;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.NativeCallback;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.function.Consumer;

/** Hearing Access Profile Client Native Callback (from native to Java). */
public class HapClientNativeCallback extends NativeCallback {
    private static final String TAG = HapClientNativeCallback.class.getSimpleName();

    private final HapClientService mHapClientService;

    HapClientNativeCallback(AdapterService adapterService, HapClientService hapClientService) {
        super(adapterService);
        mHapClientService = requireNonNull(hapClientService);
    }

    private void sendMessageToService(Consumer<HapClientService> action) {
        if (Flags.hapOnMainLooper()) {
            mHapClientService.post(action);
            return;
        }
        if (!mHapClientService.isAvailable()) {
            Log.e(TAG, "Action ignored, service not available. " + inlineStackTrace());
            return;
        }
        action.accept(mHapClientService);
    }

    @VisibleForTesting
    void onConnectionStateChanged(byte[] address, int state) {
        BluetoothDevice device = getDevice(address);

        sendMessageToService(s -> s.onConnectionStateChanged(device, state));
    }

    @VisibleForTesting
    void onDeviceAvailable(byte[] address, int features) {
        BluetoothDevice device = getDevice(address);

        sendMessageToService(s -> s.onDeviceAvailable(device, features));
    }

    @VisibleForTesting
    void onFeaturesUpdate(byte[] address, int features) {
        BluetoothDevice device = getDevice(address);

        sendMessageToService(s -> s.onFeaturesUpdate(device, features));
    }

    @VisibleForTesting
    void onPresetSelected(byte[] address, int presetIndex) {
        BluetoothDevice device = getDevice(address);

        sendMessageToService(s -> s.onPresetSelected(device, presetIndex));
    }

    @VisibleForTesting
    void onPresetSelectedForGroup(int groupId, int presetIndex) {
        sendMessageToService(s -> s.onPresetSelectedForGroup(groupId, presetIndex));
    }

    @VisibleForTesting
    void onPresetSelectionFailed(byte[] address, int nativeStatus) {
        BluetoothDevice device = getDevice(address);
        int status = nativeStatusToBluetoothStatusCodes(nativeStatus);

        sendMessageToService(s -> s.onPresetSelectionFailed(device, status));
    }

    @VisibleForTesting
    void onPresetSelectionForGroupFailed(int groupId, int nativeStatus) {
        int status = nativeStatusToBluetoothStatusCodes(nativeStatus);

        sendMessageToService(s -> s.onPresetSelectionForGroupFailed(groupId, status));
    }

    @VisibleForTesting
    void onPresetInfo(byte[] address, int reason, BluetoothHapPresetInfo[] presetsArray) {
        BluetoothDevice device = getDevice(address);
        List<BluetoothHapPresetInfo> presets = List.of(presetsArray);

        sendMessageToService(s -> s.onPresetInfo(device, reason, presets));
    }

    @VisibleForTesting
    void onPresetInfoForGroup(int groupId, int reason, BluetoothHapPresetInfo[] presetsArray) {
        List<BluetoothHapPresetInfo> presets = List.of(presetsArray);

        sendMessageToService(s -> s.onPresetInfoForGroup(groupId, reason, presets));
    }

    @VisibleForTesting
    void onSetPresetNameFailed(byte[] address, int nativeStatus) {
        BluetoothDevice device = getDevice(address);
        int status = nativeStatusToBluetoothStatusCodes(nativeStatus);

        sendMessageToService(s -> s.onSetPresetNameFailed(device, status));
    }

    @VisibleForTesting
    void onSetPresetNameForGroupFailed(int groupId, int nativeStatus) {
        int status = nativeStatusToBluetoothStatusCodes(nativeStatus);

        sendMessageToService(s -> s.onSetPresetNameForGroupFailed(groupId, status));
    }

    /* WARNING: Matches status codes defined in bta_has.h */
    @VisibleForTesting static final int STATUS_NO_ERROR = 0;
    @VisibleForTesting static final int STATUS_SET_NAME_NOT_ALLOWED = 1;
    @VisibleForTesting static final int STATUS_OPERATION_NOT_SUPPORTED = 2;
    @VisibleForTesting static final int STATUS_OPERATION_NOT_POSSIBLE = 3;
    @VisibleForTesting static final int STATUS_INVALID_PRESET_NAME_LENGTH = 4;
    @VisibleForTesting static final int STATUS_INVALID_PRESET_INDEX = 5;
    @VisibleForTesting static final int STATUS_GROUP_OPERATION_NOT_SUPPORTED = 6;
    @VisibleForTesting static final int STATUS_PROCEDURE_ALREADY_IN_PROGRESS = 7;
    @VisibleForTesting static final int STATUS_TIMEOUT = 8;

    private static int nativeStatusToBluetoothStatusCodes(int statusCode) {
        return switch (statusCode) {
            case STATUS_NO_ERROR -> BluetoothStatusCodes.SUCCESS;
            case STATUS_SET_NAME_NOT_ALLOWED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED;
            case STATUS_OPERATION_NOT_SUPPORTED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
            case STATUS_OPERATION_NOT_POSSIBLE ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED;
            case STATUS_INVALID_PRESET_NAME_LENGTH ->
                    BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG;
            case STATUS_INVALID_PRESET_INDEX -> BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            case STATUS_GROUP_OPERATION_NOT_SUPPORTED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
            case STATUS_PROCEDURE_ALREADY_IN_PROGRESS -> BluetoothStatusCodes.ERROR_UNKNOWN;
            case STATUS_TIMEOUT -> BluetoothStatusCodes.ERROR_TIMEOUT;
            default -> BluetoothStatusCodes.ERROR_UNKNOWN;
        };
    }
}
