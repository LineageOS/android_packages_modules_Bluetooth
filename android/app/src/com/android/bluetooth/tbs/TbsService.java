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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeCall;
import android.bluetooth.IBluetoothLeCallControl;
import android.bluetooth.IBluetoothLeCallControlCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TbsService extends ProfileService {
    private static final String TAG = TbsService.class.getSimpleName();

    private static TbsService sTbsService;

    private final Map<BluetoothDevice, Integer> mDeviceAuthorizations = new HashMap<>();
    private final TbsGeneric mTbsGeneric;

    public TbsService(AdapterService adapterService) {
        super(requireNonNull(adapterService));

        // Mark service as started
        setTbsService(this);

        mTbsGeneric = new TbsGeneric(adapterService, new TbsGatt(adapterService, this));
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileCcpServerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new TbsServerBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Tbs Service");

        if (sTbsService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        // Mark service as stopped
        setTbsService(null);

        mTbsGeneric.cleanup();

        mDeviceAuthorizations.clear();
    }

    /**
     * Get the TbsService instance
     *
     * @return TbsService instance
     */
    public static synchronized TbsService getTbsService() {
        if (sTbsService == null) {
            Log.w(TAG, "getTbsService: service is NULL");
            return null;
        }

        if (!sTbsService.isAvailable()) {
            Log.w(TAG, "getTbsService: service is not available");
            return null;
        }

        return sTbsService;
    }

    private static synchronized void setTbsService(TbsService instance) {
        Log.d(TAG, "setTbsService: set to=" + instance);

        sTbsService = instance;
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

        LeAudioService leAudioService = LeAudioService.getLeAudioService();
        if (leAudioService == null) {
            Log.e(TAG, "TBS access not permitted. LeAudioService not available");
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        if (leAudioService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN) {
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

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class TbsServerBinder extends IBluetoothLeCallControl.Stub
            implements IProfileServiceBinder {
        private TbsService mService;

        TbsServerBinder(TbsService service) {
            mService = service;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private TbsService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
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
        public void callStateChanged(
                int ccid, ParcelUuid callId, int state, AttributionSource source) {
            TbsService service = getService(source);
            if (service != null) {
                service.callStateChanged(ccid, callId.getUuid(), state);
            }
        }

        @Override
        public void currentCallsList(
                int ccid, List<BluetoothLeCall> calls, AttributionSource source) {
            TbsService service = getService(source);
            if (service != null) {
                service.currentCallsList(ccid, calls);
            }
        }
    }

    @VisibleForTesting
    void registerBearer(
            String token,
            IBluetoothLeCallControlCallback callback,
            String uci,
            List<String> uriSchemes,
            int capabilities,
            String providerName,
            int technology) {
        Log.d(TAG, "registerBearer: token=" + token);

        boolean success =
                mTbsGeneric.addBearer(
                        token, callback, uci, uriSchemes, capabilities, providerName, technology);
        if (success) {
            try {
                callback.asBinder()
                        .linkToDeath(
                                () -> {
                                    Log.e(TAG, token + " application died, removing...");
                                    unregisterBearer(token);
                                },
                                0);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }

        Log.d(TAG, "registerBearer: token=" + token + " success=" + success);
    }

    @VisibleForTesting
    void unregisterBearer(String token) {
        Log.d(TAG, "unregisterBearer: token=" + token);

        mTbsGeneric.removeBearer(token);
    }

    @VisibleForTesting
    public void requestResult(int ccid, int requestId, int result) {
        Log.d(TAG, "requestResult: ccid=" + ccid + " requestId=" + requestId + " result=" + result);

        mTbsGeneric.requestResult(ccid, requestId, result);
    }

    @VisibleForTesting
    void callAdded(int ccid, BluetoothLeCall call) {
        Log.d(TAG, "callAdded: ccid=" + ccid + " call=" + call);

        mTbsGeneric.callAdded(ccid, call);
    }

    @VisibleForTesting
    void callRemoved(int ccid, UUID callId, int reason) {
        Log.d(TAG, "callRemoved: ccid=" + ccid + " callId=" + callId + " reason=" + reason);

        mTbsGeneric.callRemoved(ccid, callId, reason);
    }

    @VisibleForTesting
    void callStateChanged(int ccid, UUID callId, int state) {
        Log.d(TAG, "callStateChanged: ccid=" + ccid + " callId=" + callId + " state=" + state);

        mTbsGeneric.callStateChanged(ccid, callId, state);
    }

    @VisibleForTesting
    void currentCallsList(int ccid, List<BluetoothLeCall> calls) {
        Log.d(TAG, "currentCallsList: ccid=" + ccid + " calls=" + calls);

        mTbsGeneric.currentCallsList(ccid, calls);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        sb.append("TbsService instance:\n");

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
            sb.append("\n\tDevice: ")
                    .append(entry.getKey())
                    .append(", access: ")
                    .append(accessString);
        }
    }
}
