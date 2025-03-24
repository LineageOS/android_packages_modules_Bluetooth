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

package com.android.bluetooth.bass_client;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.IBluetoothLeBroadcastAssistant;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.ScanFilter;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class BassClientServiceBinder extends IBluetoothLeBroadcastAssistant.Stub
        implements IProfileServiceBinder {
    private static final String TAG = BassClientServiceBinder.class.getSimpleName();

    private BassClientService mService;

    BassClientServiceBinder(BassClientService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private BassClientService getServiceAndEnforceConnect(AttributionSource source) {
        BassClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service;
    }

    @RequiresPermission(allOf = {BLUETOOTH_SCAN, BLUETOOTH_PRIVILEGED})
    private BassClientService getServiceAndEnforceScan(AttributionSource source) {
        BassClientService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkScanPermissionForDataDelivery(
                        service, source, TAG, "getServiceAndEnforceScan")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service;
    }

    @Override
    public int getConnectionState(BluetoothDevice sink, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(sink);
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return service.getConnectionPolicy(device);
    }

    @Override
    public void registerCallback(
            IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.registerCallback(cb);
    }

    @Override
    public void unregisterCallback(
            IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.unregisterCallback(cb);
    }

    @Override
    public void startSearchingForSources(List<ScanFilter> filters, AttributionSource source) {
        BassClientService service = getServiceAndEnforceScan(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.startSearchingForSources(filters);
    }

    @Override
    public void stopSearchingForSources(AttributionSource source) {
        BassClientService service = getServiceAndEnforceScan(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.stopSearchingForSources();
    }

    @Override
    public boolean isSearchInProgress(AttributionSource source) {
        BassClientService service = getServiceAndEnforceScan(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return false;
        }
        return service.isSearchInProgress();
    }

    @Override
    public void addSource(
            BluetoothDevice sink,
            BluetoothLeBroadcastMetadata sourceMetadata,
            boolean isGroupOp,
            AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.addSource(sink, sourceMetadata, isGroupOp);
    }

    @Override
    public void modifySource(
            BluetoothDevice sink,
            int sourceId,
            BluetoothLeBroadcastMetadata updatedMetadata,
            AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.modifySource(sink, sourceId, updatedMetadata);
    }

    @Override
    public void removeSource(BluetoothDevice sink, int sourceId, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return;
        }
        service.removeSource(sink, sourceId);
    }

    @Override
    public List<BluetoothLeBroadcastReceiveState> getAllSources(
            BluetoothDevice sink, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return Collections.emptyList();
        }
        return service.getAllSources(sink);
    }

    @Override
    public int getMaximumSourceCapacity(BluetoothDevice sink, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return 0;
        }
        return service.getMaximumSourceCapacity(sink);
    }

    @Override
    public BluetoothLeBroadcastMetadata getSourceMetadata(
            BluetoothDevice sink, int sourceId, AttributionSource source) {
        BassClientService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            Log.e(TAG, "Service is null");
            return null;
        }
        return service.getSourceMetadata(sink, sourceId);
    }
}
