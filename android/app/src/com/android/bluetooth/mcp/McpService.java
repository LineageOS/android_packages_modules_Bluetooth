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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.ParcelUuid;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides Media Control Profile, as a service in the Bluetooth application. */
public class McpService extends ProfileService {
    private static final String TAG = Util.BT_PREFIX + McpService.class.getSimpleName();

    private final MediaControlProfile mGmcs;
    private final Map<BluetoothDevice, Integer> mDeviceAuthorizations = new HashMap<>();

    public McpService(AdapterService adapterService) {
        this(adapterService, new MediaControlProfile(adapterService));
    }

    @VisibleForTesting
    McpService(AdapterService adapterService, MediaControlProfile mediaControlProfile) {
        super(BluetoothProfile.MCP_SERVER, adapterService);
        mGmcs = requireNonNull(mediaControlProfile);

        mGmcs.init(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileMcpServerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mGmcs.cleanup();
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
            sb.append("\n    Device: ")
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
        Log.i(TAG, "setDeviceAuthorized(): device: " + device + ", isAuthorized: " + isAuthorized);
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

        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.e(TAG, "MCS access not permitted. LeAudioService not available");
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        if (leAudio.get().getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN) {
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

    public void playRequest() {
        mGmcs.onMediaControlRequest(new Request(Request.Opcodes.PLAY, 0));
    }
}
