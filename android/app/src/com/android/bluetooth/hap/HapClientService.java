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
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;
import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringConsumer;

import static java.util.Collections.emptyList;
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

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.SneakyThrow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Provides Bluetooth Hearing Access profile, as a service. */
public class HapClientService extends ConnectableProfile {
    private static final String TAG = HapClientService.class.getSimpleName();

    // Upper limit of all HearingAccess devices: Bonded or Connected
    private static final int MAX_HEARING_ACCESS_STATE_MACHINES = 10;
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private final Map<BluetoothDevice, HapClientStateMachine> mStateMachines =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceCurrentPresetMap = new HashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceFeaturesMap = new HashMap<>();
    private final Map<BluetoothDevice, List<BluetoothHapPresetInfo>> mPresetsMap = new HashMap<>();
    private final ActiveDeviceManager mActiveDeviceManager;
    private final Handler mHandler;
    private final Looper mStateMachinesLooper;
    private final HandlerThread mStateMachinesThread;
    private final HapClientNativeInterface mNativeInterface;

    @VisibleForTesting
    @GuardedBy("mCallbacks")
    final RemoteCallbackList<IBluetoothHapClientCallback> mCallbacks = new RemoteCallbackList<>();

    public HapClientService(
            AdapterService adapterService, ActiveDeviceManager activeDeviceManager) {
        this(
                adapterService,
                activeDeviceManager,
                Flags.hapOnMainLooper() ? Looper.getMainLooper() : null,
                null);
    }

    @VisibleForTesting
    HapClientService(
            AdapterService adapterService,
            ActiveDeviceManager activeDeviceManager,
            Looper looper,
            HapClientNativeInterface nativeInterface) {
        super(BluetoothProfile.HAP_CLIENT, adapterService);
        mActiveDeviceManager = activeDeviceManager;
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new HapClientNativeInterface(
                                        new HapClientNativeCallback(getAdapterService(), this)));

        if (Flags.hapOnMainLooper()) {
            mStateMachinesLooper = requireNonNull(looper);
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
        } else {
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
        }

