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

package com.android.bluetooth.avrcpcontroller;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAvrcpPlayerSettings;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothAvrcpController;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class AvrcpControllerServiceBinder extends IBluetoothAvrcpController.Stub
        implements IProfileServiceBinder {
    private static final String TAG = AvrcpControllerServiceBinder.class.getSimpleName();

    private AvrcpControllerService mService;

    AvrcpControllerServiceBinder(AvrcpControllerService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private AvrcpControllerService getService(AttributionSource source) {
        AvrcpControllerService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        return service;
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        AvrcpControllerService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        AvrcpControllerService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        AvrcpControllerService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public void sendGroupNavigationCmd(
            BluetoothDevice device, int keyCode, int keyState, AttributionSource source) {
        getService(source);
        Log.w(TAG, "sendGroupNavigationCmd not implemented");
    }

    @Override
    public void setPlayerApplicationSetting(
            BluetoothAvrcpPlayerSettings settings, AttributionSource source) {
        getService(source);
        Log.w(TAG, "setPlayerApplicationSetting not implemented");
    }

    @Override
    public BluetoothAvrcpPlayerSettings getPlayerSettings(
            BluetoothDevice device, AttributionSource source) {
        getService(source);
        Log.w(TAG, "getPlayerSettings not implemented");
        return null;
    }
}
