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

package com.android.bluetooth.mapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothMapClient;
import android.content.AttributionSource;
import android.net.Uri;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

/**
 * This class implements the IClient interface - or actually it validates the preconditions for
 * calling the actual functionality in the MapClientService, and calls it.
 */
class MapClientServiceBinder extends IBluetoothMapClient.Stub implements IProfileServiceBinder {
    private static final String TAG = MapClientServiceBinder.class.getSimpleName();

    private MapClientService mService;

    MapClientServiceBinder(MapClientService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private MapClientService getService(AttributionSource source) {
        MapClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !(getCallingUserHandle().isSystem()
                        || Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG))
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }
        return service;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private MapClientService getServiceAndEnforcePrivileged(AttributionSource source) {
        MapClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !(getCallingUserHandle().isSystem()
                        || Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG))
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service;
    }

    @Override
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "connect()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return false;
        }
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "disconnect()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        Log.v(TAG, "getConnectedDevices()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.v(TAG, "getDevicesMatchingConnectionStates()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "getConnectionState()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        Log.v(TAG, "setConnectionPolicy()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "getConnectionPolicy()");

        MapClientService service = getServiceAndEnforcePrivileged(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        return service.getConnectionPolicy(device);
    }

    @Override
    public boolean sendMessage(
            BluetoothDevice device,
            Uri[] contacts,
            String message,
            PendingIntent sentIntent,
            PendingIntent deliveredIntent,
            AttributionSource source) {
        Log.v(TAG, "sendMessage()");

        MapClientService service = getService(source);
        if (service == null) {
            return false;
        }

        Log.d(TAG, "Checking Permission of sendMessage");
        mService.enforceCallingOrSelfPermission(
                Manifest.permission.SEND_SMS, "Need SEND_SMS permission");

        return service.sendMessage(device, contacts, message, sentIntent, deliveredIntent);
    }
}
