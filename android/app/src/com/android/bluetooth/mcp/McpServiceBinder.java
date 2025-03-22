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

package com.android.bluetooth.mcp;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothMcpServiceManager;
import android.content.AttributionSource;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

class McpServiceBinder extends IBluetoothMcpServiceManager.Stub implements IProfileServiceBinder {
    private static final String TAG = McpServiceBinder.class.getSimpleName();

    private McpService mService;

    McpServiceBinder(McpService svc) {
        mService = svc;
    }

    private McpService getService() {
        if (mService != null && mService.isAvailable()) {
            return mService;
        }
        Log.e(TAG, "getService() - Service requested, but not available!");
        return null;
    }

    @Override
    public void setDeviceAuthorized(
            BluetoothDevice device, boolean isAuthorized, AttributionSource source) {
        McpService service = getService();
        if (service == null) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setDeviceAuthorized(device, isAuthorized);
    }

    @Override
    public void cleanup() {
        if (mService != null) {
            mService.cleanup();
        }
        mService = null;
    }
}
