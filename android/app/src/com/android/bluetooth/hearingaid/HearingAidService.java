/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.hearingaid;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothHearingAid.AdvertisementServiceData;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth HearingAid profile, as a service in the Bluetooth application. */
public class HearingAidService extends ConnectableProfile {
    private static final String TAG = HearingAidService.class.getSimpleName();

    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    // Upper limit of all HearingAid devices: Bonded or Connected
    private static final int MAX_HEARING_AID_STATE_MACHINES = 10;

    private final ActiveDeviceManager mActiveDeviceManager;
    private final HearingAidNativeInterface mNativeInterface;
    private final AudioManager mAudioManager;
    private final HandlerThread mStateMachinesThread;
    private final Looper mStateMachinesLooper;
    private final Handler mHandler;

    private final Map<BluetoothDevice, HearingAidStateMachine> mStateMachines = new HashMap<>();
    private final Map<BluetoothDevice, Long> mDeviceHiSyncIdMap = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceCapabilitiesMap = new HashMap<>();
    private final Map<Long, Boolean> mHiSyncIdConnectedMap = new HashMap<>();
    private final AudioManagerOnAudioDevicesAddedCallback mAudioManagerOnAudioDevicesAddedCallback =
            new AudioManagerOnAudioDevicesAddedCallback();
    private final AudioManagerOnAudioDevicesRemovedCallback
            mAudioManagerOnAudioDevicesRemovedCallback =
                    new AudioManagerOnAudioDevicesRemovedCallback();

    private BluetoothDevice mActiveDevice;
    private long mActiveDeviceHiSyncId = BluetoothHearingAid.HI_SYNC_ID_INVALID;

    public HearingAidService(
            AdapterService adapterService, ActiveDeviceManager activeDeviceManager) {
        this(adapterService, null, activeDeviceManager, null);
    }

    @VisibleForTesting
    HearingAidService(
            AdapterService adapterService,
            HearingAidNativeInterface nativeInterface,
            ActiveDeviceManager activeDeviceManager,
            Looper looper) {
        super(BluetoothProfile.HEARING_AID, adapterService);
        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesThread = new HandlerThread("HearingAidService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
        }
        var nativeCallback = new HearingAidNativeCallback(getAdapterService(), this);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new HearingAidNativeInterface(nativeCallback));
        mActiveDeviceManager = activeDeviceManager;
        mAudioManager = requireNonNull(obtainSystemService(AudioManager.class));

