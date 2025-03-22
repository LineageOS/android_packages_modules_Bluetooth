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

package com.android.bluetooth.hid;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothHidHost;
import android.content.AttributionSource;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class HidHostServiceBinder extends IBluetoothHidHost.Stub implements IProfileServiceBinder {
    private static final String TAG = HidHostServiceBinder.class.getSimpleName();

    private HidHostService mService;

    HidHostServiceBinder(HidHostService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private HidHostService getService(AttributionSource source) {
        HidHostService service = mService;

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
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.disconnect(device);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        return getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}, source);
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public boolean setPreferredTransport(
            BluetoothDevice device, int transport, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setPreferredTransport(device, transport);
    }

    @Override
    public int getPreferredTransport(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return BluetoothDevice.TRANSPORT_AUTO;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getPreferredTransport(device);
    }

    /* The following APIs regarding test app for compliance */
    @Override
    public boolean getProtocolMode(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.getProtocolMode(device);
    }

    @Override
    public boolean virtualUnplug(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.virtualUnplug(device);
    }

    @Override
    public boolean setProtocolMode(
            BluetoothDevice device, int protocolMode, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setProtocolMode(device, protocolMode);
    }

    @Override
    public boolean getReport(
            BluetoothDevice device,
            byte reportType,
            byte reportId,
            int bufferSize,
            AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.getReport(device, reportType, reportId, bufferSize);
    }

    @Override
    public boolean setReport(
            BluetoothDevice device, byte reportType, String report, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setReport(device, reportType, report);
    }

    @Override
    public boolean sendData(BluetoothDevice device, String report, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.sendData(device, report);
    }

    @Override
    public boolean setIdleTime(BluetoothDevice device, byte idleTime, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setIdleTime(device, idleTime);
    }

    @Override
    public boolean getIdleTime(BluetoothDevice device, AttributionSource source) {
        HidHostService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.getIdleTime(device);
    }
}
