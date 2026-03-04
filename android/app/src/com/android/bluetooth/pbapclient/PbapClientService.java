/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpPseRecord;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth Phone Book Access Profile Client profile. */
public class PbapClientService extends ConnectableProfile {
    private static final String TAG = PbapClientService.class.getSimpleName();

    private static final String SERVICE_NAME = "Phonebook Access PCE";

    /** The component names for the owned authenticator service */
    private static final String AUTHENTICATOR_SERVICE =
            PbapClientAccountAuthenticatorService.class.getCanonicalName();

    // MAXIMUM_DEVICES set to 10 to prevent an excessive number of simultaneous devices.
    private static final int MAXIMUM_DEVICES = 10;

    private final PbapClientContactsStorage mPbapClientContactsStorage;
    private final Map<BluetoothDevice, PbapClientStateMachine> mPbapClientStateMachineMap;
    private final Handler mHandler;
    private final Looper mStateMachinesLooper;

    private int mSdpHandle = -1;

    class PbapClientStateMachineCallback implements PbapClientStateMachine.Callback {
        private final BluetoothDevice mDevice;

        PbapClientStateMachineCallback(BluetoothDevice device) {
            mDevice = device;
        }

        @Override
        public void onConnectionStateChanged(int oldState, int newState) {
            Log.v(
                    TAG,
                    "Device connection state changed, device="
                            + mDevice
                            + ", old="
                            + oldState
                            + ", new="
                            + newState);
            if (oldState != newState && newState == STATE_DISCONNECTED) {
                removeDevice(mDevice);
            }
        }
    }

    public PbapClientService(AdapterService adapterService) {
        super(BluetoothProfile.PBAP_CLIENT, adapterService);
        mHandler = new Handler(Looper.getMainLooper());
        mStateMachinesLooper = null;

        mPbapClientContactsStorage = new PbapClientContactsStorage(adapterService);
        mPbapClientStateMachineMap =
                new ConcurrentHashMap<BluetoothDevice, PbapClientStateMachine>();
        mPbapClientContactsStorage.start();

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        registerSdpRecord();
    }

    @VisibleForTesting
    PbapClientService(
            AdapterService adapterService,
            PbapClientContactsStorage storage,
            Map<BluetoothDevice, PbapClientStateMachine> deviceMap,
            Looper looper) {
        super(BluetoothProfile.PBAP_CLIENT, adapterService);

        // This is an override unique to this constructor which belongs to tests only
        mHandler = new Handler(looper);
        mStateMachinesLooper = looper;

        mPbapClientContactsStorage = storage;
        mPbapClientStateMachineMap = deviceMap;

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        mPbapClientContactsStorage.start();

        registerSdpRecord();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfilePbapClientEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new PbapClientServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        cleanUpSdpRecord();

        // Unregister SDP event handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null);

        // Try to bring down all the connections gracefully
        synchronized (mPbapClientStateMachineMap) {
            for (PbapClientStateMachine sm : mPbapClientStateMachineMap.values()) {
                sm.disconnect();
            }
            mPbapClientStateMachineMap.clear();
        }
        mPbapClientContactsStorage.stop();

        setComponentAvailable(AUTHENTICATOR_SERVICE, false);
    }

    /**
     * Add our PBAP Client SDP record to the device SDP database
     *
     * <p>This allows our client to be recognized by the remove device. The record must be cleaned
     * up when we shutdown.
     */
    private void registerSdpRecord() {
        final var nativeInterface = getAdapterService().getSdpManagerNativeInterface();
        if (nativeInterface.isEmpty()) {
            Log.e(TAG, "SdpManagerNativeInterface is not available");
            return;
        }
        mSdpHandle =
                nativeInterface.get().createPbapPceRecord(SERVICE_NAME, PbapSdpRecord.VERSION_1_2);
    }

