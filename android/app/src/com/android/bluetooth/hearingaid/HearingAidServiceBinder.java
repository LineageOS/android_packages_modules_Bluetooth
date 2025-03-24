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

package com.android.bluetooth.hearingaid;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothHearingAid.AdvertisementServiceData;
import android.bluetooth.IBluetoothHearingAid;
import android.content.AttributionSource;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class HearingAidServiceBinder extends IBluetoothHearingAid.Stub implements IProfileServiceBinder {
    private static final String TAG = HearingAidServiceBinder.class.getSimpleName();

    private HearingAidService mService;

    HearingAidServiceBinder(HearingAidService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private HearingAidService getService(AttributionSource source) {
        HearingAidService service = mService;

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
        HearingAidService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return false;
        }

        if (device == null) {
            return service.removeActiveDevice(false);
        } else {
            return service.setActiveDevice(device);
        }
    }

    @Override
    public List<BluetoothDevice> getActiveDevices(AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getActiveDevices();
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public void setVolume(int volume, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setVolume(volume);
    }

    @Override
    public long getHiSyncId(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return BluetoothHearingAid.HI_SYNC_ID_INVALID;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getHiSyncId(device);
    }

    @Override
    public int getDeviceSide(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return BluetoothHearingAid.SIDE_UNKNOWN;
        }

        int side = service.getCapabilities(device);
        if (side != BluetoothHearingAid.SIDE_UNKNOWN) {
            side &= 1;
        }

        return side;
    }

    @Override
    public int getDeviceMode(BluetoothDevice device, AttributionSource source) {
        HearingAidService service = getService(source);
        if (service == null) {
            return BluetoothHearingAid.MODE_UNKNOWN;
        }

        int mode = service.getCapabilities(device);
        if (mode != BluetoothHearingAid.MODE_UNKNOWN) {
            mode = mode >> 1 & 1;
        }

        return mode;
    }

    @Override
    public AdvertisementServiceData getAdvertisementServiceData(
            BluetoothDevice device, AttributionSource source) {
        HearingAidService service = mService;

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkScanPermissionForDataDelivery(
                        service, source, TAG, "getAdvertisementServiceData")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAdvertisementServiceData(device);
    }
}
