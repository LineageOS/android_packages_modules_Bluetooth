/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.bluetooth.mapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothMapClient;
import android.bluetooth.SdpMasRecord;
import android.content.AttributionSource;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MapClientService extends ProfileService {
    private static final String TAG = MapClientService.class.getSimpleName();

    static final int MAXIMUM_CONNECTED_DEVICES = 4;

    private final Map<BluetoothDevice, MceStateMachine> mMapInstanceMap =
            new ConcurrentHashMap<>(1);

    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final MnsService mMnsServer;
    private final Looper mStateMachinesLooper;
    private final Handler mHandler;

    private static MapClientService sMapClientService;

    public MapClientService(AdapterService adapterService) {
        this(adapterService, null, null);
    }

    @VisibleForTesting
    MapClientService(AdapterService adapterService, Looper looper, MnsService mnsServer) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(adapterService.getDatabase());
        mMnsServer = requireNonNullElseGet(mnsServer, () -> new MnsService(this));

        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesLooper = null;
        } else {
            mHandler = new Handler(looper);

            // MapClient is only using a common state machine looper for test.
            // In real device, it use a thread per device connected to avoid congestion.
            mStateMachinesLooper = looper;
        }

        removeUncleanAccounts();
        MapClientContent.clearAllContent(this);
        setMapClientService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileMapClientEnabled().orElse(false);
    }

    public static synchronized MapClientService getMapClientService() {
        if (sMapClientService == null) {
            Log.w(TAG, "getMapClientService(): service is null");
            return null;
        }
        if (!sMapClientService.isAvailable()) {
            Log.w(TAG, "getMapClientService(): service is not available ");
            return null;
        }
        return sMapClientService;
    }

    @VisibleForTesting
    static synchronized void setMapClientService(MapClientService instance) {
        Log.d(TAG, "setMapClientService(): set to: " + instance);
        sMapClientService = instance;
    }

    @VisibleForTesting
    Map<BluetoothDevice, MceStateMachine> getInstanceMap() {
        return mMapInstanceMap;
    }

    /**
     * Connect the given Bluetooth device.
     *
     * @return true if connection is successful, false otherwise.
     */
    public synchronized boolean connect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "connect(device= " + device + "): devices=" + mMapInstanceMap.keySet());
        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.w(
                    TAG,
                    "Connection not allowed: <"
                            + device.getAddress()
                            + "> is CONNECTION_POLICY_FORBIDDEN");
            return false;
        }
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        if (mapStateMachine == null) {
            // a map state machine instance doesn't exist yet, create a new one if we can.
            if (mMapInstanceMap.size() < MAXIMUM_CONNECTED_DEVICES) {
                addDeviceToMapAndConnect(device);
                return true;
            } else {
                // Maxed out on the number of allowed connections.
                // see if some of the current connections can be cleaned-up, to make room.
                removeUncleanAccounts();
                if (mMapInstanceMap.size() < MAXIMUM_CONNECTED_DEVICES) {
                    addDeviceToMapAndConnect(device);
                    return true;
                } else {
                    Log.e(
                            TAG,
                            "Maxed out on the number of allowed MAP connections. "
                                    + "Connect request rejected on "
                                    + device);
                    return false;
                }
            }
        }

        // StateMachine already exists in the map.
        int state = getConnectionState(device);
        if (state == STATE_CONNECTED || state == STATE_CONNECTING) {
            Log.w(TAG, "Received connect request while already connecting/connected.");
            return true;
        }

        // StateMachine exists but not in connecting or connected state! it should
        // have been removed form the map. lets get rid of it and add a new one.
        Log.d(TAG, "StateMachine exists for a device in unexpected state: " + state);
        mMapInstanceMap.remove(device);
        mapStateMachine.doQuit();

        addDeviceToMapAndConnect(device);
        Log.d(TAG, "connect(device= " + device + "): end devices=" + mMapInstanceMap.keySet());
        return true;
    }

    private synchronized void addDeviceToMapAndConnect(BluetoothDevice device) {
        // When creating a new StateMachine, its state is set to CONNECTING - which will trigger
        // connect.
        MceStateMachine mapStateMachine;
        if (mStateMachinesLooper != null) {
            mapStateMachine =
                    new MceStateMachine(this, device, mAdapterService, mStateMachinesLooper);
        } else {
            mapStateMachine = new MceStateMachine(this, device, mAdapterService);
        }
        mMapInstanceMap.put(device, mapStateMachine);
    }

    public synchronized boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(device= " + device + "): devices=" + mMapInstanceMap.keySet());
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        // a map state machine instance doesn't exist. maybe it is already gone?
        if (mapStateMachine == null) {
            return false;
        }
        int connectionState = mapStateMachine.getState();
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }
        mapStateMachine.disconnect();
        Log.d(TAG, "disconnect(device= " + device + "): end devices=" + mMapInstanceMap.keySet());
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesMatchingConnectionStates(new int[] {BluetoothAdapter.STATE_CONNECTED});
    }

    MceStateMachine getMceStateMachineForDevice(BluetoothDevice device) {
        return mMapInstanceMap.get(device);
    }

    public synchronized List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.d(TAG, "getDevicesMatchingConnectionStates" + Arrays.toString(states));
        List<BluetoothDevice> deviceList = new ArrayList<>();
        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        int connectionState;
        for (BluetoothDevice device : bondedDevices) {
            connectionState = getConnectionState(device);
            Log.d(TAG, "Device: " + device + "State: " + connectionState);
            for (int i = 0; i < states.length; i++) {
                if (connectionState == states[i]) {
                    deviceList.add(device);
                }
            }
        }
        Log.d(TAG, deviceList.toString());
        return deviceList;
    }

    public synchronized int getConnectionState(BluetoothDevice device) {
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        // a map state machine instance doesn't exist yet, create a new one if we can.
        return (mapStateMachine == null) ? STATE_DISCONNECTED : mapStateMachine.getState();
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.v(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.MAP_CLIENT, connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.MAP_CLIENT);
    }

    public synchronized boolean sendMessage(
            BluetoothDevice device,
            Uri[] contacts,
            String message,
            PendingIntent sentIntent,
            PendingIntent deliveredIntent) {
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        return mapStateMachine != null
                && mapStateMachine.sendMapMessage(contacts, message, sentIntent, deliveredIntent);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new Binder(this);
    }

    @Override
    public synchronized void cleanup() {
        Log.i(TAG, "Cleanup MapClient Service");

        mMnsServer.stop();
        for (MceStateMachine stateMachine : mMapInstanceMap.values()) {
            if (stateMachine.getState() == BluetoothAdapter.STATE_CONNECTED) {
                stateMachine.disconnect();
            }
            stateMachine.doQuit();
        }
        mMapInstanceMap.clear();

        // Unregister Handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null);

        removeUncleanAccounts();

        setMapClientService(null);
    }

    /**
     * cleanupDevice removes the associated state machine from the instance map
     *
     * @param device BluetoothDevice address of remote device
     * @param sm the state machine to clean up or {@code null} to clean up any state machine.
     */
    @VisibleForTesting
    public void cleanupDevice(BluetoothDevice device, MceStateMachine sm) {
        Log.d(TAG, "cleanup(device= " + device + "): devices=" + mMapInstanceMap.keySet());
        synchronized (mMapInstanceMap) {
            MceStateMachine stateMachine = mMapInstanceMap.get(device);
            if (stateMachine != null) {
                if (sm == null || stateMachine == sm) {
                    mMapInstanceMap.remove(device);
                    stateMachine.doQuit();
                } else {
                    Log.w(TAG, "Trying to clean up wrong state machine");
                }
            }
        }
        Log.d(TAG, "cleanup(device= " + device + "): end devices=" + mMapInstanceMap.keySet());
    }

    @VisibleForTesting
    void removeUncleanAccounts() {
        Log.d(TAG, "removeUncleanAccounts(): devices=" + mMapInstanceMap.keySet());
        Iterator iterator = mMapInstanceMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BluetoothDevice, MceStateMachine> profileConnection =
                    (Map.Entry) iterator.next();
            if (profileConnection.getValue().getState() == STATE_DISCONNECTED) {
                iterator.remove();
            }
        }
        Log.d(TAG, "removeUncleanAccounts(): end devices=" + mMapInstanceMap.keySet());
    }

    public synchronized boolean getUnreadMessages(BluetoothDevice device) {
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        if (mapStateMachine == null) {
            return false;
        }
        return mapStateMachine.getUnreadMessages();
    }

    /**
     * Returns the SDP record's MapSupportedFeatures field (see Bluetooth MAP 1.4 spec, page 114).
     *
     * @param device The Bluetooth device to get this value for.
     * @return the SDP record's MapSupportedFeatures field.
     */
    public synchronized int getSupportedFeatures(BluetoothDevice device) {
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        if (mapStateMachine == null) {
            Log.d(TAG, "in getSupportedFeatures, returning 0");
            return 0;
        }
        return mapStateMachine.getSupportedFeatures();
    }

    public synchronized boolean setMessageStatus(
            BluetoothDevice device, String handle, int status) {
        MceStateMachine mapStateMachine = mMapInstanceMap.get(device);
        if (mapStateMachine == null) {
            return false;
        }
        return mapStateMachine.setMessageStatus(handle, status);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (MceStateMachine stateMachine : mMapInstanceMap.values()) {
            stateMachine.dump(sb);
        }
    }

    // Binder object: Must be static class or memory leak may occur

    /**
     * This class implements the IClient interface - or actually it validates the preconditions for
     * calling the actual functionality in the MapClientService, and calls it.
     */
    @VisibleForTesting
    static class Binder extends IBluetoothMapClient.Stub implements IProfileServiceBinder {
        private MapClientService mService;

        Binder(MapClientService service) {
            mService = service;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private MapClientService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            MapClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !(getCallingUserHandle().isSystem()
                            || Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG))
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }
            return service;
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private MapClientService getServiceAndEnforcePrivileged(AttributionSource source) {
            // Cache mService because it can change while getService is called
            MapClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !(getCallingUserHandle().isSystem()
                            || Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG))
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @Override
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "connect()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return false;
            }

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "disconnect()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            Log.v(TAG, "getConnectedDevices()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            Log.v(TAG, "getDevicesMatchingConnectionStates()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "getConnectionState()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            Log.v(TAG, "setConnectionPolicy()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return false;
            }

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "getConnectionPolicy()");

            MapClientService service = getServiceAndEnforcePrivileged(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            return service.getConnectionPolicy(device);
        }

        @Override
        public boolean sendMessage(
                BluetoothDevice device,
                Uri[] contacts,
                String message,
                PendingIntent sentIntent,
                PendingIntent deliveredIntent,
                AttributionSource source) {
            Log.v(TAG, "sendMessage()");

            MapClientService service = getService(source);
            if (service == null) {
                return false;
            }

            Log.d(TAG, "Checking Permission of sendMessage");
            mService.enforceCallingOrSelfPermission(
                    Manifest.permission.SEND_SMS, "Need SEND_SMS permission");

            return service.sendMessage(device, contacts, message, sentIntent, deliveredIntent);
        }
    }

    public void aclDisconnected(BluetoothDevice device, int transport) {
        mHandler.post(() -> handleAclDisconnected(device, transport));
    }

    private void handleAclDisconnected(BluetoothDevice device, int transport) {
        MceStateMachine stateMachine = mMapInstanceMap.get(device);
        if (stateMachine == null) {
            Log.e(TAG, "No StateMachine found for the device=" + device);
            return;
        }

        Log.i(
                TAG,
                "Received ACL disconnection event, device=" + device + ", transport=" + transport);

        if (transport != BluetoothDevice.TRANSPORT_BREDR) {
            return;
        }

        if (stateMachine.getState() == STATE_CONNECTED) {
            stateMachine.disconnect();
        }
    }

    public void receiveSdpSearchRecord(
            BluetoothDevice device, int status, Parcelable record, ParcelUuid uuid) {
        mHandler.post(() -> handleSdpSearchRecordReceived(device, status, record, uuid));
    }

    private void handleSdpSearchRecordReceived(
            BluetoothDevice device, int status, Parcelable record, ParcelUuid uuid) {
        MceStateMachine stateMachine = mMapInstanceMap.get(device);
        Log.d(TAG, "Received SDP Record, device=" + device + ", uuid=" + uuid);
        if (stateMachine == null) {
            Log.e(TAG, "No StateMachine found for the device=" + device);
            return;
        }
        if (uuid.equals(BluetoothUuid.MAS)) {
            // Check if we have a valid SDP record.
            SdpMasRecord masRecord = (SdpMasRecord) record;
            Log.d(TAG, "SDP complete, status: " + status + ", record:" + masRecord);
            stateMachine.sendSdpResult(status, masRecord);
        }
    }
}