        mNativeInterface.init();
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileAshaCentralEnabled().orElse(true);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new HearingAidServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        // Cleanup native interface
        mNativeInterface.cleanup();

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (HearingAidStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
            }
            mStateMachines.clear();
        }

        // Clear HiSyncId map, capabilities map and HiSyncId Connected map
        mDeviceHiSyncIdMap.clear();
        mDeviceCapabilitiesMap.clear();
        mHiSyncIdConnectedMap.clear();

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }

        mHandler.removeCallbacksAndMessages(null);

        if (!Flags.admCentralizeActiveDeviceHandling()) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioManagerOnAudioDevicesAddedCallback);
            mAudioManager.unregisterAudioDeviceCallback(mAudioManagerOnAudioDevicesRemovedCallback);
        }
    }

    /**
     * Connects the hearing aid profile to the passed in device
     *
     * @param device is the device with which we will connect the hearing aid profile
     * @return true if hearing aid profile successfully connected, false otherwise
     */
    @Override
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        requireNonNull(device);

        if (!okToConnect(device)) {
            return false;
        }

        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
        if (!Util.arrayContains(featureUuids, BluetoothUuid.HEARING_AID)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have Hearing Aid UUID");
            return false;
        }

        long hiSyncId =
                mDeviceHiSyncIdMap.getOrDefault(device, BluetoothHearingAid.HI_SYNC_ID_INVALID);

        if (hiSyncId != mActiveDeviceHiSyncId
                && hiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID
                && mActiveDeviceHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
            for (BluetoothDevice connectedDevice : getConnectedDevices()) {
                disconnect(connectedDevice);
            }
        }

        synchronized (mStateMachines) {
            HearingAidStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
            }
            smConnect.sendMessage(HearingAidStateMachine.MESSAGE_CONNECT);
        }

        for (BluetoothDevice storedDevice : mDeviceHiSyncIdMap.keySet()) {
            if (device.equals(storedDevice)) {
                continue;
            }
            if (mDeviceHiSyncIdMap.getOrDefault(
                            storedDevice, BluetoothHearingAid.HI_SYNC_ID_INVALID)
                    == hiSyncId) {
                synchronized (mStateMachines) {
                    HearingAidStateMachine sm = getOrCreateStateMachine(storedDevice);
                    if (sm == null) {
                        Log.e(TAG, "Ignored connect request for " + device + " : no state machine");
                        continue;
                    }
                    sm.sendMessage(HearingAidStateMachine.MESSAGE_CONNECT);
                }
                if (hiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID
                        && !device.equals(storedDevice)) {
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Disconnects hearing aid profile for the passed in device
     *
     * @param device is the device with which we want to disconnected the hearing aid profile
     * @return true if hearing aid profile successfully disconnected, false otherwise
     */
    @Override
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        long hiSyncId =
                mDeviceHiSyncIdMap.getOrDefault(device, BluetoothHearingAid.HI_SYNC_ID_INVALID);

        for (BluetoothDevice storedDevice : mDeviceHiSyncIdMap.keySet()) {
            if (mDeviceHiSyncIdMap.getOrDefault(
                            storedDevice, BluetoothHearingAid.HI_SYNC_ID_INVALID)
                    == hiSyncId) {
                synchronized (mStateMachines) {
                    HearingAidStateMachine sm = mStateMachines.get(storedDevice);
                    if (sm == null) {
                        Log.e(
                                TAG,
                                "Ignored disconnect request for " + device + " : no state machine");
                        continue;
                    }
                    sm.sendMessage(HearingAidStateMachine.MESSAGE_DISCONNECT);
                }
                if (hiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID
                        && !device.equals(storedDevice)) {
                    break;
                }
            }
        }
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (HearingAidStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check any peer device is connected. The check considers any peer device is connected.
     *
     * @param device the peer device to connect to
     * @return true if there are any peer device connected.
     */
    public boolean isConnectedPeerDevices(BluetoothDevice device) {
        long hiSyncId = getHiSyncId(device);
        if (getConnectedPeerDevices(hiSyncId).isEmpty()) {
            return false;
        }
        return true;
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
                if (!Util.arrayContains(featureUuids, BluetoothUuid.HEARING_AID)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                HearingAidStateMachine sm = mStateMachines.get(device);
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
            for (HearingAidStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    /**
     * Get the HiSyncIdMap for testing
     *
     * @return mDeviceHiSyncIdMap
     */
    @VisibleForTesting
    Map<BluetoothDevice, Long> getHiSyncIdMap() {
        return mDeviceHiSyncIdMap;
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
        synchronized (mStateMachines) {
            HearingAidStateMachine sm = mStateMachines.get(device);
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
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
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

    void setVolume(int volume) {
        mNativeInterface.setVolume(volume);
    }

    public long getHiSyncId(BluetoothDevice device) {
        if (device == null) {
            return BluetoothHearingAid.HI_SYNC_ID_INVALID;
        }
        return mDeviceHiSyncIdMap.getOrDefault(device, BluetoothHearingAid.HI_SYNC_ID_INVALID);
    }

    int getCapabilities(BluetoothDevice device) {
        return mDeviceCapabilitiesMap.getOrDefault(device, -1);
    }

    AdvertisementServiceData getAdvertisementServiceData(BluetoothDevice device) {
        int capability = getAdapterService().getAshaCapability(device);
        int id = getAdapterService().getAshaTruncatedHiSyncId(device);
        if (capability < 0) {
            Log.i(TAG, "device does not have AdvertisementServiceData");
            return null;
        }
        return new AdvertisementServiceData(capability, id);
    }

    /**
     * Remove the active device.
     *
     * @param stopAudio whether to stop current media playback.
     * @return true on success, otherwise false
     */
    public boolean removeActiveDevice(boolean stopAudio) {
        Log.d(TAG, "removeActiveDevice: stopAudio=" + stopAudio);
        synchronized (mStateMachines) {
            if (mActiveDeviceHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                reportActiveDevice(null, stopAudio);
                mActiveDeviceHiSyncId = BluetoothHearingAid.HI_SYNC_ID_INVALID;
            }
        }
        return true;
    }

    /**
     * Set the active device.
     *
     * @param device the new active device. Should not be null.
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "setActiveDevice: device should not be null!");
            return removeActiveDevice(true);
        }
        Log.d(TAG, "setActiveDevice: " + device);
        synchronized (mStateMachines) {
            /* No action needed since this is the same device as previously activated */
            if (device.equals(mActiveDevice)) {
                Log.d(TAG, "setActiveDevice: The device is already active. Ignoring.");
                return true;
            }

            if (getConnectionState(device) != STATE_CONNECTED) {
                Log.e(TAG, "setActiveDevice(" + device + "): failed because device not connected");
                return false;
            }
            Long deviceHiSyncId =
                    mDeviceHiSyncIdMap.getOrDefault(device, BluetoothHearingAid.HI_SYNC_ID_INVALID);
            if (deviceHiSyncId != mActiveDeviceHiSyncId) {
                mActiveDeviceHiSyncId = deviceHiSyncId;
                reportActiveDevice(device, false);
            }
        }
        return true;
    }

    /**
     * Get the connected physical Hearing Aid devices that are active
     *
     * @return the list of active devices. The first element is the left active device; the second
     *     element is the right active device. If either or both side is not active, it will be null
     *     on that position
     */
    public List<BluetoothDevice> getActiveDevices() {
        ArrayList<BluetoothDevice> activeDevices = new ArrayList<>();
        activeDevices.add(null);
        activeDevices.add(null);

        synchronized (mStateMachines) {
            long activeDeviceHiSyncId = mActiveDeviceHiSyncId;
            if (activeDeviceHiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                return activeDevices;
            }

            mDeviceHiSyncIdMap.entrySet().stream()
                    .filter(entry -> activeDeviceHiSyncId == entry.getValue())
                    .map(Map.Entry::getKey)
                    .filter(device -> getConnectionState(device) == STATE_CONNECTED)
                    .forEach(
                            device -> {
                                int deviceSide = getCapabilities(device) & 1;
                                if (deviceSide == BluetoothHearingAid.SIDE_RIGHT) {
                                    activeDevices.set(1, device);
                                } else {
                                    activeDevices.set(0, device);
                                }
                            });
        }

        return activeDevices;
    }

    /**
     * Get the current active device
     *
     * @return the current active device
     */
    public BluetoothDevice getActiveDevice() {
        return mActiveDevice;
    }

    void onConnectionStateChangedFromNative(BluetoothDevice device, int state) {
        synchronized (mStateMachines) {
            var stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                stateMachine =
                        switch (state) {
                            case STATE_CONNECTED, STATE_CONNECTING ->
                                    getOrCreateStateMachine(device);
                            default -> null;
                        };
                if (stateMachine == null) {
                    Log.e(TAG, "onConnectionStateChanged(): No state machine for " + device);
                    return;
                }
            }
            stateMachine.sendMessage(
                    HearingAidStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state);
        }
    }

    void onDeviceAvailableFromNative(BluetoothDevice device, int capabilities, long hiSyncId) {
        Log.d(
                TAG,
                ("Device available: device=" + device)
                        + (" capabilities=" + capabilities)
                        + (" hiSyncId=" + hiSyncId));
        mDeviceCapabilitiesMap.put(device, capabilities);
        mDeviceHiSyncIdMap.put(device, hiSyncId);
    }

    private void notifyActiveDeviceChanged() {
        getAdapterService().handleActiveDeviceChange(getProfileId(), mActiveDevice);
        Intent intent = new Intent(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mActiveDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT);
    }

    /* Notifications of audio device disconnection events. */
    private class AudioManagerOnAudioDevicesRemovedCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (Flags.admCentralizeActiveDeviceHandling()) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            for (AudioDeviceInfo deviceInfo : removedDevices) {
                if (deviceInfo.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                    Log.d(TAG, " onAudioDevicesRemoved: device type: " + deviceInfo.getType());
                    if (mAudioManager != null) {
                        notifyActiveDeviceChanged();
                        mAudioManager.unregisterAudioDeviceCallback(this);
                    } else {
                        Log.w(TAG, "onAudioDevicesRemoved: mAudioManager is null");
                    }
                }
            }
        }
    }

    /* Notifications of audio device connection events. */
    private class AudioManagerOnAudioDevicesAddedCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (Flags.admCentralizeActiveDeviceHandling()) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            for (AudioDeviceInfo deviceInfo : addedDevices) {
                if (deviceInfo.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                    Log.d(TAG, " onAudioDevicesAdded: device type: " + deviceInfo.getType());
                    if (mAudioManager != null) {
                        notifyActiveDeviceChanged();
                        mAudioManager.unregisterAudioDeviceCallback(this);
                    } else {
                        Log.w(TAG, "onAudioDevicesAdded: mAudioManager is null");
                    }
                }
            }
        }
    }

    /**
     * Handle when AudioManager add audio device.
     *
     * @return true if the success, otherwise false
     */
    public boolean handleAudioDeviceAdded() {
        if (!Flags.admCentralizeActiveDeviceHandling()) {
            return false;
        }
        if (mAudioManager == null) {
            Log.w(TAG, "onAudioDevicesAdded: mAudioManager is null");
            return false;
        }
        notifyActiveDeviceChanged();
        return true;
    }

    /**
     * Handle when AudioManager remove audio device.
     *
     * @return true if the success, otherwise false
     */
    public boolean handleAudioDeviceRemoved() {
        if (!Flags.admCentralizeActiveDeviceHandling()) {
            return false;
        }
        if (mAudioManager == null) {
            Log.w(TAG, "onAudioDevicesRemoved: mAudioManager is null");
            return false;
        }
        notifyActiveDeviceChanged();
        return true;
    }

    private HearingAidStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            HearingAidStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }
            // Limit the maximum number of state machines to avoid DoS attack
            if (mStateMachines.size() >= MAX_HEARING_AID_STATE_MACHINES) {
                Log.e(
                        TAG,
                        "Maximum number of HearingAid state machines reached: "
                                + MAX_HEARING_AID_STATE_MACHINES);
                return null;
            }
            Log.d(TAG, "Creating a new state machine for " + device);
            sm = new HearingAidStateMachine(this, device, mNativeInterface, mStateMachinesLooper);
            sm.start();
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /**
     * Report the active device change to the active device manager and the media framework.
     *
     * @param device the new active device; or null if no active device
     * @param stopAudio whether to stop audio when device is null.
     */
    private void reportActiveDevice(BluetoothDevice device, boolean stopAudio) {
        Log.d(TAG, "reportActiveDevice: device=" + device + " stopAudio=" + stopAudio);

        if (device != null && stopAudio) {
            Log.e(TAG, "Illegal arguments: stopAudio should be false when device is not null!");
            stopAudio = false;
        }

        // Note: This is just a safety check for handling illegal call - setActiveDevice(null).
        if (device == null && stopAudio && getConnectionState(mActiveDevice) == STATE_CONNECTED) {
            Log.e(
                    TAG,
                    "Illegal arguments: stopAudio should be false when the active hearing aid "
                            + "is still connected!");
            stopAudio = false;
        }

        BluetoothDevice previousAudioDevice = mActiveDevice;

        mActiveDevice = device;

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_ACTIVE_DEVICE_CHANGED,
                getProfileId(),
                getAdapterService().obfuscateAddress(device),
                getAdapterService().getMetricId(device));

        Log.d(
                TAG,
                "Hearing Aid audio: "
                        + previousAudioDevice
                        + " -> "
                        + device
                        + ". Stop audio: "
                        + stopAudio);

        if (!Flags.admCentralizeActiveDeviceHandling()) {
            if (device != null) {
                mAudioManager.registerAudioDeviceCallback(
                        mAudioManagerOnAudioDevicesAddedCallback, mHandler);
            } else {
                mAudioManager.registerAudioDeviceCallback(
                        mAudioManagerOnAudioDevicesRemovedCallback, mHandler);
            }
        }
        mAudioManager.handleBluetoothActiveDeviceChanged(
                device,
                previousAudioDevice,
                BluetoothProfileConnectionInfo.createHearingAidInfo(!stopAudio));
    }

    @Override
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
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }
        mDeviceHiSyncIdMap.remove(device);
        synchronized (mStateMachines) {
            HearingAidStateMachine sm = mStateMachines.get(device);
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
            HearingAidStateMachine sm = mStateMachines.get(device);
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

    public List<BluetoothDevice> getConnectedPeerDevices(long hiSyncId) {
        List<BluetoothDevice> result = new ArrayList<>();
        for (BluetoothDevice peerDevice : getConnectedDevices()) {
            if (getHiSyncId(peerDevice) == hiSyncId) {
                result.add(peerDevice);
            }
        }
        return result;
    }

    synchronized void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
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
        if (toState == STATE_CONNECTED) {
            long myHiSyncId = getHiSyncId(device);
            if (!mHiSyncIdConnectedMap.getOrDefault(myHiSyncId, false)) {
                mHiSyncIdConnectedMap.put(myHiSyncId, true);
            }
        }
        if (fromState == STATE_CONNECTED && getConnectedDevices().isEmpty()) {
            long myHiSyncId = getHiSyncId(device);
            mHiSyncIdConnectedMap.put(myHiSyncId, false);
            // ActiveDeviceManager will call removeActiveDevice().
        }
        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            int bondState = getAdapterService().getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
            }
        }
        getAdapterService()
                .notifyProfileConnectionStateChangeToScan(getProfileId(), fromState, toState);
        getAdapterService()
                .handleProfileConnectionStateChange(getProfileId(), device, fromState, toState);
        mActiveDeviceManager.profileConnectionStateChanged(
                getProfileId(), device, fromState, toState);
        getAdapterService()
                .updateProfileConnectionAdapterProperties(
                        device, getProfileId(), toState, fromState);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (HearingAidStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }
    }
}
