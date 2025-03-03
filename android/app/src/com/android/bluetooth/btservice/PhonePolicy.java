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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.bas.BatteryService;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.util.SystemProperties;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

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
    private static final String TAG =
            Utils.TAG_PREFIX_BLUETOOTH + PhonePolicy.class.getSimpleName();

    private static final String AUTO_CONNECT_PROFILES_PROPERTY =
            "bluetooth.auto_connect_profiles.enabled";

    private static final String LE_AUDIO_CONNECTION_BY_DEFAULT_PROPERTY =
            "ro.bluetooth.leaudio.le_audio_connection_by_default";

    @VisibleForTesting
    static final String BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY =
            "persist.bluetooth.leaudio.bypass_allow_list";

    @VisibleForTesting static final Duration CONNECT_OTHER_PROFILES_TIMEOUT = Duration.ofSeconds(6);

    private final DatabaseManager mDatabaseManager;
    private final AdapterService mAdapterService;
    private final ServiceFactory mFactory;
    private final Handler mHandler;
    private final HashSet<BluetoothDevice> mHeadsetRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mA2dpRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mConnectOtherProfilesDeviceSet = new HashSet<>();

    @VisibleForTesting boolean mAutoConnectProfilesSupported;
    @VisibleForTesting boolean mLeAudioEnabledByDefault;

    @Override
    public void onBluetoothStateChange(int prevState, int newState) {
        // Only act if the adapter has actually changed state from non-ON to ON.
        // NOTE: ON is the state depicting BREDR ON and not just BLE ON.
        if (newState == BluetoothAdapter.STATE_ON) {
            resetStates();
            autoConnect();
        }
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

    public void handleAclConnected(BluetoothDevice device) {
        mHandler.post(() -> processDeviceConnected(device));
    }

    public void cleanup() {
        mAdapterService.unregisterBluetoothStateCallback(this);
        resetStates();
    }

    PhonePolicy(AdapterService service, Looper looper, ServiceFactory factory) {
        mAdapterService = service;
        mDatabaseManager = requireNonNull(service.getDatabase());
        mFactory = factory;
        mHandler = new Handler(looper);
        mAutoConnectProfilesSupported =
                SystemProperties.getBoolean(AUTO_CONNECT_PROFILES_PROPERTY, false);
        mLeAudioEnabledByDefault =
                SystemProperties.getBoolean(LE_AUDIO_CONNECTION_BY_DEFAULT_PROPERTY, true);
        mAdapterService.registerBluetoothStateCallback(mHandler::post, this);
    }

    boolean isLeAudioOnlyGroup(BluetoothDevice device) {
        String log = "isLeAudioOnlyGroup(" + device + "): ";
        if (!Flags.leaudioAllowLeaudioOnlyDevices()) {
            Log.d(TAG, log + "missing flag leaudio_allow_leaudio_only_devices");
            return false;
        }

        CsipSetCoordinatorService csipSetCoordinatorService =
                mFactory.getCsipSetCoordinatorService();

        if (csipSetCoordinatorService == null) {
            Log.d(TAG, log + "csipSetCoordinatorService is null");
            return false;
        }

        int groupId = csipSetCoordinatorService.getGroupId(device, BluetoothUuid.CAP);
        if (groupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            Log.d(TAG, log + "group id is INVALID");
            return false;
        }

        int groupSize = csipSetCoordinatorService.getDesiredGroupSize(groupId);
        List<BluetoothDevice> groupDevices =
                csipSetCoordinatorService.getGroupDevicesOrdered(groupId);

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

        if (!Flags.leaudioAllowLeaudioOnlyDevices()) {
            Log.d(TAG, log + "missing flag leaudio_allow_leaudio_only_devices");
            return false;
        }

        if (!Utils.arrayContains(uuids, BluetoothUuid.LE_AUDIO)) {
            Log.d(TAG, log + "Device does not supports LE_AUDIO");
            return false;
        }

        int deviceType = mAdapterService.getRemoteType(device);

        if (deviceType != BluetoothDevice.DEVICE_TYPE_LE) {
            Log.d(TAG, log + "Device is not LE: " + deviceType);
            return false;
        }

        if (Utils.arrayContains(uuids, BluetoothUuid.HEARING_AID)) {
            Log.d(TAG, log + "Device supports ASHA");
            return false;
        }

        /* For no CSIS device, allow LE Only devices. */
        if (!Utils.arrayContains(uuids, BluetoothUuid.COORDINATED_SET)) {
            Log.d(TAG, log + "Device is LE_AUDIO only. (no CSIP supports)");
            return true;
        }

        // For CSIS devices it is bit harder to check.
        return isLeAudioOnlyGroup(device);
    }

    private static final String SYSPROP_HAP_ENABLED = "bluetooth.profile.hap.enabled_by_default";

    // return true if device support Hearing Access Service and it has not been manually disabled
    private boolean shouldEnableHapByDefault(BluetoothDevice device, ParcelUuid[] uuids) {
        if (!Flags.enableHapByDefault()) {
            Log.i(TAG, "shouldEnableHapByDefault: Flag is disabled");
            return false;
        }

        HapClientService hap = mFactory.getHapClientService();
        if (hap == null) {
            Log.e(TAG, "shouldEnableHapByDefault: No HapClientService");
            return false;
        }

        if (!SystemProperties.getBoolean(SYSPROP_HAP_ENABLED, true)) {
            Log.i(TAG, "shouldEnableHapByDefault: SystemProperty is overridden to false");
            return false;
        }

        return Utils.arrayContains(uuids, BluetoothUuid.HAS)
                && hap.getConnectionPolicy(device) != CONNECTION_POLICY_FORBIDDEN;
    }

    private boolean shouldBlockBroadcastForHapDevice(BluetoothDevice device, ParcelUuid[] uuids) {
        if (!Flags.leaudioDisableBroadcastForHapDevice()) {
            Log.i(TAG, "disableBroadcastForHapDevice: Flag is disabled");
            return false;
        }

        HapClientService hap = mFactory.getHapClientService();
        if (hap == null) {
            Log.e(TAG, "shouldBlockBroadcastForHapDevice: No HapClientService");
            return false;
        }

        if (!SystemProperties.getBoolean(SYSPROP_HAP_ENABLED, true)) {
            Log.i(TAG, "shouldBlockBroadcastForHapDevice: SystemProperty is overridden to false");
            return false;
        }

        return Utils.arrayContains(uuids, BluetoothUuid.HAS)
                && hap.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED;
    }

    // Policy implementation, all functions MUST be private
    private void processInitProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids) {
        String log = "processInitProfilePriorities(" + device + "): ";
        HidHostService hidService = mFactory.getHidHostService();
        A2dpService a2dpService = mFactory.getA2dpService();
        HeadsetService headsetService = mFactory.getHeadsetService();
        PanService panService = mFactory.getPanService();
        HearingAidService hearingAidService = mFactory.getHearingAidService();
        LeAudioService leAudioService = mFactory.getLeAudioService();
        CsipSetCoordinatorService csipSetCoordinatorService =
                mFactory.getCsipSetCoordinatorService();
        VolumeControlService volumeControlService = mFactory.getVolumeControlService();
        HapClientService hapClientService = mFactory.getHapClientService();
        BassClientService bcService = mFactory.getBassClientService();
        BatteryService batteryService = mFactory.getBatteryService();

        final boolean isBypassLeAudioAllowlist =
                SystemProperties.getBoolean(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, false);

        boolean isLeAudioOnly = isLeAudioOnlyDevice(device, uuids);
        boolean shouldEnableHapByDefault = shouldEnableHapByDefault(device, uuids);
        boolean isLeAudioProfileAllowed =
                (leAudioService != null)
                        && Utils.arrayContains(uuids, BluetoothUuid.LE_AUDIO)
                        && (leAudioService.getConnectionPolicy(device)
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
        if ((hidService != null)
                && (Utils.arrayContains(uuids, BluetoothUuid.HID)
                        || Utils.arrayContains(uuids, BluetoothUuid.HOGP)
                        || Utils.arrayContains(uuids, HidHostService.ANDROID_HEADTRACKER_UUID))
                && (hidService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (mAutoConnectProfilesSupported) {
                hidService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.HID_HOST, CONNECTION_POLICY_ALLOWED);
            }
            MetricsLogger.getInstance()
                    .count(
                            (Utils.arrayContains(uuids, BluetoothUuid.HID)
                                            && Utils.arrayContains(uuids, BluetoothUuid.HOGP))
                                    ? BluetoothProtoEnums.HIDH_COUNT_SUPPORT_BOTH_HID_AND_HOGP
                                    : BluetoothProtoEnums.HIDH_COUNT_SUPPORT_ONLY_HID_OR_HOGP,
                            1);
        }

        if ((headsetService != null)
                && ((Utils.arrayContains(uuids, BluetoothUuid.HSP)
                                || Utils.arrayContains(uuids, BluetoothUuid.HFP))
                        && (headsetService.getConnectionPolicy(device)
                                == CONNECTION_POLICY_UNKNOWN))) {
            if (!isDualModeAudioEnabled() && isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Dual mode device detected: clear hfp profile priority");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
            } else {
                if (mAutoConnectProfilesSupported) {
                    headsetService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        if ((a2dpService != null)
                && (Utils.arrayContains(uuids, BluetoothUuid.A2DP_SINK)
                        || Utils.arrayContains(uuids, BluetoothUuid.ADV_AUDIO_DIST))
                && (a2dpService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (!isDualModeAudioEnabled() && isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Dual mode device detected: clear A2dp profile priority");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);
            } else {
                if (mAutoConnectProfilesSupported) {
                    a2dpService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        // CSIP should be connected prior to LE Audio
        if ((csipSetCoordinatorService != null)
                && (Utils.arrayContains(uuids, BluetoothUuid.COORDINATED_SET))
                && (csipSetCoordinatorService.getConnectionPolicy(device)
                        == CONNECTION_POLICY_UNKNOWN)) {
            // Always allow CSIP during pairing process regardless of LE audio preference
            if (mAutoConnectProfilesSupported) {
                csipSetCoordinatorService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device,
                                BluetoothProfile.CSIP_SET_COORDINATOR,
                                CONNECTION_POLICY_ALLOWED);
            }
        }

        /* Make sure to connect Volume Control before LeAudio service */
        if ((volumeControlService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.VOLUME_CONTROL)
                && (volumeControlService.getConnectionPolicy(device)
                        == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Setting VCP priority");
                if (mAutoConnectProfilesSupported) {
                    volumeControlService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device,
                                    BluetoothProfile.VOLUME_CONTROL,
                                    CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO is not allowed: Clear VCP priority");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device,
                                BluetoothProfile.VOLUME_CONTROL,
                                CONNECTION_POLICY_FORBIDDEN);
            }
        }

        // If we do not have a stored priority for HFP/A2DP (all roles) then default to on.
        if ((panService != null)
                && (Utils.arrayContains(uuids, BluetoothUuid.PANU)
                        && (panService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)
                        && mAdapterService
                                .getResources()
                                .getBoolean(R.bool.config_bluetooth_pan_enable_autoconnect))) {
            if (mAutoConnectProfilesSupported) {
                panService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.PAN, CONNECTION_POLICY_ALLOWED);
            }
        }

        if ((leAudioService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.LE_AUDIO)
                && (leAudioService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.d(TAG, log + "Setting LE_AUDIO priority");
                if (mAutoConnectProfilesSupported) {
                    leAudioService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO is not allowed: Clear LE_AUDIO priority");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if ((hearingAidService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.HEARING_AID)
                && (hearingAidService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed) {
                Log.i(TAG, log + "LE_AUDIO is preferred over ASHA");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.HEARING_AID, CONNECTION_POLICY_FORBIDDEN);
            } else {
                Log.d(TAG, log + "Setting ASHA priority");
                if (mAutoConnectProfilesSupported) {
                    hearingAidService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device,
                                    BluetoothProfile.HEARING_AID,
                                    CONNECTION_POLICY_ALLOWED);
                }
            }
        }

        if ((hapClientService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.HAS)
                && (hapClientService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            Log.d(TAG, log + "Setting HAP priority");
            if (isLeAudioProfileAllowed) {
                if (mAutoConnectProfilesSupported) {
                    hapClientService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_ALLOWED);
                }
            } else {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.HAP_CLIENT, CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if ((bcService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.BASS)
                && (bcService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            if (isLeAudioProfileAllowed && !shouldBlockBroadcastForHapDevice(device, uuids)) {
                Log.d(TAG, log + "Setting BASS priority");
                if (mAutoConnectProfilesSupported) {
                    bcService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
                } else {
                    mAdapterService
                            .getDatabase()
                            .setProfileConnectionPolicy(
                                    device,
                                    BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                                    CONNECTION_POLICY_ALLOWED);
                }
            } else {
                Log.d(TAG, log + "LE_AUDIO Broadcast is not allowed: Clear BASS priority");
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device,
                                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                                CONNECTION_POLICY_FORBIDDEN);
            }
        }

        if ((batteryService != null)
                && Utils.arrayContains(uuids, BluetoothUuid.BATTERY)
                && (batteryService.getConnectionPolicy(device) == CONNECTION_POLICY_UNKNOWN)) {
            Log.d(TAG, log + "Setting BATTERY priority");
            if (mAutoConnectProfilesSupported) {
                batteryService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            } else {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(
                                device, BluetoothProfile.BATTERY, CONNECTION_POLICY_ALLOWED);
            }
        }
    }

    void handleLeAudioOnlyDeviceAfterCsipConnect(BluetoothDevice device) {
        String log = "handleLeAudioOnlyDeviceAfterCsipConnect(" + device + "): ";

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService == null
                || (leAudioService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                || !mAdapterService.isProfileSupported(device, BluetoothProfile.LE_AUDIO)) {
            Log.d(TAG, log + "Nothing to do");
            return;
        }

        List<BluetoothDevice> groupDevices = new ArrayList<>();
        boolean isAnyOtherGroupMemberAllowed = false;

        CsipSetCoordinatorService csipSetCoordinatorService =
                mFactory.getCsipSetCoordinatorService();
        if (csipSetCoordinatorService != null) {
            /* Since isLeAudioOnlyGroup return true it means csipSetCoordinatorService is valid */
            groupDevices =
                    csipSetCoordinatorService.getGroupDevicesOrdered(
                            csipSetCoordinatorService.getGroupId(device, BluetoothUuid.CAP));

            for (BluetoothDevice dev : groupDevices) {
                if (leAudioService.getConnectionPolicy(dev) == CONNECTION_POLICY_ALLOWED) {
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

        if (!isAnyOtherGroupMemberAllowed && !isLeAudioOnlyGroup) {
            /* Log no needed as above function will log on error. */
            return;
        }

        for (BluetoothDevice dev : groupDevices) {
            if (leAudioService.getConnectionPolicy(dev) != CONNECTION_POLICY_ALLOWED) {
                /* Setting LeAudio service as allowed is sufficient,
                 * because other LeAudio services e.g. VC will
                 * be enabled by LeAudio service automatically.
                 */
                Log.d(TAG, log + "...." + dev);
                leAudioService.setConnectionPolicy(dev, CONNECTION_POLICY_ALLOWED);
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
                        handleLeAudioOnlyDeviceAfterCsipConnect(device);
            }
            connectOtherProfile(device);
        } else if (nextState == STATE_DISCONNECTED) {
            if (prevState == STATE_CONNECTING || prevState == STATE_DISCONNECTING) {
                mDatabaseManager.setDisconnection(device, profile);
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

        mDatabaseManager.setConnection(device, profile);

        boolean isDualMode = isDualModeAudioEnabled();
        Log.d(TAG, log + "isDualMode=" + isDualMode);

        if (profile == BluetoothProfile.LE_AUDIO) {
            A2dpService a2dpService = mFactory.getA2dpService();
            HeadsetService hsService = mFactory.getHeadsetService();
            LeAudioService leAudioService = mFactory.getLeAudioService();
            HearingAidService hearingAidService = mFactory.getHearingAidService();

            if (leAudioService == null) {
                Log.d(TAG, log + "LeAudioService is null");
                return;
            }
            List<BluetoothDevice> leAudioActiveGroupDevices =
                    leAudioService.getGroupDevices(leAudioService.getGroupId(device));

            // Disable classic audio profiles and ASHA for all group devices as lead can change
            for (BluetoothDevice activeGroupDevice : leAudioActiveGroupDevices) {
                if (hsService != null && !isDualMode) {
                    Log.d(TAG, log + "Disable HFP for the LE_AUDIO group: " + activeGroupDevice);
                    hsService.setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
                if (a2dpService != null && !isDualMode) {
                    Log.d(TAG, log + "Disable A2DP for the LE_AUDIO group: " + activeGroupDevice);
                    a2dpService.setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
                if (hearingAidService != null) {
                    Log.d(TAG, log + "Disable ASHA for the LE_AUDIO group: " + activeGroupDevice);
                    hearingAidService.setConnectionPolicy(
                            activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
                }
            }
        }
    }

    private void processDeviceConnected(BluetoothDevice device) {
        Log.d(TAG, "processDeviceConnected(" + device + ")");
        mDatabaseManager.setConnection(device);
    }

    private boolean handleAllProfilesDisconnected(BluetoothDevice device) {
        boolean atLeastOneProfileConnectedForDevice = false;
        boolean allProfilesEmpty = true;
        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();
        LeAudioService leAudioService = mFactory.getLeAudioService();
        CsipSetCoordinatorService csipSetCoordinatorService =
                mFactory.getCsipSetCoordinatorService();

        if (hsService != null) {
            List<BluetoothDevice> hsConnDevList = hsService.getConnectedDevices();
            allProfilesEmpty &= hsConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= hsConnDevList.contains(device);
        }
        if (a2dpService != null) {
            List<BluetoothDevice> a2dpConnDevList = a2dpService.getConnectedDevices();
            allProfilesEmpty &= a2dpConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= a2dpConnDevList.contains(device);
        }
        if (csipSetCoordinatorService != null) {
            List<BluetoothDevice> csipConnDevList = csipSetCoordinatorService.getConnectedDevices();
            allProfilesEmpty &= csipConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= csipConnDevList.contains(device);
        }
        if (panService != null) {
            List<BluetoothDevice> panConnDevList = panService.getConnectedDevices();
            allProfilesEmpty &= panConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= panConnDevList.contains(device);
        }
        if (leAudioService != null) {
            List<BluetoothDevice> leAudioConnDevList = leAudioService.getConnectedDevices();
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
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            Log.e(TAG, log + "Bluetooth is not ON. Exiting autoConnect");
            return;
        }
        if (mAdapterService.isQuietModeEnabled()) {
            Log.i(TAG, log + "Bluetooth is in quiet mode. Cancelling autoConnect");
            return;
        }

        final BluetoothDevice mostRecentlyActiveA2dpDevice =
                mDatabaseManager.getMostRecentlyConnectedA2dpDevice();
        if (mostRecentlyActiveA2dpDevice != null) {
            Log.d(TAG, log + "Attempting most recent A2DP device" + mostRecentlyActiveA2dpDevice);
            autoConnectHeadset(mostRecentlyActiveA2dpDevice);
            autoConnectA2dp(mostRecentlyActiveA2dpDevice);
            autoConnectHidHost(mostRecentlyActiveA2dpDevice);
            return;
        }

        if (Flags.autoConnectOnMultipleHfpWhenNoA2dpDevice()) {
            final List<BluetoothDevice> mostRecentlyConnectedHfpDevices =
                    mDatabaseManager.getMostRecentlyActiveHfpDevices();
            for (BluetoothDevice hfpDevice : mostRecentlyConnectedHfpDevices) {
                Log.d(TAG, log + "Attempting HFP device" + hfpDevice);
                autoConnectHeadset(hfpDevice);
            }
            if (mostRecentlyConnectedHfpDevices.size() == 0) {
                Log.d(TAG, log + "No hfp device to connect");
            }
            return;
        }
        Log.d(TAG, log + "Multi HFP is not enabled");

        // Try to autoConnect with Hfp only if there was no a2dp valid device
        final BluetoothDevice mostRecentlyConnectedHfpDevice =
                mDatabaseManager.getMostRecentlyActiveHfpDevice();
        if (mostRecentlyConnectedHfpDevice != null) {
            Log.d(TAG, log + "Attempting most recent HFP device" + mostRecentlyConnectedHfpDevice);
            autoConnectHeadset(mostRecentlyConnectedHfpDevice);
            return;
        }
        Log.i(TAG, log + "No device to reconnect to");
    }

    private void autoConnectA2dp(BluetoothDevice device) {
        String log = "autoConnectA2dp(" + device + "): ";
        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            Log.w(TAG, log + "Failed to connect, A2DP service is null");
            return;
        }
        int connectionPolicy = a2dpService.getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped A2DP auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting A2DP");
        a2dpService.connect(device);
    }

    private void autoConnectHeadset(BluetoothDevice device) {
        String log = "autoConnectHeadset(" + device + "): ";
        final HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            Log.w(TAG, log + "Failed to connect, HFP service is null");
            return;
        }
        int connectionPolicy = hsService.getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped HFP auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting HFP");
        hsService.connect(device);
    }

    private void autoConnectHidHost(BluetoothDevice device) {
        String log = "autoConnectHidHost(" + device + "): ";
        final HidHostService hidHostService = mFactory.getHidHostService();
        if (hidHostService == null) {
            Log.w(TAG, log + "Failed to connect, HID service is null");
            return;
        }
        int connectionPolicy = hidHostService.getConnectionPolicy(device);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            Log.d(TAG, log + "Skipped HID auto-connect. connectionPolicy=" + connectionPolicy);
            return;
        }
        Log.d(TAG, log + "Connecting HID");
        hidHostService.connect(device);
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
        if (currentState != BluetoothAdapter.STATE_ON) {
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

        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();
        LeAudioService leAudioService = mFactory.getLeAudioService();
        CsipSetCoordinatorService csipSetCoordinatorService =
                mFactory.getCsipSetCoordinatorService();
        VolumeControlService volumeControlService = mFactory.getVolumeControlService();
        BatteryService batteryService = mFactory.getBatteryService();
        HidHostService hidHostService = mFactory.getHidHostService();
        BassClientService bcService = mFactory.getBassClientService();
        HapClientService hapClientService = mFactory.getHapClientService();

        if (hsService != null) {
            if (!mHeadsetRetrySet.contains(device)
                    && (hsService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (hsService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying HFP connection");
                mHeadsetRetrySet.add(device);
                hsService.connect(device);
            }
        }
        if (a2dpService != null) {
            if (!mA2dpRetrySet.contains(device)
                    && (a2dpService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (a2dpService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying A2DP connection");
                mA2dpRetrySet.add(device);
                a2dpService.connect(device);
            }
        }
        if (panService != null) {
            List<BluetoothDevice> panConnDevList = panService.getConnectedDevices();
            // TODO: the panConnDevList.isEmpty() check below should be removed once
            // Multi-PAN is supported.
            if (panConnDevList.isEmpty()
                    && (panService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (panService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying PAN connection");
                panService.connect(device);
            }
        }
        if (leAudioService != null) {
            List<BluetoothDevice> leAudioConnDevList = leAudioService.getConnectedDevices();
            if (!leAudioConnDevList.contains(device)
                    && (leAudioService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (leAudioService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying LE_AUDIO connection");
                leAudioService.connect(device);
            }
        }
        if (csipSetCoordinatorService != null) {
            List<BluetoothDevice> csipConnDevList = csipSetCoordinatorService.getConnectedDevices();
            if (!csipConnDevList.contains(device)
                    && (csipSetCoordinatorService.getConnectionPolicy(device)
                            == CONNECTION_POLICY_ALLOWED)
                    && (csipSetCoordinatorService.getConnectionState(device)
                            == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying CSIP connection");
                csipSetCoordinatorService.connect(device);
            }
        }
        if (volumeControlService != null) {
            List<BluetoothDevice> vcConnDevList = volumeControlService.getConnectedDevices();
            if (!vcConnDevList.contains(device)
                    && (volumeControlService.getConnectionPolicy(device)
                            == CONNECTION_POLICY_ALLOWED)
                    && (volumeControlService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying VCP connection");
                volumeControlService.connect(device);
            }
        }
        if (batteryService != null) {
            List<BluetoothDevice> connectedDevices = batteryService.getConnectedDevices();
            if (!connectedDevices.contains(device)
                    && (batteryService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (batteryService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying BATTERY connection");
                batteryService.connect(device);
            }
        }
        if (hidHostService != null) {
            if ((hidHostService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (hidHostService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying HID connection");
                hidHostService.connect(device);
            }
        }
        if (bcService != null) {
            List<BluetoothDevice> connectedDevices = bcService.getConnectedDevices();
            if (!connectedDevices.contains(device)
                    && (bcService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED)
                    && (bcService.getConnectionState(device) == STATE_DISCONNECTED)) {
                Log.d(TAG, log + "Retrying BASS connection");
                bcService.connect(device);
            }
        }
        if (Flags.connectHapOnOtherProfileConnect()) {
            if (hapClientService != null) {
                List<BluetoothDevice> connectedDevices = hapClientService.getConnectedDevices();
                if (!connectedDevices.contains(device)
                        && (hapClientService.getConnectionPolicy(device)
                                == CONNECTION_POLICY_ALLOWED)
                        && (hapClientService.getConnectionState(device) == STATE_DISCONNECTED)) {
                    Log.d(TAG, log + "Retrying HAP connection");
                    hapClientService.connect(device);
                }
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

    /**
     * Resets the service connection policies for the device. This is called when the {@link
     * BluetoothDevice#removeBond} is requested for the device.
     *
     * @param device is the remote device whose services have been discovered
     */
    void onRemoveBondRequest(BluetoothDevice device) {
        if (!Flags.preventServiceConnectionsOnRemoveBond()) {
            return;
        }

        Log.d(TAG, "onRemoveBondRequest(" + device + "): Disabling all profiles");
        // Don't allow any profiles to connect to the device.
        for (int profileId = BluetoothProfile.HEADSET;
                profileId < BluetoothProfile.MAX_PROFILE_ID;
                profileId++) {
            if (mAdapterService.getDatabase().getProfileConnectionPolicy(device, profileId)
                    == CONNECTION_POLICY_ALLOWED) {
                mAdapterService
                        .getDatabase()
                        .setProfileConnectionPolicy(device, profileId, CONNECTION_POLICY_FORBIDDEN);
            }
        }
    }
}
