/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;
import static android.bluetooth.BluetoothProfile.getProfileName;

import static com.android.bluetooth.Utils.isDualModeAudioEnabled;
import static com.android.bluetooth.btservice.BondStateMachine.bondStateToString;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.State;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Describes the phone policy
//
// Policies are usually governed by outside events that may warrant an action. We talk about various
// events and the resulting outcome from this policy:
//
// 1. Adapter turned ON: At this point we will try to auto-connect the (device, profile) pairs which
// have PRIORITY_AUTO_CONNECT. The fact that we *only* auto-connect Headset and A2DP is something
// that is hardcoded and specific to phone policy (see autoConnect() function)
// 2. When the profile connection-state changes: At this point if a new profile gets CONNECTED we
// will try to connect other profiles on the same device. This is to avoid collision if devices
// somehow end up trying to connect at same time or general connection issues.
public class PhonePolicy implements AdapterService.BluetoothStateCallback {
    private static final String TAG = Util.BT_PREFIX + PhonePolicy.class.getSimpleName();

    private static final String AUTO_CONNECT_PROFILES_PROPERTY =
            "bluetooth.auto_connect_profiles.enabled";

    private static final String LE_AUDIO_CONNECTION_BY_DEFAULT_PROPERTY =
            "ro.bluetooth.leaudio.le_audio_connection_by_default";

    @VisibleForTesting
    static final String BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY =
            "persist.bluetooth.leaudio.bypass_allow_list";

    @VisibleForTesting static final Duration CONNECT_OTHER_PROFILES_TIMEOUT = Duration.ofSeconds(6);

    private final BluetoothStorageManager mStorage;
    private final AdapterService mAdapterService;
    private final Handler mHandler;
    private final Set<BluetoothDevice> mHeadsetRetrySet = new HashSet<>();
    private final Set<BluetoothDevice> mA2dpRetrySet = new HashSet<>();
    private final Set<BluetoothDevice> mConnectOtherProfilesDeviceSet = new HashSet<>();

    @VisibleForTesting boolean mAutoConnectProfilesSupported;
    @VisibleForTesting boolean mLeAudioEnabledByDefault;

    PhonePolicy(AdapterService adapterService, Looper looper, BluetoothStorageManager storage) {
        mAdapterService = adapterService;
        mStorage = requireNonNull(storage);
        mHandler = new Handler(looper);
        mAutoConnectProfilesSupported =
                SystemProperties.getBoolean(AUTO_CONNECT_PROFILES_PROPERTY, false);
        mLeAudioEnabledByDefault =
                SystemProperties.getBoolean(LE_AUDIO_CONNECTION_BY_DEFAULT_PROPERTY, true);
        mAdapterService.registerBluetoothStateCallback(mHandler::post, this);
    }

    @Override
    public void onBluetoothStateChange(int prevState, int newState) {
        if (newState != State.ON) {
            // Only act if the adapter has actually changed state from non-ON to ON.
            return;
        }
        resetStates();
        autoConnect();
    }

    public void profileConnectionStateChanged(
            int profile, BluetoothDevice device, int fromState, int toState) {
        if (profile != BluetoothProfile.A2DP
                && profile != BluetoothProfile.HEADSET
                && profile != BluetoothProfile.LE_AUDIO
                && profile != BluetoothProfile.CSIP_SET_COORDINATOR
                && profile != BluetoothProfile.VOLUME_CONTROL) {
            return;
        }
        mHandler.post(() -> processProfileStateChanged(profile, device, fromState, toState));
    }

    /**
     * Called when active state of audio profiles changed
     *
     * @param profile The Bluetooth profile of which active state changed
     * @param device The device currently activated. {@code null} if no A2DP device activated
     */
    public void profileActiveDeviceChanged(int profile, BluetoothDevice device) {
        mHandler.post(() -> processActiveDeviceChanged(device, profile));
    }

    public void cleanup() {
        mAdapterService.unregisterBluetoothStateCallback(this);
        resetStates();
    }