    /**
     * Remove our PBAP Client SDP record from the device SDP database
     *
     * <p>Gracefully removes PBAP Client support from our SDP records. Called when shutting down.
     */
    private void cleanUpSdpRecord() {
        if (mSdpHandle < 0) {
            Log.e(TAG, "cleanUpSdpRecord, SDP record never created");
            return;
        }
        int sdpHandle = mSdpHandle;
        mSdpHandle = -1;
        final var nativeInterface = getAdapterService().getSdpManagerNativeInterface();
        if (nativeInterface.isEmpty()) {
            Log.e(
                    TAG,
                    "cleanUpSdpRecord failed, SdpManagerNativeInterface is not available,"
                            + " sdpHandle="
                            + sdpHandle);
            return;
        }
        Log.i(TAG, "cleanUpSdpRecord, mSdpHandle=" + sdpHandle);
        if (!nativeInterface.get().removeSdpRecord(sdpHandle)) {
            Log.e(TAG, "cleanUpSdpRecord, removeSdpRecord failed, sdpHandle=" + sdpHandle);
        }
    }

    private PbapClientStateMachine getDeviceStateMachine(BluetoothDevice device) {
        synchronized (mPbapClientStateMachineMap) {
            return mPbapClientStateMachineMap.get(device);
        }
    }

