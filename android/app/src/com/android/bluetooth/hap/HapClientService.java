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

package com.android.bluetooth.hap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringConsumer;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHapClientCallback;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/** Provides Bluetooth Hearing Access profile, as a service. */
public class HapClientService extends ProfileService {
    private static final String TAG = HapClientService.class.getSimpleName();

    // Upper limit of all HearingAccess devices: Bonded or Connected
    private static final int MAX_HEARING_ACCESS_STATE_MACHINES = 10;
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private static HapClientService sHapClient;

    private final Map<BluetoothDevice, HapClientStateMachine> mStateMachines = new HashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceCurrentPresetMap = new HashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceFeaturesMap = new HashMap<>();
    private final Map<BluetoothDevice, List<BluetoothHapPresetInfo>> mPresetsMap = new HashMap<>();
    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final Handler mHandler;
    private final Looper mStateMachinesLooper;
    private final HandlerThread mStateMachinesThread;
    private final HapClientNativeInterface mNativeInterface;

    @VisibleForTesting
    @GuardedBy("mCallbacks")
    final RemoteCallbackList<IBluetoothHapClientCallback> mCallbacks = new RemoteCallbackList<>();

    @VisibleForTesting ServiceFactory mFactory = new ServiceFactory();

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileHapClientEnabled().orElse(false);
    }

    @VisibleForTesting
    static synchronized void setHapClient(HapClientService instance) {
        Log.d(TAG, "setHapClient(): set to: " + instance);
        sHapClient = instance;
    }

    /**
     * Get the HapClientService instance
     *
     * @return HapClientService instance
     */
    public static synchronized HapClientService getHapClientService() {
        if (sHapClient == null) {
            Log.w(TAG, "getHapClientService(): service is NULL");
            return null;
        }

        if (!sHapClient.isAvailable()) {
            Log.w(TAG, "getHapClientService(): service is not available");
            return null;
        }
        return sHapClient;
    }

    public HapClientService(AdapterService adapterService) {
        this(adapterService, null, null);
    }

    @VisibleForTesting
    HapClientService(
            AdapterService adapterService,
            Looper looper,
            HapClientNativeInterface nativeInterface) {
        super(adapterService);
        mAdapterService = requireNonNull(adapterService);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new HapClientNativeInterface(
                                        new HapClientNativeCallback(adapterService, this)));
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());

        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesThread = new HandlerThread("HapClientService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
        }

        // Initialize native interface
        mNativeInterface.init();

        // Mark service as started
        setHapClient(this);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new HapClientBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup HapClient Service");

        if (sHapClient == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        // Marks service as stopped
        setHapClient(null);

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (HapClientStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
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

        // Cleanup GATT interface
        mNativeInterface.cleanup();

        // Cleanup the internals
        mDeviceCurrentPresetMap.clear();
        mDeviceFeaturesMap.clear();
        mPresetsMap.clear();

        synchronized (mCallbacks) {
            mCallbacks.kill();
        }
    }

    /** Process a change in the bonding state for a device */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);

        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        mDeviceCurrentPresetMap.remove(device);
        mDeviceFeaturesMap.remove(device);
        mPresetsMap.remove(device);

        synchronized (mStateMachines) {
            HapClientStateMachine sm = mStateMachines.get(device);
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
            HapClientStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            mStateMachines.remove(device);
        }
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
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.HAS)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                HapClientStateMachine sm = mStateMachines.get(device);
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

    /**
     * @return A list of connected {@link BluetoothDevice}.
     */
    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (HapClientStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
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
            HapClientStateMachine sm = mStateMachines.get(device);
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
                device, BluetoothProfile.HAP_CLIENT, connectionPolicy);
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
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HAP_CLIENT);
    }

    /** Check whether it can connect to a peer device. */
    boolean okToConnect(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connection policy and accept or reject the connection.
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

    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
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
        }
        ActiveDeviceManager adManager = mAdapterService.getActiveDeviceManager();
        if (adManager != null) {
            adManager.profileConnectionStateChanged(
                    BluetoothProfile.HAP_CLIENT, device, fromState, toState);
        }
    }

    /**
     * Connects the hearing access service client to the passed in device
     *
     * @param device is the device with which we will connect the hearing access service client
     * @return true if hearing access service client successfully connected, false otherwise
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
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.HAS)) {
            Log.e(
                    TAG,
                    "Cannot connect to "
                            + device
                            + " : Remote does not have Hearing Access Service UUID");
            return false;
        }
        synchronized (mStateMachines) {
            HapClientStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
            }
            smConnect.sendMessage(HapClientStateMachine.MESSAGE_CONNECT);
        }

        return true;
    }

    /**
     * Disconnects hearing access service client for the passed in device
     *
     * @param device is the device with which we want to disconnect the hearing access service
     *     client
     * @return true if hearing access service client successfully disconnected, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            HapClientStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                sm.sendMessage(HapClientStateMachine.MESSAGE_DISCONNECT);
            }
        }

        return true;
    }

    private HapClientStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            HapClientStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }
            // Limit the maximum number of state machines to avoid DoS attack
            if (mStateMachines.size() >= MAX_HEARING_ACCESS_STATE_MACHINES) {
                Log.e(
                        TAG,
                        "Maximum number of HearingAccess state machines reached: "
                                + MAX_HEARING_ACCESS_STATE_MACHINES);
                return null;
            }
            Log.d(TAG, "Creating a new state machine for " + device);
            sm = new HapClientStateMachine(this, device, mNativeInterface, mStateMachinesLooper);
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    @VisibleForTesting
    int getHapGroup(BluetoothDevice device) {
        CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();

        if (csipClient != null) {
            Map<Integer, ParcelUuid> groups = csipClient.getGroupUuidMapByDevice(device);
            for (Map.Entry<Integer, ParcelUuid> entry : groups.entrySet()) {
                if (entry.getValue().equals(BluetoothUuid.CAP)) {
                    return entry.getKey();
                }
            }
        }
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    @VisibleForTesting
    int getActivePresetIndex(BluetoothDevice device) {
        return mDeviceCurrentPresetMap.getOrDefault(
                device, BluetoothHapClient.PRESET_INDEX_UNAVAILABLE);
    }

    @VisibleForTesting
    BluetoothHapPresetInfo getActivePresetInfo(BluetoothDevice device) {
        int index = getActivePresetIndex(device);
        if (index == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            return null;
        }

        List<BluetoothHapPresetInfo> current_presets = mPresetsMap.get(device);
        if (current_presets == null) {
            return null;
        }

        for (BluetoothHapPresetInfo preset : current_presets) {
            if (preset.getIndex() == index) {
                return preset;
            }
        }

        return null;
    }

    private void broadcastToClient(
            RemoteExceptionIgnoringConsumer<IBluetoothHapClientCallback> consumer) {
        synchronized (mCallbacks) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                consumer.accept(mCallbacks.getBroadcastItem(i));
            }
            mCallbacks.finishBroadcast();
        }
    }

    @VisibleForTesting
    void selectPreset(BluetoothDevice device, int presetIndex) {
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            int status = BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            broadcastToClient(cb -> cb.onPresetSelectionFailed(device, status));
            return;
        }

        mNativeInterface.selectActivePreset(device, presetIndex);
    }

    @VisibleForTesting
    void selectPresetForGroup(int groupId, int presetIndex) {
        if (!isGroupIdValid(groupId)) {
            int status = BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID;
            broadcastToClient(cb -> cb.onPresetSelectionForGroupFailed(groupId, status));
            return;
        }
        if (!isPresetIndexValid(groupId, presetIndex)) {
            int status = BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            broadcastToClient(cb -> cb.onPresetSelectionForGroupFailed(groupId, status));
            return;
        }

        mNativeInterface.groupSelectActivePreset(groupId, presetIndex);
    }

    void switchToNextPreset(BluetoothDevice device) {
        mNativeInterface.nextActivePreset(device);
    }

    void switchToNextPresetForGroup(int groupId) {
        mNativeInterface.groupNextActivePreset(groupId);
    }

    void switchToPreviousPreset(BluetoothDevice device) {
        mNativeInterface.previousActivePreset(device);
    }

    void switchToPreviousPresetForGroup(int groupId) {
        mNativeInterface.groupPreviousActivePreset(groupId);
    }

    BluetoothHapPresetInfo getPresetInfo(BluetoothDevice device, int presetIndex) {
        BluetoothHapPresetInfo defaultValue = null;
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) return defaultValue;

        if (Utils.isPtsTestMode()) {
            /* We want native to be called for PTS testing even we have all
             * the data in the cache here
             */
            mNativeInterface.getPresetInfo(device, presetIndex);
        }
        List<BluetoothHapPresetInfo> current_presets = mPresetsMap.get(device);
        if (current_presets != null) {
            for (BluetoothHapPresetInfo preset : current_presets) {
                if (preset.getIndex() == presetIndex) {
                    return preset;
                }
            }
        }

        return defaultValue;
    }

    List<BluetoothHapPresetInfo> getAllPresetInfo(BluetoothDevice device) {
        if (mPresetsMap.containsKey(device)) {
            return mPresetsMap.get(device);
        }
        return Collections.emptyList();
    }

    int getFeatures(BluetoothDevice device) {
        if (mDeviceFeaturesMap.containsKey(device)) {
            return mDeviceFeaturesMap.get(device);
        }
        return 0x00;
    }

    private static int stackEventPresetInfoReasonToProfileStatus(int statusCode) {
        return switch (statusCode) {
            case HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO ->
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_UPDATE ->
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_DELETED ->
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_AVAILABILITY_CHANGED ->
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_REQUEST_RESPONSE ->
                    BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST;
            default -> BluetoothStatusCodes.ERROR_UNKNOWN;
        };
    }

    private void notifyPresetInfoChanged(BluetoothDevice device, int infoReason) {
        List current_presets = mPresetsMap.get(device);
        if (current_presets == null) return;

        broadcastToClient(
                cb ->
                        cb.onPresetInfoChanged(
                                device,
                                current_presets,
                                stackEventPresetInfoReasonToProfileStatus(infoReason)));
    }

    private static int stackEventStatusToProfileStatus(int statusCode) {
        return switch (statusCode) {
            case HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED;
            case HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
            case HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED;
            case HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH ->
                    BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG;
            case HapClientStackEvent.STATUS_INVALID_PRESET_INDEX ->
                    BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            case HapClientStackEvent.STATUS_GROUP_OPERATION_NOT_SUPPORTED ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
            case HapClientStackEvent.STATUS_PROCEDURE_ALREADY_IN_PROGRESS ->
                    BluetoothStatusCodes.ERROR_UNKNOWN;
            default -> BluetoothStatusCodes.ERROR_UNKNOWN;
        };
    }

    private boolean isPresetIndexValid(BluetoothDevice device, int presetIndex) {
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            return false;
        }

        List<BluetoothHapPresetInfo> device_presets = mPresetsMap.get(device);
        if (device_presets == null) {
            return false;
        }
        for (BluetoothHapPresetInfo preset : device_presets) {
            if (preset.getIndex() == presetIndex) {
                return true;
            }
        }
        return false;
    }

    private boolean isPresetIndexValid(int groupId, int presetIndex) {
        List<BluetoothDevice> all_group_devices = getGroupDevices(groupId);
        if (all_group_devices.isEmpty()) {
            return false;
        }

        for (BluetoothDevice device : all_group_devices) {
            if (!isPresetIndexValid(device, presetIndex)) {
                return false;
            }
        }
        return true;
    }

    private boolean isGroupIdValid(int groupId) {
        if (groupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            return false;
        }

        CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();
        if (csipClient == null) {
            return false;
        }
        List<Integer> groups = csipClient.getAllGroupIds(BluetoothUuid.CAP);
        return groups.contains(groupId);
    }

    @VisibleForTesting
    void setPresetName(BluetoothDevice device, int presetIndex, String name) {
        if (!isPresetIndexValid(device, presetIndex)) {
            int status = BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            broadcastToClient(cb -> cb.onSetPresetNameFailed(device, status));
            return;
        }
        // WARNING: We should check cache if preset exists and is writable, but then we would still
        //          need a way to trigger this action with an invalid index or on a non-writable
        //          preset for tests purpose.
        mNativeInterface.setPresetName(device, presetIndex, name);
    }

    @VisibleForTesting
    void setPresetNameForGroup(int groupId, int presetIndex, String name) {
        if (!isGroupIdValid(groupId)) {
            int status = BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID;
            broadcastToClient(cb -> cb.onSetPresetNameForGroupFailed(groupId, status));
            return;
        }
        if (!isPresetIndexValid(groupId, presetIndex)) {
            int status = BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            broadcastToClient(cb -> cb.onSetPresetNameForGroupFailed(groupId, status));
            return;
        }

        mNativeInterface.groupSetPresetName(groupId, presetIndex, name);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (HapClientStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }
    }

    void updateDevicePresetsCache(
            BluetoothDevice device, int infoReason, List<BluetoothHapPresetInfo> presets) {
        switch (infoReason) {
            case HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO -> {
                mPresetsMap.put(device, presets);
            }
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_UPDATE,
                    HapClientStackEvent.PRESET_INFO_REASON_PRESET_AVAILABILITY_CHANGED,
                    HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_REQUEST_RESPONSE -> {
                List current_presets = mPresetsMap.get(device);
                if (current_presets != null) {
                    for (BluetoothHapPresetInfo new_preset : presets) {
                        ListIterator<BluetoothHapPresetInfo> iter = current_presets.listIterator();
                        while (iter.hasNext()) {
                            if (iter.next().getIndex() == new_preset.getIndex()) {
                                iter.remove();
                                break;
                            }
                        }
                    }
                    current_presets.addAll(presets);
                    presets = current_presets;
                }
                mPresetsMap.put(device, presets);
            }
            case HapClientStackEvent.PRESET_INFO_REASON_PRESET_DELETED -> {
                List current_presets = mPresetsMap.get(device);
                if (current_presets != null) {
                    for (BluetoothHapPresetInfo new_preset : presets) {
                        ListIterator<BluetoothHapPresetInfo> iter = current_presets.listIterator();
                        while (iter.hasNext()) {
                            if (iter.next().getIndex() == new_preset.getIndex()) {
                                iter.remove();
                                break;
                            }
                        }
                    }
                    mPresetsMap.put(device, current_presets);
                }
            }
            default -> {}
        }
    }

    private List<BluetoothDevice> getGroupDevices(int groupId) {
        if (groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
            return Collections.emptyList();
        }

        CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();
        if (csipClient == null) {
            return Collections.emptyList();
        }

        return csipClient.getGroupDevicesOrdered(groupId);
    }

    void messageFromNative(HapClientStackEvent stackEvent) {
        if (!isAvailable()) {
            Log.e(TAG, "Event ignored, service not available: " + stackEvent);
            return;
        }
        // Decide which event should be sent to the state machine
        if (stackEvent.type == HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
            resendToStateMachine(stackEvent);
            return;
        }

        BluetoothDevice device = stackEvent.device;

        switch (stackEvent.type) {
            case HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE -> {
                int features = stackEvent.valueInt1;

                if (device != null) {
                    mDeviceFeaturesMap.put(device, features);

                    Intent intent =
                            new Intent(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE)
                                    .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                                    .putExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, features);
                    getBaseContext()
                            .sendBroadcastWithMultiplePermissions(
                                    intent, new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED});
                }
            }

            case HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES -> {
                int features = stackEvent.valueInt1;

                if (device != null) {
                    mDeviceFeaturesMap.put(device, features);
                    Log.d(
                            TAG,
                            ("device=" + device)
                                    + (" features=" + String.format("0x%04X", features)));
                }
            }
            case HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED -> {
                int presetIndex = stackEvent.valueInt1;
                int groupId = stackEvent.valueInt2;
                // FIXME: Add app request queueing to support other reasons
                int reason = BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;

                if (device != null) {
                    mDeviceCurrentPresetMap.put(device, presetIndex);
                    broadcastToClient(cb -> cb.onPresetSelected(device, presetIndex, reason));

                } else if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                    List<BluetoothDevice> all_group_devices = getGroupDevices(groupId);
                    for (BluetoothDevice dev : all_group_devices) {
                        mDeviceCurrentPresetMap.put(dev, presetIndex);
                        broadcastToClient(cb -> cb.onPresetSelected(dev, presetIndex, reason));
                    }
                }
            }
            case HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR -> {
                int groupId = stackEvent.valueInt2;
                int status = stackEventStatusToProfileStatus(stackEvent.valueInt1);

                if (device != null) {
                    broadcastToClient(cb -> cb.onPresetSelectionFailed(device, status));
                } else if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                    broadcastToClient(cb -> cb.onPresetSelectionForGroupFailed(groupId, status));
                }
            }
            case HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO -> {
                int infoReason = stackEvent.valueInt2;
                int groupId = stackEvent.valueInt3;
                ArrayList presets = stackEvent.valueList;

                if (device != null) {
                    updateDevicePresetsCache(device, infoReason, presets);
                    notifyPresetInfoChanged(device, infoReason);

                } else if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                    List<BluetoothDevice> all_group_devices = getGroupDevices(groupId);
                    for (BluetoothDevice dev : all_group_devices) {
                        updateDevicePresetsCache(dev, infoReason, presets);
                        notifyPresetInfoChanged(dev, infoReason);
                    }
                }
            }
            case HapClientStackEvent.EVENT_TYPE_ON_PRESET_NAME_SET_ERROR -> {
                int status = stackEventStatusToProfileStatus(stackEvent.valueInt1);
                int groupId = stackEvent.valueInt3;

                if (device != null) {
                    broadcastToClient(cb -> cb.onSetPresetNameFailed(device, status));
                } else if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                    broadcastToClient(cb -> cb.onSetPresetNameForGroupFailed(groupId, status));
                }
            }
            case HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO_ERROR -> {
                // Used only to report back on hidden API calls used for testing.
                Log.d(TAG, stackEvent.toString());
            }
            default -> {}
        }
    }

    private void resendToStateMachine(HapClientStackEvent stackEvent) {
        synchronized (mStateMachines) {
            BluetoothDevice device = stackEvent.device;
            HapClientStateMachine sm = mStateMachines.get(device);

            if (sm == null) {
                if (stackEvent.type == HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                    switch (stackEvent.valueInt1) {
                        case STATE_CONNECTED, STATE_CONNECTING -> {
                            sm = getOrCreateStateMachine(device);
                        }
                        default -> {}
                    }
                }
            }
            if (sm == null) {
                Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                return;
            }
            sm.sendMessage(HapClientStateMachine.MESSAGE_STACK_EVENT, stackEvent);
        }
    }

    void registerCallback(IBluetoothHapClientCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.register(callback);
        }
    }

    void unregisterCallback(IBluetoothHapClientCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }
}
