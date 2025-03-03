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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothCsipSetCoordinator.CSIS_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

import static com.android.bluetooth.flags.Flags.vcpDeviceVolumeApiImprovements;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.RequiresPermission;
import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IAudioInputCallback;
import android.bluetooth.IBluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.AttributionSource;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.SneakyThrow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class VolumeControlService extends ProfileService {
    private static final String TAG = VolumeControlService.class.getSimpleName();

    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;
    private static final int LE_AUDIO_MAX_VOL = 255;
    /* As defined by Volume Control Service 1.0.1, 3.3.1. Volume Flags behavior.
     * User Set Volume Setting means that remote keeps volume in its cache. */
    @VisibleForTesting static final int VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK = 0x01;

    private static final int GROUP_ID_INVALID = -1;

    private static VolumeControlService sVolumeControlService;

    @VisibleForTesting
    @GuardedBy("mCallbacks")
    final RemoteCallbackList<IBluetoothVolumeControlCallback> mCallbacks =
            new RemoteCallbackList<>();

    private final AdapterService mAdapterService;
    private final AudioManager mAudioManager;
    private final DatabaseManager mDatabaseManager;
    private final Handler mHandler;
    private final HandlerThread mStateMachinesThread;
    private final Looper mStateMachinesLooper;
    private final VolumeControlNativeInterface mNativeInterface;

    private final Map<BluetoothDevice, VolumeControlStateMachine> mStateMachines = new HashMap<>();
    private final Map<BluetoothDevice, VolumeControlOffsetDescriptor> mAudioOffsets =
            new HashMap<>();
    private final Map<BluetoothDevice, VolumeControlInputDescriptor> mAudioInputs =
            new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mGroupVolumeCache = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> mGroupMuteCache = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceVolumeCache = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Boolean> mDeviceMuteCache = new ConcurrentHashMap<>();

    private Boolean mIgnoreSetVolumeFromAF = false;

    @VisibleForTesting ServiceFactory mFactory = new ServiceFactory();

    public VolumeControlService(AdapterService adapterService) {
        this(adapterService, null, null);
    }

    @VisibleForTesting
    VolumeControlService(
            AdapterService adapterService,
            Looper looper,
            VolumeControlNativeInterface nativeInterface) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new VolumeControlNativeInterface(
                                        new VolumeControlNativeCallback(adapterService, this)));
        mAudioManager = requireNonNull(getSystemService(AudioManager.class));
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
        setVolumeControlService(this);
        mNativeInterface.init();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileVcpControllerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothVolumeControlBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup VolumeControl Service");

        // Mark service as stopped
        setVolumeControlService(null);

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
        mGroupVolumeCache.clear();
        mGroupMuteCache.clear();
        mDeviceVolumeCache.clear();
        mDeviceMuteCache.clear();

        synchronized (mCallbacks) {
            mCallbacks.kill();
        }
    }

    /**
     * Get the VolumeControlService instance
     *
     * @return VolumeControlService instance
     */
    public static synchronized VolumeControlService getVolumeControlService() {
        if (sVolumeControlService == null) {
            Log.w(TAG, "getVolumeControlService(): service is NULL");
            return null;
        }

        if (!sVolumeControlService.isAvailable()) {
            Log.w(TAG, "getVolumeControlService(): service is not available");
            return null;
        }
        return sVolumeControlService;
    }

    @VisibleForTesting
    static synchronized void setVolumeControlService(VolumeControlService instance) {
        Log.d(TAG, "setVolumeControlService(): set to: " + instance);
        sVolumeControlService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            return false;
        }

        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }
        final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
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

        CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();
        if (csipClient != null) {
            int groupId = csipClient.getGroupId(device, BluetoothUuid.CAP);
            if (groupId != CSIS_GROUP_ID_INVALID) {
                return groupId;
            }
        } else {
            Log.w(TAG, "CSIP not available");
        }

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService != null) {
            int groupId = leAudioService.getGroupId(device);
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                return groupId;
            }
        } else {
            Log.w(TAG, "leAudioService not available");
        }

        return GROUP_ID_INVALID;
    }

    private List<BluetoothDevice> getGroupDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();

        if (groupId == GROUP_ID_INVALID) {
            return result;
        }

        CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();
        if (csipClient != null) {
            result = csipClient.getGroupDevicesOrdered(groupId);
            if (!result.isEmpty()) {
                return result;
            }
        } else {
            Log.w(TAG, "CSIP not available");
        }

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService != null) {
            result = leAudioService.getGroupDevices(groupId);
            if (!result.isEmpty()) {
                return result;
            }
        } else {
            Log.w(TAG, "leAudioService not available");
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

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device) {
        /* Make sure device is valid */
        if (device == null) {
            Log.e(TAG, "okToConnect: Invalid device");
            return false;
        }
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
        if (bondState != BOND_BONDED) {
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
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
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
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
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

    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
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
                device, BluetoothProfile.VOLUME_CONTROL, connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.VOLUME_CONTROL);
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

    @VisibleForTesting
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
            mDeviceVolumeCache.put(device, volume);
            mNativeInterface.setVolume(device, volume);

            if (vcpDeviceVolumeApiImprovements()) {
                // We only receive the volume change and mute state needs to be acquired manually
                Boolean isStreamMute =
                        mAudioManager.isStreamMute(getBluetoothContextualVolumeStream());
                adjustDeviceMute(device, volume, isStreamMute);
            }
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
            mIgnoreSetVolumeFromAF = false;
            return;
        }

        if (volume < 0) {
            Log.w(TAG, "Tried to set invalid volume " + volume + ". Ignored.");
            return;
        }

        synchronized (mDeviceVolumeCache) {
            mGroupVolumeCache.put(groupId, volume);
            if (vcpDeviceVolumeApiImprovements()) {
                for (BluetoothDevice dev : getGroupDevices(groupId)) {
                    mDeviceVolumeCache.put(dev, volume);
                }
            }
        }
        mNativeInterface.setGroupVolume(groupId, volume);

        // We only receive the volume change and mute state needs to be acquired manually
        Boolean isGroupMute = getGroupMute(groupId);
        Boolean isStreamMute = mAudioManager.isStreamMute(getBluetoothContextualVolumeStream());

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
        } else if (vcpDeviceVolumeApiImprovements()) {
            for (BluetoothDevice device : getGroupDevices(groupId)) {
                adjustDeviceMute(device, volume, isStreamMute);
            }
        }
    }

    /**
     * Get group cached volume. If not cached, then try to read from any device from this group.
     *
     * @param groupId the group identifier
     * @return the cached volume
     */
    public int getGroupVolume(int groupId) {
        if (vcpDeviceVolumeApiImprovements()) {
            synchronized (mDeviceVolumeCache) {
                Integer volume = mGroupVolumeCache.get(groupId);
                if (volume != null) {
                    return volume;
                }
                Log.d(TAG, "No group volume available");
                for (BluetoothDevice device : getGroupDevices(groupId)) {
                    volume = mDeviceVolumeCache.get(device);
                    if (volume != null) {
                        Log.w(TAG, "Volume taken from device: " + device);
                        return volume;
                    }
                }
                return VOLUME_CONTROL_UNKNOWN_VOLUME;
            }
        } else {
            return mGroupVolumeCache.getOrDefault(groupId, VOLUME_CONTROL_UNKNOWN_VOLUME);
        }
    }

    /**
     * Get device cached volume. If not cached, then try to read from its group.
     *
     * @param device the device
     * @return the cached volume
     */
    public int getDeviceVolume(BluetoothDevice device) {
        if (vcpDeviceVolumeApiImprovements()) {
            synchronized (mDeviceVolumeCache) {
                Integer volume = mDeviceVolumeCache.get(device);
                if (volume != null) {
                    return volume;
                }
                return mGroupVolumeCache.getOrDefault(
                        getGroupId(device), VOLUME_CONTROL_UNKNOWN_VOLUME);
            }
        } else {
            return mDeviceVolumeCache.getOrDefault(device, VOLUME_CONTROL_UNKNOWN_VOLUME);
        }
    }

    /**
     * This should be called by LeAudioService when LE Audio group change it active state.
     *
     * @param groupId the group identifier
     * @param active indicator if group is active or not
     */
    public synchronized void setGroupActive(int groupId, boolean active) {
        Log.d(TAG, "setGroupActive: " + groupId + ", active: " + active);
        if (!active) {
            /* For now we don't need to handle group inactivation */
            return;
        }

        int groupVolume = getGroupVolume(groupId);
        Boolean groupMute = getGroupMute(groupId);

        if (groupVolume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
            /* Don't need to show volume when activating known device. */
            updateGroupCacheAndAudioSystem(groupId, groupVolume, groupMute, /* showInUI*/ false);
        }
    }

    /**
     * Get device cached mute status. If not cached, then try to read from its group.
     *
     * @param device the device
     * @return mute status
     */
    public Boolean getMute(BluetoothDevice device) {
        synchronized (mDeviceMuteCache) {
            Boolean isMute = mDeviceMuteCache.get(device);
            if (isMute != null) {
                return isMute;
            }
            return mGroupMuteCache.getOrDefault(getGroupId(device), false);
        }
    }

    /**
     * Get group cached mute status. If not cached, then try to read from any device from this
     * group.
     *
     * @param groupId the group identifier
     * @return mute status
     */
    public Boolean getGroupMute(int groupId) {
        if (vcpDeviceVolumeApiImprovements()) {
            synchronized (mDeviceMuteCache) {
                Boolean isMute = mGroupMuteCache.get(groupId);
                if (isMute != null) {
                    return isMute;
                }
                for (BluetoothDevice device : getGroupDevices(groupId)) {
                    isMute = mDeviceMuteCache.get(device);
                    if (isMute != null) {
                        return isMute;
                    }
                }
                return false;
            }
        } else {
            return mGroupMuteCache.getOrDefault(groupId, false);
        }
    }

    public void mute(BluetoothDevice device) {
        mDeviceMuteCache.put(device, true);
        mNativeInterface.mute(device);
    }

    public void muteGroup(int groupId) {
        synchronized (mDeviceMuteCache) {
            mGroupMuteCache.put(groupId, true);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceMuteCache.put(dev, true);
            }
        }
        mNativeInterface.muteGroup(groupId);
    }

    public void unmute(BluetoothDevice device) {
        mDeviceMuteCache.put(device, false);
        mNativeInterface.unmute(device);
    }

    public void unmuteGroup(int groupId) {
        synchronized (mDeviceMuteCache) {
            mGroupMuteCache.put(groupId, false);
            for (BluetoothDevice dev : getGroupDevices(groupId)) {
                mDeviceMuteCache.put(dev, false);
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
        if (vcpDeviceVolumeApiImprovements()) {
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
        } else {
            Integer groupVolume = getGroupVolume(groupId);
            if (groupVolume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
                Log.i(TAG, "Setting value:" + groupVolume + " to " + device);
                mNativeInterface.setVolume(device, groupVolume);
            }

            Boolean isGroupMuted = getGroupMute(groupId);
            Log.i(TAG, "Setting mute:" + isGroupMuted + " to " + device);
            if (isGroupMuted) {
                mNativeInterface.mute(device);
            } else {
                mNativeInterface.unmute(device);
            }
        }
    }

    void updateGroupCacheAndAudioSystem(int groupId, int volume, boolean mute, boolean showInUI) {
        Log.d(
                TAG,
                " updateGroupCacheAndAudioSystem: groupId: "
                        + groupId
                        + ", vol: "
                        + volume
                        + ", mute: "
                        + mute
                        + ", showInUI"
                        + showInUI);

        if (volume < 0) {
            Log.w(TAG, "Tried to update invalid volume " + volume + ". Ignored.");
            return;
        }

        synchronized (mDeviceVolumeCache) {
            mGroupVolumeCache.put(groupId, volume);
            mGroupMuteCache.put(groupId, mute);
            if (vcpDeviceVolumeApiImprovements()) {
                for (BluetoothDevice dev : getGroupDevices(groupId)) {
                    mDeviceVolumeCache.put(dev, volume);
                    mDeviceMuteCache.put(dev, mute);
                }
            }
        }

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService != null) {
            int currentlyActiveGroupId = leAudioService.getActiveGroupId();
            if (currentlyActiveGroupId == GROUP_ID_INVALID || groupId != currentlyActiveGroupId) {
                BassClientService bassClientService = mFactory.getBassClientService();
                if (bassClientService == null
                        || bassClientService.getSyncedBroadcastSinks().stream()
                                .map(dev -> getGroupId(dev))
                                .noneMatch(
                                        id -> id == groupId && leAudioService.isPrimaryGroup(id))) {
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

        int streamType = getBluetoothContextualVolumeStream();
        int flags = AudioManager.FLAG_BLUETOOTH_ABS_VOLUME;
        if (showInUI) {
            flags |= AudioManager.FLAG_SHOW_UI;
        }

        mAudioManager.setStreamVolume(streamType, getAudioDeviceVolume(streamType, volume), flags);

        if (mAudioManager.isStreamMute(streamType) != mute) {
            int adjustment = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            mAudioManager.adjustStreamVolume(streamType, adjustment, flags);
        }
    }

    int getBleVolumeFromCurrentStream() {
        int streamType = getBluetoothContextualVolumeStream();
        int streamVolume = mAudioManager.getStreamVolume(streamType);
        int streamMaxVolume = mAudioManager.getStreamMaxVolume(streamType);

        /* leaudio expect volume value in range 0 to 255 */
        return (int) Math.round((double) streamVolume * LE_AUDIO_MAX_VOL / streamMaxVolume);
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
                if (vcpDeviceVolumeApiImprovements()) {
                    // Ignore volume from AF because persisted volume was used
                    mIgnoreSetVolumeFromAF = true;
                }
                updateGroupCacheAndAudioSystem(groupId, volume, mute, false);
                return;
            }

            // Reset flag is used
            if (vcpDeviceVolumeApiImprovements()) {
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
                        mIgnoreSetVolumeFromAF = true;
                    }
                }
            } else {
                int groupVolume = getGroupVolume(groupId);
                if (groupVolume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
                    Log.i(
                            TAG,
                            "Setting group volume: " + groupVolume + " to the device: " + device);
                    setGroupVolume(groupId, groupVolume);
                } else {
                    int systemVolume = getBleVolumeFromCurrentStream();
                    Log.i(
                            TAG,
                            "Setting system volume: " + systemVolume + " to the group: " + groupId);
                    setGroupVolume(groupId, systemVolume);
                }
            }

            return;
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

        if (!vcpDeviceVolumeApiImprovements() && !isAutonomous) {
            /* If the change is triggered by Android device, the stream is already changed.
             * However it might be called with isAutonomous, one the first read of after
             * reconnection. Make sure device has group volume. Also it might happen that
             * remote side send us wrong value - lets check it.
             */

            int groupVolume = getGroupVolume(groupId);
            Boolean groupMute = getGroupMute(groupId);

            if ((groupVolume == volume) && (groupMute == mute)) {
                Log.i(TAG, " Volume:" + volume + ", mute:" + mute + " confirmed by remote side.");
                return;
            }

            if (device != null) {
                // Correct the volume level only if device was already reported as connected.
                boolean can_change_volume = false;
                synchronized (mStateMachines) {
                    VolumeControlStateMachine sm = mStateMachines.get(device);
                    if (sm != null) {
                        can_change_volume = sm.isConnected();
                    }
                }

                if (can_change_volume && (groupVolume != volume)) {
                    Log.i(TAG, "Setting value:" + groupVolume + " to " + device);
                    mNativeInterface.setVolume(device, groupVolume);
                }
                if (can_change_volume && (groupMute != mute)) {
                    Log.i(TAG, "Setting mute:" + groupMute + " to " + device);
                    if (groupMute) {
                        mNativeInterface.mute(device);
                    } else {
                        mNativeInterface.unmute(device);
                    }
                }
            } else {
                Log.e(
                        TAG,
                        "Volume changed did not succeed. Volume: "
                                + volume
                                + " expected volume: "
                                + groupVolume);
            }
        }

        if (isAutonomous && device == null) {
            /* Received group notification for autonomous change. Update cache and audio system. */
            updateGroupCacheAndAudioSystem(groupId, volume, mute, true);
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

        if (volume == VOLUME_CONTROL_UNKNOWN_VOLUME) return -1;
        return getAudioDeviceVolume(getBluetoothContextualVolumeStream(), volume);
    }

    int getAudioDeviceVolume(int streamType, int bleVolume) {
        int deviceMaxVolume = mAudioManager.getStreamMaxVolume(streamType);

        // TODO: Investigate what happens in classic BT when BT volume is changed to zero.
        double deviceVolume = (double) (bleVolume * deviceMaxVolume) / LE_AUDIO_MAX_VOL;
        return (int) Math.round(deviceVolume);
    }

    // Copied from AudioService.getBluetoothContextualVolumeStream() and modified it.
    int getBluetoothContextualVolumeStream() {
        int mode = mAudioManager.getMode();

        Log.d(TAG, "Volume mode:" + mode + "; Description: 0:normal, 1:ring, 2,3:call");

        return switch (mode) {
            case AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> {
                yield AudioManager.STREAM_VOICE_CALL;
            }
            case AudioManager.MODE_RINGTONE -> {
                Log.d(TAG, " Update during ringtone applied to voice call");
                yield AudioManager.STREAM_VOICE_CALL;
            }
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
         * Offset ids a countinous from 1 to number_of_ext_outputs*/
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
            BluetoothDevice device, int numberOfExternalOutputs, int numberOfExternaInputs) {
        handleExternalOutputs(device, numberOfExternalOutputs);
        handleExternalInputs(device, numberOfExternaInputs);
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
            handleDeviceAvailable(device, stackEvent.valueInt1, stackEvent.valueInt2);
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
            if (sm == null) {
                if (stackEvent.type
                        == VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                    switch (stackEvent.valueInt1) {
                        case STATE_CONNECTED, STATE_CONNECTING -> {
                            sm = getOrCreateStateMachine(stackEvent.device);
                        }
                    }
                }
            }
            if (sm == null) {
                Log.w(TAG, "Cannot forward stack event: no state machine: " + stackEvent);
                handleStackEvent(stackEvent);
                return;
            }
            sm.sendMessage(VolumeControlStateMachine.MESSAGE_STACK_EVENT, stackEvent);
        }
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
            int broadcastVolume = VOLUME_CONTROL_UNKNOWN_VOLUME;
            if (volume.isPresent()) {
                broadcastVolume = volume.get();
                if (!vcpDeviceVolumeApiImprovements()) {
                    mDeviceVolumeCache.put(dev, broadcastVolume);
                }
            } else {
                broadcastVolume = getDeviceVolume(dev);
                if (!vcpDeviceVolumeApiImprovements()
                        && broadcastVolume == VOLUME_CONTROL_UNKNOWN_VOLUME) {
                    broadcastVolume = getGroupVolume(getGroupId(dev));
                }
            }
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
            if (bondState == BOND_NONE) {
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
            }
        } else if (!vcpDeviceVolumeApiImprovements() && toState == STATE_CONNECTED) {
            // Restore the group volume if it was changed while the device was not yet connected.
            Integer groupId = getGroupId(device);
            if (groupId != GROUP_ID_INVALID) {
                Integer groupVolume = getGroupVolume(groupId);
                if (groupVolume != VOLUME_CONTROL_UNKNOWN_VOLUME) {
                    mNativeInterface.setVolume(device, groupVolume);
                }

                Boolean groupMute = getGroupMute(groupId);
                if (groupMute) {
                    mNativeInterface.mute(device);
                } else {
                    mNativeInterface.unmute(device);
                }
            }
        }
        mAdapterService.handleProfileConnectionStateChange(
                BluetoothProfile.VOLUME_CONTROL, device, fromState, toState);
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothVolumeControlBinder extends IBluetoothVolumeControl.Stub
            implements IProfileServiceBinder {
        @VisibleForTesting boolean mIsTesting = false;
        private VolumeControlService mService;

        BluetoothVolumeControlBinder(VolumeControlService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private VolumeControlService getService(AttributionSource source) {
            requireNonNull(source);

            // Cache mService because it can change while getService is called
            VolumeControlService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            return service;
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getConnectionPolicy(device);
        }

        @Override
        public boolean isVolumeOffsetAvailable(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.isVolumeOffsetAvailable(device);
        }

        @Override
        public int getNumberOfVolumeOffsetInstances(
                BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return 0;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getNumberOfVolumeOffsetInstances(device);
        }

        @Override
        public void setVolumeOffset(
                BluetoothDevice device,
                int instanceId,
                int volumeOffset,
                AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setVolumeOffset(device, instanceId, volumeOffset);
        }

        @Override
        public void setDeviceVolume(
                BluetoothDevice device, int volume, boolean isGroupOp, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setDeviceVolume(device, volume, isGroupOp);
        }

        @Override
        public void setGroupVolume(int groupId, int volume, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.setGroupVolume(groupId, volume);
        }

        @Override
        public int getGroupVolume(int groupId, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return 0;
            }

            return service.getGroupVolume(groupId);
        }

        @Override
        public void setGroupActive(int groupId, boolean active, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.setGroupActive(groupId, active);
        }

        @Override
        public void mute(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.mute(device);
        }

        @Override
        public void muteGroup(int groupId, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.muteGroup(groupId);
        }

        @Override
        public void unmute(BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.unmute(device);
        }

        @Override
        public void unmuteGroup(int groupId, AttributionSource source) {
            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.unmuteGroup(groupId);
        }

        private static void postAndWait(Handler handler, Runnable runnable) {
            FutureTask<Void> task = new FutureTask(Executors.callable(runnable));

            handler.post(task);
            try {
                task.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException e) {
                SneakyThrow.sneakyThrow(e);
            } catch (ExecutionException e) {
                SneakyThrow.sneakyThrow(e.getCause());
            }
        }

        @Override
        public void registerCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            requireNonNull(callback);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.registerCallback(callback));
        }

        @Override
        public void unregisterCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            requireNonNull(callback);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.unregisterCallback(callback));
        }

        @Override
        public void notifyNewRegisteredCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            requireNonNull(callback);

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.notifyNewRegisteredCallback(callback));
        }

        private static void validateBluetoothDevice(BluetoothDevice device) {
            requireNonNull(device);
            String address = device.getAddress();
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw new IllegalArgumentException("Invalid device address: " + address);
            }
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private <R> R aicsWrapper(
                AttributionSource source,
                BluetoothDevice device,
                Function<VolumeControlInputDescriptor, R> fn,
                R defaultValue) {
            validateBluetoothDevice(device);

            VolumeControlService service = getService(source);
            if (service == null) {
                return defaultValue;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            VolumeControlInputDescriptor inputs = service.mAudioInputs.get(device);
            if (inputs == null) {
                Log.w(TAG, "No audio inputs for " + device);
                return defaultValue;
            }

            return fn.apply(inputs);
        }

        @Override
        public int getNumberOfAudioInputControlServices(
                AttributionSource source, BluetoothDevice device) {
            validateBluetoothDevice(device);
            Log.d(TAG, "getNumberOfAudioInputControlServices(" + device + ")");
            return aicsWrapper(source, device, i -> i.size(), 0);
        }

        @Override
        public void registerAudioInputControlCallback(
                AttributionSource source,
                BluetoothDevice device,
                int instanceId,
                IAudioInputCallback callback) {
            requireNonNull(callback);
            Log.d(
                    TAG,
                    "registerAudioInputControlCallback("
                            + (device + ", " + instanceId + ", " + callback)
                            + ")");
            aicsWrapper(
                    source,
                    device,
                    i -> {
                        i.registerCallback(instanceId, callback);
                        return null;
                    },
                    null);
        }

        @Override
        public void unregisterAudioInputControlCallback(
                AttributionSource source,
                BluetoothDevice device,
                int instanceId,
                IAudioInputCallback callback) {
            requireNonNull(callback);
            Log.d(
                    TAG,
                    "unregisterAudioInputControlCallback("
                            + (device + ", " + instanceId + ", " + callback)
                            + ")");
            aicsWrapper(
                    source,
                    device,
                    i -> {
                        i.unregisterCallback(instanceId, callback);
                        return null;
                    },
                    null);
        }

        @Override
        public int getAudioInputGainSettingUnit(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputGainSettingUnit(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.getGainSettingUnit(instanceId), 0);
        }

        @Override
        public int getAudioInputGainSettingMin(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputGainSettingMin(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.getGainSettingMin(instanceId), 0);
        }

        @Override
        public int getAudioInputGainSettingMax(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputGainSettingMax(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.getGainSettingMax(instanceId), 0);
        }

        @Override
        public String getAudioInputDescription(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputDescription(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.getDescription(instanceId), "");
        }

        @Override
        public boolean isAudioInputDescriptionWritable(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "isAudioInputDescriptionWritable(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.isDescriptionWritable(instanceId), false);
        }

        @Override
        public boolean setAudioInputDescription(
                AttributionSource source,
                BluetoothDevice device,
                int instanceId,
                String description) {
            requireNonNull(description);
            Log.d(TAG, "setAudioInputDescription(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source, device, i -> i.setDescription(instanceId, description), false);
        }

        @Override
        public @AudioInputStatus int getAudioInputStatus(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputStatus(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source,
                    device,
                    i -> i.getStatus(instanceId),
                    (int) bluetooth.constants.aics.AudioInputStatus.INACTIVE);
        }

        @Override
        public @AudioInputType int getAudioInputType(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputType(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source,
                    device,
                    i -> i.getType(instanceId),
                    bluetooth.constants.AudioInputType.UNSPECIFIED);
        }

        @Override
        public int getAudioInputGainSetting(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputGainSetting(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.getGainSetting(instanceId), 0);
        }

        @Override
        public boolean setAudioInputGainSetting(
                AttributionSource source, BluetoothDevice device, int instanceId, int gainSetting) {
            Log.d(TAG, "setAudioInputGainSetting(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source, device, i -> i.setGainSetting(instanceId, gainSetting), false);
        }

        @Override
        public @GainMode int getAudioInputGainMode(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputGainMode(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source,
                    device,
                    i -> i.getGainMode(instanceId),
                    (int) bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY);
        }

        @Override
        public boolean setAudioInputGainMode(
                AttributionSource source,
                BluetoothDevice device,
                int instanceId,
                @GainMode int gainMode) {
            Log.d(TAG, "setAudioInputGainMode(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.setGainMode(instanceId, gainMode), false);
        }

        @Override
        public @Mute int getAudioInputMute(
                AttributionSource source, BluetoothDevice device, int instanceId) {
            Log.d(TAG, "getAudioInputMute(" + device + ", " + instanceId + ")");
            return aicsWrapper(
                    source,
                    device,
                    i -> i.getMute(instanceId),
                    (int) bluetooth.constants.aics.Mute.DISABLED);
        }

        @Override
        public boolean setAudioInputMute(
                AttributionSource source, BluetoothDevice device, int instanceId, @Mute int mute) {
            Log.d(TAG, "setAudioInputMute(" + device + ", " + instanceId + ")");
            return aicsWrapper(source, device, i -> i.setMute(instanceId, mute), false);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
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

        for (Map.Entry<Integer, Integer> entry : mGroupVolumeCache.entrySet()) {
            ProfileService.println(
                    sb,
                    "    GroupId: "
                            + entry.getKey()
                            + " volume: "
                            + entry.getValue()
                            + ", mute: "
                            + getGroupMute(entry.getKey()));
        }

        if (vcpDeviceVolumeApiImprovements()) {
            for (Map.Entry<BluetoothDevice, Integer> entry : mDeviceVolumeCache.entrySet()) {
                ProfileService.println(
                        sb,
                        "    Device: "
                                + entry.getKey()
                                + " volume: "
                                + entry.getValue()
                                + ", mute: "
                                + getMute(entry.getKey()));
            }
        }
    }
}
