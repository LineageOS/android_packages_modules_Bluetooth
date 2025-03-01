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

package com.android.bluetooth.pbapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothPbapClient;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Handler for incoming service calls destined for PBAP Client */
class PbapClientBinder extends IBluetoothPbapClient.Stub implements IProfileServiceBinder {
    private static final String TAG = PbapClientBinder.class.getSimpleName();

    private PbapClientService mService;

    PbapClientBinder(PbapClientService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private PbapClientService getService(AttributionSource source) {
        // Cache mService because it can change while getService is called
        PbapClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)) {
            Log.w(TAG, "getService() failed, service not available");
            return null;
        }

        if (!Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            Log.w(TAG, "getService() failed, rejected due to permissions");
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service;
    }

    @Override
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "connect(device=" + device + ")");
        PbapClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "disconnect(device=" + device + ")");
        PbapClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        Log.d(TAG, "getConnectedDevices()");
        PbapClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.d(TAG, "getDevicesMatchingConnectionStates(states=" + Arrays.toString(states) + ")");
        PbapClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "getConnectionState(device=" + device + ")");
        PbapClientService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        Log.d(TAG, "setConnectionPolicy(device=" + device + ", policy=" + connectionPolicy + ")");

        PbapClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "getConnectionPolicy(device=" + device + ")");
        PbapClientService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        return service.getConnectionPolicy(device);
    }
}
