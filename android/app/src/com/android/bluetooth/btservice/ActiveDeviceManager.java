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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The active device manager is responsible for keeping track of the connected
 * A2DP/HFP/AVRCP/HearingAid/LE audio devices and select which device is active (for each profile).
 * The active device manager selects a fallback device when the currently active device is
 * disconnected, and it selects BT devices that are lastly activated one.
 *
 * <p>Current policy (subject to change):
 *
 * <p>1) If the maximum number of connected devices is one, the manager doesn't do anything. Each
 * profile is responsible for automatically selecting the connected device as active. Only if the
 * maximum number of connected devices is more than one, the rules below will apply.
 *
 * <p>2) The selected A2DP active device is the one used for AVRCP as well.
 *
 * <p>3) The HFP active device might be different from the A2DP active device.
 *
 * <p>4) The Active Device Manager always listens for the change of active devices. When it changed
 * (e.g., triggered indirectly by user action on the UI), the new active device is marked as the
 * current active device for that profile.
 *
 * <p>5) If there is a HearingAid active device, then A2DP, HFP and LE audio active devices must be
 * set to null (i.e., A2DP, HFP and LE audio cannot have active devices). The reason is that A2DP,
 * HFP or LE audio cannot be used together with HearingAid.
 *
 * <p>6) If there are no connected devices (e.g., during startup, or after all devices have been
 * disconnected, the active device per profile (A2DP/HFP/HearingAid/LE audio) is selected as
 * follows:
 *
 * <p>6.1) The last connected HearingAid device is selected as active. If there is an active A2DP,
 * HFP or LE audio device, those must be set to null.
 *
 * <p>6.2) The last connected A2DP, HFP or LE audio device is selected as active. However, if there
 * is an active HearingAid device, then the A2DP, HFP, or LE audio active device is not set (must
 * remain null).
 *
 * <p>7) If the currently active device (per profile) is disconnected, the Active Device Manager
 * just marks that the profile has no active device, and the lastly activated BT device that is
 * still connected would be selected.
 *
 * <p>8) If there is already an active device, however, if active device change notified with a null
 * device, the corresponding profile is marked as having no active device.
 *
 * <p>TODO: Remove with com.android.bluetooth.flags.adm_remove_handling_wired
 *
 * <p>9) If a wired audio device is connected, the audio output is switched by the Audio Framework
 * itself to that device. We detect this here, and the active device for each profile
 * (A2DP/HFP/HearingAid/LE audio) is set to null to reflect the output device state change. However,
 * if the wired audio device is disconnected, we don't do anything explicit and apply the default
 * behavior instead:
 *
 * <p>9.1) If the wired headset is still the selected output device (i.e. the active device is set
 * to null), the Phone itself will become the output device (i.e. the active device will remain
 * null). If music was playing, it will stop.
 *
 * <p>9.2) If one of the Bluetooth devices is the selected active device (e.g., by the user in the
 * UI), disconnecting the wired audio device will have no impact. E.g., music will continue
 * streaming over the active Bluetooth device.
 */
public class ActiveDeviceManager implements AdapterService.BluetoothStateCallback {
    private static final String TAG = ActiveDeviceManager.class.getSimpleName();

    @VisibleForTesting static final int A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS = 5_000;

    private final AdapterService mAdapterService;
    private DatabaseManager mDbManager;
    private final ServiceFactory mFactory;
    private HandlerThread mHandlerThread = null;
    private Handler mHandler = null;
    private final AudioManager mAudioManager;
    @VisibleForTesting final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mA2dpConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHfpConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHearingAidConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeAudioConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeHearingAidConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mPendingLeHearingAidActiveDevice = new ArrayList<>();

    @GuardedBy("mLock")
    private BluetoothDevice mA2dpActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mHfpActiveDevice = null;

    @GuardedBy("mLock")
    private final Set<BluetoothDevice> mHearingAidActiveDevices = new ArraySet<>();

    @GuardedBy("mLock")
    private BluetoothDevice mLeAudioActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mLeHearingAidActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mPendingActiveDevice = null;

    private BluetoothDevice mClassicDeviceToBeActivated = null;
    private BluetoothDevice mClassicDeviceNotToBeActivated = null;

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    @Override
    public void onBluetoothStateChange(int prevState, int newState) {
        mHandler.post(() -> handleAdapterStateChanged(newState));
    }

