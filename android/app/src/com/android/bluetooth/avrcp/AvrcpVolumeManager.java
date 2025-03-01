/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Handles volume changes from or to the remote device or system.
 *
 * <p>{@link AudioManager#setDeviceVolumeBehavior} is used to inform Media Framework of the current
 * active {@link BluetoothDevice} and its absolute volume support.
 *
 * <p>When absolute volume is supported, the volume should be synced between Media framework and the
 * remote device. Otherwise, only system volume is used.
 *
 * <p>AVRCP volume ranges from 0 to 127 which might not correspond to the system volume. As such,
 * volume sent to either Media Framework or remote device is converted accordingly.
 *
 * <p>Volume changes are stored as system volume in {@link SharedPreferences} and retrieved at
 * device connection.
 */
class AvrcpVolumeManager extends AudioDeviceCallback {
    private static final String TAG = AvrcpVolumeManager.class.getSimpleName();

    // All volumes are stored at system volume values, not AVRCP values
    private static final String VOLUME_MAP = "bluetooth_volume_map";
    private static final String VOLUME_CHANGE_LOG_TITLE = "BTAudio Volume Events";

    @VisibleForTesting static final int AVRCP_MAX_VOL = 127;
    private static final int STREAM_MUSIC = AudioManager.STREAM_MUSIC;
    private static final int VOLUME_CHANGE_LOGGER_SIZE = 30;
    private final int mDeviceMaxVolume;
    private final int mNewDeviceVolume;
    private final BluetoothEventLogger mVolumeEventLogger =
            new BluetoothEventLogger(VOLUME_CHANGE_LOGGER_SIZE, VOLUME_CHANGE_LOG_TITLE);

    AdapterService mAdapterService;
    AudioManager mAudioManager;
    AvrcpNativeInterface mNativeInterface;

    // Absolute volume support map.
    HashMap<BluetoothDevice, Boolean> mDeviceMap = new HashMap();

    // Volume stored is system volume (0 - {@code mDeviceMaxVolume}).
    HashMap<BluetoothDevice, Integer> mVolumeMap = new HashMap();

    BluetoothDevice mCurrentDevice = null;
    boolean mAbsoluteVolumeSupported = false;

    /**
     * Converts given {@code avrcpVolume} (0 - 127) to equivalent in system volume (0 - {@code
     * mDeviceMaxVolume}).
     *
     * <p>Max system volume is retrieved from {@link AudioManager}.
     */
    @VisibleForTesting
    int avrcpToSystemVolume(int avrcpVolume) {
        return (int) Math.round((double) avrcpVolume * mDeviceMaxVolume / AVRCP_MAX_VOL);
    }

    /**
     * Converts given {@code deviceVolume} (0 - {@code mDeviceMaxVolume}) to equivalent in AVRCP
     * volume (0 - 127).
     *
     * <p>Max system volume is retrieved from {@link AudioManager}.
     */
    @VisibleForTesting
    int systemToAvrcpVolume(int deviceVolume) {
        int avrcpVolume =
                (int) Math.round((double) deviceVolume * AVRCP_MAX_VOL / mDeviceMaxVolume);
        if (avrcpVolume > 127) avrcpVolume = 127;
        return avrcpVolume;
    }

    /**
     * Retrieves the {@link SharedPreferences} of the map device / volume.
     *
     * <p>The map is read to retrieve the last volume set for a bonded {@link BluetoothDevice}.
     *
     * <p>The map is written each time a volume update occurs from or to the remote device.
     */
    private SharedPreferences getVolumeMap() {
        return ((Context) mAdapterService).getSharedPreferences(VOLUME_MAP, Context.MODE_PRIVATE);
    }

    /**
     * Informs {@link AudioManager} that a new {@link BluetoothDevice} has been connected and is the
     * new desired audio output.
     *
     * <p>If AVRCP absolute volume is supported, this will also send the saved or new volume to the
     * remote device.
     *
     * <p>Absolute volume support is conditional to its presence in the {@code mDeviceMap}.
     */
    private void switchVolumeDevice(@NonNull BluetoothDevice device) {
        // Inform the audio manager that the device has changed
        d("switchVolumeDevice: Set Absolute volume support to " + mDeviceMap.get(device));
        final AudioDeviceAttributes deviceAttributes =
                new AudioDeviceAttributes(
                        AudioDeviceAttributes.ROLE_OUTPUT,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        device.getAddress());
        final int deviceVolumeBehavior =
                mDeviceMap.get(device)
                        ? AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE
                        : AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE;

        CompletableFuture.runAsync(
                        () ->
                                mAudioManager.setDeviceVolumeBehavior(
                                        deviceAttributes, deviceVolumeBehavior),
                        Utils.BackgroundExecutor)
                .exceptionally(
                        e -> {
                            Log.e(TAG, "switchVolumeDevice has thrown an Exception", e);
                            return null;
                        });

        // Get the current system volume and try to get the preference volume
        int savedVolume = getVolume(device, mNewDeviceVolume);

        d("switchVolumeDevice: savedVolume=" + savedVolume);

        // If absolute volume for the device is supported, set the volume for the device
        if (mDeviceMap.get(device)) {
            int avrcpVolume = systemToAvrcpVolume(savedVolume);
            mVolumeEventLogger.logd(
                    TAG, "switchVolumeDevice: Updating device volume: avrcpVolume=" + avrcpVolume);
            mNativeInterface.sendVolumeChanged(device, avrcpVolume);
        }
    }

    /**
     * Instantiates all class variables.
     *
     * <p>Fills {@code mVolumeMap} with content from {@link #getVolumeMap}, removing unbonded
     * devices if necessary.
     */
    AvrcpVolumeManager(
            AdapterService adapterService,
            AudioManager audioManager,
            AvrcpNativeInterface nativeInterface) {
        mAdapterService = adapterService;
        mAudioManager = audioManager;
        mNativeInterface = nativeInterface;
        mDeviceMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mNewDeviceVolume = mDeviceMaxVolume / 2;

        mAudioManager.registerAudioDeviceCallback(this, null);

        // Load the stored volume preferences into a hash map since shared preferences are slow
        // to poll and update. If the device has been unbonded since last start remove it from
        // the map.
        Map<String, ?> allKeys = getVolumeMap().getAll();
        SharedPreferences.Editor volumeMapEditor = getVolumeMap().edit();
        for (Map.Entry<String, ?> entry : allKeys.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            BluetoothDevice d = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(key);

            if (value instanceof Integer
                    && mAdapterService.getBondState(d) == BluetoothDevice.BOND_BONDED) {
                mVolumeMap.put(d, (Integer) value);
            } else {
                d("Removing " + key + " from the volume map");
                volumeMapEditor.remove(key);
            }
        }
        volumeMapEditor.apply();
    }

    /**
     * Stores system volume (0 - {@code mDeviceMaxVolume}) for device in {@code mVolumeMap} and
     * writes the map in the {@link SharedPreferences}.
     */
    synchronized void storeVolumeForDevice(@NonNull BluetoothDevice device, int storeVolume) {
        if (mAdapterService.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            return;
        }
        SharedPreferences.Editor pref = getVolumeMap().edit();
        mVolumeEventLogger.logd(
                TAG,
                "storeVolume: Storing stream volume level for device "
                        + device
                        + " : "
                        + storeVolume);
        mVolumeMap.put(device, storeVolume);
        pref.putInt(device.getAddress(), storeVolume);
        // Always use apply() since it is asynchronous, otherwise the call can hang waiting for
        // storage to be written.
        pref.apply();
    }

    /**
     * Retrieves system volume (0 - {@code mDeviceMaxVolume}) and calls {@link
     * #storeVolumeForDevice(BluetoothDevice, int)} with {@code device}.
     */
    synchronized void storeVolumeForDevice(@NonNull BluetoothDevice device) {
        int storeVolume = mAudioManager.getLastAudibleStreamVolume(STREAM_MUSIC);
        storeVolumeForDevice(device, storeVolume);
    }

    /**
     * Removes the stored volume of a device from {@code mVolumeMap} and writes the map in the
     * {@link SharedPreferences}.
     */
    synchronized void removeStoredVolumeForDevice(@NonNull BluetoothDevice device) {
        if (mAdapterService.getBondState(device) != BluetoothDevice.BOND_NONE) {
            return;
        }
        SharedPreferences.Editor pref = getVolumeMap().edit();
        mVolumeEventLogger.logd(
                TAG, "RemoveStoredVolume: Remove stored stream volume level for device " + device);
        mVolumeMap.remove(device);
        pref.remove(device.getAddress());
        // Always use apply() since it is asynchronous, otherwise the call can hang waiting for
        // storage to be written.
        pref.apply();
    }

    /**
     * Returns system volume (0 - {@code mDeviceMaxVolume}) stored in {@code mVolumeMap} for
     * corresponding {@code device}.
     *
     * @param defaultValue Value to return if device is not in the map.
     */
    synchronized int getVolume(@NonNull BluetoothDevice device, int defaultValue) {
        if (!mVolumeMap.containsKey(device)) {
            Log.w(TAG, "getVolume: Couldn't find volume preference for device: " + device);
            return defaultValue;
        }

        d("getVolume: Returning volume " + mVolumeMap.get(device));
        return mVolumeMap.get(device);
    }

    /** Returns the system volume (0 - {@code mDeviceMaxVolume}) applied to a new device */
    public int getNewDeviceVolume() {
        return mNewDeviceVolume;
    }

    /**
     * Informs {@link AudioManager} of a remote device volume change and stores it.
     *
     * <p>See {@link #avrcpToSystemVolume}.
     *
     * @param avrcpVolume in range (0 - 127) received from remote device.
     */
    void setVolume(@NonNull BluetoothDevice device, int avrcpVolume) {
        int deviceVolume = avrcpToSystemVolume(avrcpVolume);
        mVolumeEventLogger.logd(
                TAG,
                "setVolume:"
                        + " device="
                        + device
                        + " avrcpVolume="
                        + avrcpVolume
                        + " deviceVolume="
                        + deviceVolume
                        + " mDeviceMaxVolume="
                        + mDeviceMaxVolume);
        mAudioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                deviceVolume,
                (deviceVolume != getVolume(device, -1) ? AudioManager.FLAG_SHOW_UI : 0)
                        | AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
        storeVolumeForDevice(device);
    }

    /**
     * Informs remote device of a system volume change and stores it.
     *
     * <p>See {@link #systemToAvrcpVolume}.
     *
     * @param deviceVolume in range (0 - {@code mDeviceMaxVolume}) received from system.
     */
    void sendVolumeChanged(@NonNull BluetoothDevice device, int deviceVolume) {
        if (deviceVolume == getVolume(device, -1)) {
            d("sendVolumeChanged: Skipping update volume to same as current.");
            return;
        }
        int avrcpVolume = systemToAvrcpVolume(deviceVolume);
        mVolumeEventLogger.logd(
                TAG,
                "sendVolumeChanged:"
                        + " device="
                        + device
                        + " avrcpVolume="
                        + avrcpVolume
                        + " deviceVolume="
                        + deviceVolume
                        + " mDeviceMaxVolume="
                        + mDeviceMaxVolume);
        mNativeInterface.sendVolumeChanged(device, avrcpVolume);
        storeVolumeForDevice(device);
    }

    /** Returns whether absolute volume is supported by {@code device}. */
    boolean getAbsoluteVolumeSupported(BluetoothDevice device) {
        if (mDeviceMap.containsKey(device)) {
            return mDeviceMap.get(device);
        }
        return false;
    }

    /**
     * Callback from Media Framework to indicate new audio device was added.
     *
     * <p>Checks if the current active device is in the {@code addedDevices} list in order to inform
     * {@link AudioManager} to take it as selected audio output. See {@link #switchVolumeDevice}.
     *
     * <p>If the remote device absolute volume support hasn't been established yet or if the current
     * active device is not in the {@code addedDevices} list, this doesn't inform {@link
     * AudioManager}. See {@link #deviceConnected} and {@link #volumeDeviceSwitched}.
     */
    @Override
    public synchronized void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
        if (mCurrentDevice == null) {
            d("onAudioDevicesAdded: Not expecting device changed");
            return;
        }

        boolean foundDevice = false;
        d("onAudioDevicesAdded: size: " + addedDevices.length);
        for (int i = 0; i < addedDevices.length; i++) {
            d("onAudioDevicesAdded: address=" + addedDevices[i].getAddress());
            if (addedDevices[i].getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    && Objects.equals(addedDevices[i].getAddress(), mCurrentDevice.getAddress())) {
                foundDevice = true;
                break;
            }
        }

        if (!foundDevice) {
            d("Didn't find deferred device in list: device=" + mCurrentDevice);
            return;
        }

        // A2DP can sometimes connect and set a device to active before AVRCP has determined if the
        // device supports absolute volume. Defer switching the device until AVRCP returns the
        // info.
        if (!mDeviceMap.containsKey(mCurrentDevice)) {
            Log.w(TAG, "volumeDeviceSwitched: Device isn't connected: " + mCurrentDevice);
            return;
        }

        switchVolumeDevice(mCurrentDevice);
    }

    /**
     * Stores absolute volume support for {@code device}. If the current active device is the same
     * as {@code device}, calls {@link #switchVolumeDevice}.
     */
    synchronized void deviceConnected(@NonNull BluetoothDevice device, boolean absoluteVolume) {
        d("deviceConnected: device=" + device + " absoluteVolume=" + absoluteVolume);

        mDeviceMap.put(device, absoluteVolume);

        // AVRCP features lookup has completed after the device became active. Switch to the new
        // device now.
        if (device.equals(mCurrentDevice)) {
            switchVolumeDevice(device);
        }
    }

    /**
     * Called when the A2DP active device changed, this will call {@link #switchVolumeDevice} if we
     * already know the absolute volume support of {@code device}.
     */
    synchronized void volumeDeviceSwitched(@Nullable BluetoothDevice device) {
        d("volumeDeviceSwitched: mCurrentDevice=" + mCurrentDevice + " device=" + device);

        if (Objects.equals(device, mCurrentDevice)) {
            return;
        }

        mCurrentDevice = device;
        if (!mDeviceMap.containsKey(device)) {
            // Wait until AudioManager informs us that the new device is connected
            return;
        }
        switchVolumeDevice(device);
    }

    synchronized void deviceDisconnected(@NonNull BluetoothDevice device) {
        d("deviceDisconnected: device=" + device);
        mDeviceMap.remove(device);
    }

    /** Cleans up and unregisters any registered callbacks. */
    void cleanup() {
        mAudioManager.unregisterAudioDeviceCallback(this);
    }

    public void dump(StringBuilder sb) {
        sb.append("AvrcpVolumeManager:\n");
        sb.append("  mCurrentDevice: ").append(mCurrentDevice).append("\n");
        sb.append("  Current System Volume: ")
                .append(mAudioManager.getStreamVolume(STREAM_MUSIC))
                .append("\n");
        sb.append("  Device Volume Memory Map:\n");
        sb.append(
                String.format(
                        "    %-17s : %-14s : %3s : %s\n",
                        "Device Address", "Device Name", "Vol", "AbsVol"));
        Map<String, ?> allKeys = getVolumeMap().getAll();
        for (Map.Entry<String, ?> entry : allKeys.entrySet()) {
            Object value = entry.getValue();
            BluetoothDevice d =
                    BluetoothAdapter.getDefaultAdapter().getRemoteDevice(entry.getKey());

            String deviceName = mAdapterService.getRemoteName(d);
            if (deviceName == null) {
                deviceName = "";
            } else if (deviceName.length() > 14) {
                deviceName = deviceName.substring(0, 11).concat("...");
            }

            String absoluteVolume = "NotConnected";
            if (mDeviceMap.containsKey(d)) {
                absoluteVolume = mDeviceMap.get(d).toString();
            }

            if (value instanceof Integer) {
                sb.append(
                        String.format(
                                "    %-17s : %-14s : %3d : %s\n",
                                d.getAddress(), deviceName, (Integer) value, absoluteVolume));
            }
        }

        StringBuilder tempBuilder = new StringBuilder();
        mVolumeEventLogger.dump(tempBuilder);
        // Tab volume event logs over by two spaces
        sb.append(tempBuilder.toString().replaceAll("(?m)^", "  "));
        tempBuilder.append("\n");
    }

    static void d(String msg) {
        Log.d(TAG, msg);
    }
}
