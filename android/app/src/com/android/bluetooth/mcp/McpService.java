/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothMcpServiceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.os.ParcelUuid;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides Media Control Profile, as a service in the Bluetooth application. */
public class McpService extends ProfileService {
    private static final String TAG = Utils.TAG_PREFIX_BLUETOOTH + McpService.class.getSimpleName();

    private static McpService sMcpService;

    private final MediaControlProfile mGmcs;
    private final Map<BluetoothDevice, Integer> mDeviceAuthorizations = new HashMap<>();

    public McpService(Context ctx) {
        this(ctx, null);
    }

    @VisibleForTesting
    McpService(Context ctx, MediaControlProfile mediaControlProfile) {
        super(ctx);
        if (mediaControlProfile == null) {
            mGmcs = new MediaControlProfile(this);
        } else {
            mGmcs = mediaControlProfile;
        }

        setMcpService(this); // Mark service as started

        mGmcs.init();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileMcpServerEnabled().orElse(false);
    }

    private static synchronized void setMcpService(McpService instance) {
        Log.d(TAG, "setMcpService(): set to: " + instance);
        sMcpService = instance;
    }

    public static synchronized McpService getMcpService() {
        if (sMcpService == null) {
            Log.w(TAG, "getMcpService(): service is NULL");
            return null;
        }

        if (!sMcpService.isAvailable()) {
            Log.w(TAG, "getMcpService(): service is not available");
            return null;
        }
        return sMcpService;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothMcpServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Mcp Service");

        if (sMcpService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        mGmcs.cleanup();

        // Mark service as stopped
        setMcpService(null);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        mGmcs.dump(sb);

        for (Map.Entry<BluetoothDevice, Integer> entry : mDeviceAuthorizations.entrySet()) {
            String accessString;
            if (entry.getValue() == BluetoothDevice.ACCESS_REJECTED) {
                accessString = "ACCESS_REJECTED";
            } else if (entry.getValue() == BluetoothDevice.ACCESS_ALLOWED) {
                accessString = "ACCESS_ALLOWED";
            } else {
                accessString = "ACCESS_UNKNOWN";
            }
            sb.append("\n\t\tDevice: ")
                    .append(entry.getKey())
                    .append(", access: ")
                    .append(accessString);
        }
    }

    public void onDeviceUnauthorized(BluetoothDevice device) {
        if (Utils.isPtsTestMode()) {
            Log.d(TAG, "PTS test: setDeviceAuthorized");
            setDeviceAuthorized(device, true);
            return;
        }
        Log.w(TAG, "onDeviceUnauthorized - authorization notification not implemented yet ");
        setDeviceAuthorized(device, false);
    }

    /**
     * Remove authorization information for the device.
     *
     * @param device device to remove from the service information
     */
    public void removeDeviceAuthorizationInfo(BluetoothDevice device) {
        Log.i(TAG, "removeDeviceAuthorizationInfo(): device: " + device);
        mDeviceAuthorizations.remove(device);
    }

    public void setDeviceAuthorized(BluetoothDevice device, boolean isAuthorized) {
        Log.i(
                TAG,
                "\tsetDeviceAuthorized(): device: " + device + ", isAuthorized: " + isAuthorized);
        int authorization =
                isAuthorized ? BluetoothDevice.ACCESS_ALLOWED : BluetoothDevice.ACCESS_REJECTED;
        mDeviceAuthorizations.put(device, authorization);

        mGmcs.onDeviceAuthorizationSet(device);
    }

    int getDeviceAuthorization(BluetoothDevice device) {
        /* Media control is allowed for
         * 1. in PTS mode
         * 2. authorized devices
         * 3. Any LeAudio devices which are allowed to connect
         */
        int authorization =
                mDeviceAuthorizations.getOrDefault(
                        device,
                        Utils.isPtsTestMode()
                                ? BluetoothDevice.ACCESS_ALLOWED
                                : BluetoothDevice.ACCESS_UNKNOWN);
        if (authorization != BluetoothDevice.ACCESS_UNKNOWN) {
            return authorization;
        }

        LeAudioService leAudioService = LeAudioService.getLeAudioService();
        if (leAudioService == null) {
            Log.e(TAG, "MCS access not permitted. LeAudioService not available");
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        if (leAudioService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN) {
            Log.d(TAG, "MCS authorization allowed based on supported LeAudio service");
            setDeviceAuthorized(device, true);
            return BluetoothDevice.ACCESS_ALLOWED;
        }

        Log.e(TAG, "MCS access not permitted");
        return BluetoothDevice.ACCESS_UNKNOWN;
    }

    List<ParcelUuid> getNotificationSubscriptions(int ccid, BluetoothDevice device) {
        return mGmcs.getNotificationSubscriptions(ccid, device);
    }

    void setNotificationSubscription(
            int ccid, BluetoothDevice device, ParcelUuid charUuid, boolean doNotify) {
        mGmcs.setNotificationSubscription(ccid, device, charUuid, doNotify);
    }

    /** Binder object: must be a static class or memory leak may occur */
    static class BluetoothMcpServiceBinder extends IBluetoothMcpServiceManager.Stub
            implements IProfileServiceBinder {
        private McpService mService;

        BluetoothMcpServiceBinder(McpService svc) {
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
}