    /**
     * Called when audio profile connection state changed
     *
     * @param profile The Bluetooth profile of which connection state changed
     * @param device The device of which connection state was changed
     * @param fromState The previous connection state of the device
     * @param toState The new connection state of the device
     */
    public void profileConnectionStateChanged(
            int profile, BluetoothDevice device, int fromState, int toState) {
        if (toState == STATE_CONNECTED) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mHandler.post(() -> handleA2dpConnected(device));
                    break;
                case BluetoothProfile.HEADSET:
                    mHandler.post(() -> handleHfpConnected(device));
                    break;
                case BluetoothProfile.LE_AUDIO:
                    mHandler.post(() -> handleLeAudioConnected(device));
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHandler.post(() -> handleHearingAidConnected(device));
                    break;
                case BluetoothProfile.HAP_CLIENT:
                    mHandler.post(() -> handleHapConnected(device));
                    break;
            }
        } else if (fromState == STATE_CONNECTED) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mHandler.post(() -> handleA2dpDisconnected(device));
                    break;
                case BluetoothProfile.HEADSET:
                    mHandler.post(() -> handleHfpDisconnected(device));
                    break;
                case BluetoothProfile.LE_AUDIO:
                    mHandler.post(() -> handleLeAudioDisconnected(device));
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHandler.post(() -> handleHearingAidDisconnected(device));
                    break;
                case BluetoothProfile.HAP_CLIENT:
                    mHandler.post(() -> handleHapDisconnected(device));
                    break;
            }
        }
    }

    /**
     * Called when active state of audio profiles changed
     *
     * @param profile The Bluetooth profile of which active state changed
     * @param device The device currently activated. {@code null} if no device is active
     */
    public void profileActiveDeviceChanged(int profile, BluetoothDevice device) {
        switch (profile) {
            case BluetoothProfile.A2DP:
                mHandler.post(() -> handleA2dpActiveDeviceChanged(device));
                break;
            case BluetoothProfile.HEADSET:
                mHandler.post(() -> handleHfpActiveDeviceChanged(device));
                break;
            case BluetoothProfile.LE_AUDIO:
                mHandler.post(() -> handleLeAudioActiveDeviceChanged(device));
                break;
            case BluetoothProfile.HEARING_AID:
                mHandler.post(() -> handleHearingAidActiveDeviceChanged(device));
                break;
        }
    }

    private void handleAdapterStateChanged(int currentState) {
        Log.d(TAG, "handleAdapterStateChanged: currentState=" + currentState);
        if (currentState == BluetoothAdapter.STATE_ON) {
            resetState();
        }
    }

    /**
     * Handles the active device logic for when A2DP is connected. Does the following: 1. Check if a
     * hearing aid device is active. We will always prefer hearing aid devices, so if one is active,
     * we will not make this A2DP device active. 2. If there is no hearing aid device active, we
     * will make this A2DP device active. 3. We will make this device active for HFP if it's already
     * connected to HFP 4. If dual mode is disabled, we clear the LE Audio active device to ensure
     * mutual exclusion between classic and LE audio.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleA2dpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "handleA2dpConnected: " + device);
            if (mA2dpConnectedDevices.contains(device)) {
                Log.d(TAG, "This device is already connected: " + device);
                return;
            }
            mA2dpConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting A2dp device as active: "
                                + device);
                if (mPendingActiveDevice != null) {
                    mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                }
                return;
            }

            if (mHearingAidActiveDevices.isEmpty() && mLeHearingAidActiveDevice == null) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time if both profile already connected.
                if (mHfpConnectedDevices.contains(device)) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device);
                    boolean hfpMadeActive = setHfpActiveDevice(device);
                    if ((a2dpMadeActive || hfpMadeActive) && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, true);
                    }
                    return;
                }
                // Activate A2DP if audio mode is normal or HFP is not supported or enabled.
                if (mDbManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET)
                                != CONNECTION_POLICY_ALLOWED
                        || mAudioManager.getMode() == AudioManager.MODE_NORMAL) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device);
                    if (a2dpMadeActive && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, true);
                    }
                } else {
                    Log.d(TAG, "A2DP activation is suspended until HFP connected: " + device);
                    if (mPendingActiveDevice != null) {
                        mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                    }
                    mPendingActiveDevice = device;
                    // Activate A2DP if HFP is failed to connect.
                    mHandler.postDelayed(
                            () -> {
                                Log.w(TAG, "HFP connection timeout. Activate A2DP for " + device);
                                setA2dpActiveDevice(device);
                            },
                            mPendingActiveDevice,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Handles the active device logic for when HFP is connected. Does the following: 1. Check if a
     * hearing aid device is active. We will always prefer hearing aid devices, so if one is active,
     * we will not make this HFP device active. 2. If there is no hearing aid device active, we will
     * make this HFP device active. 3. We will make this device active for A2DP if it's already
     * connected to A2DP 4. If dual mode is disabled, we clear the LE Audio active device to ensure
     * mutual exclusion between classic and LE audio.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleHfpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "handleHfpConnected: " + device);
            if (mHfpConnectedDevices.contains(device)) {
                Log.d(TAG, "This device is already connected: " + device);
                return;
            }
            mHfpConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting Hfp device as active: "
                                + device);
                if (mPendingActiveDevice != null) {
                    mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                }
                return;
            }

            if (mHearingAidActiveDevices.isEmpty() && mLeHearingAidActiveDevice == null) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time once both profile connected.
                if (mA2dpConnectedDevices.contains(device)) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device);
                    boolean hfpMadeActive = setHfpActiveDevice(device);

                    /* Make LEA inactive if device is made active for any classic audio profile
                    and dual mode is disabled */
                    if ((a2dpMadeActive || hfpMadeActive) && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, true);
                    }
                    return;
                }
                // Activate HFP if audio mode is not normal or A2DP is not supported or enabled.
                if (mDbManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP)
                                != CONNECTION_POLICY_ALLOWED
                        || mAudioManager.getMode() != AudioManager.MODE_NORMAL) {
                    if (Utils.isWatch(mAdapterService, device)) {
                        Log.i(TAG, "Do not set hfp active for watch device " + device);
                        return;
                    }
                    // Tries to make the device active for HFP
                    boolean hfpMadeActive = setHfpActiveDevice(device);

                    // Makes LEA inactive if device is made active for HFP & dual mode is disabled
                    if (hfpMadeActive && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, true);
                    }
                } else {
                    Log.d(TAG, "HFP activation is suspended until A2DP connected: " + device);
                    if (mPendingActiveDevice != null) {
                        mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                    }
                    mPendingActiveDevice = device;
                    // Activate HFP if A2DP is failed to connect.
                    mHandler.postDelayed(
                            () -> {
                                Log.w(TAG, "A2DP connection timeout. Activate HFP for " + device);
                                setHfpActiveDevice(device);
                            },
                            mPendingActiveDevice,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleHearingAidConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "handleHearingAidConnected: " + device);
            if (mHearingAidConnectedDevices.contains(device)) {
                Log.d(TAG, "This device is already connected: " + device);
                return;
            }
            mHearingAidConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting HearingAid device as "
                                + "active:  "
                                + device);
                return;
            }
            // New connected device: select it as active
            if (setHearingAidActiveDevice(device)) {
                final LeAudioService leAudioService = mFactory.getLeAudioService();
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
                if (Flags.admVerifyActiveFallbackDevice() && leAudioService != null) {
                    setLeAudioActiveDevice(
                            null, !leAudioService.getActiveDevices().contains(device));
                } else {
                    setLeAudioActiveDevice(null, true);
                }
            }
        }
    }

    private void handleLeAudioConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "handleLeAudioConnected: " + device);

            final LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService == null || device == null) {
                return;
            }
            leAudioService.deviceConnected(device);

            if (mLeAudioConnectedDevices.contains(device)) {
                Log.d(TAG, "This device is already connected: " + device);
                return;
            }

            mLeAudioConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting le audio device as active: "
                                + device);
                return;
            }

            if (!leAudioService.isGroupAvailableForStream(leAudioService.getGroupId(device))) {
                Log.i(TAG, "LE Audio device is not available for streaming now." + device);
                return;
            }

            if (mHearingAidActiveDevices.isEmpty()
                    && mLeHearingAidActiveDevice == null
                    && mPendingLeHearingAidActiveDevice.isEmpty()) {
                // New connected device: select it as active
                boolean leAudioMadeActive = setLeAudioActiveDevice(device);
                if (leAudioMadeActive && !Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
            } else if (mPendingLeHearingAidActiveDevice.contains(device)) {
                if (setLeHearingAidActiveDevice(device)) {
                    setHearingAidActiveDevice(null, true);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
            }
        }
    }

    private void handleHapConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "handleHapConnected: " + device);
            if (mLeHearingAidConnectedDevices.contains(device)) {
                Log.d(TAG, "This device is already connected: " + device);
                return;
            }
            mLeHearingAidConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting Hap device as active: "
                                + device);
                return;
            }

            if (!mLeAudioConnectedDevices.contains(device)) {
                mPendingLeHearingAidActiveDevice.add(device);
            } else if (Objects.equals(mLeAudioActiveDevice, device)) {
                mLeHearingAidActiveDevice = device;
            } else {
                // New connected device: select it as active
                if (setLeHearingAidActiveDevice(device)) {
                    setHearingAidActiveDevice(null, true);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
            }
        }
    }

    private void handleA2dpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleA2dpDisconnected: "
                            + device
                            + ", mA2dpActiveDevice="
                            + mA2dpActiveDevice);
            mA2dpConnectedDevices.remove(device);
            if (Objects.equals(mA2dpActiveDevice, device)) {
                if (!setFallbackDeviceActiveLocked(device)) {
                    setA2dpActiveDevice(null, false);
                }
            }
        }
    }

    private void handleHfpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHfpDisconnected: " + device + ", mHfpActiveDevice=" + mHfpActiveDevice);
            mHfpConnectedDevices.remove(device);
            if (Objects.equals(mHfpActiveDevice, device)) {
                if (mHfpConnectedDevices.isEmpty()) {
                    setHfpActiveDevice(null);
                }
                setFallbackDeviceActiveLocked(device);
            }
        }
    }

    private void handleHearingAidDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHearingAidDisconnected: "
                            + device
                            + ", mHearingAidActiveDevices="
                            + mHearingAidActiveDevices);
            mHearingAidConnectedDevices.remove(device);
            if (mHearingAidActiveDevices.remove(device) && mHearingAidActiveDevices.isEmpty()) {
                if (!setFallbackDeviceActiveLocked(device)) {
                    setHearingAidActiveDevice(null, false);
                }
            }
        }
    }

    private void handleLeAudioDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleLeAudioDisconnected: "
                            + device
                            + ", mLeAudioActiveDevice="
                            + mLeAudioActiveDevice);

            final LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService == null || device == null) {
                return;
            }

            mLeAudioConnectedDevices.remove(device);
            mLeHearingAidConnectedDevices.remove(device);

            boolean hasFallbackDevice = false;
            if (Objects.equals(mLeAudioActiveDevice, device)) {
                hasFallbackDevice = setFallbackDeviceActiveLocked(device);
                if (!hasFallbackDevice && !Flags.admFixDisconnectOfSetMember()) {
                    leAudioService.removeActiveDevice(false);
                }
            }
            leAudioService.deviceDisconnected(device, hasFallbackDevice);
        }
    }

    private void handleHapDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHapDisconnected: "
                            + device
                            + ", mLeHearingAidActiveDevice="
                            + mLeHearingAidActiveDevice);
            mLeHearingAidConnectedDevices.remove(device);
            mPendingLeHearingAidActiveDevice.remove(device);
            if (Objects.equals(mLeHearingAidActiveDevice, device)) {
                mLeHearingAidActiveDevice = null;
            }
        }
    }

    /**
     * Update the LE Audio active device following a change of (dual mode compatible) active device
     * in a classic audio profile such as A2DP or HFP.
     *
     * @param previousActiveDevice previous active device of the classic profile
     * @param nextActiveDevice current active device of the classic profile
     */
    private void updateLeAudioActiveDeviceIfDualMode(
            @Nullable BluetoothDevice previousActiveDevice,
            @Nullable BluetoothDevice nextActiveDevice) {
        if (!Utils.isDualModeAudioEnabled()) {
            return;
        }

        if (nextActiveDevice != null) {
            boolean isDualModeDevice =
                    mAdapterService.isAllSupportedClassicAudioProfilesActive(nextActiveDevice);
            if (isDualModeDevice) {
                // If the active device for a classic audio profile is changed
                // to a dual mode compatible device, then also update the
                // active device for LE Audio.
                setLeAudioActiveDevice(nextActiveDevice);
            }
        } else {
            boolean wasDualModeDevice =
                    mAdapterService.isAllSupportedClassicAudioProfilesActive(previousActiveDevice);
            if (wasDualModeDevice) {
                // If the active device for a classic audio profile was a
                // dual mode compatible device, then also update the
                // active device for LE Audio.
                setLeAudioActiveDevice(null, true);
            }
        }
    }

    /**
     * Handles the active device logic for when the A2DP active device changes. Does the following:
     * 1. Clear the active hearing aid. 2. If dual mode is enabled and all supported classic audio
     * profiles are enabled, makes this device active for LE Audio. If not, clear the LE Audio
     * active device. 3. Make HFP active for this device if it is already connected to HFP. 4.
     * Stores the new A2DP active device.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleA2dpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleA2dpActiveDeviceChanged: "
                            + device
                            + ", mA2dpActiveDevice="
                            + mA2dpActiveDevice);
            if (!Objects.equals(mA2dpActiveDevice, device)) {
                if (device != null) {
                    setHearingAidActiveDevice(null, true);
                }
                updateLeAudioActiveDeviceIfDualMode(mA2dpActiveDevice, device);
            }

            // Just assign locally the new value
            mA2dpActiveDevice = device;

            // Activate HFP if needed.
            if (device != null) {
                if (Objects.equals(mClassicDeviceNotToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceNotToBeActivated);
                    mClassicDeviceNotToBeActivated = null;
                    return;
                }
                if (Objects.equals(mClassicDeviceToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mClassicDeviceToBeActivated = null;
                }

                if (mClassicDeviceToBeActivated != null) {
                    mClassicDeviceNotToBeActivated = mClassicDeviceToBeActivated;
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mHandler.postDelayed(
                            () -> mClassicDeviceNotToBeActivated = null,
                            mClassicDeviceNotToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                    mClassicDeviceToBeActivated = null;
                }
                if (!Objects.equals(mHfpActiveDevice, device)
                        && mHfpConnectedDevices.contains(device)
                        && mDbManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET)
                                == CONNECTION_POLICY_ALLOWED) {
                    mClassicDeviceToBeActivated = device;
                    setHfpActiveDevice(device);
                    mHandler.postDelayed(
                            () -> mClassicDeviceToBeActivated = null,
                            mClassicDeviceToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Handles the active device logic for when the HFP active device changes. Does the following:
     * 1. Clear the active hearing aid. 2. If dual mode is enabled and all supported classic audio
     * profiles are enabled, makes this device active for LE Audio. If not, clear the LE Audio
     * active device. 3. Make A2DP active for this device if it is already connected to A2DP. 4.
     * Stores the new HFP active device.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleHfpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHfpActiveDeviceChanged: "
                            + device
                            + ", mHfpActiveDevice="
                            + mHfpActiveDevice);
            if (!Objects.equals(mHfpActiveDevice, device)) {
                if (device != null) {
                    setHearingAidActiveDevice(null, true);
                }

                updateLeAudioActiveDeviceIfDualMode(mHfpActiveDevice, device);

                if ((!Utils.isDualModeAudioEnabled() && device == null)) {
                    Log.d(TAG, "HFP active device is null. Try to fallback to the active device.");
                    synchronized (mLock) {
                        setFallbackDeviceActiveLocked(mHfpActiveDevice /* recentlyRemovedDevice */);
                    }
                }
            }

            // Just assign locally the new value
            mHfpActiveDevice = device;

            // Activate A2DP if needed.
            if (device != null) {
                if (Objects.equals(mClassicDeviceNotToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceNotToBeActivated);
                    mClassicDeviceNotToBeActivated = null;
                    return;
                }
                if (Objects.equals(mClassicDeviceToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mClassicDeviceToBeActivated = null;
                }

                if (mClassicDeviceToBeActivated != null) {
                    mClassicDeviceNotToBeActivated = mClassicDeviceToBeActivated;
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mHandler.postDelayed(
                            () -> mClassicDeviceNotToBeActivated = null,
                            mClassicDeviceNotToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                    mClassicDeviceToBeActivated = null;
                }
                if (!Objects.equals(mA2dpActiveDevice, device)
                        && mA2dpConnectedDevices.contains(device)
                        && mDbManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP)
                                == CONNECTION_POLICY_ALLOWED) {
                    mClassicDeviceToBeActivated = device;
                    setA2dpActiveDevice(device);
                    mHandler.postDelayed(
                            () -> mClassicDeviceToBeActivated = null,
                            mClassicDeviceToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleHearingAidActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHearingAidActiveDeviceChanged: "
                            + device
                            + ", mHearingAidActiveDevices="
                            + mHearingAidActiveDevices);
            // Just assign locally the new value
            final HearingAidService hearingAidService = mFactory.getHearingAidService();
            if (hearingAidService != null) {
                long hiSyncId = hearingAidService.getHiSyncId(device);
                if (device != null && getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                    mHearingAidActiveDevices.add(device);
                } else {
                    mHearingAidActiveDevices.clear();
                    mHearingAidActiveDevices.addAll(
                            hearingAidService.getConnectedPeerDevices(hiSyncId));
                }
            }
            if (device != null) {
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
                setLeAudioActiveDevice(null, true);
            }
        }
    }

    private void handleLeAudioActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleLeAudioActiveDeviceChanged: "
                            + device
                            + ", mLeAudioActiveDevice="
                            + mLeAudioActiveDevice);

            if (device != null && !mLeAudioConnectedDevices.contains(device)) {
                Log.w(
                        TAG,
                        "Failed to activate device "
                                + device
                                + ". Reason: Device is not connected.");
                return;
            }

            // Just assign locally the new value
            if (device != null && !Objects.equals(mLeAudioActiveDevice, device)) {
                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
                setHearingAidActiveDevice(null, true);
            }

            if (mLeHearingAidConnectedDevices.contains(device)) {
                mLeHearingAidActiveDevice = device;
            }

            // This covers the call audio routing case across classic BT and BLE.
            // Because there's only one active device at the same time. So if a device connect with
            // HFP & LE audio and when LE audio device is disconnected, we should fallback the
            // active device to the HFP.
            // LE case has isBroadcastingAudio which would set the active device to null when
            // broadcasting the audio. So we shouldn't try to change the active device in this case.
            if (device == null && !Utils.isDualModeAudioEnabled() && !isBroadcastingAudio()) {
                Log.d(TAG, "LE audio active device is null. Try to fallback to the active device.");
                synchronized (mLock) {
                    setFallbackDeviceActiveLocked(mLeAudioActiveDevice /* recentlyRemovedDevice */);
                }
            }

            mLeAudioActiveDevice = device;
        }
    }

    /** Notifications of audio device connection and disconnection events. */
    @VisibleForTesting
    class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        private static boolean isWiredAudioHeadset(AudioDeviceInfo deviceInfo) {
            switch (deviceInfo.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return true;
                default:
                    break;
            }
            return false;
        }

        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            Log.d(TAG, "onAudioDevicesAdded");
            if (!Flags.admRemoveHandlingWired()) {
                if (!Arrays.stream(addedDevices)
                        .anyMatch(AudioManagerAudioDeviceCallback::isWiredAudioHeadset)) {
                    return;
                }
                wiredAudioDeviceConnected();
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            Log.d(TAG, "onAudioDevicesRemoved");
            if (!Flags.admRemoveHandlingWired()) {
                if (!Arrays.stream(removedDevices)
                        .anyMatch(AudioManagerAudioDeviceCallback::isWiredAudioHeadset)) {
                    return;
                }
                synchronized (mLock) {
                    setFallbackDeviceActiveLocked(null);
                }
            }
        }
    }

    ActiveDeviceManager(AdapterService service, ServiceFactory factory) {
        mAdapterService = service;
        mDbManager = mAdapterService.getDatabase();
        mFactory = factory;
        mAudioManager = service.getSystemService(AudioManager.class);
        mAudioManagerAudioDeviceCallback = new AudioManagerAudioDeviceCallback();
    }

    void start() {
        Log.d(TAG, "start()");

        mHandlerThread = new HandlerThread("BluetoothActiveDeviceManager");
        BluetoothMethodProxy mp = BluetoothMethodProxy.getInstance();
        mp.threadStart(mHandlerThread);
        mHandler = new Handler(mp.handlerThreadGetLooper(mHandlerThread));

        mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);
        mAdapterService.registerBluetoothStateCallback((command) -> mHandler.post(command), this);
    }

    void cleanup() {
        Log.d(TAG, "cleanup()");

        mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);
        mAdapterService.unregisterBluetoothStateCallback(this);
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            try {
                mHandlerThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
            mHandlerThread = null;
        }
        resetState();
    }

    /**
     * Get the {@link Looper} for the handler thread. This is used in testing and helper objects
     *
     * @return {@link Looper} for the handler thread
     */
    @VisibleForTesting
    public Looper getHandlerLooper() {
        if (mHandler == null) {
            return null;
        }
        return mHandler.getLooper();
    }

    private boolean setA2dpActiveDevice(@NonNull BluetoothDevice device) {
        return setA2dpActiveDevice(device, false);
    }

    private boolean setA2dpActiveDevice(
            @Nullable BluetoothDevice device, boolean hasFallbackDevice) {
        Log.d(
                TAG,
                "setA2dpActiveDevice("
                        + device
                        + ")"
                        + (device == null ? " hasFallbackDevice=" + hasFallbackDevice : ""));
        synchronized (mLock) {
            if (mPendingActiveDevice != null) {
                mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                mPendingActiveDevice = null;
            }
        }

        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            return false;
        }

        boolean success = false;
        if (device == null) {
            success = a2dpService.removeActiveDevice(!hasFallbackDevice);
        } else {
            success = a2dpService.setActiveDevice(device);
        }

        if (!success) {
            return false;
        }

        synchronized (mLock) {
            mA2dpActiveDevice = device;
        }
        return true;
    }

    private boolean setHfpActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            Log.d(TAG, "setHfpActiveDevice(" + device + ")");
            if (mPendingActiveDevice != null) {
                mHandler.removeCallbacksAndMessages(mPendingActiveDevice);
                mPendingActiveDevice = null;
            }
            final HeadsetService headsetService = mFactory.getHeadsetService();
            if (headsetService == null) {
                return false;
            }
            BluetoothSinkAudioPolicy audioPolicy = headsetService.getHfpCallAudioPolicy(device);
            if (audioPolicy != null
                    && audioPolicy.getActiveDevicePolicyAfterConnection()
                            == BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED) {
                return false;
            }
            if (!headsetService.setActiveDevice(device)) {
                return false;
            }
            mHfpActiveDevice = device;
        }
        return true;
    }

    private boolean setHearingAidActiveDevice(@NonNull BluetoothDevice device) {
        return setHearingAidActiveDevice(device, false);
    }

    private boolean setHearingAidActiveDevice(
            @Nullable BluetoothDevice device, boolean hasFallbackDevice) {
        Log.d(
                TAG,
                "setHearingAidActiveDevice("
                        + device
                        + ")"
                        + (device == null ? " hasFallbackDevice=" + hasFallbackDevice : ""));

        final HearingAidService hearingAidService = mFactory.getHearingAidService();
        if (hearingAidService == null) {
            return false;
        }

        synchronized (mLock) {
            if (device == null) {
                if (!hearingAidService.removeActiveDevice(!hasFallbackDevice)) {
                    return false;
                }
                mHearingAidActiveDevices.clear();
                return true;
            }

            long hiSyncId = hearingAidService.getHiSyncId(device);
            if (getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                mHearingAidActiveDevices.add(device);
                return true;
            }

            if (!hearingAidService.setActiveDevice(device)) {
                return false;
            }
            mHearingAidActiveDevices.clear();
            mHearingAidActiveDevices.addAll(hearingAidService.getConnectedPeerDevices(hiSyncId));
        }
        return true;
    }

    private boolean setLeAudioActiveDevice(@NonNull BluetoothDevice device) {
        return setLeAudioActiveDevice(device, false);
    }

    private boolean setLeAudioActiveDevice(
            @Nullable BluetoothDevice device, boolean hasFallbackDevice) {
        Log.d(TAG, "setLeAudioActiveDevice(" + device + ", " + hasFallbackDevice + ")");
        synchronized (mLock) {
            final LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService == null) {
                return false;
            }
            boolean success;
            if (device == null) {
                success = leAudioService.removeActiveDevice(hasFallbackDevice);
            } else {
                if ((mLeAudioActiveDevice != null)
                        && (Objects.equals(
                                mLeAudioActiveDevice, leAudioService.getLeadDevice(device)))) {
                    Log.d(TAG, "New LeAudioDevice is a part of an active group");
                    return true;
                }
                success = leAudioService.setActiveDevice(device);
            }

            if (!success) {
                return false;
            }

            mLeAudioActiveDevice = leAudioService.getLeadDevice(device);

            if (device == null) {
                mLeHearingAidActiveDevice = null;
                mPendingLeHearingAidActiveDevice.remove(device);
            }
        }
        return true;
    }

    private boolean setLeHearingAidActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            if (!Objects.equals(mLeAudioActiveDevice, device)) {
                if (!setLeAudioActiveDevice(device)) {
                    return false;
                }
            }
            if (Objects.equals(mLeAudioActiveDevice, device)) {
                // setLeAudioActiveDevice succeed
                mLeHearingAidActiveDevice = device;
                mPendingLeHearingAidActiveDevice.remove(device);
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean areSameGroupMembers(BluetoothDevice firstDevice, BluetoothDevice secondDevice) {

        if (!Flags.admFixDisconnectOfSetMember()) {
            /* This function shall return false without the fix flag. */
            return false;
        }

        if (firstDevice == null || secondDevice == null) {
            return false;
        }

        final LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.e(TAG, "LeAudioService not available");
            return false;
        }

        int groupIdFirst = leAudioService.getGroupId(firstDevice);
        int groupIdSecond = leAudioService.getGroupId(secondDevice);

        if (groupIdFirst == BluetoothLeAudio.GROUP_ID_INVALID
                || groupIdSecond == BluetoothLeAudio.GROUP_ID_INVALID) {
            return false;
        }

        return groupIdFirst == groupIdSecond;
    }

    /**
     * TODO: This method can return true when a fallback device for an unrelated profile is found.
     * Take disconnected profile as an argument, and find the exact fallback device. Also, split
     * this method to smaller methods for better readability.
     *
     * @return true when the fallback device is activated, false otherwise
     */
    @GuardedBy("mLock")
    private boolean setFallbackDeviceActiveLocked(BluetoothDevice recentlyRemovedDevice) {
        Log.d(TAG, "setFallbackDeviceActive, recently removed: " + recentlyRemovedDevice);
        mDbManager = mAdapterService.getDatabase();
        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        final LeAudioService leAudioService = mFactory.getLeAudioService();
        if (!mHearingAidConnectedDevices.isEmpty()) {
            connectedHearingAidDevices.addAll(mHearingAidConnectedDevices);
        }
        if (!mLeHearingAidConnectedDevices.isEmpty() && leAudioService != null) {
            for (BluetoothDevice dev : mLeHearingAidConnectedDevices) {
                if (leAudioService.isGroupAvailableForStream(leAudioService.getGroupId(dev))) {
                    connectedHearingAidDevices.add(dev);
                }
            }
        }

        if (!connectedHearingAidDevices.isEmpty()) {
            BluetoothDevice device =
                    mDbManager.getMostRecentlyConnectedDevicesInList(connectedHearingAidDevices);
            if (device != null) {
                /* Check if fallback device shall be used. It should be used when a new
                 * device is connected. If the most recently connected device is the same as
                 * recently removed device, it means it just switched profile it is using and is
                 * not new one.
                 */
                boolean hasFallbackDevice = true;
                if (Flags.admVerifyActiveFallbackDevice()) {
                    hasFallbackDevice =
                            !(recentlyRemovedDevice != null
                                    && device.equals(recentlyRemovedDevice)
                                    && connectedHearingAidDevices.size() == 1);
                }
                if (mHearingAidConnectedDevices.contains(device)) {
                    Log.d(TAG, "Found a hearing aid fallback device: " + device);
                    setHearingAidActiveDevice(device);
                    setA2dpActiveDevice(null, hasFallbackDevice);
                    setHfpActiveDevice(null);
                    setLeAudioActiveDevice(null, hasFallbackDevice);
                } else {
                    Log.d(TAG, "Found a LE hearing aid fallback device: " + device);
                    if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                        Log.d(
                                TAG,
                                "Do nothing, removed device belong to the same group as the"
                                        + " fallback device.");
                        return true;
                    }
                    setLeHearingAidActiveDevice(device);
                    setHearingAidActiveDevice(null, hasFallbackDevice);
                    setA2dpActiveDevice(null, hasFallbackDevice);
                    setHfpActiveDevice(null);
                }
                return true;
            }
        }

        A2dpService a2dpService = mFactory.getA2dpService();
        BluetoothDevice a2dpFallbackDevice = null;
        if (a2dpService != null) {
            a2dpFallbackDevice = a2dpService.getFallbackDevice();
            Log.d(TAG, "a2dpFallbackDevice: " + a2dpFallbackDevice);
        }

        HeadsetService headsetService = mFactory.getHeadsetService();
        BluetoothDevice headsetFallbackDevice = null;
        if (headsetService != null) {
            headsetFallbackDevice = headsetService.getFallbackDevice();
            Log.d(TAG, "headsetFallbackDevice: " + headsetFallbackDevice);
        }

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        if (leAudioService != null) {
            for (BluetoothDevice dev : mLeAudioConnectedDevices) {
                if (leAudioService.isGroupAvailableForStream(leAudioService.getGroupId(dev))) {
                    connectedDevices.add(dev);
                }
            }
        }
        Log.d(TAG, "Audio mode: " + mAudioManager.getMode());
        switch (mAudioManager.getMode()) {
            case AudioManager.MODE_NORMAL:
                if (a2dpFallbackDevice != null) {
                    connectedDevices.add(a2dpFallbackDevice);
                }
                break;
            case AudioManager.MODE_RINGTONE:
                if (headsetFallbackDevice != null && headsetService.isInbandRingingEnabled()) {
                    connectedDevices.add(headsetFallbackDevice);
                }
                break;
            default:
                if (headsetFallbackDevice != null) {
                    connectedDevices.add(headsetFallbackDevice);
                }
        }
        BluetoothDevice device = mDbManager.getMostRecentlyConnectedDevicesInList(connectedDevices);
        if (device == null) {
            Log.d(TAG, "No fallback devices are found");
            return false;
        }
        Log.d(TAG, "Most recently connected device: " + device);
        if (mAudioManager.getMode() == AudioManager.MODE_NORMAL) {
            if (Objects.equals(a2dpFallbackDevice, device)) {
                Log.d(TAG, "Found an A2DP fallback device: " + device);
                setA2dpActiveDevice(device);
                setHfpActiveDevice(headsetFallbackDevice);
                /* If dual mode is enabled, LEA will be made active once all supported
                classic audio profiles are made active for the device. */
                if (!Utils.isDualModeAudioEnabled()) {
                    setLeAudioActiveDevice(null, true);
                }
                setHearingAidActiveDevice(null, true);
            } else {
                Log.d(TAG, "Found a LE audio fallback device: " + device);
                if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                    Log.d(
                            TAG,
                            "Do nothing, removed device belong to the same group as the fallback"
                                    + " device.");
                    return true;
                }

                if (!setLeAudioActiveDevice(device)) {
                    return false;
                }

                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
                setHearingAidActiveDevice(null, true);
            }
        } else {
            if (Objects.equals(headsetFallbackDevice, device)) {
                Log.d(TAG, "Found a HFP fallback device: " + device);
                setHfpActiveDevice(device);
                setA2dpActiveDevice(a2dpFallbackDevice);
                if (!Utils.isDualModeAudioEnabled()) {
                    setLeAudioActiveDevice(null, true);
                }
                setHearingAidActiveDevice(null, true);
            } else {
                Log.d(TAG, "Found a LE audio fallback device: " + device);
                if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                    Log.d(
                            TAG,
                            "Do nothing, removed device belong to the same group as the fallback"
                                    + " device.");
                    return true;
                }

                setLeAudioActiveDevice(device);
                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
                setHearingAidActiveDevice(null, true);
            }
        }
        return true;
    }

    private void resetState() {
        synchronized (mLock) {
            mA2dpConnectedDevices.clear();
            mA2dpActiveDevice = null;

            mHfpConnectedDevices.clear();
            mHfpActiveDevice = null;

            mHearingAidConnectedDevices.clear();
            mHearingAidActiveDevices.clear();

            mLeAudioConnectedDevices.clear();
            mLeAudioActiveDevice = null;

            mLeHearingAidConnectedDevices.clear();
            mLeHearingAidActiveDevice = null;
            mPendingLeHearingAidActiveDevice.clear();
        }
    }

    @VisibleForTesting
    BluetoothDevice getA2dpActiveDevice() {
        synchronized (mLock) {
            return mA2dpActiveDevice;
        }
    }

    @VisibleForTesting
    BluetoothDevice getHfpActiveDevice() {
        synchronized (mLock) {
            return mHfpActiveDevice;
        }
    }

    @VisibleForTesting
    Set<BluetoothDevice> getHearingAidActiveDevices() {
        synchronized (mLock) {
            return mHearingAidActiveDevices;
        }
    }

    @VisibleForTesting
    BluetoothDevice getLeAudioActiveDevice() {
        synchronized (mLock) {
            return mLeAudioActiveDevice;
        }
    }

    @GuardedBy("mLock")
    private long getHearingAidActiveHiSyncIdLocked() {
        final HearingAidService hearingAidService = mFactory.getHearingAidService();
        if (hearingAidService != null && !mHearingAidActiveDevices.isEmpty()) {
            return hearingAidService.getHiSyncId(mHearingAidActiveDevices.iterator().next());
        }
        return BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    /**
     * Checks if le audio broadcasting is ON
     *
     * @return {@code true} if is broadcasting audio, {@code false} otherwise
     */
    private boolean isBroadcastingAudio() {
        final LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.d(TAG, "isBroadcastingAudio: false - there is no LeAudioService");
            return false;
        }

        if (!leAudioService.isBroadcastStarted()) {
            Log.d(TAG, "isBroadcastingAudio: false - getAllBroadcastMetadata is empty");
            return false;
        }

        Log.d(TAG, "isBroadcastingAudio: true");
        return true;
    }

    /**
     * Called when a wired audio device is connected. It might be called multiple times each time a
     * wired audio device is connected.
     */
    @VisibleForTesting
    void wiredAudioDeviceConnected() {
        Log.d(TAG, "wiredAudioDeviceConnected");
        setA2dpActiveDevice(null, true);
        setHfpActiveDevice(null);
        setHearingAidActiveDevice(null, true);
        setLeAudioActiveDevice(null, true);
    }
}
