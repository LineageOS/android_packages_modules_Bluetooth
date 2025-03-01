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

package com.android.bluetooth.csip;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothCsipSetCoordinator;
import android.bluetooth.IBluetoothCsipSetCoordinatorCallback;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.AttributionSource;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Provides Bluetooth CSIP Set Coordinator profile, as a service. */
public class CsipSetCoordinatorService extends ProfileService {
    private static final String TAG = CsipSetCoordinatorService.class.getSimpleName();

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private static CsipSetCoordinatorService sCsipSetCoordinatorService;

    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final Handler mHandler;
    private final HandlerThread mStateMachinesThread;
    private final Looper mStateMachinesLooper;
    private final CsipSetCoordinatorNativeInterface mNativeInterface;
    private final ServiceFactory mServiceFactory;

    @GuardedBy("mStateMachines")
    private final Map<BluetoothDevice, CsipSetCoordinatorStateMachine> mStateMachines =
            new HashMap<>();

    private final Map<Integer, ParcelUuid> mGroupIdToUuidMap = new HashMap<>();
    private final Map<BluetoothDevice, Map<Integer, Integer>> mDeviceGroupIdRankMap =
            new ConcurrentHashMap<>();
    // Tracks the number of devices in the CSIP group (greater than or equal to available devices)
    private final Map<Integer, Integer> mGroupIdToGroupSize = new HashMap<>();
    // Tracks the number of available devices mapped to the group id
    private final Map<Integer, Set<BluetoothDevice>> mGroupIdToConnectedDevices = new HashMap<>();
    private final Map<BluetoothDevice, Integer> mFoundSetMemberToGroupId = new HashMap<>();
    private final Map<ParcelUuid, Map<Executor, IBluetoothCsipSetCoordinatorCallback>> mCallbacks =
            new HashMap<>();
    private final Map<Integer, Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback>> mLocks =
            new ConcurrentHashMap<>();

    private LeAudioService mLeAudioService;

    public CsipSetCoordinatorService(AdapterService adapterService) {
        this(adapterService, null, null, new ServiceFactory());
    }

