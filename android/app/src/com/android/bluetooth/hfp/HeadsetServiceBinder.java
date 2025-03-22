/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothHeadset;
import android.content.AttributionSource;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class HeadsetServiceBinder extends IBluetoothHeadset.Stub implements IProfileServiceBinder {
    private static final String TAG = HeadsetServiceBinder.class.getSimpleName();

    private HeadsetService mService;

    HeadsetServiceBinder(HeadsetService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private HeadsetService getService(AttributionSource source) {
        HeadsetService service = mService;

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
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public boolean isNoiseReductionSupported(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.isNoiseReductionSupported(device);
    }

    @Override
    public boolean isVoiceRecognitionSupported(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.isVoiceRecognitionSupported(device);
    }

    @Override
    public boolean startVoiceRecognition(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        requireNonNull(device);
        return service.startVoiceRecognition(device);
    }

    @Override
    public boolean stopVoiceRecognition(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.stopVoiceRecognition(device);
    }

    @Override
    public boolean isAudioConnected(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.isAudioConnected(device);
    }

    @Override
    public int getAudioState(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAudioState(device);
    }

    @Override
    public int connectAudio(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.connectAudio();
    }

    @Override
    public int disconnectAudio(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.disconnectAudio();
    }

    @Override
    public void setAudioRouteAllowed(boolean allowed, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setAudioRouteAllowed(allowed);
    }

    @Override
    public boolean getAudioRouteAllowed(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAudioRouteAllowed();
    }

    @Override
    public void setForceScoAudio(boolean forced, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return;
        }
        service.setForceScoAudio(forced);
    }

    @Override
    public boolean startScoUsingVirtualVoiceCall(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service.startScoUsingVirtualVoiceCall();
    }

    @Override
    public boolean stopScoUsingVirtualVoiceCall(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service.stopScoUsingVirtualVoiceCall();
    }

    @Override
    public boolean sendVendorSpecificResultCode(
            BluetoothDevice device, String command, String arg, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.sendVendorSpecificResultCode(device, command, arg);
    }

    @Override
    public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        return service.setActiveDevice(device);
    }

    @Override
    public BluetoothDevice getActiveDevice(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return null;
        }
        return service.getActiveDevice();
    }

    @Override
    public boolean isInbandRingingEnabled(AttributionSource source) {
        HeadsetService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isInbandRingingEnabled();
    }
}
