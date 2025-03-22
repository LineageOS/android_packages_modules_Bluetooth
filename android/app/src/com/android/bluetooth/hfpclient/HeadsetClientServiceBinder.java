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

package com.android.bluetooth.hfpclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.IBluetoothHeadsetClient;
import android.content.AttributionSource;
import android.os.Bundle;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Handlers for incoming service calls */
class HeadsetClientServiceBinder extends IBluetoothHeadsetClient.Stub
        implements IProfileServiceBinder {
    private static final String TAG = HeadsetClientServiceBinder.class.getSimpleName();

    private HeadsetClientService mService;

    HeadsetClientServiceBinder(HeadsetClientService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private HeadsetClientService getService(AttributionSource source) {
        HeadsetClientService service = mService;

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
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public boolean startVoiceRecognition(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.startVoiceRecognition(device);
    }

    @Override
    public boolean stopVoiceRecognition(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.stopVoiceRecognition(device);
    }

    @Override
    public int getAudioState(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
        }
        return service.getAudioState(device);
    }

    @Override
    public void setAudioRouteAllowed(
            BluetoothDevice device, boolean allowed, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            Log.w(TAG, "Service handle is null for setAudioRouteAllowed!");
            return;
        }
        service.setAudioRouteAllowed(device, allowed);
    }

    @Override
    public boolean getAudioRouteAllowed(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            Log.w(TAG, "Service handle is null for getAudioRouteAllowed!");
            return false;
        }
        return service.getAudioRouteAllowed(device);
    }

    @Override
    public boolean connectAudio(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.connectAudio(device);
    }

    @Override
    public boolean disconnectAudio(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnectAudio(device);
    }

    @Override
    public boolean acceptCall(BluetoothDevice device, int flag, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.acceptCall(device, flag);
    }

    @Override
    public boolean rejectCall(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.rejectCall(device);
    }

    @Override
    public boolean holdCall(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.holdCall(device);
    }

    @Override
    public boolean terminateCall(
            BluetoothDevice device, BluetoothHeadsetClientCall call, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            Log.w(TAG, "service is null");
            return false;
        }
        return service.terminateCall(device, call != null ? call.getUUID() : null);
    }

    @Override
    public boolean explicitCallTransfer(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.explicitCallTransfer(device);
    }

    @Override
    public boolean enterPrivateMode(BluetoothDevice device, int index, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.enterPrivateMode(device, index);
    }

    @Override
    public BluetoothHeadsetClientCall dial(
            BluetoothDevice device, String number, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return null;
        }
        return HeadsetClientService.toLegacyCall(service.dial(device, number));
    }

    @Override
    public List<BluetoothHeadsetClientCall> getCurrentCalls(
            BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        List<BluetoothHeadsetClientCall> currentCalls = new ArrayList<>();
        if (service == null) {
            return currentCalls;
        }

        List<HfpClientCall> calls = service.getCurrentCalls(device);
        if (calls != null) {
            for (HfpClientCall call : calls) {
                currentCalls.add(HeadsetClientService.toLegacyCall(call));
            }
        }
        return currentCalls;
    }

    @Override
    public boolean sendDTMF(BluetoothDevice device, byte code, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.sendDTMF(device, code);
    }

    @Override
    public boolean getLastVoiceTagNumber(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.getLastVoiceTagNumber(device);
    }

    @Override
    public Bundle getCurrentAgEvents(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return null;
        }

        service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getCurrentAgEvents(device);
    }

    @Override
    public boolean sendVendorAtCommand(
            BluetoothDevice device, int vendorId, String atCommand, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.sendVendorAtCommand(device, vendorId, atCommand);
    }

    @Override
    public Bundle getCurrentAgFeatures(BluetoothDevice device, AttributionSource source) {
        HeadsetClientService service = getService(source);
        if (service == null) {
            return null;
        }
        return service.getCurrentAgFeaturesBundle(device);
    }
}