        // Initialize native interface
        mNativeInterface.init();
    }

    public void syncPost(Consumer<HapClientService> consumer) {
        syncPost(
                (s) -> {
                    consumer.accept(s);
                    return null;
                },
                null);
    }

    public void post(Consumer<HapClientService> consumer) {
        Utils.enforceMainLooperIsNotUsed();

        mHandler.post(
                () -> {
                    // Service can become unavailable while the message is being posted
                    if (!isAvailable()) {
                        Log.e(TAG, "Service is no longer available.");
                        return;
                    }
                    consumer.accept(this);
                });
    }

    public <T> T syncPost(Function<HapClientService, T> function, T defaultValue) {
        Utils.enforceMainLooperIsNotUsed();

        FutureTask<T> task =
                new FutureTask<>(
                        () -> {
                            // Service can become unavailable while the message is being posted
                            if (!isAvailable()) {
                                Log.e(TAG, "Service is no longer available.");
                                return defaultValue;
                            }
                            return function.apply(this);
                        });
        mHandler.post(task);
        try {
            // Any method calling postAndWait should most likely be done in under 1 seconds.
            return task.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            SneakyThrow.sneakyThrow(e);
        } catch (ExecutionException e) {
            SneakyThrow.sneakyThrow(e.getCause());
        }
        return defaultValue;
    }

    void enforceMainLooperIsUsed() {
        if (!Flags.hapOnMainLooper()) {
            return;
        }
        // inline below once flag is rollout
        Utils.enforceMainLooperIsUsed();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileHapClientEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new HapClientServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

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

    @Override
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (Flags.hapOnMainLooper()) {
            bondStateChanged(device, toState);
        } else {
            mHandler.post(() -> bondStateChanged(device, toState));
        }
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        enforceMainLooperIsUsed();
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
        enforceMainLooperIsUsed();
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final var bondedDevices = getAdapterService().getBondedDevices();
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
                if (!Util.arrayContains(featureUuids, BluetoothUuid.HAS)) {
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
        if (Flags.hapOnMainLooper()) {
            // Getter can be accessed from Binder thread
            return mStateMachines.values().stream()
                    .filter(HapClientStateMachine::isConnected)
                    .map(HapClientStateMachine::getDevice)
                    .toList();
        }
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
    @Override
    public int getConnectionState(BluetoothDevice device) {
        enforceMainLooperIsUsed();
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
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        enforceMainLooperIsUsed();
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        enforceMainLooperIsUsed();
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
            int bondState = getAdapterService().getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
            }
        }
        mActiveDeviceManager.profileConnectionStateChanged(
                getProfileId(), device, fromState, toState);
        getAdapterService()
                .updateProfileConnectionAdapterProperties(
                        device, getProfileId(), toState, fromState);
    }

    @Override
    public boolean connect(BluetoothDevice device) {
        enforceMainLooperIsUsed();
        Log.d(TAG, "connect(): " + device);
        requireNonNull(device);

        if (!okToConnect(device)) {
            return false;
        }

        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
        if (!Util.arrayContains(featureUuids, BluetoothUuid.HAS)) {
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
                return false;
            }
            if (Flags.hapOnMainLooper()) {
                smConnect.dispatchMessage(HapClientStateMachine.MESSAGE_CONNECT);
                return true;
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
    @Override
    public boolean disconnect(BluetoothDevice device) {
        enforceMainLooperIsUsed();
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            HapClientStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                if (Flags.hapOnMainLooper()) {
                    sm.dispatchMessage(HapClientStateMachine.MESSAGE_DISCONNECT);
                    return true;
                }
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

    int getHapGroup(BluetoothDevice device) {
        final var csipSetCoordinator = getAdapterService().getCsipSetCoordinatorService();
        if (csipSetCoordinator.isPresent()) {
            final Map<Integer, ParcelUuid> groups =
                    csipSetCoordinator.get().getGroupUuidMapByDevice(device);
            for (Map.Entry<Integer, ParcelUuid> entry : groups.entrySet()) {
                if (entry.getValue().equals(BluetoothUuid.CAP)) {
                    return entry.getKey();
                }
            }
        }
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    int getActivePresetIndex(BluetoothDevice device) {
        return mDeviceCurrentPresetMap.getOrDefault(
                device, BluetoothHapClient.PRESET_INDEX_UNAVAILABLE);
    }

    BluetoothHapPresetInfo getActivePresetInfo(BluetoothDevice device) {
        int presetIndex = getActivePresetIndex(device);
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            return null;
        }

        return mPresetsMap.getOrDefault(device, emptyList()).stream()
                .filter(preset -> preset.getIndex() == presetIndex)
                .findFirst()
                .orElse(null);
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

    void selectPreset(BluetoothDevice device, int presetIndex) {
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            int status = BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
            broadcastToClient(cb -> cb.onPresetSelectionFailed(device, status));
            return;
        }

        mNativeInterface.selectActivePreset(device, presetIndex);
    }

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
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            return null;
        }

        if (Utils.isPtsTestMode()) {
            // Force sending over the air command. Returned result is not affected.
            mNativeInterface.getPresetInfo(device, presetIndex);
        }

        return mPresetsMap.getOrDefault(device, emptyList()).stream()
                .filter(preset -> preset.getIndex() == presetIndex)
                .findFirst()
                .orElse(null);
    }

    List<BluetoothHapPresetInfo> getAllPresetInfo(BluetoothDevice device) {
        if (Utils.isPtsTestMode()) {
            // Force sending over the air command. Returned result is not affected.
            mNativeInterface.getAllPresetInfo(device);
        }
        return mPresetsMap.getOrDefault(device, emptyList());
    }

    int getFeatures(BluetoothDevice device) {
        return mDeviceFeaturesMap.getOrDefault(device, 0x00);
    }

    /* WARNING: Matches status codes defined in bta_has.h */
    @VisibleForTesting static final int PRESET_INFO_REASON_ALL_PRESET_INFO = 0;
    @VisibleForTesting static final int PRESET_INFO_REASON_PRESET_INFO_UPDATE = 1;
    private static final int PRESET_INFO_REASON_PRESET_DELETED = 2;
    private static final int PRESET_INFO_REASON_PRESET_AVAILABILITY_CHANGED = 3;
    private static final int PRESET_INFO_REASON_PRESET_INFO_REQUEST_RESPONSE = 4;

    private static int nativeReasonToBluetoothStatusCodes(int nativeReason) {
        return switch (nativeReason) {
            case PRESET_INFO_REASON_ALL_PRESET_INFO ->
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;
            case PRESET_INFO_REASON_PRESET_INFO_UPDATE ->
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case PRESET_INFO_REASON_PRESET_DELETED -> BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case PRESET_INFO_REASON_PRESET_AVAILABILITY_CHANGED ->
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case PRESET_INFO_REASON_PRESET_INFO_REQUEST_RESPONSE ->
                    BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST;
            default -> BluetoothStatusCodes.ERROR_UNKNOWN;
        };
    }

    private void notifyPresetInfoChanged(BluetoothDevice device, int nativeReason) {
        List<BluetoothHapPresetInfo> current_presets = mPresetsMap.get(device);
        if (current_presets == null) return;

        int reason = nativeReasonToBluetoothStatusCodes(nativeReason);
        broadcastToClient(cb -> cb.onPresetInfoChanged(device, current_presets, reason));
    }

    private boolean isPresetIndexValid(BluetoothDevice device, int presetIndex) {
        if (presetIndex == BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            return false;
        }

        return mPresetsMap.getOrDefault(device, emptyList()).stream()
                .anyMatch(preset -> preset.getIndex() == presetIndex);
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

        return getAdapterService()
                .getCsipSetCoordinatorService()
                .map(csipClient -> csipClient.getAllGroupIds(BluetoothUuid.CAP).contains(groupId))
                .orElse(false);
    }

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
            BluetoothDevice device, int reason, List<BluetoothHapPresetInfo> presets) {
        switch (reason) {
            case PRESET_INFO_REASON_ALL_PRESET_INFO -> mPresetsMap.put(device, presets);
            case PRESET_INFO_REASON_PRESET_INFO_UPDATE,
                    PRESET_INFO_REASON_PRESET_AVAILABILITY_CHANGED,
                    PRESET_INFO_REASON_PRESET_INFO_REQUEST_RESPONSE -> {

                // Remove updated presets from the list and add the new one while keeping order
                List<BluetoothHapPresetInfo> unchangedPresets = getFilteredPresets(device, presets);
                List<BluetoothHapPresetInfo> finalPresets =
                        Stream.concat(unchangedPresets.stream(), presets.stream())
                                .sorted(Comparator.comparingInt(BluetoothHapPresetInfo::getIndex))
                                .toList();

                mPresetsMap.put(device, finalPresets);
            }
            case PRESET_INFO_REASON_PRESET_DELETED -> {
                List<BluetoothHapPresetInfo> remainingPresets = getFilteredPresets(device, presets);
                mPresetsMap.put(device, remainingPresets);
            }
            default -> {}
        }
    }

    private List<BluetoothHapPresetInfo> getFilteredPresets(
            BluetoothDevice device, List<BluetoothHapPresetInfo> presetsToFilter) {
        // Create a Set of indices from the new presets for efficient lookup.
        // This is much faster (O(1) lookup) than iterating through a list every time.
        final Set<Integer> presetsIndexToFilter =
                presetsToFilter.stream()
                        .map(BluetoothHapPresetInfo::getIndex)
                        .collect(Collectors.toSet());

        return mPresetsMap.getOrDefault(device, emptyList()).stream()
                .filter(p -> !presetsIndexToFilter.contains(p.getIndex()))
                .toList();
    }

    private List<BluetoothDevice> getGroupDevices(int groupId) {
        if (groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
            return emptyList();
        }

        return getAdapterService()
                .getCsipSetCoordinatorService()
                .map(csipClient -> csipClient.getGroupDevicesOrdered(groupId))
                .orElse(emptyList());
    }

    void onConnectionStateChanged(BluetoothDevice device, int state) {
        var log = "onConnectionStateChanged(" + device + ", " + getConnectionStateName(state) + ")";
        synchronized (mStateMachines) {
            HapClientStateMachine sm =
                    switch (state) {
                        case STATE_CONNECTED, STATE_CONNECTING -> getOrCreateStateMachine(device);
                        default -> mStateMachines.get(device);
                    };

            if (sm == null) {
                Log.e(TAG, log + ": No state machine");
                return;
            }
            Log.d(TAG, log);
            if (Flags.hapOnMainLooper()) {
                sm.dispatchMessage(HapClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state);
                return;
            }
            sm.sendMessage(HapClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state);
        }
    }

    void onDeviceAvailable(BluetoothDevice device, int features) {
        mDeviceFeaturesMap.put(device, features);

        Intent intent =
                new Intent(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE)
                        .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                        .putExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, features);
        getBaseContext()
                .sendBroadcastWithMultiplePermissions(
                        intent, new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED});
    }

    void onFeaturesUpdate(BluetoothDevice device, int features) {
        mDeviceFeaturesMap.put(device, features);
        Log.d(TAG, "onFeaturesUpdate(" + device + ", " + String.format("0x%04X", features));
    }

    void onPresetSelected(BluetoothDevice device, int presetIndex) {
        int reason = BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;

        mDeviceCurrentPresetMap.put(device, presetIndex);
        broadcastToClient(cb -> cb.onPresetSelected(device, presetIndex, reason));
    }

    void onPresetSelectedForGroup(int groupId, int presetIndex) {
        for (BluetoothDevice device : getGroupDevices(groupId)) {
            onPresetSelected(device, presetIndex);
        }
    }

    void onPresetSelectionFailed(BluetoothDevice device, int status) {
        broadcastToClient(cb -> cb.onPresetSelectionFailed(device, status));
    }

    void onPresetSelectionForGroupFailed(int groupId, int status) {
        broadcastToClient(cb -> cb.onPresetSelectionForGroupFailed(groupId, status));
    }

    void onPresetInfo(
            BluetoothDevice device, int nativeReason, List<BluetoothHapPresetInfo> presets) {
        updateDevicePresetsCache(device, nativeReason, presets);
        notifyPresetInfoChanged(device, nativeReason);
    }

    void onPresetInfoForGroup(int groupId, int nativeReason, List<BluetoothHapPresetInfo> presets) {
        for (BluetoothDevice device : getGroupDevices(groupId)) {
            onPresetInfo(device, nativeReason, presets);
        }
    }

    void onSetPresetNameFailed(BluetoothDevice device, int status) {
        broadcastToClient(cb -> cb.onSetPresetNameFailed(device, status));
    }

    void onSetPresetNameForGroupFailed(int groupId, int status) {
        broadcastToClient(cb -> cb.onSetPresetNameForGroupFailed(groupId, status));
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
