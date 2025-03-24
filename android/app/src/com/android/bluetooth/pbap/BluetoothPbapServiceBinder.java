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

package com.android.bluetooth.pbap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothPbap;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class BluetoothPbapServiceBinder extends IBluetoothPbap.Stub implements IProfileServiceBinder {
    private static final String TAG = BluetoothPbapServiceBinder.class.getSimpleName();

    private BluetoothPbapService mService;

    BluetoothPbapServiceBinder(BluetoothPbapService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private BluetoothPbapService getService(AttributionSource source) {
        BluetoothPbapService service = mService;

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
        Log.d(TAG, "getConnectedDevices");
        BluetoothPbapService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.d(TAG, "getDevicesMatchingConnectionStates");
        BluetoothPbapService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "getConnectionState: " + device);
        BluetoothPbapService service = getService(source);
        if (service == null) {
            return BluetoothAdapter.STATE_DISCONNECTED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        Log.d(TAG, "setConnectionPolicy for device=" + device + " policy=" + connectionPolicy);
        BluetoothPbapService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public void disconnect(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "disconnect");
        BluetoothPbapService service = getService(source);
        if (service == null) {
            return;
        }
        service.disconnect(device);
    }
}
