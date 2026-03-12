/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.getProfileName;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_DOCKED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_SLATE;

import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_OFF;
import static com.android.bluetooth.btservice.AdapterSuspendStateMachine.MSG_SCREEN_ON;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.flags.Flags;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AdapterSuspend {
    private static final String TAG = Util.BT_PREFIX + AdapterSuspend.class.getSimpleName();

    // Event mask bits corresponding to specific HCI events
    // as defined in Bluetooth core v5.4, Vol 4, Part E, 7.3.1.
    private static final long MASK_DISCONNECT_CMPLT = 1 << 4;
    private static final long MASK_MODE_CHANGE = 1 << 19;

    private static final int DEVICE_STATE_NONE = 0;
    private static final int DEVICE_STATE_DOCKED = 1;
    private static final int DEVICE_STATE_LID_CLOSED = 2;
    private static final int DEVICE_STATE_LID_OPEN = 3;
    private static final int DEVICE_STATE_TABLET = 4;

    // Constants for suspend state sent to other services
    public static final int AWAKE = 0;
    public static final int SHALLOW_SLEEP = 1;
    public static final int DEEP_SLEEP = 2;

    enum SuspendTasks {
        PROFILE_DISCONNECTION,
        ADVERTISEMENT,
        ACL_DISCONNECTION,
    }

    static final String BLUETOOTH_SUSPEND_DISCONNECT_ACL =
            "bluetooth.power.suspend.disconnect_acl.enabled";

    static final String BLUETOOTH_SUSPEND_SCAN_MODE_NONE =
            "bluetooth.power.suspend.scan_mode_none.enabled";

    static final String BLUETOOTH_SUSPEND_STOP_LE_SCAN =
            "bluetooth.power.suspend.stop_le_scan.enabled";

    static final String BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT =
            "bluetooth.power.suspend.pause_advertisement.enabled";

    private static final int[] AUDIO_PROFILES = {
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
        BluetoothProfile.HEARING_AID,
        BluetoothProfile.LE_AUDIO
    };

    private final AdapterService mAdapterService;
    private final AdapterNativeInterface mAdapterNativeInterface;
    private final DeviceStateManager mDeviceStateManager;
    private final AdapterSuspendStateMachine mSuspendStateMachine;
    private final Handler mHandler;

    private final boolean mDisconnectAclOnSuspend;
    private final boolean mScanModeNoneOnSuspend;
    private final boolean mStopLeScanOnSuspend;
    private final boolean mPauseAdvertisementOnSuspend;

    private List<BluetoothDevice> mLastActiveAudioDevices = new ArrayList<>();

    private final Map<Integer, Set<BluetoothDevice>> mDisconnectProfileDevices = new HashMap<>();
    private boolean mAllowWakeByHid;
    private EnumSet<SuspendTasks> mDelayedSuspendTasks = EnumSet.noneOf(SuspendTasks.class);

    private final DeviceStateManager.DeviceStateCallback mDeviceStateCallback =
            new DeviceStateManager.DeviceStateCallback() {
                @Override
                public void onDeviceStateChanged(@NonNull DeviceState state) {
                    int nextState = DEVICE_STATE_NONE;
                    if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_DOCKED)) {
                        nextState = DEVICE_STATE_DOCKED;
                    } else if (state.hasProperty(
                            PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_CLOSED)) {
                        nextState = DEVICE_STATE_LID_CLOSED;
                    } else if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_OPEN)) {
                        nextState = DEVICE_STATE_LID_OPEN;
                    } else if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_SLATE)) {
                        nextState = DEVICE_STATE_TABLET;
                    } else {
                        Log.w(TAG, "Device state does not have a valid property");
                    }

                    switch (nextState) {
                        case DEVICE_STATE_LID_OPEN -> {
                            Log.d(TAG, "Lid open, screen on");
                            mSuspendStateMachine.setTabletMode(false);
                            mSuspendStateMachine.sendMessage(MSG_SCREEN_ON);
                        }
                        case DEVICE_STATE_DOCKED -> mSuspendStateMachine.setTabletMode(false);
                        case DEVICE_STATE_TABLET -> mSuspendStateMachine.setTabletMode(true);
                        case DEVICE_STATE_LID_CLOSED -> {
                            Log.d(TAG, "Lid closed");
                            mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_CLOSED);
                        }
                        default -> Log.d(TAG, "Unknown state " + nextState);
                    }
                }
            };

    AdapterSuspend(
            AdapterService adapterService,
            Looper looper,
            DeviceStateManager deviceStateManager,
            boolean disconnectAcl,
            boolean scanModeNone,
            boolean stopLeScan,
            boolean pauseAdvertisement) {
        mAdapterService = requireNonNull(adapterService);
        mAdapterNativeInterface = requireNonNull(adapterService.getNative());
        mDisconnectAclOnSuspend = disconnectAcl;
        mScanModeNoneOnSuspend = scanModeNone;
        mStopLeScanOnSuspend = stopLeScan;
        mPauseAdvertisementOnSuspend = pauseAdvertisement;

        mHandler = new Handler(requireNonNull(looper));
        mSuspendStateMachine = new AdapterSuspendStateMachine(adapterService, this, looper);
        mDeviceStateManager = requireNonNull(deviceStateManager);
        mDeviceStateManager.registerCallback(mHandler::post, mDeviceStateCallback);
    }

    void onDisplayChanged(boolean isScreenOn) {
        mSuspendStateMachine.dispatchMessage(isScreenOn ? MSG_SCREEN_ON : MSG_SCREEN_OFF);
    }

    public boolean isPauseAdvertisementEnabled() {
        return mPauseAdvertisementOnSuspend;
    }

    void aclDisconnected(BluetoothDevice device, int transport) {
        if (!mDelayedSuspendTasks.contains(SuspendTasks.ACL_DISCONNECTION)) {
            return;
        }

        Log.d(TAG, "Device ACL disconnected=" + device);
        Set<RemoteDevices.AclLinkSpec> connectedDevices =
                mAdapterService.getRemoteDevices().getConnectedDevices();
        if (connectedDevices.isEmpty()) {
            onSuspendTaskCompleted(SuspendTasks.ACL_DISCONNECTION);
        } else {
            Log.d(TAG, "Remaining device ACLs to disconnect=" + connectedDevices);
        }
    }

    void profileConnectionStateChanged(
            int profile, BluetoothDevice device, int fromState, int toState) {
        if (!mDisconnectProfileDevices.containsKey(profile)
                || toState != BluetoothProfile.STATE_DISCONNECTED) {
            return;
        }

        Set<BluetoothDevice> devices = mDisconnectProfileDevices.get(profile);
        if (!devices.contains(device)) {
            return;
        }

        Log.d(TAG, "Device disconnected=" + device + ", profile=" + getProfileName(profile));
        devices.remove(device);
        if (devices.isEmpty()) {
            mDisconnectProfileDevices.remove(profile);
            if (mDisconnectProfileDevices.isEmpty()) {
                disconnectAllAcls();
                onSuspendTaskCompleted(SuspendTasks.PROFILE_DISCONNECTION);
            }
        } else {
            Log.d(TAG, "Remaining devices to disconnect=" + devices);
        }
    }

    void cleanup() {
        mDeviceStateManager.unregisterCallback(mDeviceStateCallback);
    }

    void handleSuspend(boolean allowWakeByHid) {
        long mask = MASK_DISCONNECT_CMPLT | MASK_MODE_CHANGE;
        long leMask = 0;

        mDelayedSuspendTasks = EnumSet.noneOf(SuspendTasks.class);
        mAllowWakeByHid = allowWakeByHid;
        if (mScanModeNoneOnSuspend) {
            mAdapterService.setSuspendState(true /* suspend */);
        }

        if (mStopLeScanOnSuspend) {
            final var scanController = mAdapterService.getBluetoothScanController();
            if (scanController != null) {
                scanController.doOnScanThread(
                        () -> scanController.onSystemSuspendChanged(true /* suspend */));
            }
        }

        if (mDisconnectAclOnSuspend) {
            mAdapterService
                    .getLeAudioService()
                    .ifPresent(leAudio -> leAudio.setSystemSuspended(true));
            if (!Flags.leHidConnectionPolicySuspend()) {
                mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);
                mAdapterNativeInterface.clearFilterAcceptList();
            } else {
                mAdapterNativeInterface.setSuspendState(true);
            }
            mAdapterNativeInterface.clearEventFilter();
            storeActiveAudioDevices();
            storeDisconnectProfileDevices();

            if (!mDisconnectProfileDevices.isEmpty()) {
                Log.d(TAG, "Disconnect profiles for=" + mDisconnectProfileDevices);
                mDelayedSuspendTasks.add(SuspendTasks.PROFILE_DISCONNECTION);
                disconnectProfiles();
            } else {
                manageHidHostConnection(true);
                disconnectAllAcls();
            }
        }

        if (mPauseAdvertisementOnSuspend) {
            mAdapterService
                    .getGattService()
                    .ifPresent(
                            gatt -> {
                                mDelayedSuspendTasks.add(SuspendTasks.ADVERTISEMENT);
                                gatt.getAdvertiseManager().enterSuspend();
                            });
        }

        if (!isSuspendReady()) {
            mAdapterService.acquireWakeLock("bt_suspend_ready");
        }
    }

    void handleResume() {
        long mask = 0;
        long leMask = 0;
        if (mDisconnectAclOnSuspend) {
            if (!Flags.leHidConnectionPolicySuspend()) {
                mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);
                mAdapterNativeInterface.restoreFilterAcceptList();
                mAdapterNativeInterface.clearEventFilter();
            } else {
                mAdapterNativeInterface.setSuspendState(false);
                manageHidHostConnection(false);
            }
            mAdapterService
                    .getLeAudioService()
                    .ifPresent(leAudio -> leAudio.setSystemSuspended(false));
            for (BluetoothDevice device : mLastActiveAudioDevices) {
                Log.i(TAG, "Reconnect to=" + device);
                mAdapterService.connectAllEnabledProfiles(device);
            }
            mLastActiveAudioDevices.clear();
            if (!mDisconnectProfileDevices.isEmpty()) {
                Log.w(TAG, "Device list to disconnect is not empty=" + mDisconnectProfileDevices);
                mDisconnectProfileDevices.clear();
            }
        }

        if (mStopLeScanOnSuspend) {
            final var scanController = mAdapterService.getBluetoothScanController();
            if (scanController != null) {
                scanController.doOnScanThread(
                        () -> scanController.onSystemSuspendChanged(false /* suspend */));
            }
        }

        if (mScanModeNoneOnSuspend) {
            mAdapterService.setSuspendState(false /* suspend */);
        }

        if (mPauseAdvertisementOnSuspend) {
            mAdapterService
                    .getGattService()
                    .ifPresent(gatt -> gatt.getAdvertiseManager().exitSuspend());
        }
    }

    private void storeActiveAudioDevices() {
        // handleSuspend can be called more than once in some condition. If so, we shouldn't store
        // the devices the second time to handle the possibility where they have been disconnected.
        if (!mLastActiveAudioDevices.isEmpty()) {
            Log.d(TAG, "Audio devices are already stored=" + mLastActiveAudioDevices);
            return;
        }

        for (int audioProfile : AUDIO_PROFILES) {
            List<BluetoothDevice> devices = mAdapterService.getActiveDevices(audioProfile);
            // getActiveDevices might return a list containing null elements. Filter them first.
            devices = devices.stream().filter(Objects::nonNull).collect(Collectors.toList());
            if (!devices.isEmpty()) {
                mLastActiveAudioDevices = devices;
                var profileName = getProfileName(audioProfile);
                Log.i(TAG, "Store " + devices + " for reconnection for profile=" + profileName);
                break;
            }
        }
    }

    private boolean isLeTransport(BluetoothDevice dev) {
        return mAdapterService
                .getHidHostService()
                .map(s -> s.getPreferredTransport(dev) == BluetoothDevice.TRANSPORT_LE)
                .orElse(false);
    }

    private void storeDisconnectProfileDevice(int profile) {
        Log.i(TAG, "Disconnect devices for profile=" + getProfileName(profile));
        Set<BluetoothDevice> devices =
                mAdapterService.getConnectedDevicesForProfile(profile).stream()
                        .filter(Objects::nonNull)
                        .collect(toCollection(HashSet::new));

        // For HID, we only care about LE devices
        if (profile == BluetoothProfile.HID_HOST) {
            devices.removeIf(device -> !isLeTransport(device));
        }

        if (!devices.isEmpty()) {
            mDisconnectProfileDevices.put(profile, devices);
        }
    }

    private void storeDisconnectProfileDevices() {
        if (!mDisconnectProfileDevices.isEmpty()) {
            Log.w(TAG, "Disconnect devices have been stored=" + mDisconnectProfileDevices);
            return;
        }

        storeDisconnectProfileDevice(BluetoothProfile.HEARING_AID);
        if (Flags.leHidConnectionPolicySuspend()) {
            storeDisconnectProfileDevice(BluetoothProfile.HID_HOST);
        }
    }

    /**
     * This function is to update the state of Bluetooth wakelock and send message to state machine.
     */
    void updateWakeLockState(boolean enabled) {
        Log.d(TAG, "Wakelock state=" + enabled);
        if (enabled) {
            mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_WAKELOCK_ACQUIRED);
        } else {
            mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_WAKELOCK_RELEASED);
        }
    }

    private void disconnectProfiles() {
        disconnectAsha();
        manageHidHostConnection(true);
    }

    private void disconnectAsha() {
        Set<BluetoothDevice> devices =
                mDisconnectProfileDevices.getOrDefault(
                        BluetoothProfile.HEARING_AID, Collections.emptySet());
        Log.d(TAG, "Disconnect ASHA for=" + devices);
        for (BluetoothDevice device : devices) {
            if (Flags.addNewLocalDisconnectReason()) {
                mAdapterService.disconnectAllEnabledProfiles(
                        device, BluetoothStatusCodes.ERROR_DISCONNECT_REASON_ADAPTER_SUSPEND);
            } else {
                mAdapterService.disconnectAllEnabledProfiles(device, BluetoothStatusCodes.SUCCESS);
            }
        }
    }

    private void onSuspendTaskCompleted(SuspendTasks task) {
        if (isSuspendReady()) {
            Log.w(TAG, "Task " + task + " is completed after wakelock was released");
            return;
        }
        if (!mDelayedSuspendTasks.contains(task)) {
            Log.w(TAG, "Task " + task + " is not scheduled");
            return;
        }

        mDelayedSuspendTasks.remove(task);
        Log.v(TAG, "Suspend remaining tasks=" + mDelayedSuspendTasks);
        if (isSuspendReady()) {
            Log.i(TAG, "suspend ready");
            mAdapterService.releaseWakeLock("bt_suspend_ready");
        }
    }

    private void disconnectAllAcls() {
        if (!Flags.leHidConnectionPolicySuspend()) {
            mAdapterNativeInterface.disconnectAllAcls();
            if (mAllowWakeByHid) {
                mAdapterNativeInterface.allowWakeByHid();
            }
            return;
        }

        Set<RemoteDevices.AclLinkSpec> connectedDevices =
                mAdapterService.getRemoteDevices().getConnectedDevices();
        if (!connectedDevices.isEmpty()) {
            Log.i(TAG, "disconnect acls for devices: " + connectedDevices);
            mDelayedSuspendTasks.add(SuspendTasks.ACL_DISCONNECTION);
            mAdapterNativeInterface.disconnectAllAcls();
        }
    }

    private void manageHidHostConnection(boolean suspending) {
        if (!Flags.leHidConnectionPolicySuspend()) {
            return;
        }

        if (suspending) {
            Log.i(TAG, "Configure allow wake by HID " + mAllowWakeByHid);
            int suspendState = mAllowWakeByHid ? SHALLOW_SLEEP : DEEP_SLEEP;
            mAdapterService
                    .getHidHostService()
                    .ifPresent(
                            profile -> {
                                profile.onSuspendStateChange(suspendState);
                            });
            if (mAllowWakeByHid) {
                mAdapterNativeInterface.allowWakeByHid();
            }
        } else {
            mAdapterService
                    .getHidHostService()
                    .ifPresent(
                            profile -> {
                                profile.onSuspendStateChange(AWAKE);
                            });
            mAdapterNativeInterface.clearEventFilter();
        }
    }

    /**
     * Called by the advertising thread to notify that it has finished the preparation for suspend.
     */
    public void advertiseSuspendReady() {
        if (Util.isInstrumentationTestMode()) {
            onSuspendTaskCompleted(SuspendTasks.ADVERTISEMENT);
            return;
        }

        mHandler.post(() -> onSuspendTaskCompleted(SuspendTasks.ADVERTISEMENT));
    }

    private boolean isSuspendReady() {
        return mDelayedSuspendTasks.isEmpty();
    }

    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(TAG);
        writer.println("  Disconnect ACL on suspend=" + mDisconnectAclOnSuspend);
        writer.println("  Set scan mode to none on suspend=" + mScanModeNoneOnSuspend);
        writer.println("  Stop Le scan on suspend=" + mStopLeScanOnSuspend);
        writer.println("  Pause advertisement on suspend=" + mPauseAdvertisementOnSuspend);
        writer.println();
        mSuspendStateMachine.dump(fd, writer, args);
    }
}
