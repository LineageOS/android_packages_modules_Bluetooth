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

package com.android.bluetooth.map;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.IBluetoothMap;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;

import java.util.Collections;
import java.util.List;

/**
 * This class implements the IBluetoothMap interface - or actually it validates the preconditions
 * for calling the actual functionality in the MapService, and calls it.
 */
class BluetoothMapServiceBinder extends IBluetoothMap.Stub implements IProfileServiceBinder {
    private static final String TAG = BluetoothMapServiceBinder.class.getSimpleName();

    private BluetoothMapService mService;

    BluetoothMapServiceBinder(BluetoothMapService service) {
        mService = service;
    }

    @Override
    public synchronized void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private BluetoothMapService getService(AttributionSource source) {
        BluetoothMapService service = mService;

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
    public int getState(AttributionSource source) {
        Log.v(TAG, "getState()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return BluetoothMap.STATE_DISCONNECTED;
            }
            return service.getState();
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    16);
            throw e;
        }
    }

    @Override
    public BluetoothDevice getClient(AttributionSource source) {
        Log.v(TAG, "getClient()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                Log.v(TAG, "getClient() - no service - returning " + null);
                return null;
            }
            BluetoothDevice client = service.getRemoteDevice();
            Log.v(TAG, "getClient() - returning " + client);
            return client;
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    17);
            throw e;
        }
    }

    @Override
    public boolean isConnected(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "isConnected()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.getConnectionState(device) == STATE_CONNECTED;
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    18);
            throw e;
        }
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "disconnect()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return false;
            }
            service.disconnect(device);
            return true;
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    19);
            throw e;
        }
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        Log.v(TAG, "getConnectedDevices()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getConnectedDevices();
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    20);
            throw e;
        }
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.v(TAG, "getDevicesMatchingConnectionStates()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states);
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    21);
            throw e;
        }
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.v(TAG, "getConnectionState()");
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    22);
            throw e;
        }
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.setConnectionPolicy(device, connectionPolicy);
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    23);
            throw e;
        }
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        try {
            BluetoothMapService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }
            return service.getConnectionPolicy(device);
        } catch (RuntimeException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SERVICE,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    24);
            throw e;
        }
    }
}