    @VisibleForTesting
    CsipSetCoordinatorService(
            AdapterService adapterService,
            Looper looper,
            CsipSetCoordinatorNativeInterface nativeInterface,
            ServiceFactory serviceFactory) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new CsipSetCoordinatorNativeInterface());
        mServiceFactory = requireNonNull(serviceFactory);
        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesThread = new HandlerThread("CsipSetCoordinatorService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
        }

        // Get LE Audio service (can be null)
        mLeAudioService = mServiceFactory.getLeAudioService();

        // Mark service as started
        setCsipSetCoordinatorService(this);

        // Initialize native interface
        mNativeInterface.init();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileCsipSetCoordinatorEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothCsisBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup CSIP Set Coordinator Service");

        if (sCsipSetCoordinatorService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        // Cleanup native interface
        mNativeInterface.cleanup();

        // Mark service as stopped
        setCsipSetCoordinatorService(null);

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
                sm.cleanup();
            }
            mStateMachines.clear();
        }

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }

        // Unregister Handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null);

        mDeviceGroupIdRankMap.clear();
        mCallbacks.clear();
        mFoundSetMemberToGroupId.clear();
        mGroupIdToGroupSize.clear();
        mGroupIdToConnectedDevices.clear();
        mGroupIdToUuidMap.clear();

        mLocks.clear();
    }

    /**
     * Get the CsipSetCoordinatorService instance
     *
     * @return CsipSetCoordinatorService instance
     */
    public static synchronized CsipSetCoordinatorService getCsipSetCoordinatorService() {
        if (sCsipSetCoordinatorService == null) {
            Log.w(TAG, "getCsipSetCoordinatorService(): service is NULL");
            return null;
        }

        if (!sCsipSetCoordinatorService.isAvailable()) {
            Log.w(TAG, "getCsipSetCoordinatorService(): service is not available");
            return null;
        }
        return sCsipSetCoordinatorService;
    }

    private static synchronized void setCsipSetCoordinatorService(
            CsipSetCoordinatorService instance) {
        Log.d(TAG, "setCsipSetCoordinatorService(): set to: " + instance);
        sCsipSetCoordinatorService = instance;
    }

    /**
     * Connect the given Bluetooth device.
     *
     * @return true if connection is successful, false otherwise.
     */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            return false;
        }

        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }
        final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.COORDINATED_SET)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have CSIS UUID");
            return false;
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
                return false;
            }
            smConnect.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        }

        return true;
    }

    /**
     * Disconnect the given Bluetooth device.
     *
     * @return true if disconnect is successful, false otherwise.
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = getOrCreateStateMachine(device);
            if (sm != null) {
                sm.sendMessage(CsipSetCoordinatorStateMachine.DISCONNECT);
            }
        }

        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.COORDINATED_SET)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /** Register for CSIS */
    public void registerCsisMemberObserver(
            @CallbackExecutor Executor executor,
            ParcelUuid uuid,
            IBluetoothCsipSetCoordinatorCallback callback) {
        Map<Executor, IBluetoothCsipSetCoordinatorCallback> entries =
                mCallbacks.getOrDefault(uuid, null);
        if (entries == null) {
            entries = new HashMap<>();
            entries.put(executor, callback);
            Log.d(TAG, " Csis adding new callback for " + uuid);
            mCallbacks.put(uuid, entries);
            return;
        }

        if (entries.containsKey(executor)) {
            if (entries.get(executor) == callback) {
                Log.d(TAG, " Execute and callback already added " + uuid);
                return;
            }
        }

        Log.d(TAG, " Csis adding callback " + uuid);
        entries.put(executor, callback);
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
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
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
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
     * @param device the remote device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true on success, otherwise false
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.CSIP_SET_COORDINATOR, connectionPolicy);
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
     * @param device the remote device
     * @return connection policy of the specified device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(
                device, BluetoothProfile.CSIP_SET_COORDINATOR);
    }

    /**
     * Lock a given group.
     *
     * @param groupId group ID to lock
     * @param callback callback with the lock request result
     * @return unique lock identifier used for unlocking
     */
    public @Nullable UUID lockGroup(
            int groupId, @NonNull IBluetoothCsipSetCoordinatorLockCallback callback) {
        if (callback == null) {
            Log.d(TAG, "lockGroup(): " + groupId + ", callback not provided ");
            return null;
        }

        synchronized (mGroupIdToUuidMap) {
            if (!mGroupIdToUuidMap.containsKey(groupId)) {
                try {
                    callback.onGroupLockSet(
                            groupId, BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID, false);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                return null;
            }
        }

        UUID uuid = UUID.randomUUID();
        synchronized (mLocks) {
            if (mLocks.containsKey(groupId)) {
                try {
                    callback.onGroupLockSet(
                            groupId, BluetoothStatusCodes.ERROR_CSIP_GROUP_LOCKED_BY_OTHER, true);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                Log.d(TAG, "lockGroup(): " + groupId + ", ERROR_CSIP_GROUP_LOCKED_BY_OTHER ");
                return null;
            }

            mLocks.put(groupId, new Pair<>(uuid, callback));
        }

        Log.d(TAG, "lockGroup(): locking group: " + groupId);
        mNativeInterface.groupLockSet(groupId, true);
        return uuid;
    }

    /**
     * Unlock a given group.
     *
     * @param lockUuid unique lock identifier used for unlocking
     */
    public void unlockGroup(@NonNull UUID lockUuid) {
        if (lockUuid == null) {
            Log.d(TAG, "unlockGroup(): lockUuid is null");
            return;
        }

        synchronized (mLocks) {
            for (Map.Entry<Integer, Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback>> entry :
                    mLocks.entrySet()) {
                Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback> uuidCbPair = entry.getValue();
                if (uuidCbPair.first.equals(lockUuid)) {
                    Log.d(TAG, "unlockGroup(): unlocking ... " + lockUuid);
                    mNativeInterface.groupLockSet(entry.getKey(), false);
                    return;
                }
            }
        }
    }

    /**
     * Check whether a given group is currently locked.
     *
     * @param groupId unique group identifier
     * @return true if group is currently locked, otherwise false.
     */
    public boolean isGroupLocked(int groupId) {
        return mLocks.containsKey(groupId);
    }

    /**
     * Get collection of group IDs for a given UUID
     *
     * @param uuid profile context UUID
     * @return list of group IDs
     */
    public List<Integer> getAllGroupIds(ParcelUuid uuid) {
        return mGroupIdToUuidMap.entrySet().stream()
                .filter(e -> uuid.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Get group ID for a given device and UUID
     *
     * @param device potential group member
     * @param uuid profile context UUID
     * @return group ID
     */
    public Integer getGroupId(BluetoothDevice device, ParcelUuid uuid) {
        Map<Integer, Integer> device_groups =
                mDeviceGroupIdRankMap.getOrDefault(device, new HashMap<>());
        return mGroupIdToUuidMap.entrySet().stream()
                .filter(e -> (device_groups.containsKey(e.getKey()) && e.getValue().equals(uuid)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(IBluetoothCsipSetCoordinator.CSIS_GROUP_ID_INVALID);
    }

    /**
     * Get device's groups/
     *
     * @param device group member device
     * @return map of group id and related uuids.
     */
    public Map<Integer, ParcelUuid> getGroupUuidMapByDevice(BluetoothDevice device) {
        Map<Integer, Integer> device_groups =
                mDeviceGroupIdRankMap.getOrDefault(device, new HashMap<>());
        return mGroupIdToUuidMap.entrySet().stream()
                .filter(e -> device_groups.containsKey(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get grouped devices
     *
     * @param groupId group ID
     * @return related list of devices sorted from the lowest to the highest rank value.
     */
    public @NonNull List<BluetoothDevice> getGroupDevicesOrdered(int groupId) {
        final Map<BluetoothDevice, Integer> deviceRankMap = new HashMap();
        for (Map.Entry<BluetoothDevice, ?> entry : mDeviceGroupIdRankMap.entrySet()) {
            Map<Integer, Integer> rankMap = (Map<Integer, Integer>) entry.getValue();
            BluetoothDevice device = entry.getKey();
            if (rankMap.containsKey(groupId)) {
                deviceRankMap.put(device, rankMap.get(groupId));
            }
        }

        // Return device list sorted by descending rank order
        return deviceRankMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(e -> e.getKey())
                .collect(Collectors.toList());
    }

    /**
     * Get grouped devices
     *
     * @param device group member device
     * @param uuid profile context UUID
     * @return related list of devices sorted from the lowest to the highest rank value.
     */
    public @NonNull List<BluetoothDevice> getGroupDevicesOrdered(
            BluetoothDevice device, ParcelUuid uuid) {
        List<Integer> groupIds = getAllGroupIds(uuid);
        for (Integer id : groupIds) {
            List<BluetoothDevice> devices = getGroupDevicesOrdered(id);
            if (devices.contains(device)) {
                return devices;
            }
        }
        return Collections.emptyList();
    }

    /**
     * Get group desired size
     *
     * @param groupId group ID
     * @return the number of group members
     */
    public int getDesiredGroupSize(int groupId) {
        return mGroupIdToGroupSize.getOrDefault(
                groupId, IBluetoothCsipSetCoordinator.CSIS_GROUP_SIZE_UNKNOWN);
    }

    private void handleDeviceAvailable(
            BluetoothDevice device, int groupId, int rank, UUID uuid, int groupSize) {
        mGroupIdToGroupSize.put(groupId, groupSize);
        ParcelUuid parcel_uuid = new ParcelUuid(uuid);
        if (!getAllGroupIds(parcel_uuid).contains(groupId)) {
            mGroupIdToUuidMap.put(groupId, parcel_uuid);
        }

        if (!mDeviceGroupIdRankMap.containsKey(device)) {
            mDeviceGroupIdRankMap.put(device, new HashMap<Integer, Integer>());
        }

        Map<Integer, Integer> all_device_groups = mDeviceGroupIdRankMap.get(device);
        all_device_groups.put(groupId, rank);
    }

    /**
     * If all the group devices are now available, make sure CSIP connection policy mirrors the LEA
     * connection policy.
     *
     * @param groupId is the group that has a new device available
     */
    private void disableCsipIfNeeded(int groupId) {
        /* Make sure CSIP connection policy mirrors that of LeAudioService once all CSIP
        characteristic reads have completed (ensures we can pair other set devices) */
        if (mLeAudioService == null) {
            mLeAudioService = mServiceFactory.getLeAudioService();
        }

        if (mLeAudioService != null) {
            if (!mGroupIdToConnectedDevices.containsKey(groupId)) {
                Log.w(TAG, "No connected devices for groupId=" + groupId);
                return;
            }
            if (!mGroupIdToGroupSize.containsKey(groupId)) {
                Log.w(TAG, "No group size stored for groupId=" + groupId);
                return;
            }
            if (mGroupIdToConnectedDevices.get(groupId).size() < mGroupIdToGroupSize.get(groupId)) {
                Log.d(
                        TAG,
                        "disableCsipIfNeeded: groupId "
                                + groupId
                                + "has "
                                + mGroupIdToConnectedDevices.get(groupId).size()
                                + " connected devices out"
                                + " of a group size of "
                                + mGroupIdToGroupSize.get(groupId));
                return;
            }
            for (BluetoothDevice groupDevice : mGroupIdToConnectedDevices.get(groupId)) {
                if (mLeAudioService.getConnectionPolicy(groupDevice)
                        == CONNECTION_POLICY_FORBIDDEN) {
                    Log.i(
                            TAG,
                            "Setting CSIP connection policy to FORBIDDEN for device "
                                    + groupDevice
                                    + " after all group devices bonded because LEA "
                                    + "connection policy is FORBIDDEN");
                    setConnectionPolicy(groupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
            }
        } else {
            Log.w(TAG, "checkIfGroupPaired: LE Audio Service is null");
        }
    }

    private static void executeCallback(
            Executor exec,
            IBluetoothCsipSetCoordinatorCallback callback,
            BluetoothDevice device,
            int groupId)
            throws RemoteException {
        exec.execute(
                () -> {
                    try {
                        callback.onCsisSetMemberAvailable(device, groupId);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                });
    }

    private void handleSetMemberAvailable(BluetoothDevice device, int groupId) {
        if (!mGroupIdToUuidMap.containsKey(groupId)) {
            Log.e(TAG, " UUID not found for group id " + groupId);
            return;
        }

        if (mCallbacks.isEmpty()) {
            return;
        }

        ParcelUuid uuid = mGroupIdToUuidMap.get(groupId);
        if (mCallbacks.get(uuid) == null) {
            Log.e(TAG, " There is no clients for uuid: " + uuid);
            return;
        }

        for (Map.Entry<Executor, IBluetoothCsipSetCoordinatorCallback> entry :
                mCallbacks.get(uuid).entrySet()) {
            Log.d(TAG, " executing " + uuid + " " + entry.getKey());
            try {
                executeCallback(entry.getKey(), entry.getValue(), device, groupId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    int getApiStatusCode(int nativeResult) {
        switch (nativeResult) {
            case IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_SUCCESS:
                return BluetoothStatusCodes.SUCCESS;
            case IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_FAILED_INVALID_GROUP:
                return BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID;
            case IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_FAILED_GROUP_NOT_CONNECTED:
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
            case IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_FAILED_LOCKED_BY_OTHER:
                return BluetoothStatusCodes.ERROR_CSIP_GROUP_LOCKED_BY_OTHER;
            case IBluetoothCsipSetCoordinator.CSIS_LOCKED_GROUP_MEMBER_LOST:
                return BluetoothStatusCodes.ERROR_CSIP_LOCKED_GROUP_MEMBER_LOST;
            case IBluetoothCsipSetCoordinator.CSIS_GROUP_LOCK_FAILED_OTHER_REASON:
            default:
                Log.e(TAG, " Unknown status code: " + nativeResult);
                return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
    }

    void handleGroupLockChanged(int groupId, int status, boolean isLocked) {
        synchronized (mLocks) {
            if (!mLocks.containsKey(groupId)) {
                return;
            }

            IBluetoothCsipSetCoordinatorLockCallback cb = mLocks.get(groupId).second;
            try {
                cb.onGroupLockSet(groupId, getApiStatusCode(status), isLocked);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            // Unlocking invalidates the existing lock if exist
            if (!isLocked) {
                mLocks.remove(groupId);
            }
        }
    }

    void notifySetMemberAvailable(BluetoothDevice device, int groupId) {
        Log.d(TAG, "notifySetMemberAvailable: " + device + ", " + groupId);

        /* Sent intent as well */
        Intent intent =
                new Intent(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE)
                        .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                        .putExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, groupId)
                        .addFlags(
                                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendOrderedBroadcast(intent, BLUETOOTH_PRIVILEGED);

        /* Notify registered parties */
        handleSetMemberAvailable(device, groupId);
    }

    void messageFromNative(CsipSetCoordinatorStackEvent stackEvent) {
        BluetoothDevice device = stackEvent.device;
        Log.d(TAG, "Message from native: " + stackEvent);

        int groupId = stackEvent.valueInt1;
        if (stackEvent.type == CsipSetCoordinatorStackEvent.EVENT_TYPE_DEVICE_AVAILABLE) {
            requireNonNull(device);

            handleDeviceAvailable(
                    device,
                    groupId,
                    stackEvent.valueInt3,
                    stackEvent.valueUuid1,
                    stackEvent.valueInt2);

            Intent intent =
                    new Intent(BluetoothCsipSetCoordinator.ACTION_CSIS_DEVICE_AVAILABLE)
                            .putExtra(BluetoothDevice.EXTRA_DEVICE, stackEvent.device)
                            .putExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, groupId)
                            .putExtra(
                                    BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE,
                                    stackEvent.valueInt2)
                            .putExtra(
                                    BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID,
                                    stackEvent.valueUuid1)
                            .addFlags(
                                    Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            sendOrderedBroadcast(intent, BLUETOOTH_PRIVILEGED);
        } else if (stackEvent.type
                == CsipSetCoordinatorStackEvent.EVENT_TYPE_SET_MEMBER_AVAILABLE) {
            requireNonNull(device);
            if (!mFoundSetMemberToGroupId.containsKey(device)) {
                mFoundSetMemberToGroupId.put(device, groupId);
            }
            if (mGroupIdToConnectedDevices.containsKey(groupId)) {
                notifySetMemberAvailable(device, groupId);
            }
        } else if (stackEvent.type == CsipSetCoordinatorStackEvent.EVENT_TYPE_GROUP_LOCK_CHANGED) {
            int lock_status = stackEvent.valueInt2;
            boolean lock_state = stackEvent.valueBool1;
            handleGroupLockChanged(groupId, lock_status, lock_state);
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);

            if (stackEvent.type
                    == CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                if (sm == null) {
                    switch (stackEvent.valueInt1) {
                        case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED:
                        case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING:
                            sm = getOrCreateStateMachine(device);
                            break;
                        default:
                            break;
                    }
                }

                if (sm == null) {
                    Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                    return;
                }
                sm.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, stackEvent);
            }
        }
    }

    private CsipSetCoordinatorStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }

            Log.d(TAG, "Creating a new state machine for " + device);
            sm =
                    CsipSetCoordinatorStateMachine.make(
                            device, this, mNativeInterface, mStateMachinesLooper);
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /** Process a change in the bonding state for a device */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /**
     * Remove state machine if the bonding for a device is removed
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are: {@link
     *     BluetoothDevice#BOND_NONE}, {@link BluetoothDevice#BOND_BONDING}, {@link
     *     BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        if (bondState == BluetoothDevice.BOND_BONDING
                && mFoundSetMemberToGroupId.containsKey(device)) {
            mFoundSetMemberToGroupId.remove(device);
        }

        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        mDeviceGroupIdRankMap.remove(device);
        for (Map.Entry<Integer, Set<BluetoothDevice>> entry :
                mGroupIdToConnectedDevices.entrySet()) {
            entry.getValue().remove(device);
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnecting device because it was unbonded.");
                disconnect(device);
                return;
            }
            removeStateMachine(device);
        }
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }
    }

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

    @VisibleForTesting
    synchronized void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (!isAvailable()) {
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        if ((device == null) || (fromState == toState)) {
            Log.e(
                    TAG,
                    "connectionStateChanged: unexpected invocation. device="
                            + device
                            + " fromState="
                            + fromState
                            + " toState="
                            + toState);
            return;
        }

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
            }
        } else if (toState == STATE_CONNECTED) {
            int groupId = getGroupId(device, BluetoothUuid.CAP);
            if (!mGroupIdToConnectedDevices.containsKey(groupId)) {
                mGroupIdToConnectedDevices.put(groupId, new HashSet<>());
            }
            for (Map.Entry<BluetoothDevice, Integer> entry : mFoundSetMemberToGroupId.entrySet()) {
                if (entry.getValue() == groupId) {
                    notifySetMemberAvailable(entry.getKey(), groupId);
                }
            }
            mGroupIdToConnectedDevices.get(groupId).add(device);
            disableCsipIfNeeded(groupId);
        }
        mAdapterService.handleProfileConnectionStateChange(
                BluetoothProfile.CSIP_SET_COORDINATOR, device, fromState, toState);
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothCsisBinder extends IBluetoothCsipSetCoordinator.Stub
            implements IProfileServiceBinder {
        private CsipSetCoordinatorService mService;

        BluetoothCsisBinder(CsipSetCoordinatorService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private CsipSetCoordinatorService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            CsipSetCoordinatorService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            requireNonNull(device);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            return service.getConnectionPolicy(device);
        }

        @Override
        public ParcelUuid lockGroup(
                int groupId,
                @NonNull IBluetoothCsipSetCoordinatorLockCallback callback,
                AttributionSource source) {
            requireNonNull(callback);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return null;
            }

            UUID lockUuid = service.lockGroup(groupId, callback);
            return lockUuid == null ? null : new ParcelUuid(lockUuid);
        }

        @Override
        public void unlockGroup(@NonNull ParcelUuid lockUuid, AttributionSource source) {
            requireNonNull(lockUuid);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return;
            }

            service.unlockGroup(lockUuid.getUuid());
        }

        @Override
        public List<Integer> getAllGroupIds(ParcelUuid uuid, AttributionSource source) {
            requireNonNull(uuid);
            requireNonNull(source);

            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getAllGroupIds(uuid);
        }

        @Override
        public Map<Integer, ParcelUuid> getGroupUuidMapByDevice(
                BluetoothDevice device, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return null;
            }

            return service.getGroupUuidMapByDevice(device);
        }

        @Override
        public int getDesiredGroupSize(int groupId, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return IBluetoothCsipSetCoordinator.CSIS_GROUP_SIZE_UNKNOWN;
            }

            return service.getDesiredGroupSize(groupId);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        synchronized (mStateMachines) {
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                sm.dump(sb);
            }
        }
        ProfileService.println(sb, "mFoundSetMemberToGroupId: ");
        for (Map.Entry<BluetoothDevice, Integer> entry : mFoundSetMemberToGroupId.entrySet()) {
            ProfileService.println(
                    sb, "  member device: " + entry.getKey() + ", group ID: " + entry.getValue());
        }
    }
}