    boolean isLeAudioOnlyGroup(BluetoothDevice device) {
        String log = "isLeAudioOnlyGroup(" + device + "): ";

        final var csipSetCoordinator = mAdapterService.getCsipSetCoordinatorService();
        if (csipSetCoordinator.isEmpty()) {
            Log.d(TAG, log + "csipSetCoordinatorService is null");
            return false;
        }

        int groupId = csipSetCoordinator.get().getGroupId(device, BluetoothUuid.CAP);
        if (groupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            Log.d(TAG, log + "group id is INVALID");
            return false;
        }

        int groupSize = csipSetCoordinator.get().getDesiredGroupSize(groupId);
        List<BluetoothDevice> groupDevices =
                csipSetCoordinator.get().getGroupDevicesOrdered(groupId);

        if (groupDevices.size() != groupSize) {
            Log.d(TAG, log + "incomplete group: " + groupDevices.size() + "!=" + groupSize + ")");
            return false;
        }

        for (BluetoothDevice dev : groupDevices) {
            int remoteType = mAdapterService.getRemoteType(dev);

            if (remoteType != BluetoothDevice.DEVICE_TYPE_LE) {
                Log.d(TAG, log + "Device is not LE: " + remoteType);
                return false;
            }

            if (!mAdapterService.isProfileSupported(dev, BluetoothProfile.LE_AUDIO)) {
                Log.d(TAG, log + "Device does not support LE_AUDIO");
                return false;
            }

            if (mAdapterService.isProfileSupported(dev, BluetoothProfile.HEARING_AID)) {
                Log.d(TAG, log + "Device supports ASHA");
                return false;
            }
        }

        return true;
    }

    boolean isLeAudioOnlyDevice(BluetoothDevice device, ParcelUuid[] uuids) {
        String log = "isLeAudioOnlyDevice(" + device + "): ";
        /* This functions checks if device belongs to the LeAudio group which
         * is LeAudio only. This is either
         * - LeAudio only Headset (no BR/EDR mode)
         * - LeAudio Hearing Aid  (no ASHA)
         *
         * Note, that we need to have all set bonded to take the decision.
         * If the set is not bonded, we cannot assume that.
         */

        if (!Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)) {
            Log.d(TAG, log + "Device does not supports LE_AUDIO");
            return false;
        }

        int deviceType = mAdapterService.getRemoteType(device);

        if (deviceType != BluetoothDevice.DEVICE_TYPE_LE) {
            Log.d(TAG, log + "Device is not LE: " + deviceType);
            return false;
        }

        if (Util.arrayContains(uuids, BluetoothUuid.HEARING_AID)) {
            Log.d(TAG, log + "Device supports ASHA");
            return false;
        }

        /* For no CSIS device, allow LE Only devices. */
        if (!Util.arrayContains(uuids, BluetoothUuid.COORDINATED_SET)) {
            Log.d(TAG, log + "Device is LE_AUDIO only. (no CSIP supports)");
            return true;
        }

