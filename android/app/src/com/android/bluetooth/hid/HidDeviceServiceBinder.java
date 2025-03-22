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
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.IBluetoothHidDevice;
import android.bluetooth.IBluetoothHidDeviceCallback;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class HidDeviceServiceBinder extends IBluetoothHidDevice.Stub implements IProfileServiceBinder {
    private static final String TAG = HidDeviceServiceBinder.class.getSimpleName();

    private HidDeviceService mService;

    HidDeviceServiceBinder(HidDeviceService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private HidDeviceService getService(AttributionSource source) {
        // Cache mService because it can change while getService is called
        HidDeviceService service = mService;

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
    public boolean registerApp(
            BluetoothHidDeviceAppSdpSettings sdp,
            BluetoothHidDeviceAppQosSettings inQos,
            BluetoothHidDeviceAppQosSettings outQos,
            IBluetoothHidDeviceCallback callback,
            AttributionSource source) {
        Log.d(TAG, "registerApp()");

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.registerApp(sdp, inQos, outQos, callback);
    }

    @Override
    public boolean unregisterApp(AttributionSource source) {
        Log.d(TAG, "unregisterApp()");

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.unregisterApp();
    }

    @Override
    public boolean sendReport(
            BluetoothDevice device, int id, byte[] data, AttributionSource source) {
        Log.d(TAG, "sendReport(): device=" + device + "  id=" + id);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.sendReport(device, id, data);
    }

    @Override
    public boolean replyReport(
            BluetoothDevice device, byte type, byte id, byte[] data, AttributionSource source) {
        Log.d(TAG, "replyReport(): device=" + device + " type=" + type + " id=" + id);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.replyReport(device, type, id, data);
    }

    @Override
    public boolean unplug(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "unplug(): device=" + device);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.unplug(device);
    }

    @Override
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "connect(): device=" + device);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "disconnect(): device=" + device);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        Log.d(
                TAG,
                "setConnectionPolicy():"
                        + (" device=" + device)
                        + (" connectionPolicy=" + connectionPolicy));

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public boolean reportError(BluetoothDevice device, byte error, AttributionSource source) {
        Log.d(TAG, "reportError(): device=" + device + " error=" + error);

        HidDeviceService service = getService(source);
        if (service == null) {
            return false;
        }
        return service.reportError(device, error);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        Log.d(TAG, "getConnectionState(): device=" + device);

        HidDeviceService service = getService(source);
        if (service == null) {
            return BluetoothHidDevice.STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        Log.d(TAG, "getConnectedDevices()");

        return getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}, source);
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        Log.d(TAG, "getDevicesMatchingConnectionStates(): states=" + Arrays.toString(states));

        HidDeviceService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public String getUserAppName(AttributionSource source) {
        HidDeviceService service = getService(source);
        if (service == null) {
            return "";
        }
        return service.getUserAppName();
    }
}
