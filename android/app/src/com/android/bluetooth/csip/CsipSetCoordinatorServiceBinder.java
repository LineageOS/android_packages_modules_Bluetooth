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

package com.android.bluetooth.csip;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothCsipSetCoordinator;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class CsipSetCoordinatorServiceBinder extends IBluetoothCsipSetCoordinator.Stub
        implements IProfileServiceBinder {
    private static final String TAG = CsipSetCoordinatorServiceBinder.class.getSimpleName();

    private CsipSetCoordinatorService mService;

    CsipSetCoordinatorServiceBinder(CsipSetCoordinatorService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private CsipSetCoordinatorService getService(AttributionSource source) {
        CsipSetCoordinatorService service = mService;

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

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        return service.getConnectionPolicy(device);
    }

    @Override
    public ParcelUuid lockGroup(
            int groupId,
            @NonNull IBluetoothCsipSetCoordinatorLockCallback callback,
            AttributionSource source) {
        requireNonNull(callback);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return null;
        }

        UUID lockUuid = service.lockGroup(groupId, callback);
        return lockUuid == null ? null : new ParcelUuid(lockUuid);
    }

    @Override
    public void unlockGroup(@NonNull ParcelUuid lockUuid, AttributionSource source) {
        requireNonNull(lockUuid);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return;
        }
        service.unlockGroup(lockUuid.getUuid());
    }

    @Override
    public List<Integer> getAllGroupIds(ParcelUuid uuid, AttributionSource source) {
        requireNonNull(uuid);
        requireNonNull(source);

        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getAllGroupIds(uuid);
    }

    @Override
    public Map<Integer, ParcelUuid> getGroupUuidMapByDevice(
            BluetoothDevice device, AttributionSource source) {
        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return null;
        }
        return service.getGroupUuidMapByDevice(device);
    }

    @Override
    public int getDesiredGroupSize(int groupId, AttributionSource source) {
        CsipSetCoordinatorService service = getService(source);
        if (service == null) {
            return IBluetoothCsipSetCoordinator.CSIS_GROUP_SIZE_UNKNOWN;
        }
        return service.getDesiredGroupSize(groupId);
    }
}
