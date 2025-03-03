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

package com.android.bluetooth.hap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

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

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.List;

/** HapClientBinder class */
@VisibleForTesting
class HapClientBinder extends IBluetoothHapClient.Stub
        implements ProfileService.IProfileServiceBinder {
    private static final String TAG = HapClientBinder.class.getSimpleName();

    private HapClientService mService;

    HapClientBinder(HapClientService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private HapClientService getService(AttributionSource source) {
        requireNonNull(source);
        // Cache mService because it can change while getService is called
        HapClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            Log.w(TAG, "Hearing Access call not allowed for non-active user");
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service;
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }

        requireNonNull(device);

        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        HapClientService service = getService(source);
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
        HapClientService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        requireNonNull(device);

        return service.getConnectionPolicy(device);
    }

    @Override
    public int getActivePresetIndex(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        }

        requireNonNull(device);

        return service.getActivePresetIndex(device);
    }

    @Override
    public BluetoothHapPresetInfo getActivePresetInfo(
            BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return null;
        }

        requireNonNull(device);

        return service.getActivePresetInfo(device);
    }

    @Override
    public int getHapGroup(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }

        requireNonNull(device);

        return service.getHapGroup(device);
    }

    @Override
    public void selectPreset(BluetoothDevice device, int presetIndex, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(device);

        service.selectPreset(device, presetIndex);
    }

    @Override
    public void selectPresetForGroup(int groupId, int presetIndex, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        service.selectPresetForGroup(groupId, presetIndex);
    }

    @Override
    public void switchToNextPreset(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(device);

        service.switchToNextPreset(device);
    }

    @Override
    public void switchToNextPresetForGroup(int groupId, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        service.switchToNextPresetForGroup(groupId);
    }

    @Override
    public void switchToPreviousPreset(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(device);

        service.switchToPreviousPreset(device);
    }

    @Override
    public void switchToPreviousPresetForGroup(int groupId, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        service.switchToPreviousPresetForGroup(groupId);
    }

    @Override
    public BluetoothHapPresetInfo getPresetInfo(
            BluetoothDevice device, int presetIndex, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return null;
        }

        requireNonNull(device);

        return service.getPresetInfo(device, presetIndex);
    }

    @Override
    public List<BluetoothHapPresetInfo> getAllPresetInfo(
            BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        requireNonNull(device);

        return service.getAllPresetInfo(device);
    }

    @Override
    public int getFeatures(BluetoothDevice device, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return 0x00;
        }

        requireNonNull(device);

        return service.getFeatures(device);
    }

    @Override
    public void setPresetName(
            BluetoothDevice device, int presetIndex, String name, AttributionSource source) {
        HapClientService service = getService(source);
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
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(name);

        service.setPresetNameForGroup(groupId, presetIndex, name);
    }

    @Override
    public void registerCallback(IBluetoothHapClientCallback callback, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(callback);

        service.registerCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothHapClientCallback callback, AttributionSource source) {
        HapClientService service = getService(source);
        if (service == null) {
            return;
        }

        requireNonNull(callback);

        service.unregisterCallback(callback);
    }
}
