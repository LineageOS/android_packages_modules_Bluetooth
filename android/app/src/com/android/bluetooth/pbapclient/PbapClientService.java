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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpPseRecord;
import android.content.ComponentName;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.provider.CallLog;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfpclient.HfpClientConnectionService;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth Phone Book Access Profile Client profile. */
public class PbapClientService extends ProfileService {
    private static final String TAG = PbapClientService.class.getSimpleName();

    private static final String SERVICE_NAME = "Phonebook Access PCE";

    /** The component names for the owned authenticator service */
    private static final String AUTHENTICATOR_SERVICE =
            PbapClientAccountAuthenticatorService.class.getCanonicalName();

    // MAXIMUM_DEVICES set to 10 to prevent an excessive number of simultaneous devices.
    private static final int MAXIMUM_DEVICES = 10;

    @VisibleForTesting
    final Map<BluetoothDevice, PbapClientStateMachineOld> mPbapClientStateMachineOldMap =
            new ConcurrentHashMap<>();

    private static PbapClientService sPbapClientService;
    private final PbapClientContactsStorage mPbapClientContactsStorage;
    private final PbapClientAccountManager mPbapClientAccountManager;
    private final DatabaseManager mDatabaseManager;
    private final Map<BluetoothDevice, PbapClientStateMachine> mPbapClientStateMachineMap;
    private final Handler mHandler;

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

    class PbapClientAccountManagerCallback implements PbapClientAccountManager.Callback {
        @Override
        public void onAccountsChanged(List<Account> oldAccounts, List<Account> newAccounts) {
            Log.i(TAG, "onAccountsChanged: old=" + oldAccounts + ", new=" + newAccounts);
            if (oldAccounts == null) {
                removeUncleanAccounts();
                for (PbapClientStateMachineOld smOld : mPbapClientStateMachineOldMap.values()) {
                    smOld.tryDownloadIfConnected();
                }
            }
        }
    }

    public PbapClientService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mDatabaseManager = requireNonNull(adapterService.getDatabase());
        mHandler = new Handler(Looper.getMainLooper());

