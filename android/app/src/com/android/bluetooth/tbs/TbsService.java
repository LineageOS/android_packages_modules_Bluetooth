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

package com.android.bluetooth.tbs;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeCall;
import android.bluetooth.BluetoothProfile;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.profile.ProfileService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TbsService extends ProfileService {
    private static final String TAG = TbsService.class.getSimpleName();

    /** Callback for TBS events. */
    public interface Callback {
        void onBearerRegistered(int ccid);

        void onAcceptCall(int requestId, UUID uuid);

        void onTerminateCall(int requestId, UUID uuid);

        void onHoldCall(int requestId, UUID uuid);

        void onUnholdCall(int requestId, UUID uuid);

        void onPlaceCall(int requestId, UUID uuid, String uri);

        void onJoinCalls(int requestId, List<UUID> uuids);
    }

    private final GattService unusedGattService;
    private final Map<BluetoothDevice, Integer> mDeviceAuthorizations = new HashMap<>();
    private final TbsGeneric mTbsGeneric;

    public TbsService(AdapterService adapterService) {
        this(adapterService, null);
    }

    public TbsService(AdapterService adapterService, GattService gattService) {
        super(BluetoothProfile.LE_CALL_CONTROL, adapterService);
        unusedGattService = requireNonNull(gattService);

        mTbsGeneric = new TbsGeneric(adapterService, new TbsGatt(adapterService, this));
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileCcpServerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mTbsGeneric.cleanup();
        mDeviceAuthorizations.clear();
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

    /**
     * Sets device authorization for TBS.
     *
     * @param device device that would be authorized
     * @param isAuthorized boolean value of authorization permission
     */
    public void setDeviceAuthorized(BluetoothDevice device, boolean isAuthorized) {
        Log.i(TAG, "setDeviceAuthorized(): device: " + device + ", isAuthorized: " + isAuthorized);
        int authorization =
                isAuthorized ? BluetoothDevice.ACCESS_ALLOWED : BluetoothDevice.ACCESS_REJECTED;
        mDeviceAuthorizations.put(device, authorization);

        mTbsGeneric.onDeviceAuthorizationSet(device);
    }

    /**
     * Returns authorization value for given device.
     *
     * @param device device that would be authorized
     * @return authorization value for device
     *     <p>Possible authorization values: {@link BluetoothDevice.ACCESS_UNKNOWN}, {@link
     *     BluetoothDevice.ACCESS_ALLOWED}
     */
    public int getDeviceAuthorization(BluetoothDevice device) {
        /* Telephony Bearer Service is allowed for
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
            Log.e(TAG, "TBS access not permitted. LeAudioService not available");
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        if (leAudio.get().getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN) {
            Log.d(TAG, "TBS authorization allowed based on supported LeAudio service");
            setDeviceAuthorized(device, true);
            return BluetoothDevice.ACCESS_ALLOWED;
        }

        Log.e(TAG, "TBS access not permitted");
        return BluetoothDevice.ACCESS_UNKNOWN;
    }

    /**
     * Set inband ringtone for the device. When set, notification will be sent to given device.
     *
     * @param device device for which inband ringtone has been set
     */
    public void setInbandRingtoneSupport(BluetoothDevice device) {
        mTbsGeneric.setInbandRingtoneSupport(device);
    }

    /**
     * Clear inband ringtone for the device. When set, notification will be sent to given device.
     *
     * @param device device for which inband ringtone has been clear
     */
    public void clearInbandRingtoneSupport(BluetoothDevice device) {
        mTbsGeneric.clearInbandRingtoneSupport(device);
    }

    public void registerBearer(
            String token,
            Callback callback,
            String uci,
            List<String> uriSchemes,
            int capabilities,
            String providerName,
            int technology) {
        Log.d(TAG, "registerBearer: token=" + token);

        boolean success =
                mTbsGeneric.addBearer(
                        token, callback, uci, uriSchemes, capabilities, providerName, technology);
        if (!success) {
            Log.e(TAG, "Failed to register bearer for token=" + token);
        }
    }

    public void unregisterBearer(String token) {
        Log.d(TAG, "unregisterBearer: token=" + token);

        mTbsGeneric.removeBearer(token);
    }

    public void requestResult(int ccid, int requestId, int result) {
        Log.d(TAG, "requestResult: ccid=" + ccid + " requestId=" + requestId + " result=" + result);

        mTbsGeneric.requestResult(ccid, requestId, result);
    }

    public void callAdded(int ccid, BluetoothLeCall call) {
        Log.d(TAG, "callAdded: ccid=" + ccid + " call=" + call);

        mTbsGeneric.callAdded(ccid, call);
    }

    public void callRemoved(int ccid, UUID callId, int reason) {
        Log.d(TAG, "callRemoved: ccid=" + ccid + " callId=" + callId + " reason=" + reason);

        mTbsGeneric.callRemoved(ccid, callId, reason);
    }

    public void callStateChanged(int ccid, UUID callId, int state) {
        Log.d(TAG, "callStateChanged: ccid=" + ccid + " callId=" + callId + " state=" + state);

        mTbsGeneric.callStateChanged(ccid, callId, state);
    }

    public void currentCallsList(int ccid, List<BluetoothLeCall> calls) {
        Log.d(TAG, "currentCallsList: ccid=" + ccid + " calls=" + calls);

        mTbsGeneric.currentCallsList(ccid, calls);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        mTbsGeneric.dump(sb);

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
}
