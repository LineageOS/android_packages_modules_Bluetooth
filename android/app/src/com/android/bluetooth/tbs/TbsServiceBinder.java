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

package com.android.bluetooth.tbs;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothLeCall;
import android.bluetooth.IBluetoothLeCallControl;
import android.bluetooth.IBluetoothLeCallControlCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.List;

class TbsServiceBinder extends IBluetoothLeCallControl.Stub implements IProfileServiceBinder {
    private static final String TAG = TbsServiceBinder.class.getSimpleName();

    private TbsService mService;

    TbsServiceBinder(TbsService service) {
        mService = service;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private TbsService getService(AttributionSource source) {
        TbsService service = mService;

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service;
    }

    @Override
    public void registerBearer(
            String token,
            IBluetoothLeCallControlCallback callback,
            String uci,
            List<String> uriSchemes,
            int capabilities,
            String providerName,
            int technology,
            AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.registerBearer(
                    token, callback, uci, uriSchemes, capabilities, providerName, technology);
        }
    }

    @Override
    public void unregisterBearer(String token, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.unregisterBearer(token);
        }
    }

    @Override
    public void requestResult(int ccid, int requestId, int result, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.requestResult(ccid, requestId, result);
        }
    }

    @Override
    public void callAdded(int ccid, BluetoothLeCall call, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.callAdded(ccid, call);
        }
    }

    @Override
    public void callRemoved(int ccid, ParcelUuid callId, int reason, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.callRemoved(ccid, callId.getUuid(), reason);
        }
    }

    @Override
    public void callStateChanged(int ccid, ParcelUuid callId, int state, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.callStateChanged(ccid, callId.getUuid(), state);
        }
    }

    @Override
    public void currentCallsList(int ccid, List<BluetoothLeCall> calls, AttributionSource source) {
        TbsService service = getService(source);
        if (service != null) {
            service.currentCallsList(ccid, calls);
        }
    }
}
