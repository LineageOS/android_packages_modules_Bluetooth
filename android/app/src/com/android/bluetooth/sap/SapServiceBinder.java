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

package com.android.bluetooth.sap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSap;
import android.bluetooth.IBluetoothSap;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

/**
 * This class implements the IBluetoothSap interface - or actually it validates the preconditions
 * for calling the actual functionality in the SapService, and calls it.
 */
class SapServiceBinder extends IBluetoothSap.Stub implements IProfileServiceBinder {
    private static final String TAG = SapServiceBinder.class.getSimpleName();

    private SapService mService;

    SapServiceBinder(SapService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private SapService getService(AttributionSource source) {
        SapService service = mService;

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        return service;
    }

    @Override
    public int getState(AttributionSource source) {
        Log.v(TAG, "getState()");

        SapService service = getService(source);
        if (service == null) {
            return BluetoothSap.STATE_DISCONNECTED;
        }
        return service.getState();
    }

    @Override
    public BluetoothDevice getClient(AttributionSource source) {
        Log.v(TAG, "getClient()");

        SapService service = getService(source);
        if (service == null) {
            return null;
        }

        Log.v(TAG, "getClient() - returning " + service.getRemoteDevice());
        return service.getRemoteDevice();
    }

    @Override
    public boolean isConnected(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "isConnected()");

        SapService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.getConnectionState(device) == STATE_CONNECTED;
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "disconnect()");

        SapService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        Log.v(TAG, "getConnectedDevices()");

        SapService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.v(TAG, "getDevicesMatchingConnectionStates()");

        SapService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "getConnectionState()");

        SapService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        SapService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        SapService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        return service.getConnectionPolicy(device);
    }
}