        if (Flags.pbapClientStorageRefactor()) {
            mPbapClientContactsStorage = new PbapClientContactsStorage(adapterService);
            mPbapClientAccountManager = null;
            mPbapClientStateMachineMap = new ConcurrentHashMap<>();
            mPbapClientContactsStorage.start();
        } else {
            mPbapClientAccountManager =
                    new PbapClientAccountManager(
                            adapterService, new PbapClientAccountManagerCallback());
            mPbapClientContactsStorage = null;
            mPbapClientStateMachineMap = null;
            mPbapClientAccountManager.start();
        }

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        registerSdpRecord();
        setPbapClientService(this);
    }

    @VisibleForTesting
    PbapClientService(
            AdapterService adapterService,
            PbapClientContactsStorage storage,
            Map<BluetoothDevice, PbapClientStateMachine> deviceMap) {
        super(requireNonNull(adapterService));
        mDatabaseManager = requireNonNull(adapterService.getDatabase());
        mHandler = new Handler(Looper.getMainLooper());

        mPbapClientContactsStorage = storage;
        mPbapClientStateMachineMap = deviceMap;

        // For compatibility with tests while we phase the old state machine out
        mPbapClientAccountManager =
                new PbapClientAccountManager(
                        adapterService, new PbapClientAccountManagerCallback());

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        if (Flags.pbapClientStorageRefactor()) {
            mPbapClientContactsStorage.start();
        } else {
            mPbapClientAccountManager.start();
        }

        registerSdpRecord();
        setPbapClientService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfilePbapClientEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new PbapClientBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup PbapClient Service");

        setPbapClientService(null);
        cleanUpSdpRecord();

        // Unregister SDP event handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null);

        if (Flags.pbapClientStorageRefactor()) {
            // Try to bring down all the connections gracefully
            synchronized (mPbapClientStateMachineMap) {
                for (PbapClientStateMachine sm : mPbapClientStateMachineMap.values()) {
                    sm.disconnect();
                }
                mPbapClientStateMachineMap.clear();
            }
            mPbapClientContactsStorage.stop();
        } else {
            for (PbapClientStateMachineOld smOld : mPbapClientStateMachineOldMap.values()) {
                smOld.doQuit();
            }
            mPbapClientStateMachineOldMap.clear();
            removeUncleanAccounts();
            mPbapClientAccountManager.stop();
        }

        setComponentAvailable(AUTHENTICATOR_SERVICE, false);
    }

    /**
     * Add our PBAP Client SDP record to the device SDP database
     *
     * <p>This allows our client to be recognized by the remove device. The record must be cleaned
     * up when we shutdown.
     */
    private void registerSdpRecord() {
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(TAG, "SdpManagerNativeInterface is not available");
            return;
        }
        mSdpHandle = nativeInterface.createPbapPceRecord(SERVICE_NAME, PbapSdpRecord.VERSION_1_2);
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
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(
                    TAG,
                    "cleanUpSdpRecord failed, SdpManagerNativeInterface is not available,"
                            + " sdpHandle="
                            + sdpHandle);
            return;
        }
        Log.i(TAG, "cleanUpSdpRecord, mSdpHandle=" + sdpHandle);
        if (!nativeInterface.removeSdpRecord(sdpHandle)) {
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
                stateMachine =
                        new PbapClientStateMachine(
                                device,
                                mPbapClientContactsStorage,
                                this,
                                new PbapClientStateMachineCallback(device));
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
     * Clean up any existing accounts.
     *
     * <p>This function gets the list of available Pbap Client accounts and deletes them. Deletion
     * of the account causes Contacts Provider to also delete the associated contacts data. We
     * separately clean up the call log data associated with a given account too.
     */
    private void removeUncleanAccounts() {
        if (Flags.pbapClientStorageRefactor()) {
            Log.i(TAG, "removeUncleanAccounts: this is the responsibility of contacts storage");
            return;
        }

        List<Account> accounts = mPbapClientAccountManager.getAccounts();
        Log.i(TAG, "removeUncleanAccounts: Found " + accounts.size() + " accounts");

        for (Account account : accounts) {
            Log.d(TAG, "removeUncleanAccounts: removing call logs for account=" + account);
            try {
                // The device ID for call logs is the name of the account
                getContentResolver()
                        .delete(
                                CallLog.Calls.CONTENT_URI,
                                CallLog.Calls.PHONE_ACCOUNT_ID + "=?",
                                new String[] {account.name});
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.", e);
            }

            Log.i(TAG, "removeUncleanAccounts: removing account=" + account);
            mPbapClientAccountManager.removeAccount(account);
        }
    }

    private void removeHfpCallLog(String accountName) {
        Log.d(TAG, "Removing call logs from " + accountName);
        // Delete call logs belonging to accountName==BD_ADDR that also match component "hfpclient"
        ComponentName componentName = new ComponentName(this, HfpClientConnectionService.class);
        String selectionFilter =
                CallLog.Calls.PHONE_ACCOUNT_ID
                        + "=? AND "
                        + CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                        + "=?";
        String[] selectionArgs = new String[] {accountName, componentName.flattenToString()};
        try {
            getContentResolver().delete(CallLog.Calls.CONTENT_URI, selectionFilter, selectionArgs);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.");
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
            if (Flags.pbapClientStorageRefactor()) {
                Account account = mPbapClientContactsStorage.getStorageAccountForDevice(device);
                mPbapClientContactsStorage.removeCallHistory(account);
                return;
            } else {
                // HFP client stores entries in calllog.db by BD_ADDR and component name
                // Using the current Service as the context.
                removeHfpCallLog(device.getAddress());
            }
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

        if (Flags.pbapClientStorageRefactor()) {
            synchronized (mPbapClientStateMachineMap) {
                ProfileService.println(
                        sb,
                        "Devices ("
                                + mPbapClientStateMachineMap.size()
                                + "/ "
                                + MAXIMUM_DEVICES
                                + ")");
                for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
                    stateMachine.dump(sb);
                    ProfileService.println(sb, "");
                }
            }
            ProfileService.println(sb, mPbapClientContactsStorage.dump());
        } else {
            for (PbapClientStateMachineOld smOld : mPbapClientStateMachineOldMap.values()) {
                smOld.dump(sb);
            }
            ProfileService.println(sb, mPbapClientAccountManager.dump());
        }
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

        if (transport != BluetoothDevice.TRANSPORT_BREDR) {
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
            SdpPseRecord pseRecord = (SdpPseRecord) record;
            if (pseRecord == null) {
                Log.w(TAG, "Received null PSE record for device=" + device);
                return;
            }

            if (Flags.pbapClientStorageRefactor()) {
                PbapClientStateMachine stateMachine = getDeviceStateMachine(device);
                if (stateMachine == null) {
                    Log.e(TAG, "No StateMachine found for the device=" + device.toString());
                    return;
                }
                stateMachine.onSdpResultReceived(status, new PbapSdpRecord(device, pseRecord));
            } else {
                PbapClientStateMachineOld smOld = mPbapClientStateMachineOldMap.get(device);
                if (smOld == null) {
                    Log.e(TAG, "No StateMachine found for the device=" + device.toString());
                    return;
                }
                smOld.onSdpResultReceived(status, new PbapSdpRecord(device, pseRecord));
            }
        }
    }

    // *********************************************************************************************
    // * API methods
    // *********************************************************************************************

    /** Get the singleton instance of PbapClientService, if one exists */
    public static synchronized PbapClientService getPbapClientService() {
        if (sPbapClientService == null) {
            Log.w(TAG, "getPbapClientService(): service is null");
            return null;
        }
        if (!sPbapClientService.isAvailable()) {
            Log.w(TAG, "getPbapClientService(): service is not available");
            return null;
        }
        return sPbapClientService;
    }

    /**
     * Set the singleton instance of PbapClientService
     *
     * <p>This function is meant to be used by tests only.
     */
    @VisibleForTesting
    static synchronized void setPbapClientService(PbapClientService instance) {
        Log.v(TAG, "setPbapClientService(): set to: " + instance);
        sPbapClientService = instance;
    }

    /**
     * Requests a connection to the given device's PBAP Server
     *
     * @param device is the device with which we will connect to
     * @return true if we successfully begin the connection process, false otherwise
     */
    public boolean connect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "connect(device=" + device.getAddress() + ")");
        if (getConnectionPolicy(device) <= CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }

        if (Flags.pbapClientStorageRefactor()) {
            return addDevice(device);
        } else {
            synchronized (mPbapClientStateMachineOldMap) {
                PbapClientStateMachineOld smOld = mPbapClientStateMachineOldMap.get(device);
                if (smOld == null && mPbapClientStateMachineOldMap.size() < MAXIMUM_DEVICES) {
                    HandlerThread smThread = new HandlerThread("PbapClientStateMachineOld");
                    smThread.start();

                    smOld = new PbapClientStateMachineOld(this, device, smThread);
                    smOld.start();
                    mPbapClientStateMachineOldMap.put(device, smOld);
                    return true;
                } else {
                    Log.w(TAG, "Received connect request while already connecting/connected.");
                    return false;
                }
            }
        }
    }

    /**
     * Disconnects the pbap client profile from the passed in device
     *
     * @param device is the device with which we will disconnect the pbap client profile
     * @return true if we disconnected the pbap client profile, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }

        Log.d(TAG, "disconnect(device=" + device.getAddress() + ")");
        if (Flags.pbapClientStorageRefactor()) {
            PbapClientStateMachine pbapClientStateMachine = getDeviceStateMachine(device);
            if (pbapClientStateMachine != null) {
                pbapClientStateMachine.disconnect();
                return true;
            }
        } else {
            PbapClientStateMachineOld smOld = mPbapClientStateMachineOldMap.get(device);
            if (smOld != null) {
                smOld.disconnect(device);
                return true;
            }
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

        if (Flags.pbapClientStorageRefactor()) {
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
        } else {
            for (Map.Entry<BluetoothDevice, PbapClientStateMachineOld> stateMachineEntryOld :
                    mPbapClientStateMachineOldMap.entrySet()) {
                int currentDeviceState = stateMachineEntryOld.getValue().getConnectionState();
                for (int state : states) {
                    if (currentDeviceState == state) {
                        deviceList.add(stateMachineEntryOld.getKey());
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
    public int getConnectionState(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }

        if (Flags.pbapClientStorageRefactor()) {
            PbapClientStateMachine pbapClientStateMachine = getDeviceStateMachine(device);
            if (pbapClientStateMachine == null) {
                return STATE_DISCONNECTED;
            } else {
                return pbapClientStateMachine.getConnectionState();
            }
        } else {
            PbapClientStateMachineOld smOld = mPbapClientStateMachineOldMap.get(device);
            if (smOld == null) {
                return STATE_DISCONNECTED;
            } else {
                return smOld.getConnectionState(device);
            }
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
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.PBAP_CLIENT, connectionPolicy)) {
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
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.PBAP_CLIENT);
    }

    // *********************************************************************************************
    // * Pre-Refactor Methods
    // *********************************************************************************************

    @VisibleForTesting
    PbapClientService(AdapterService adapterService, PbapClientAccountManager accountManager) {
        super(requireNonNull(adapterService));
        mDatabaseManager = requireNonNull(adapterService.getDatabase());
        mHandler = new Handler(Looper.getMainLooper());

        if (Flags.pbapClientStorageRefactor()) {
            throw new IllegalStateException("pbapClientStorageRefactor: Invalid constructor call");
        }

        mPbapClientAccountManager = requireNonNull(accountManager);
        mPbapClientContactsStorage = null;
        mPbapClientStateMachineMap = null;

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        mPbapClientAccountManager.start();

        registerSdpRecord();
        setPbapClientService(this);
    }

    void cleanupDevice(BluetoothDevice device) {
        if (Flags.pbapClientStorageRefactor()) {
            Log.w(TAG, "This should not be used in this configuration");
        }

        Log.d(TAG, "Cleanup device: " + device);
        synchronized (mPbapClientStateMachineOldMap) {
            PbapClientStateMachineOld smOld = mPbapClientStateMachineOldMap.get(device);
            if (smOld != null) {
                mPbapClientStateMachineOldMap.remove(device);
                smOld.doQuit();
            }
        }
    }

    /**
     * Determine if our account type is available and ready to be interacted with
     *
     * @return True is account type is ready, false otherwise
     */
    public boolean isAccountTypeReady() {
        if (Flags.pbapClientStorageRefactor()) {
            Log.w(TAG, "This should not be used in this configuration");
        }
        return mPbapClientAccountManager.isAccountTypeInitialized();
    }

    /**
     * Add an account
     *
     * @param account The account you wish to add
     * @return True if the account addition was successful, False otherwise
     */
    public boolean addAccount(Account account) {
        if (Flags.pbapClientStorageRefactor()) {
            Log.w(TAG, "This should not be used in this configuration");
        }
        return mPbapClientAccountManager.addAccount(account);
    }

    /**
     * Remove an account
     *
     * @param account The account you wish to remove
     * @return True if the account removal was successful, False otherwise
     */
    public boolean removeAccount(Account account) {
        if (Flags.pbapClientStorageRefactor()) {
            Log.w(TAG, "This should not be used in this configuration");
        }
        return mPbapClientAccountManager.removeAccount(account);
    }
}