        // For CSIS devices it is bit harder to check.
        return isLeAudioOnlyGroup(device);
    }

    private static final String SYSPROP_HAP_ENABLED = "bluetooth.profile.hap.enabled_by_default";

    // return true if device support Hearing Access Service and it has not been manually disabled
    private boolean shouldEnableHapByDefault(BluetoothDevice device, ParcelUuid[] uuids) {
        final var hap = mAdapterService.getHapClientService();
        if (hap.isEmpty()) {
            Log.e(TAG, "shouldEnableHapByDefault: No HapClientService");
            return false;
        }

        if (!SystemProperties.getBoolean(SYSPROP_HAP_ENABLED, true)) {
            Log.i(TAG, "shouldEnableHapByDefault: SystemProperty is overridden to false");
            return false;
        }

        return Util.arrayContains(uuids, BluetoothUuid.HAS)
                && hap.get().getConnectionPolicy(device) != CONNECTION_POLICY_FORBIDDEN;
    }

    private boolean shouldBlockBroadcastForHapDevice(BluetoothDevice device, ParcelUuid[] uuids) {
        if (!Flags.leaudioDisableBroadcastForHapDevice()) {
            Log.i(TAG, "disableBroadcastForHapDevice: Flag is disabled");
            return false;
        }

        final var hap = mAdapterService.getHapClientService();
        if (hap.isEmpty()) {
            Log.e(TAG, "shouldBlockBroadcastForHapDevice: No HapClientService");
            return false;
        }

        if (!SystemProperties.getBoolean(SYSPROP_HAP_ENABLED, true)) {
            Log.i(TAG, "shouldBlockBroadcastForHapDevice: SystemProperty is overridden to false");
            return false;
        }

        return Util.arrayContains(uuids, BluetoothUuid.HAS)
                && hap.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED;
    }

    // Policy implementation, all functions MUST be private
    private void processInitProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids) {
        String log = "processInitProfilePriorities(" + device + "): ";
        final var a2dp = mAdapterService.getA2dpService();
        final var battery = mAdapterService.getBatteryService();
        final var bassClient = mAdapterService.getBassClientService();
        final var csipSetCoordinator = mAdapterService.getCsipSetCoordinatorService();
        final var hapClient = mAdapterService.getHapClientService();
        final var headset = mAdapterService.getHeadsetService();
        final var hearingAid = mAdapterService.getHearingAidService();
        final var hidHost = mAdapterService.getHidHostService();
        final var leAudio = mAdapterService.getLeAudioService();
        final var pan = mAdapterService.getPanService();
        final var volumeControl = mAdapterService.getVolumeControlService();
        final var mcpClient = mAdapterService.getMcpClientService();

        final boolean isBypassLeAudioAllowlist =
                SystemProperties.getBoolean(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, false);

        boolean isLeAudioOnly = isLeAudioOnlyDevice(device, uuids);
        boolean shouldEnableHapByDefault = shouldEnableHapByDefault(device, uuids);
        boolean isLeAudioProfileAllowed =
                (leAudio.isPresent())
                        && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)
                        && (leAudio.get().getConnectionPolicy(device)
                                != CONNECTION_POLICY_FORBIDDEN)
                        && (mLeAudioEnabledByDefault || isDualModeAudioEnabled())
                        && (isBypassLeAudioAllowlist
                                || shouldEnableHapByDefault
                                || mAdapterService.isLeAudioAllowed(device)
                                || isLeAudioOnly);
        Log.d(
                TAG,
                log
                        + ("mLeAudioEnabledByDefault=" + mLeAudioEnabledByDefault)
                        + (" isBypassLeAudioAllowlist=" + isBypassLeAudioAllowlist)
                        + (" isLeAudioAllowDevice=" + mAdapterService.isLeAudioAllowed(device))
                        + (" mAutoConnectProfilesSupported=" + mAutoConnectProfilesSupported)
                        + (" isLeAudioProfileAllowed=" + isLeAudioProfileAllowed)
                        + (" isLeAudioOnly=" + isLeAudioOnly)
                        + (" shouldEnableHapByDefault=" + shouldEnableHapByDefault));

        // Set profile priorities only for the profiles discovered on the remote device.
        // This avoids needless auto-connect attempts to profiles non-existent on the remote device
        if (hidHost.isPresent()
                && (Util.arrayContains(uuids, BluetoothUuid.HID)
                        || Util.arrayContains(uuids, BluetoothUuid.HOGP)
                        || Util.arrayContains(uuids, HidHostService.ANDROID_HEADTRACKER_UUID))
                && (hidHost.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (mAutoConnectProfilesSupported) {
                hidHost.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.HID_HOST, CONNECTION_POLICY_ALLOWED);
            }
            MetricsLogger.getInstance()
                    .count(
                            (Util.arrayContains(uuids, BluetoothUuid.HID)
                                            && Util.arrayContains(uuids, BluetoothUuid.HOGP))
                                    ? BluetoothProtoEnums.HIDH_COUNT_SUPPORT_BOTH_HID_AND_HOGP
                                    : BluetoothProtoEnums.HIDH_COUNT_SUPPORT_ONLY_HID_OR_HOGP,
                            1);
        }

        if (headset.isPresent()
                && ((Util.arrayContains(uuids, BluetoothUuid.HSP)
                                || Util.arrayContains(uuids, BluetoothUuid.HFP))
                        && (headset.get().getConnectionPolicy(device)
                                == CONNECTION_POLICY_UNKNOWN))) {
            if (!isDualModeAudioEnabled() && isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Dual mode device detected: clear hfp profile priority");
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
            } else {
                if (mAutoConnectProfilesSupported) {
                    headset.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        if (a2dp.isPresent()
                && (Util.arrayContains(uuids, BluetoothUuid.A2DP_SINK)
                        || Util.arrayContains(uuids, BluetoothUuid.ADV_AUDIO_DIST))
                && (a2dp.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (!isDualModeAudioEnabled() && isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Dual mode device detected: clear A2dp profile priority");
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);
            } else {
                if (mAutoConnectProfilesSupported) {
                    a2dp.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        // CSIP should be connected prior to LE Audio
        if (csipSetCoordinator.isPresent()
                && (Util.arrayContains(uuids, BluetoothUuid.COORDINATED_SET))
                && (csipSetCoordinator.get().getConnectionPolicy(device)
                        == CONNECTION_POLICY_UNKNOWN)) {
            // Always allow CSIP during pairing process regardless of LE audio preference
            if (mAutoConnectProfilesSupported) {
                csipSetCoordinator.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.CSIP_SET_COORDINATOR, CONNECTION_POLICY_ALLOWED);
            }
        }

        /* Make sure to connect Volume Control before LeAudio service */
        if (volumeControl.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.VOLUME_CONTROL)
                && (volumeControl.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Setting VCP priority");
                if (mAutoConnectProfilesSupported) {
                    volumeControl.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.VOLUME_CONTROL, CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO is not allowed: Clear VCP priority");
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.VOLUME_CONTROL, CONNECTION_POLICY_FORBIDDEN);
            }
        }

        // If we do not have a stored priority for HFP/A2DP (all roles) then default to on.
        if (pan.isPresent()
                && (Util.arrayContains(uuids, BluetoothUuid.PANU)
                        && (pan.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)
                        && mAdapterService
                                .getResources()
                                .getBoolean(R.bool.config_bluetooth_pan_enable_autoconnect))) {
            if (mAutoConnectProfilesSupported) {
                pan.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.PAN, CONNECTION_POLICY_ALLOWED);
            }
        }

        if (leAudio.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)
                && (leAudio.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Setting LE_AUDIO priority");
                if (mAutoConnectProfilesSupported) {
                    leAudio.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO is not allowed: Clear LE_AUDIO priority");
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if (hearingAid.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.HEARING_AID)
                && (hearingAid.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.i(TAG, log + "LE_AUDIO is preferred over ASHA");
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.HEARING_AID, CONNECTION_POLICY_FORBIDDEN);
            } else {
                Log.d(TAG, log + "Setting ASHA priority");
                if (mAutoConnectProfilesSupported) {
                    hearingAid.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.HEARING_AID, CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        if (hapClient.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.HAS)
                && (hapClient.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            Log.d(TAG, log + "Setting HAP priority");
            if (isLeAudioProfileAllowed) {
                if (mAutoConnectProfilesSupported && !Flags.hapOnMainLooper()) {
                    hapClient.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else if (mAutoConnectProfilesSupported && Flags.hapOnMainLooper()) {
                    hapClient
                            .get()
                            .syncPost(
                                    h -> h.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED));
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_ALLOWED);
                }
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if (bassClient.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.BASS)
                && (bassClient.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed && !shouldBlockBroadcastForHapDevice(device, uuids)) {
                Log.d(TAG, log + "Setting BASS priority");
                if (mAutoConnectProfilesSupported) {
                    bassClient.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService.setProfileConnectionPolicy(
                            device,
                            BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                            CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO Broadcast is not allowed: Clear BASS priority");
                mAdapterService.setProfileConnectionPolicy(
                        device,
                        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                        CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if (battery.isPresent()
                && Util.arrayContains(uuids, BluetoothUuid.BATTERY)
                && (battery.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            Log.d(TAG, log + "Setting BATTERY priority");
            if (mAutoConnectProfilesSupported) {
                battery.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.BATTERY, CONNECTION_POLICY_ALLOWED);
            }
        }

        if (mcpClient.isPresent()
                && mcpClient.get().isEnabled()
                && Util.arrayContains(uuids, BluetoothUuid.GENERIC_MEDIA_CONTROL)
                && (mcpClient.get().getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            Log.d(TAG, log + "Setting MCP_CLIENT priority");
            if (mAutoConnectProfilesSupported) {
                mcpClient.get().setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService.setProfileConnectionPolicy(
                        device, BluetoothProfile.MCP_CLIENT, CONNECTION_POLICY_ALLOWED);
            }
        }
    }

    void handleConnectionPolicyAfterCsipConnect(BluetoothDevice device) {
        String log = "handleConnectionPolicyAfterCsipConnect(" + device + "): ";

        final var leAudio = mAdapterService.getLeAudioService();
        if (leAudio.isEmpty()
                || (leAudio.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                || !mAdapterService.isProfileSupported(device, BluetoothProfile.LE_AUDIO)) {
            Log.d(TAG, log + "Nothing to do");
            return;
        }

        List<BluetoothDevice> groupDevices = new ArrayList<>();
        boolean isAnyOtherGroupMemberAllowed = false;

        /* isLeAudioOnlyGroup returning true implies csipSetCoordinatorService is valid */
        final var csipSetCoordinator = mAdapterService.getCsipSetCoordinatorService();
        if (csipSetCoordinator.isPresent()) {
            /* Since isLeAudioOnlyGroup return true it means csipSetCoordinatorService is valid */
            groupDevices =
                    csipSetCoordinator
                            .get()
                            .getGroupDevicesOrdered(
                                    csipSetCoordinator.get().getGroupId(device, BluetoothUuid.CAP));
            for (BluetoothDevice dev : groupDevices) {
                if (leAudio.get().getConnectionPolicy(dev) == CONNECTION_POLICY_ALLOWED) {
                    isAnyOtherGroupMemberAllowed = true;
                    break;
                }
            }
        }

        boolean isLeAudioOnlyGroup = isLeAudioOnlyGroup(device);
        Log.d(
                TAG,
                log
                        + ("isAnyOtherGroupMemberAllowed=" + isAnyOtherGroupMemberAllowed)
                        + (" isLeAudioOnlyGroup=" + isLeAudioOnlyGroup));

        /* This is the key check for Dual Mode devices.
         * If the group is dual mode and no other member has an active ALLOWED policy,
         * we return early to avoid enabling all profiles for the entire group.
         */
        if (!isAnyOtherGroupMemberAllowed && !isLeAudioOnlyGroup) {
            /* Log no needed as above function will log on error. */
            return;
        }

        /* For LE Audio Only groups, or for a Dual Mode group that already has an active member,
         * iterate through all members and ensure their LE Audio connection policy is set to
         * ALLOWED.
         */
        for (BluetoothDevice dev : groupDevices) {
            if (leAudio.get().getConnectionPolicy(dev) != CONNECTION_POLICY_ALLOWED) {
                int bondState = mAdapterService.getBondState(dev);
                if (bondState != BluetoothDevice.BOND_BONDED) {
                    Log.w(
                            TAG,
                            log
                                    + "member"
                                    + dev
                                    + " not bonded, do not set LEA policy to ALLOWED.");
                } else {
                    /* Setting LeAudio service as allowed is sufficient,
                     * because other LeAudio services e.g. VC will
                     * be enabled by LeAudio service automatically.
                     */
                    Log.d(TAG, log + "...." + dev);
                    leAudio.get().setConnectionPolicy(dev, CONNECTION_POLICY_ALLOWED);
                }
            }
        }
    }

    private void processProfileStateChanged(
            int profile, BluetoothDevice device, int prevState, int nextState) {
        Log.d(
                TAG,
                ("processProfileStateChanged(" + getProfileName(profile) + ", " + device + "): ")
                        + getConnectionStateName(prevState)
                        + "->"
                        + getConnectionStateName(nextState));
        if (nextState == STATE_CONNECTED) {
            switch (profile) {
                case BluetoothProfile.A2DP -> mA2dpRetrySet.remove(device);
                case BluetoothProfile.HEADSET -> mHeadsetRetrySet.remove(device);
                case BluetoothProfile.CSIP_SET_COORDINATOR ->
                        handleConnectionPolicyAfterCsipConnect(device);
                default -> {} // Nothing to do
            }
            connectOtherProfile(device);
        } else if (nextState == STATE_DISCONNECTED) {
            if (prevState == STATE_CONNECTING || prevState == STATE_DISCONNECTING) {
                if (profile == BluetoothProfile.A2DP || profile == BluetoothProfile.HEADSET) {
                    mStorage.onDeviceDisconnected(device, profile);
                }
            }
            handleAllProfilesDisconnected(device);
        }
    }

    /**
     * Updates the last connection date in the connection order database for the newly active device
     * if connected to the A2DP profile. If this is a dual mode audio device (supports classic and
     * LE Audio), LE Audio is made active, and {@link Utils#isDualModeAudioEnabled()} is false, A2DP
     * and HFP will be disconnected.
     *
     * @param device is the device we just made the active device
     */
    private void processActiveDeviceChanged(BluetoothDevice device, int profile) {
        String log = "processActiveDeviceChanged(" + device + ", " + getProfileName(profile) + ") ";
        if (device == null) {
            Log.d(TAG, log + "Nothing to do");
            return;
        }

        mStorage.onDeviceConnected(device, profile);

        boolean isDualMode = isDualModeAudioEnabled();
        Log.d(TAG, log + "isDualMode=" + isDualMode);

        if (profile == BluetoothProfile.LE_AUDIO) {
            final var a2dp = mAdapterService.getA2dpService();
            final var headset = mAdapterService.getHeadsetService();
            final var leAudio = mAdapterService.getLeAudioService();
            final var hearingAid = mAdapterService.getHearingAidService();

            if (leAudio.isEmpty()) {
                Log.d(TAG, log + "LeAudioService is null");
                return;
            }
            final List<BluetoothDevice> leAudioActiveGroupDevices =
                    leAudio.get().getGroupDevices(leAudio.get().getGroupId(device));

            // Disable classic audio profiles and ASHA for all group devices as lead can change
            for (BluetoothDevice activeGroupDevice : leAudioActiveGroupDevices) {
                if (headset.isPresent() && !isDualMode) {
                    Log.d(TAG, log + "Disable HFP for the LE_AUDIO group: " + activeGroupDevice);
                    headset.get()
                            .setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
                if (a2dp.isPresent() && !isDualMode) {
                    Log.d(TAG, log + "Disable A2DP for the LE_AUDIO group: " + activeGroupDevice);
                    a2dp.get().setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
                if (hearingAid.isPresent()) {
                    Log.d(TAG, log + "Disable ASHA for the LE_AUDIO group: " + activeGroupDevice);
                    hearingAid
                            .get()
                            .setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
            }
        }
    }

    private boolean handleAllProfilesDisconnected(BluetoothDevice device) {
        boolean atLeastOneProfileConnectedForDevice = false;
        boolean allProfilesEmpty = true;
        final var a2dp = mAdapterService.getA2dpService();
        final var headset = mAdapterService.getHeadsetService();
        final var leAudio = mAdapterService.getLeAudioService();
        final var pan = mAdapterService.getPanService();
        final var csipSetCoordinator = mAdapterService.getCsipSetCoordinatorService();

        if (headset.isPresent()) {
            List<BluetoothDevice> hsConnDevList = headset.get().getConnectedDevices();
            allProfilesEmpty &= hsConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= hsConnDevList.contains(device);
        }
        if (a2dp.isPresent()) {
            List<BluetoothDevice> a2dpConnDevList = a2dp.get().getConnectedDevices();
            allProfilesEmpty &= a2dpConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= a2dpConnDevList.contains(device);
        }
        if (csipSetCoordinator.isPresent()) {
            List<BluetoothDevice> csipConnDevList = csipSetCoordinator.get().getConnectedDevices();
            allProfilesEmpty &= csipConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= csipConnDevList.contains(device);
        }
        if (pan.isPresent()) {
            List<BluetoothDevice> panConnDevList = pan.get().getConnectedDevices();
            allProfilesEmpty &= panConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= panConnDevList.contains(device);
        }
        if (leAudio.isPresent()) {
            List<BluetoothDevice> leAudioConnDevList = leAudio.get().getConnectedDevices();
            allProfilesEmpty &= leAudioConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= leAudioConnDevList.contains(device);
        }

        if (!atLeastOneProfileConnectedForDevice) {
            // Consider this device as fully disconnected, don't bother connecting others
            Log.d(TAG, "handleAllProfilesDisconnected: all profiles disconnected for " + device);
            mHeadsetRetrySet.remove(device);
            mA2dpRetrySet.remove(device);
            if (allProfilesEmpty) {
                Log.d(TAG, "handleAllProfilesDisconnected: no more devices connected");
                // reset retry status so that in the next round we can start retrying connections
                resetStates();
            }
            return true;
        }
        return false;
    }

    private void resetStates() {
        mHeadsetRetrySet.clear();
        mA2dpRetrySet.clear();
    }

    @VisibleForTesting
    void autoConnect() {
        String log = "autoConnect(): ";
        if (mAdapterService.getState() != State.ON) {
            Log.e(TAG, log + "Bluetooth is not ON. Exiting autoConnect");
            return;
        }
        if (mAdapterService.isQuietModeEnabled()) {
            Log.i(TAG, log + "Bluetooth is in quiet mode. Cancelling autoConnect");
            return;
        }

        BluetoothDevice mostRecentlyActiveA2dpDevice = mStorage.getMostRecentlyActiveA2dpDevice();
        if (mostRecentlyActiveA2dpDevice != null) {
            Log.d(TAG, log + "Most recent A2DP device " + mostRecentlyActiveA2dpDevice);
            autoConnectHeadset(mostRecentlyActiveA2dpDevice);
            autoConnectA2dp(mostRecentlyActiveA2dpDevice);
            autoConnectHidHost(mostRecentlyActiveA2dpDevice);
            return;
        }

        final List<BluetoothDevice> mostRecentlyConnectedHfpDevices =
                mStorage.getMostRecentlyActiveHfpDevices();
        for (BluetoothDevice hfpDevice : mostRecentlyConnectedHfpDevices) {
            Log.d(TAG, log + "Most recent HFP device " + hfpDevice);
            autoConnectHeadset(hfpDevice);
        }
        if (mostRecentlyConnectedHfpDevices.isEmpty()) {
            Log.d(TAG, log + "There was no A2DP/HFP device to auto connect to");
        }
    }

    private void autoConnectA2dp(BluetoothDevice device) {
        String log = "autoConnectA2dp(" + device + "): ";
        final var a2dp = mAdapterService.getA2dpService();
        if (a2dp.isEmpty()) {
            Log.w(TAG, log + "Failed to connect, A2DP service is null");
            return;
        }
        final int connectionPolicy = a2dp.get().getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped A2DP auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting A2DP");
        a2dp.get().connect(device);
    }

    private void autoConnectHeadset(BluetoothDevice device) {
        String log = "autoConnectHeadset(" + device + "): ";
        final var headset = mAdapterService.getHeadsetService();
        if (headset.isEmpty()) {
            Log.w(TAG, log + "Failed to connect, HFP service is null");
            return;
        }
        final int connectionPolicy = headset.get().getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped HFP auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting HFP");
        headset.get().connect(device);
    }

    private void autoConnectHidHost(BluetoothDevice device) {
        String log = "autoConnectHidHost(" + device + "): ";
        final var hidHost = mAdapterService.getHidHostService();
        if (hidHost.isEmpty()) {
            Log.w(TAG, log + "Failed to connect, HID service is null");
            return;
        }
        final int connectionPolicy = hidHost.get().getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped HID auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting HID");
        hidHost.get().connect(device);
    }

    private void connectOtherProfile(BluetoothDevice device) {
        String log = "connectOtherProfile(" + device + "): ";
        if (mAdapterService.isQuietModeEnabled()) {
            Log.d(TAG, log + "Skip connect to other profile because quiet mode is enabled");
            return;
        }
        if (mConnectOtherProfilesDeviceSet.contains(device)) {
            Log.d(TAG, log + "Callback is already scheduled");
            return;
        }
        mConnectOtherProfilesDeviceSet.add(device);
        mHandler.postDelayed(
                () -> {
                    processConnectOtherProfiles(device);
                    mConnectOtherProfilesDeviceSet.remove(device);
                },
                CONNECT_OTHER_PROFILES_TIMEOUT.toMillis());
    }

    // This function is called whenever a profile is connected.  This allows any other bluetooth
    // profiles which are not already connected or in the process of connecting to attempt to
    // connect to the device that initiated the connection.  In the event that this function is
    // invoked and there are no current bluetooth connections no new profiles will be connected.
    private void processConnectOtherProfiles(BluetoothDevice device) {
        String log = "processConnectOtherProfiles(" + device + "): ";
        int currentState = mAdapterService.getState();
        if (currentState != State.ON) {
            Log.w(TAG, log + "Bluetooth is " + BluetoothAdapter.nameForState(currentState));
            return;
        }

        /* Make sure that device is still connected before connecting other profiles */
        if (mAdapterService.getConnectionState(device)
                == BluetoothDevice.CONNECTION_STATE_DISCONNECTED) {
            Log.d(TAG, log + "Device is no longer connected");
            return;
        }

        if (handleAllProfilesDisconnected(device)) {
            Log.d(TAG, log + "All profiles are disconnected");
            return;
        }

        final var a2dp = mAdapterService.getA2dpService();
        final var bassClient = mAdapterService.getBassClientService();
        final var battery = mAdapterService.getBatteryService();
        final var csipSetCoordinator = mAdapterService.getCsipSetCoordinatorService();
        final var hapClient = mAdapterService.getHapClientService();
        final var headset = mAdapterService.getHeadsetService();
        final var hidHost = mAdapterService.getHidHostService();
        final var leAudio = mAdapterService.getLeAudioService();
        final var pan = mAdapterService.getPanService();
        final var volumeControl = mAdapterService.getVolumeControlService();

        if (headset.isPresent()) {
            if (!mHeadsetRetrySet.contains(device)
                    && (headset.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (headset.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying HFP connection");
                mHeadsetRetrySet.add(device);
                headset.get().connect(device);
            }
        }
        if (a2dp.isPresent()) {
            if (!mA2dpRetrySet.contains(device)
                    && (a2dp.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (a2dp.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying A2DP connection");
                mA2dpRetrySet.add(device);
                a2dp.get().connect(device);
            }
        }
        if (pan.isPresent()) {
            List<BluetoothDevice> panConnDevList = pan.get().getConnectedDevices();
            // TODO: the panConnDevList.isEmpty() check below should be removed once
            // Multi-PAN is supported.
            if (panConnDevList.isEmpty()
                    && (pan.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (pan.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying PAN connection");
                pan.get().connect(device);
            }
        }
        if (leAudio.isPresent()) {
            List<BluetoothDevice> leAudioConnDevList = leAudio.get().getConnectedDevices();
            if (!leAudioConnDevList.contains(device)
                    && (leAudio.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (leAudio.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying LE_AUDIO connection");
                leAudio.get().connect(device);
            }
        }
        if (csipSetCoordinator.isPresent()) {
            List<BluetoothDevice> csipConnDevList = csipSetCoordinator.get().getConnectedDevices();
            if (!csipConnDevList.contains(device)
                    && (csipSetCoordinator.get().getConnectionPolicy(device)
                            == CONNECTION_POLICY_ALLOWED)
                    && (csipSetCoordinator.get().getConnectionState(device)
                            == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying CSIP connection");
                csipSetCoordinator.get().connect(device);
            }
        }
        if (volumeControl.isPresent()) {
            List<BluetoothDevice> vcConnDevList = volumeControl.get().getConnectedDevices();
            if (!vcConnDevList.contains(device)
                    && (volumeControl.get().getConnectionPolicy(device)
                            == CONNECTION_POLICY_ALLOWED)
                    && (volumeControl.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying VCP connection");
                volumeControl.get().connect(device);
            }
        }
        if (battery.isPresent()) {
            List<BluetoothDevice> connectedDevices = battery.get().getConnectedDevices();
            if (!connectedDevices.contains(device)
                    && (battery.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (battery.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying BATTERY connection");
                battery.get().connect(device);
            }
        }
        if (hidHost.isPresent()) {
            if ((hidHost.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (hidHost.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying HID connection");
                hidHost.get().connect(device);
            }
        }
        if (bassClient.isPresent()) {
            List<BluetoothDevice> connectedDevices = bassClient.get().getConnectedDevices();
            if (!connectedDevices.contains(device)
                    && (bassClient.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (bassClient.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying BASS connection");
                bassClient.get().connect(device);
            }
        }
        if (Flags.hapOnMainLooper()) {
            hapClient.ifPresent(
                    hap -> {
                        List<BluetoothDevice> connectedDevices = hap.getConnectedDevices();
                        if (!connectedDevices.contains(device)
                                && (hap.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                                && (hap.getConnectionState(device) == STATE_DISCONNECTED)) {
                            Log.d(TAG, log + "Retrying HAP connection");
                            hap.connect(device);
                        }
                    });
        }
        if (!Flags.hapOnMainLooper() && hapClient.isPresent()) {
            List<BluetoothDevice> connectedDevices = hapClient.get().getConnectedDevices();
            if (!connectedDevices.contains(device)
                    && (hapClient.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (hapClient.get().getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying HAP connection");
                hapClient.get().connect(device);
            }
        }
    }

    /**
     * Direct call prior to sending out {@link BluetoothDevice#ACTION_UUID}. This indicates that
     * service discovery is complete and passes the UUIDs directly to PhonePolicy.
     *
     * @param device is the remote device whose services have been discovered
     * @param uuids are the services supported by the remote device
     */
    void onUuidsDiscovered(BluetoothDevice device, ParcelUuid[] uuids) {
        String log = "onUuidsDiscovered(" + device + "): ";
        if (uuids == null) {
            Log.w(TAG, log + "uuids is null");
            return;
        }
        int bondState = mAdapterService.getBondState(device);
        if (bondState != BluetoothDevice.BOND_NONE) {
            Log.d(TAG, log + "Services discovered. bondState=" + bondStateToString(bondState));
            processInitProfilePriorities(device, uuids);
        } else {
            Log.d(TAG, log + "Device in BOND_NONE state, won't connect profiles");
        }
    }
}
