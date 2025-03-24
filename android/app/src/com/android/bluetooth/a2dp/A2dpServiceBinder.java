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

package com.android.bluetooth.a2dp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.Utils.checkCallerTargetSdk;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BufferConstraints;
import android.bluetooth.IBluetoothA2dp;
import android.content.AttributionSource;
import android.os.Build;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class A2dpServiceBinder extends IBluetoothA2dp.Stub implements IProfileServiceBinder {
    private static final String TAG = A2dpServiceBinder.class.getSimpleName();

    private A2dpService mService;

    A2dpServiceBinder(A2dpService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    private A2dpService getService() {
        A2dpService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)) {
            return null;
        }
        return service;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private A2dpService getServiceAndEnforceConnect(AttributionSource source) {
        A2dpService service = mService;

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
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
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
    public BluetoothDevice getActiveDevice(AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return null;
        }
        return service.getActiveDevice();
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public void setAvrcpAbsoluteVolume(int volume, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setAvrcpAbsoluteVolume(volume);
    }

    @Override
    public boolean isA2dpPlaying(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }
        return service.isA2dpPlaying(device);
    }

    @Override
    public List<BluetoothCodecType> getSupportedCodecTypes() {
        A2dpService service = getService();
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getSupportedCodecTypes();
    }

    @Override
    public BluetoothCodecStatus getCodecStatus(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return null;
        }

        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                service, service.getCompanionDeviceManager(), source, device);

        return service.getCodecStatus(device);
    }

    @Override
    public void setCodecConfigPreference(
            BluetoothDevice device, BluetoothCodecConfig codecConfig, AttributionSource source) {
        requireNonNull(device);
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                service, service.getCompanionDeviceManager(), source, device);

        service.setCodecConfigPreference(device, codecConfig);
    }

    @Override
    public void enableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        if (checkCallerTargetSdk(mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
        service.enableOptionalCodecs(device);
    }

    @Override
    public void disableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        if (checkCallerTargetSdk(mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
        service.disableOptionalCodecs(device);
    }

    @Override
    public int isOptionalCodecsSupported(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        }

        if (checkCallerTargetSdk(mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
        return service.getSupportsOptionalCodecs(device);
    }

    @Override
    public int isOptionalCodecsEnabled(BluetoothDevice device, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
        }

        if (checkCallerTargetSdk(mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
        return service.getOptionalCodecsEnabled(device);
    }

    @Override
    public void setOptionalCodecsEnabled(
            BluetoothDevice device, int value, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        if (checkCallerTargetSdk(mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
        service.setOptionalCodecsEnabled(device, value);
    }

    @Override
    public int getDynamicBufferSupport(AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_NONE;
        }

        return service.getDynamicBufferSupport();
    }

    @Override
    public BufferConstraints getBufferConstraints(AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return null;
        }

        return service.getBufferConstraints();
    }

    @Override
    public boolean setBufferLengthMillis(int codec, int value, AttributionSource source) {
        A2dpService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        return service.setBufferLengthMillis(codec, value);
    }
}
