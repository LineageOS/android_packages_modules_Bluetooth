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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
import static android.bluetooth.BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.IBluetoothHapClient;
import android.bluetooth.IBluetoothHapClientCallback;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ProfileService.IProfileServiceBinder;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

class HapClientServiceBinder extends IBluetoothHapClient.Stub implements IProfileServiceBinder {
    private static final String TAG = HapClientServiceBinder.class.getSimpleName();

    private HapClientService mService;

    HapClientServiceBinder(HapClientService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private HapClientService getService(AttributionSource source) {
        requireNonNull(source);
        HapClientService service = mService;

        if (Util.isInstrumentationTestMode()) {
            return service;
        }

        if (!Util.checkProfileAvailable(service, TAG)
                || !Util.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Util.enforceConnectPermissionForDataDelivery(service, source, TAG)) {
            Log.w(TAG, "Hearing Access call not allowed for non-active user");
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service;
    }

    // Post and do not wait for the action to be completed
    private static void post(HapClientService service, Consumer<HapClientService> consumer) {
        if (service == null) { // No need to re-check for available here
            return;
        }
        service.post(consumer);
    }

    // Post and wait for the action to be completed
    private static <T> T syncPost(
            HapClientService service, Function<HapClientService, T> function, T defaultValue) {
        if (service == null) { // No need to re-check for available here
            return defaultValue;
        }
        return service.syncPost(function, defaultValue);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getConnectedDevices(), emptyList());
        }
        if (service == null) {
            return emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(
                    service, s -> s.getDevicesMatchingConnectionStates(states), emptyList());
        }
        if (service == null) {
            return emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getConnectionState(device), STATE_DISCONNECTED);
        }
        if (service == null) {
            return STATE_DISCONNECTED;
        }

        requireNonNull(device);
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
            if (connectionPolicy != CONNECTION_POLICY_ALLOWED
                    && connectionPolicy != CONNECTION_POLICY_FORBIDDEN) {
                throw new IllegalArgumentException(
                        "Invalid connectionPolicy value: " + connectionPolicy);
            }
        }
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.setConnectionPolicy(device, connectionPolicy), false);
        }
        if (service == null) {
            return false;
        }

        requireNonNull(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED
                && connectionPolicy != CONNECTION_POLICY_FORBIDDEN) {
            throw new IllegalArgumentException(
                    "Invalid connectionPolicy value: " + connectionPolicy);
        }

        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getConnectionPolicy(device), CONNECTION_POLICY_UNKNOWN);
        }
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        requireNonNull(device);
        return service.getConnectionPolicy(device);
    }

    @Override
    public int getActivePresetIndex(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getActivePresetIndex(device), PRESET_INDEX_UNAVAILABLE);
        }
        if (service == null) {
            return BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        }

        requireNonNull(device);
        return service.getActivePresetIndex(device);
    }

    @Override
    public BluetoothHapPresetInfo getActivePresetInfo(
            BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getActivePresetInfo(device), null);
        }
        if (service == null) {
            return null;
        }

        requireNonNull(device);
        return service.getActivePresetInfo(device);
    }

    @Override
    public int getHapGroup(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getHapGroup(device), GROUP_ID_INVALID);
        }
        if (service == null) {
            return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }

        requireNonNull(device);
        return service.getHapGroup(device);
    }

    @Override
    public void selectPreset(BluetoothDevice device, int presetIndex, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.selectPreset(device, presetIndex));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(device);
        service.selectPreset(device, presetIndex);
    }

    @Override
    public void selectPresetForGroup(int groupId, int presetIndex, AttributionSource source) {
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.selectPresetForGroup(groupId, presetIndex));
            return;
        }
        if (service == null) {
            return;
        }
        service.selectPresetForGroup(groupId, presetIndex);
    }

    @Override
    public void switchToNextPreset(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.switchToNextPreset(device));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(device);
        service.switchToNextPreset(device);
    }

    @Override
    public void switchToNextPresetForGroup(int groupId, AttributionSource source) {
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.switchToNextPresetForGroup(groupId));
            return;
        }
        if (service == null) {
            return;
        }
        service.switchToNextPresetForGroup(groupId);
    }

    @Override
    public void switchToPreviousPreset(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.switchToPreviousPreset(device));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(device);
        service.switchToPreviousPreset(device);
    }

    @Override
    public void switchToPreviousPresetForGroup(int groupId, AttributionSource source) {
        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.switchToPreviousPresetForGroup(groupId));
            return;
        }
        if (service == null) {
            return;
        }
        service.switchToPreviousPresetForGroup(groupId);
    }

    @Override
    public BluetoothHapPresetInfo getPresetInfo(
            BluetoothDevice device, int presetIndex, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getPresetInfo(device, presetIndex), null);
        }
        if (service == null) {
            return null;
        }

        requireNonNull(device);
        return service.getPresetInfo(device, presetIndex);
    }

    @Override
    public List<BluetoothHapPresetInfo> getAllPresetInfo(
            BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getAllPresetInfo(device), emptyList());
        }
        if (service == null) {
            return emptyList();
        }

        requireNonNull(device);
        return service.getAllPresetInfo(device);
    }

    @Override
    public int getFeatures(BluetoothDevice device, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            return syncPost(service, s -> s.getFeatures(device), 0x00);
        }
        if (service == null) {
            return 0x00;
        }

        requireNonNull(device);
        return service.getFeatures(device);
    }

    @Override
    public void setPresetName(
            BluetoothDevice device, int presetIndex, String name, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(device);
            requireNonNull(name);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.setPresetName(device, presetIndex, name));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(device);
        requireNonNull(name);
        service.setPresetName(device, presetIndex, name);
    }

    @Override
    public void setPresetNameForGroup(
            int groupId, int presetIndex, String name, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(name);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.setPresetNameForGroup(groupId, presetIndex, name));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(name);
        service.setPresetNameForGroup(groupId, presetIndex, name);
    }

    @Override
    public void registerCallback(IBluetoothHapClientCallback callback, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(callback);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.registerCallback(callback));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(callback);
        service.registerCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothHapClientCallback callback, AttributionSource source) {
        if (Flags.hapOnMainLooper()) {
            requireNonNull(callback);
        }

        HapClientService service = getService(source);
        if (Flags.hapOnMainLooper()) {
            post(service, s -> s.unregisterCallback(callback));
            return;
        }
        if (service == null) {
            return;
        }

        requireNonNull(callback);
        service.unregisterCallback(callback);
    }
}
