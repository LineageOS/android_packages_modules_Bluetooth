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

package com.android.bluetooth.pan;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.TETHER_PRIVILEGED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.IBluetoothPan;
import android.bluetooth.IBluetoothPanCallback;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

/** Handlers for incoming service calls */
class PanServiceBinder extends IBluetoothPan.Stub implements IProfileServiceBinder {
    private static final String TAG = PanServiceBinder.class.getSimpleName();

    private PanService mService;

    PanServiceBinder(PanService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private PanService getService(AttributionSource source) {
        if (Utils.isInstrumentationTestMode()) {
            return mService;
        }
        if (!Utils.checkServiceAvailable(mService, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(mService, source, TAG)) {
            return null;
        }
        return mService;
    }

    @Override
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return BluetoothPan.STATE_DISCONNECTED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionState(device);
    }

    @Override
    public boolean isTetheringOn(AttributionSource source) {
        // TODO(BT) have a variable marking the on/off state
        PanService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.isTetheringOn();
    }

    @Override
    public void setBluetoothTethering(
            IBluetoothPanCallback callback, int id, boolean value, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return;
        }

        Log.d(
                TAG,
                "setBluetoothTethering:"
                        + (" value=" + value)
                        + (" pkgName= " + source.getPackageName())
                        + (" mTetherOn= " + service.isTetheringOn()));

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.enforceCallingOrSelfPermission(TETHER_PRIVILEGED, null);

        service.setBluetoothTethering(callback, id, source.getUid(), value);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        PanService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDevicesMatchingConnectionStates(states);
    }
}