    /**
     * Create a state machine for a device
     *
     * <p>PBAP Client connections are always outgoing. This function creates a device state machine
     * instance, which will manage the connection and data lifecycles of the device.
     */
    private boolean addDevice(BluetoothDevice device) {
        Log.d(TAG, "add device, device=" + device);
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine stateMachine = mPbapClientStateMachineMap.get(device);
            if (stateMachine == null) {
                if (mPbapClientStateMachineMap.size() >= MAXIMUM_DEVICES) {
                    Log.w(TAG, "Cannot connect " + device + ", too many devices connected already");
                    return false;
                }
                var looper = mStateMachinesLooper;
                if (looper == null) {
                    final var stateMachineThread = new HandlerThread("PbapClientStateMachine");
                    stateMachineThread.start();
                    looper = stateMachineThread.getLooper();
                }
                stateMachine =
                        new PbapClientStateMachine(
                                getAdapterService(),
                                device,
                                mPbapClientContactsStorage,
                                this,
                                looper,
                                new PbapClientStateMachineCallback(device),
                                null);
                stateMachine.start();
                stateMachine.connect();
                mPbapClientStateMachineMap.put(device, stateMachine);
                return true;
            } else {
                Log.w(TAG, "Cannot connect " + device + ", already connecting/connected.");
                return false;
            }
        }
    }

    /**
     * Remove a device state machine, if it exists
     *
     * <p>When a device disconnects, we gracefully clean up its state machine instance and drop our
     * reference to it. State machines cannot be reused, so this must be deleted before a device can
     * reconnect.
     */
    private void removeDevice(BluetoothDevice device) {
        Log.d(TAG, "remove device, device=" + device);
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
            if (pbapClientStateMachine != null) {
                int state = pbapClientStateMachine.getConnectionState();
                if (state != STATE_DISCONNECTED) {
                    Log.w(TAG, "Removing connected device, device=" + device + ", state=" + state);
                }
                mPbapClientStateMachineMap.remove(device);
            }
        }
    }

    /**
     * Ensure that after HFP disconnects, we remove call logs. This addresses the situation when
     * PBAP was never connected while calls were made. Ideally {@link PbapClientConnectionHandler}
     * has code to remove call logs when PBAP disconnects.
     */
    public void handleHeadsetClientConnectionStateChanged(
            BluetoothDevice device, int oldState, int newState) {
        if (newState == STATE_DISCONNECTED) {
            Log.d(TAG, "Received intent to disconnect HFP with " + device);
            Account account = mPbapClientContactsStorage.getStorageAccountForDevice(device);
            mPbapClientContactsStorage.removeCallHistory(account);
        }
    }

    /**
     * Get debug information about this PbapClientService instance
     *
     * @param sb The StringBuilder instance to add our debug dump info to
     */
    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        synchronized (mPbapClientStateMachineMap) {
            ProfileService.println(
                    sb,
                    "Devices (" + mPbapClientStateMachineMap.size() + "/ " + MAXIMUM_DEVICES + ")");
            for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
                stateMachine.dump(sb);
                ProfileService.println(sb, "");
            }
        }
        ProfileService.println(sb, mPbapClientContactsStorage.dump());
    }

    // *********************************************************************************************
    // * Events from AdapterService
    // *********************************************************************************************

    /**
     * Get notified of incoming ACL disconnections
     *
     * <p>OBEX client's are supposed to be in control of the connection lifecycle, and servers are
     * not supposed to disconnect OBEX sessions. Despite this, its normal/possible the remote device
     * to tear down connections at lower levels than OBEX, mainly the L2CAP/RFCOMM links or the ACL.
     * The OBEX framework isn't setup to be notified of these disconnections, so we must listen for
     * them separately and clean up the device connection and, if necessary, data when this happens.
     *
     * @param device The device that had the ACL disconnect
     * @param transport The transport the device disconnected on
     */
    public void aclDisconnected(BluetoothDevice device, int transport) {
        mHandler.post(() -> handleAclDisconnected(device, transport));
    }

    private void handleAclDisconnected(BluetoothDevice device, int transport) {
        Log.i(
                TAG,
                "Received ACL disconnection event, device="
                        + device.toString()
                        + ", transport="
                        + transport);

        if (transport != TRANSPORT_BREDR) {
            return;
        }

        if (getConnectionState(device) == STATE_CONNECTED) {
            disconnect(device);
        }
    }

    /**
     * Get notified of incoming SDP records
     *
     * <p>This function looks for PBAP Server records coming from remote devices, and forwards them
     * to the appropriate device's state machine instance for processing. SDP records are used to
     * determine which L2CAP/RFCOMM psm/channel to connect on, as well as which phonebooks to expect
     */
    public void receiveSdpSearchRecord(
            BluetoothDevice device, int status, Parcelable record, ParcelUuid uuid) {
        Log.v(
                TAG,
                "Received SDP record for UUID="
                        + uuid.toString()
                        + " (expected UUID="
                        + BluetoothUuid.PBAP_PSE.toString()
                        + ")");
        if (uuid.equals(BluetoothUuid.PBAP_PSE)) {
            PbapClientStateMachine stateMachine = getDeviceStateMachine(device);
            if (stateMachine == null) {
                Log.e(TAG, "No StateMachine found for the device=" + device.toString());
                return;
            }

            SdpPseRecord pseRecord = (SdpPseRecord) record;
            PbapSdpRecord pbapRecord = null;
            if (pseRecord != null) {
                pbapRecord = new PbapSdpRecord(device, pseRecord);
            }
            stateMachine.onSdpResultReceived(status, pbapRecord);
        }
    }

    // *********************************************************************************************
    // * API methods
    // *********************************************************************************************

    /**
     * Requests a connection to the given device's PBAP Server
     *
     * @param device is the device with which we will connect to
     * @return true if we successfully begin the connection process, false otherwise
     */
    @Override
    public boolean connect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "connect(device=" + device.getAddress() + ")");
        if (getConnectionPolicy(device) <= CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }

        return addDevice(device);
    }

    /**
     * Disconnects the pbap client profile from the passed in device
     *
     * @param device is the device with which we will disconnect the pbap client profile
     * @return true if we disconnected the pbap client profile, false otherwise
     */
    @Override
    public boolean disconnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }

        Log.d(TAG, "disconnect(device=" + device.getAddress() + ")");
        PbapClientStateMachine pbapClientStateMachine = getDeviceStateMachine(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.disconnect();
            return true;
        }

        Log.w(TAG, "disconnect() called on unconnected device.");
        return false;
    }

    /**
     * Get the list of PBAP Server devices this PBAP Client device is connected to
     *
     * @return The list of connected PBAP Server devices
     */
    public List<BluetoothDevice> getConnectedDevices() {
        int[] desiredStates = {STATE_CONNECTED};
        return getDevicesMatchingConnectionStates(desiredStates);
    }

    /**
     * Get the list of PBAP Server devices this PBAP Client device know about, who are in a given
     * state.
     *
     * @param states The array of BluetoothProfile states you want to match on
     * @return The list of connected PBAP Server devices
     */
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>(0);

        synchronized (mPbapClientStateMachineMap) {
            for (Map.Entry<BluetoothDevice, PbapClientStateMachine> stateMachineEntry :
                    mPbapClientStateMachineMap.entrySet()) {
                int currentDeviceState = stateMachineEntry.getValue().getConnectionState();
                for (int state : states) {
                    if (currentDeviceState == state) {
                        deviceList.add(stateMachineEntry.getKey());
                        break;
                    }
                }
            }
        }

        return deviceList;
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }

        PbapClientStateMachine pbapClientStateMachine = getDeviceStateMachine(device);
        if (pbapClientStateMachine == null) {
            return STATE_DISCONNECTED;
        } else {
            return pbapClientStateMachine.getConnectionState();
        }
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
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }
}
