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

package com.android.bluetooth.vc;

import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class VolumeControlService extends ConnectableProfile {
    private static final String TAG = VolumeControlService.class.getSimpleName();

    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;
    private static final int LE_AUDIO_MAX_VOL = 255;
    /* As defined by Volume Control Service 1.0.1, 3.3.1. Volume Flags behavior.
     * User Set Volume Setting means that remote keeps volume in its cache. */
    @VisibleForTesting static final int VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK = 0x01;

    private static final int GROUP_ID_INVALID = -1;

    @VisibleForTesting
    @GuardedBy("mCallbacks")
    final RemoteCallbackList<IBluetoothVolumeControlCallback> mCallbacks =
            new RemoteCallbackList<>();

    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final HandlerThread mStateMachinesThread;
    private final Looper mStateMachinesLooper;
    private final VolumeControlNativeInterface mNativeInterface;

    private final Map<BluetoothDevice, VolumeControlStateMachine> mStateMachines =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, VolumeControlOffsetDescriptor> mAudioOffsets =
            new HashMap<>();
    private final Map<BluetoothDevice, VolumeControlInputDescriptor> mAudioInputs =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Integer> mGroupIds = new ConcurrentHashMap<>();
    /*groupId, VolumeControlStreamType, volume*/
    private final Map<Integer, Map<Integer, Integer>> mGroupVolumeCache = new ConcurrentHashMap<>();
    /*groupId, VolumeControlStreamType, mute*/
    private final Map<Integer, Map<Integer, Boolean>> mGroupMuteCache = new ConcurrentHashMap<>();
    /*BluetoothDevice, VolumeControlStreamType, volume*/
    private final Map<BluetoothDevice, Map<Integer, Integer>> mDeviceVolumeCache =
            new ConcurrentHashMap<>();
    /*BluetoothDevice, VolumeControlStreamType, mute*/
    private final Map<BluetoothDevice, Map<Integer, Boolean>> mDeviceMuteCache =
            new ConcurrentHashMap<>();

    private Boolean mIgnoreSetVolumeFromAF = false;

    public VolumeControlService(AdapterService adapterService) {
        this(adapterService, null, null);
    }

    @VisibleForTesting
    VolumeControlService(
            AdapterService adapterService,
            Looper looper,
            VolumeControlNativeInterface nativeInterface) {
        super(BluetoothProfile.VOLUME_CONTROL, adapterService);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new VolumeControlNativeInterface(
                                        new VolumeControlNativeCallback(
                                                getAdapterService(), this)));
        mAudioManager = requireNonNull(obtainSystemService(AudioManager.class));
        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesThread = new HandlerThread("VolumeControlService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
        }
        mNativeInterface.init();
    }

    public void post(Consumer<VolumeControlService> consumer) {
        Utils.enforceMainLooperIsNotUsed();

        mHandler.post(
                () -> {
                    // Service can become unavailable while the message is being posted
                    if (!isAvailable()) {
                        Log.e(TAG, "Service is no longer available");
                        return;
                    }
                    consumer.accept(this);
                });
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileVcpControllerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new VolumeControlServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
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

        mHandler.removeCallbacksAndMessages(null);

        // Cleanup native interface
        mNativeInterface.cleanup();

        mAudioOffsets.clear();
        mGroupIds.clear();
        mGroupVolumeCache.clear();
        mGroupMuteCache.clear();
        mDeviceVolumeCache.clear();
        mDeviceMuteCache.clear();
        updateIgnoreSetVolumeFromAFFlag(false);

        synchronized (mCallbacks) {
            mCallbacks.kill();
        }
    }

    Map<BluetoothDevice, VolumeControlInputDescriptor> getAudioInputs() {
        return mAudioInputs;
    }

    @Override
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        requireNonNull(device);

        if (!okToConnect(device)) {
            return false;
        }

        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
        if (!Util.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
            Log.e(
                    TAG,
                    "Cannot connect to " + device + " : Remote does not have Volume Control UUID");
            return false;
        }

        synchronized (mStateMachines) {
            VolumeControlStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
            }
            smConnect.sendMessage(VolumeControlStateMachine.MESSAGE_CONNECT);
        }

        return true;
    }

    @Override
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = getOrCreateStateMachine(device);
            if (sm != null) {
                sm.sendMessage(VolumeControlStateMachine.MESSAGE_DISCONNECT);
            }
        }

        return true;
    }

    private int getGroupId(BluetoothDevice device) {
        if (device == null) {
            return GROUP_ID_INVALID;
        }

        return mGroupIds.getOrDefault(device, GROUP_ID_INVALID);
    }

    private List<BluetoothDevice> getGroupDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();

        if (groupId == GROUP_ID_INVALID) {
            return result;
        }

        for (Map.Entry<BluetoothDevice, Integer> entry : mGroupIds.entrySet()) {
            if (entry.getValue() == groupId) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
        }
        return devices;
    }

    private List<BluetoothDevice> getConnectedDevices(int groupId) {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                VolumeControlStateMachine sm = mStateMachines.get(dev);
                if (sm != null && sm.isConnected()) {
                    devices.add(dev);
                }
            }
        }
        return devices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final var bondedDevices = getAdapterService().getBondedDevices();
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
                if (!Util.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                VolumeControlStateMachine sm = mStateMachines.get(device);
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
     * @return the list of devices that have a state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    @Override
    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    boolean isVolumeOffsetAvailable(BluetoothDevice device) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.i(TAG, " There is no offset service for device: " + device);
            return false;
        }
        Log.i(TAG, " Offset service available for device: " + device);
        return true;
    }

    int getNumberOfVolumeOffsetInstances(BluetoothDevice device) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.i(TAG, " There is no offset service for device: " + device);
            return 0;
        }

        int numberOfInstances = offsets.size();

        Log.i(TAG, "Number of VOCS: " + numberOfInstances + ", for device: " + device);
        return numberOfInstances;
    }

    void setVolumeOffset(BluetoothDevice device, int instanceId, int volumeOffset) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " There is no offset service for device: " + device);
            return;
        }

        int numberOfInstances = offsets.size();
        if (instanceId > numberOfInstances) {
            Log.e(
                    TAG,
                    "Selected VOCS instance ID: "
                            + instanceId
                            + ", exceed available IDs: "
                            + numberOfInstances
                            + ", for device: "
                            + device);
            return;
        }

        int value = offsets.getValue(instanceId);
        if (value == volumeOffset) {
            /* Nothing to do - offset already applied */
            return;
        }

        mNativeInterface.setExtAudioOutVolumeOffset(device, instanceId, volumeOffset);
    }

    synchronized void setDeviceVolume(BluetoothDevice device, int volume, boolean isGroupOp) {
        Log.d(
                TAG,
                "setDeviceVolume: " + device + ", volume: " + volume + ", isGroupOp: " + isGroupOp);

        if (volume < 0) {
            Log.w(TAG, "Tried to set invalid volume " + volume + ". Ignored.");
            return;
        }

        if (isGroupOp) {
            int groupId = getGroupId(device);
            if (groupId == GROUP_ID_INVALID) {
                Log.e(TAG, "Device not a part of a group");
                return;
            }

            setGroupVolume(groupId, volume);
        } else {
            Log.i(TAG, "Setting individual device volume");
            int streamType = getCurrentStreamType();
            mDeviceVolumeCache
                    .computeIfAbsent(device, k -> new ConcurrentHashMap<>())
                    .put(streamType, volume);
            mNativeInterface.setVolume(device, volume);

            // We only receive the volume change and mute state needs to be acquired manually
            Boolean isStreamMute = mAudioManager.isStreamMute(streamType);
            adjustDeviceMute(device, volume, isStreamMute);
        }
    }

    private void adjustDeviceMute(BluetoothDevice device, int volume, Boolean isStreamMute) {
        Boolean isMute = getMute(device);
        if (!isMute.equals(isStreamMute)) {
            Log.d(
                    TAG,
                    "Mute state mismatch, stream mute: "
                            + isStreamMute
                            + ", device mute: "
                            + isMute
                            + ", new volume: "
                            + volume);
            if (isStreamMute) {
                Log.i(TAG, "Mute the device " + device);
                mute(device);
            }
            if (!isStreamMute && (volume > 0)) {
                Log.i(TAG, "Unmute the device " + device);
                unmute(device);
            }
        }
    }

    public synchronized void setGroupVolume(int groupId, int volume) {
        Log.d(TAG, "setGroupVolume: " + groupId + ", volume: " + volume);

        if (mIgnoreSetVolumeFromAF) {
            Log.d(TAG, "setGroupVolume ignored (from AF) because persisted/cached volume was used");
            updateIgnoreSetVolumeFromAFFlag(false);
            return;
        }

        if (volume < 0) {
            Log.w(TAG, "Tried to set invalid volume " + volume + ". Ignored.");
            return;
        }

        int streamType = getCurrentStreamType();

        synchronized (mDeviceVolumeCache) {
            mGroupVolumeCache
                    .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(streamType, volume);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceVolumeCache
                        .computeIfAbsent(dev, k -> new ConcurrentHashMap<>())
                        .put(streamType, volume);
            }
        }

        mNativeInterface.setGroupVolume(groupId, volume);

        // We only receive the volume change and mute state needs to be acquired manually
        Boolean isGroupMute = getGroupMute(groupId);
        Boolean isStreamMute = mAudioManager.isStreamMute(streamType);

        /* Note: AudioService keeps volume levels for each stream and for each device type,
         * however it stores the mute state only for the stream type but not for each individual
         * device type. When active device changes, it's volume level gets applied, but mute state
         * is not, but can be either derived from the volume level or just unmuted like for A2DP.
         * Also setting volume level > 0 to audio system will implicitly unmute the stream.
         * However LeAudio devices can keep their volume level high, while keeping it mute so we
         * have to explicitly unmute the remote device.
         */
        if (!isGroupMute.equals(isStreamMute)) {
            Log.d(
                    TAG,
                    "Mute state mismatch, stream mute: "
                            + isStreamMute
                            + ", device group mute: "
                            + isGroupMute
                            + ", new volume: "
                            + volume);
            if (isStreamMute) {
                Log.i(TAG, "Mute the group " + groupId);
                muteGroup(groupId);
            }
            if (!isStreamMute && (volume > 0)) {
                Log.i(TAG, "Unmute the group " + groupId);
                unmuteGroup(groupId);
            }
        } else {
            for (BluetoothDevice device : getGroupDevices(groupId)) {
                adjustDeviceMute(device, volume, isStreamMute);
            }
        }
    }

    /**
     * @return the volume. If not cached, return volume from any device in the group
     */
    @VisibleForTesting
    int getGroupVolume(int groupId) {
        synchronized (mDeviceVolumeCache) {
            int streamType = getCurrentStreamType();
            Integer volume =
                    mGroupVolumeCache.getOrDefault(groupId, Collections.emptyMap()).get(streamType);
            if (volume != null) {
                return volume;
            }
            Log.d(TAG, "No group volume available");
            for (BluetoothDevice device : getGroupDevices(groupId)) {
                volume =
                        mDeviceVolumeCache
                                .getOrDefault(device, Collections.emptyMap())
                                .get(streamType);
                if (volume != null) {
                    Log.w(TAG, "Volume taken from device: " + device);
                    return volume;
                }
            }
            return VOLUME_CONTROL_UNKNOWN_VOLUME;
        }
    }

    /**
     * @return the volume. If not cached, return volume from another device in the group
     */
    @VisibleForTesting
    int getDeviceVolume(BluetoothDevice device) {
        synchronized (mDeviceVolumeCache) {
            int streamType = getCurrentStreamType();
            Integer volume =
                    mDeviceVolumeCache.getOrDefault(device, Collections.emptyMap()).get(streamType);
            if (volume != null) {
                return volume;
            }
            return mGroupVolumeCache
                    .getOrDefault(getGroupId(device), Collections.emptyMap())
                    .getOrDefault(streamType, VOLUME_CONTROL_UNKNOWN_VOLUME);
        }
    }

    /** Called by LeAudioService when the group change its active state. */
    public synchronized void setGroupActive(int groupId, boolean active) {
        Log.d(TAG, "setGroupActive: " + groupId + ", active: " + active);
        if (!active) {
            /* For now we don't need to handle group inactivation */
            return;
        }

        updateAudioSystem(groupId, /* showInUI */ false);
    }

    /**
     * @return the mute status. If not cached, return volume from another device in the group
     */
    @VisibleForTesting
    Boolean getMute(BluetoothDevice device) {
        synchronized (mDeviceMuteCache) {
            int streamType = getCurrentStreamType();
            Boolean isMute =
                    mDeviceMuteCache.getOrDefault(device, Collections.emptyMap()).get(streamType);
            if (isMute != null) {
                return isMute;
            }
            return mGroupMuteCache
                    .getOrDefault(getGroupId(device), Collections.emptyMap())
                    .getOrDefault(streamType, false);
        }
    }

    /**
     * @return the mute status. If not cached, return volume from any device in the group
     */
    @VisibleForTesting
    Boolean getGroupMute(int groupId) {
        synchronized (mDeviceMuteCache) {
            int streamType = getCurrentStreamType();
            Boolean isMute =
                    mGroupMuteCache.getOrDefault(groupId, Collections.emptyMap()).get(streamType);
            if (isMute != null) {
                return isMute;
            }
            for (BluetoothDevice device : getGroupDevices(groupId)) {
                isMute =
                        mDeviceMuteCache
                                .getOrDefault(device, Collections.emptyMap())
                                .get(streamType);
                if (isMute != null) {
                    return isMute;
                }
            }
            return false;
        }
    }

    @VisibleForTesting
    void mute(BluetoothDevice device) {
        mDeviceMuteCache
                .computeIfAbsent(device, k -> new ConcurrentHashMap<>())
                .put(getCurrentStreamType(), true);
        mNativeInterface.mute(device);
    }

    @VisibleForTesting
    void muteGroup(int groupId) {
        synchronized (mDeviceMuteCache) {
            int streamType = getCurrentStreamType();
            mGroupMuteCache
                    .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(streamType, true);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceMuteCache
                        .computeIfAbsent(dev, k -> new ConcurrentHashMap<>())
                        .put(streamType, true);
            }
        }
        mNativeInterface.muteGroup(groupId);
    }

    @VisibleForTesting
    void unmute(BluetoothDevice device) {
        mDeviceMuteCache
                .computeIfAbsent(device, k -> new ConcurrentHashMap<>())
                .put(getCurrentStreamType(), false);
        mNativeInterface.unmute(device);
    }

    @VisibleForTesting
    void unmuteGroup(int groupId) {
        synchronized (mDeviceMuteCache) {
            int streamType = getCurrentStreamType();
            mGroupMuteCache
                    .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(streamType, false);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceMuteCache
                        .computeIfAbsent(dev, k -> new ConcurrentHashMap<>())
                        .put(streamType, false);
            }
        }
        mNativeInterface.unmuteGroup(groupId);
    }

    void notifyNewCallbackOfKnownVolumeInfo(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "notifyNewCallbackOfKnownVolumeInfo");

        // notify volume offset
        for (Map.Entry<BluetoothDevice, VolumeControlOffsetDescriptor> entry :
                mAudioOffsets.entrySet()) {
            VolumeControlOffsetDescriptor descriptor = entry.getValue();

            for (int id = 1; id <= descriptor.size(); id++) {
                BluetoothDevice device = entry.getKey();
                int offset = descriptor.getValue(id);
                int location = descriptor.getLocation(id);
                String description = descriptor.getDescription(id);

                Log.d(
                        TAG,
                        "notifyNewCallbackOfKnownVolumeInfo,"
                                + (" device: " + device)
                                + (", id: " + id)
                                + (", offset: " + offset)
                                + (", location: " + location)
                                + (", description: " + description));
                try {
                    callback.onVolumeOffsetChanged(device, id, offset);
                    callback.onVolumeOffsetAudioLocationChanged(device, id, location);
                    callback.onVolumeOffsetAudioDescriptionChanged(device, id, description);
                } catch (RemoteException e) {
                    // Dead client -- continue
                }
            }
        }

        // using tempCallbackList is a hack to keep using 'notifyDevicesVolumeChanged'
        // without making any extra modification
        RemoteCallbackList<IBluetoothVolumeControlCallback> tempCallbackList =
                new RemoteCallbackList<>();

        tempCallbackList.register(callback);
        notifyDevicesVolumeChanged(tempCallbackList, getDevices(), Optional.empty());
        tempCallbackList.unregister(callback);
    }

    void registerCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "registerCallback: " + callback);

        synchronized (mCallbacks) {
            /* Here we keep all the user callbacks */
            mCallbacks.register(callback);
        }

        notifyNewCallbackOfKnownVolumeInfo(callback);
    }

    void unregisterCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "unregisterCallback: " + callback);

        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    void notifyNewRegisteredCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "notifyNewRegisteredCallback: " + callback);
        notifyNewCallbackOfKnownVolumeInfo(callback);
    }

    public synchronized void handleGroupNodeAdded(int groupId, BluetoothDevice device) {
        // Ignore disconnected device, its volume will be set once it connects
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (!sm.isConnected()) {
                return;
            }
        }

        // If group volume has already changed, the new group member should set it
        int volume = getDeviceVolume(device);
        if (volume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
            Log.i(TAG, "Setting device/group volume:" + volume + " to the device:" + device);
            setDeviceVolume(device, volume, false);
            Boolean isDeviceMuted = getMute(device);
            Log.i(TAG, "Setting mute:" + isDeviceMuted + " to " + device);
            if (isDeviceMuted) {
                mute(device);
            } else {
                unmute(device);
            }
        }
    }

    private void updateCacheByAutonomousChange(
            int groupId, int volume, boolean mute, boolean showInUI) {
        Log.d(
                TAG,
                "updateGroupAutonomousChange: "
                        + ("groupId: " + groupId)
                        + (", vol: " + volume)
                        + (", mute: " + mute)
                        + (", showInUI: " + showInUI));

        if (volume < 0) {
            Log.w(TAG, "Tried to update invalid volume " + volume + ". Ignored.");
            return;
        }

        synchronized (mDeviceVolumeCache) {
            int streamType = getCurrentStreamType();
            mGroupVolumeCache
                    .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(streamType, volume);
            mGroupMuteCache
                    .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(streamType, mute);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceVolumeCache
                        .computeIfAbsent(dev, k -> new ConcurrentHashMap<>())
                        .put(streamType, volume);
                mDeviceMuteCache
                        .computeIfAbsent(dev, k -> new ConcurrentHashMap<>())
                        .put(streamType, mute);
            }
        }

        updateAudioSystem(groupId, showInUI);
    }

    private void updateAudioSystem(int groupId, boolean showInUI) {
        int streamType = getCurrentStreamType();
        Log.d(
                TAG,
                "updateAudioSystem: "
                        + ("groupId: " + groupId)
                        + (", showInUI: " + showInUI)
                        + (", streamType: " + streamType)
                        + (", mGroupVolumeCache: " + mGroupVolumeCache)
                        + (", mGroupMuteCache: " + mGroupMuteCache));

        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isPresent()) {
            int currentlyActiveGroupId = leAudio.get().getActiveGroupId();
            if (currentlyActiveGroupId == GROUP_ID_INVALID || groupId != currentlyActiveGroupId) {
                final var bassClient = getAdapterService().getBassClientService();
                if (bassClient.isEmpty()
                        || bassClient.get().getSyncedBroadcastSinks().stream()
                                .map(dev -> getGroupId(dev))
                                .noneMatch(
                                        id -> id == groupId && leAudio.get().isPrimaryGroup(id))) {
                    Log.i(
                            TAG,
                            "Skip updating to audio system if not updating volume for current"
                                    + " active group in unicast or primary group in broadcast");
                    return;
                }
            }
        } else {
            Log.w(TAG, "leAudioService not available");
        }

        mGroupVolumeCache
                .getOrDefault(groupId, Collections.emptyMap())
                .forEach(
                        (streamT, vol) -> {
                            int flags = AudioManager.FLAG_BLUETOOTH_ABS_VOLUME;
                            if (showInUI && streamT == streamType) {
                                flags |= AudioManager.FLAG_SHOW_UI;
                            }
                            mAudioManager.setStreamVolume(
                                    streamT, getAudioDeviceVolume(streamT, vol), flags);
                        });

        mGroupMuteCache
                .getOrDefault(groupId, Collections.emptyMap())
                .forEach(
                        (streamT, mute) -> {
                            int flags = AudioManager.FLAG_BLUETOOTH_ABS_VOLUME;
                            if (showInUI && streamT == streamType) {
                                flags |= AudioManager.FLAG_SHOW_UI;
                            }
                            if (mAudioManager.isStreamMute(streamT) != mute) {
                                int adjustment =
                                        mute
                                                ? AudioManager.ADJUST_MUTE
                                                : AudioManager.ADJUST_UNMUTE;
                                mAudioManager.adjustStreamVolume(streamT, adjustment, flags);
                            }
                        });
    }

    /**
     * Set the flag to ignore volume changes from the Audio Framework (AF).
     *
     * <p>This is necessary when a remote device with a persisted volume connects, or when a cached
     * volume is used. In these cases, the device becomes active, and AF send a volume change
     * request which should be ignored to respect the remote's or cached volume.
     *
     * <p>However, during a local broadcast, the newly connected device does not become active, so
     * volume changes from AF should not be ignored.
     *
     * @param value The new value for the flag.
     */
    private void updateIgnoreSetVolumeFromAFFlag(boolean value) {
        boolean broadcastActive = false;
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isPresent()) {
            broadcastActive = leAudio.get().isBroadcastActive();
        }
        if (!value || !broadcastActive) {
            Log.d(TAG, "Set mIgnoreSetVolumeFromAF: " + value);
            mIgnoreSetVolumeFromAF = value;
        } else {
            Log.d(TAG, "Skip mIgnoreSetVolumeFromAF set as local broadcast is active");
        }
    }

    synchronized void handleVolumeControlChanged(
            BluetoothDevice device,
            int groupId,
            int volume,
            int flags,
            boolean mute,
            boolean isAutonomous) {
        if (groupId == GROUP_ID_INVALID) {
            groupId = getGroupId(device);
        }
        if (groupId == GROUP_ID_INVALID) {
            Log.e(TAG, "Device not a part of the group");
            return;
        }

        if (isAutonomous && device != null) {
            Log.i(
                    TAG,
                    ("Initial volume set after connect, volume: " + volume)
                            + (", mute: " + mute)
                            + (", flags: " + flags)
                            + (", device: " + device));
            /* We are here, because system has just started and LeAudio device is connected. If
             * remote device has User Persistent flag set, Android sets the volume to local cache
             * and to the audio system if not already streaming to other devices.
             * If Reset Flag is set, then Android sets to remote devices either cached volume or
             * volume taken from audio manager (AF always call setVolume via LeAudioService at first
             * connected remote from group).
             * Note, to match BR/EDR behavior, don't show volume change in UI here
             */
            if (((flags & VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK) == 0x01)
                    && (getConnectedDevices(groupId).size() == 1)) {
                Log.i(TAG, "Setting device: " + device + " volume: " + volume + " to the system");
                // Ignore volume from AF because persisted volume was used
                updateIgnoreSetVolumeFromAFFlag(true);
                updateCacheByAutonomousChange(groupId, volume, mute, /* showInUI */ false);
            } else {
                // Reset flag is used
                int deviceVolume = getDeviceVolume(device);
                if (deviceVolume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
                    Log.i(
                            TAG,
                            "Setting device/group volume: "
                                    + deviceVolume
                                    + " to the device: "
                                    + device);
                    setDeviceVolume(device, deviceVolume, false);
                    Boolean isDeviceMuted = getMute(device);
                    Log.i(TAG, "Setting mute:" + isDeviceMuted + " to " + device);
                    if (isDeviceMuted) {
                        mute(device);
                    } else {
                        unmute(device);
                    }
                    if (getConnectedDevices(groupId).size() == 1) {
                        // Ignore volume from AF because cached volume was used
                        updateIgnoreSetVolumeFromAFFlag(true);
                    }
                } else {
                    Log.i(TAG, "Waiting for volume from AF to set to the device: " + device);
                    if (getConnectedDevices(groupId).size() == 1) {
                        // Clear ignore flag as volume from AF is needed
                        updateIgnoreSetVolumeFromAFFlag(false);
                    }
                }
            }
            if (!Flags.vcpNotifyVolumeOnEachDeviceConnection()) {
                return;
            }
        }

        Log.i(
                TAG,
                "handleVolumeControlChanged: "
                        + ("device: " + device)
                        + (", groupId: " + groupId)
                        + (", volume: " + volume));
        if (device == null) {
            // notify group devices volume changed
            synchronized (mCallbacks) {
                notifyDevicesVolumeChanged(
                        mCallbacks, getGroupDevices(groupId), Optional.of(volume));
            }
        } else {
            // notify device volume changed
            synchronized (mCallbacks) {
                notifyDevicesVolumeChanged(mCallbacks, Arrays.asList(device), Optional.of(volume));
            }
        }

        if (isAutonomous && device == null) {
            /* Received group notification for autonomous change. Update cache and audio system. */
            updateCacheByAutonomousChange(groupId, volume, mute, /* showInUI */ true);
        }
    }

    public int getAudioDeviceGroupVolume(int groupId) {
        int volume = getGroupVolume(groupId);
        if (getGroupMute(groupId)) {
            Log.w(
                    TAG,
                    "Volume level is "
                            + volume
                            + ", but muted. Will report 0 for the audio device.");
            volume = 0;
        }

        int streamType = getCurrentStreamType();
        Log.d(
                TAG,
                "getAudioDeviceGroupVolume: "
                        + ("groupId: " + groupId)
                        + (", streamType: " + streamType)
                        + (", volume: " + volume));

        if (volume == VOLUME_CONTROL_UNKNOWN_VOLUME) return VOLUME_CONTROL_UNKNOWN_VOLUME;
        return getAudioDeviceVolume(streamType, volume);
    }

    int getAudioDeviceVolume(int streamType, int bleVolume) {
        int deviceMaxVolume = mAudioManager.getStreamMaxVolume(streamType);

        // TODO: Investigate what happens in classic BT when BT volume is changed to zero.
        double deviceVolume = (double) (bleVolume * deviceMaxVolume) / LE_AUDIO_MAX_VOL;
        return (int) Math.round(deviceVolume);
    }

    int getCurrentStreamType() {
        return switch (mAudioManager.getMode()) {
            case AudioManager.MODE_RINGTONE,
                    AudioManager.MODE_IN_CALL,
                    AudioManager.MODE_IN_COMMUNICATION ->
                    AudioManager.STREAM_VOICE_CALL;
            default -> AudioManager.STREAM_MUSIC;
        };
    }

    void handleExternalOutputs(BluetoothDevice device, int numberOfExternalOutputs) {
        if (numberOfExternalOutputs == 0) {
            Log.i(TAG, "Volume offset not available");
            return;
        }

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            offsets = new VolumeControlOffsetDescriptor();
            mAudioOffsets.put(device, offsets);
        } else if (offsets.size() != numberOfExternalOutputs) {
            Log.i(TAG, "Number of offset changed: ");
            offsets.clear();
        }

        /* Stack delivers us number of audio outputs.
         * Offset ids a continuous from 1 to number_of_ext_outputs*/
        for (int i = 1; i <= numberOfExternalOutputs; i++) {
            offsets.add(i);
            /* Native stack is doing required reads under the hood */
        }
    }

    void handleExternalInputs(BluetoothDevice device, int numberOfExternalInputs) {
        if (numberOfExternalInputs == 0) {
            Log.i(TAG, "Volume offset not available");
            mAudioInputs.remove(device);
            return;
        }

        mAudioInputs.put(
                device,
                new VolumeControlInputDescriptor(mNativeInterface, device, numberOfExternalInputs));
    }

    void handleDeviceAvailable(
            BluetoothDevice device,
            int groupId,
            int numberOfExternalOutputs,
            int numberOfExternalInputs) {
        mGroupIds.put(device, groupId);
        Log.d(TAG, "handleDeviceAvailable: mGroupIds: " + mGroupIds);
        handleExternalOutputs(device, numberOfExternalOutputs);
        handleExternalInputs(device, numberOfExternalInputs);
    }

    void handleDeviceExtAudioOffsetChanged(BluetoothDevice device, int id, int value) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " value: " + value);
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setValue(id, value);

        synchronized (mCallbacks) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onVolumeOffsetChanged(device, id, value);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void handleDeviceExtAudioLocationChanged(BluetoothDevice device, int id, int location) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " location: " + location);

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setLocation(id, location);

        synchronized (mCallbacks) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mCallbacks
                            .getBroadcastItem(i)
                            .onVolumeOffsetAudioLocationChanged(device, id, location);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void handleDeviceExtAudioDescriptionChanged(
            BluetoothDevice device, int id, String description) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " description: " + description);

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setDescription(id, description);

        synchronized (mCallbacks) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mCallbacks
                            .getBroadcastItem(i)
                            .onVolumeOffsetAudioDescriptionChanged(device, id, description);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void onExtAudioInStateChanged(
            BluetoothDevice device,
            int id,
            int gainSetting,
            @Mute int mute,
            @GainMode int gainMode) {
        String logInfo =
                "onExtAudioInStateChanged("
                        + ("device:" + device)
                        + (", id" + id)
                        + (" gainSetting: " + gainSetting)
                        + (" gainMode: " + gainMode)
                        + (" mute: " + mute)
                        + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + " This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.onStateChanged(id, gainSetting, mute, gainMode);
    }

    void onExtAudioInSetGainSettingFailed(BluetoothDevice device, int id) {
        String logInfo = "onExtAudioInSetGainSettingFailed(" + device + ", " + id + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + " This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.onSetGainSettingFailed(id);
    }

    void onExtAudioInSetMuteFailed(BluetoothDevice device, int id) {
        String logInfo = "onExtAudioInSetMuteFailed(" + device + ", " + id + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + " This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.onSetMuteFailed(id);
    }

    void onExtAudioInSetGainModeFailed(BluetoothDevice device, int id) {
        String logInfo = "onExtAudioInSetGainModeFailed(" + device + ", " + id + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + " This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.onSetGainModeFailed(id);
    }

    void onExtAudioInStatusChanged(BluetoothDevice device, int id, @AudioInputStatus int status) {
        String logInfo =
                "onExtAudioInStatusChanged("
                        + ("device=" + device)
                        + (", id=" + id)
                        + (", status=" + status)
                        + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + " This device has no audio input control");
            return;
        }

        if (status != bluetooth.constants.aics.AudioInputStatus.INACTIVE
                && status != bluetooth.constants.aics.AudioInputStatus.ACTIVE) {
            Log.e(TAG, logInfo + ": Invalid status argument");
            return;
        }

        Log.d(TAG, logInfo);
        input.onStatusChanged(id, status);
    }

    void onExtAudioInTypeChanged(BluetoothDevice device, int id, @AudioInputType int type) {
        String logInfo =
                "onExtAudioInTypeChanged("
                        + ("device=" + device)
                        + (", id=" + id)
                        + (", type=" + type)
                        + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + ": This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.setType(id, type);
    }

    void onExtAudioInDescriptionChanged(
            BluetoothDevice device, int id, String description, boolean isWritable) {
        String logInfo =
                "onExtAudioInDescriptionChanged("
                        + ("device=" + device)
                        + (", id=" + id)
                        + (", description=" + description)
                        + (", isWritable=" + isWritable)
                        + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + ": This device has no audio input control");
            return;
        }

        if (description == null) {
            Log.e(TAG, logInfo + ": Invalid description argument");
            return;
        }

        Log.d(TAG, logInfo);
        input.onDescriptionChanged(id, description, isWritable);
    }

    void onExtAudioInGainSettingPropertiesChanged(
            BluetoothDevice device, int id, int unit, int min, int max) {
        String logInfo =
                "onExtAudioInGainSettingPropertiesChanged("
                        + ("device=" + device)
                        + (", id=" + id)
                        + (", unit=" + unit)
                        + (", min=" + min)
                        + (", max=" + max)
                        + ")";

        VolumeControlInputDescriptor input = mAudioInputs.get(device);
        if (input == null) {
            Log.e(TAG, logInfo + ": This device has no audio input control");
            return;
        }

        Log.d(TAG, logInfo);
        input.onGainSettingsPropertiesChanged(id, unit, min, max);
    }

    synchronized void handleStackEvent(VolumeControlStackEvent stackEvent) {
        if (!isAvailable()) {
            Log.e(TAG, "Event ignored, service not available: " + stackEvent);
            return;
        }
        Log.d(TAG, "handleStackEvent: " + stackEvent);

        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED) {
            handleVolumeControlChanged(
                    stackEvent.device,
                    stackEvent.valueInt1,
                    stackEvent.valueInt2,
                    stackEvent.valueInt3,
                    stackEvent.valueBool1,
                    stackEvent.valueBool2);
            return;
        }

        requireNonNull(stackEvent.device);

        BluetoothDevice device = stackEvent.device;
        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE) {
            handleDeviceAvailable(
                    device, stackEvent.valueInt1, stackEvent.valueInt2, stackEvent.valueInt3);
            return;
        }

        if (stackEvent.type
                == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED) {
            handleDeviceExtAudioOffsetChanged(device, stackEvent.valueInt1, stackEvent.valueInt2);
            return;
        }

        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED) {
            handleDeviceExtAudioLocationChanged(device, stackEvent.valueInt1, stackEvent.valueInt2);
            return;
        }

        if (stackEvent.type
                == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED) {
            handleDeviceExtAudioDescriptionChanged(
                    device, stackEvent.valueInt1, stackEvent.valueString1);
            return;
        }

        Log.e(TAG, "Unhandled event: " + stackEvent);
    }

    void messageFromNative(VolumeControlStackEvent stackEvent) {
        Log.d(TAG, "messageFromNative: " + stackEvent);

        // Group events should be handled here directly
        boolean isGroupEvent = (stackEvent.device == null);
        if (isGroupEvent) {
            handleStackEvent(stackEvent);
            return;
        }

        // Other device events should be serialized via their state machines so they are processed
        // in the same order they were sent from the native code.
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(stackEvent.device);
            if (sm == null
                    && stackEvent.type
                            == VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                sm =
                        switch (stackEvent.valueInt1) {
                            case STATE_CONNECTED, STATE_CONNECTING ->
                                    getOrCreateStateMachine(stackEvent.device);
                            default -> null;
                        };
            }
            if (sm != null) {
                sm.sendMessage(VolumeControlStateMachine.MESSAGE_STACK_EVENT, stackEvent);
                return;
            }
        }
        Log.w(TAG, "Cannot forward stack event: no state machine: " + stackEvent);
        handleStackEvent(stackEvent);
    }

    private VolumeControlStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }

            Log.d(TAG, "Creating a new state machine for " + device);
            sm =
                    new VolumeControlStateMachine(
                            this, device, mNativeInterface, mStateMachinesLooper);
            sm.start();
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /**
     * Notify devices with volume level
     *
     * <p>In case of handleVolumeControlChanged, volume level is known from native layer caller.
     * Notify the clients with the volume level directly and update the volume cache. In case of
     * newly registered callback, volume level is unknown from caller, notify the clients with
     * cached volume level from either device or group.
     *
     * @param callbacks list of callbacks
     * @param devices list of devices to notify volume changed
     * @param volume volume level
     */
    private void notifyDevicesVolumeChanged(
            RemoteCallbackList<IBluetoothVolumeControlCallback> callbacks,
            List<BluetoothDevice> devices,
            Optional<Integer> volume) {
        if (callbacks == null) {
            Log.e(TAG, "callbacks is null");
            return;
        }

        for (BluetoothDevice dev : devices) {
            int broadcastVolume = volume.orElseGet(() -> getDeviceVolume(dev));
            int n = callbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onDeviceVolumeChanged(dev, broadcastVolume);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            callbacks.finishBroadcast();
        }
    }

    private void removeDeviceData(BluetoothDevice device) {
        Log.d(TAG, "Remove data for device: " + device);

        int groupId = getGroupId(device);

        mAudioOffsets.remove(device);
        mAudioInputs.remove(device);
        mDeviceVolumeCache.remove(device);
        mDeviceMuteCache.remove(device);
        mGroupIds.remove(device);

        if (!getGroupDevices(groupId).isEmpty()) {
            // Return if group is not empty
            return;
        }

        Log.d(TAG, "Remove group data for id: " + groupId);
        mGroupVolumeCache.remove(groupId);
        mGroupMuteCache.remove(groupId);
    }

    @Override
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /** Remove state machine if the bonding for a device is removed */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        // Remove state machine if the bonding for a device is removed
        if (bondState != BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnecting device because it was unbonded.");
                disconnect(device);
                return;
            }
            removeStateMachine(device);
            removeDeviceData(device);
        }
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
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

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

    @VisibleForTesting
    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (device == null || fromState == toState) {
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

        if (!isAvailable()) { // Safe outside synchronized as this is posted on main Looper
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED
                && getAdapterService().getBondState(device) == BOND_NONE) {
            synchronized (this) { // TODO: Why do we need synchronized here ?
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
                removeDeviceData(device);
            }
        }

        getAdapterService()
                .handleProfileConnectionStateChange(getProfileId(), device, fromState, toState);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "StreamType: " + getCurrentStreamType());
        ProfileService.println(
                sb,
                "Group volumes {GroupId={VolumeControlStreamType=Volume}}: " + mGroupVolumeCache);
        ProfileService.println(
                sb,
                "Group mute states {GroupId={VolumeControlStreamType=Mute}}: " + mGroupMuteCache);
        ProfileService.println(
                sb,
                "Device volumes {Device={VolumeControlStreamType=Volume}}: " + mDeviceVolumeCache);
        ProfileService.println(
                sb,
                "Device mute states {Device={VolumeControlStreamType=Mute}}: " + mDeviceMuteCache);

        for (VolumeControlStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }

        for (Map.Entry<BluetoothDevice, VolumeControlOffsetDescriptor> entry :
                mAudioOffsets.entrySet()) {
            VolumeControlOffsetDescriptor descriptor = entry.getValue();
            BluetoothDevice device = entry.getKey();
            ProfileService.println(sb, "    Device: " + device);
            ProfileService.println(sb, "    Volume offset cnt: " + descriptor.size());
            descriptor.dump(sb);
        }

        for (Map.Entry<BluetoothDevice, VolumeControlInputDescriptor> entry :
                mAudioInputs.entrySet()) {
            VolumeControlInputDescriptor descriptor = entry.getValue();
            BluetoothDevice device = entry.getKey();
            ProfileService.println(sb, "    Device: " + device);
            ProfileService.println(sb, "    Volume input cnt: " + descriptor.size());
            descriptor.dump(sb);
        }
    }
}
