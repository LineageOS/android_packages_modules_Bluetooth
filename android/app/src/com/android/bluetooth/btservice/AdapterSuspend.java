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

import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;
import static android.bluetooth.BluetoothProfile.getProfileName;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_DOCKED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_SLATE;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Display;

import com.android.bluetooth.Util;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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

    enum SuspendTasks {
        PROFILE_DISCONNECTION,
        ADVERTISEMENT,
    }

    @VisibleForTesting
    static final String BLUETOOTH_SUSPEND_DISCONNECT_ACL =
            "bluetooth.power.suspend.disconnect_acl.enabled";

    @VisibleForTesting
    static final String BLUETOOTH_SUSPEND_SCAN_MODE_NONE =
            "bluetooth.power.suspend.scan_mode_none.enabled";

    static final String BLUETOOTH_SUSPEND_STOP_LE_SCAN =
            "bluetooth.power.suspend.stop_le_scan.enabled";

    @VisibleForTesting
    static final String BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT =
            "bluetooth.power.suspend.pause_advertisement.enabled";

    private static final int[] AUDIO_PROFILES = {
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
        BluetoothProfile.HEARING_AID,
        BluetoothProfile.LE_AUDIO
    };

    private static final int[] DISCONNECT_PROFILES = {BluetoothProfile.HEARING_AID};

    private final AdapterService mAdapterService;
    private final AdapterNativeInterface mAdapterNativeInterface;
    private final DeviceStateManager mDeviceStateManager;
    private final PowerManager mPowerManager;
    private final AdapterSuspendStateMachine mSuspendStateMachine;
    private final DisplayManager mDisplayManager;
    private final Handler mHandler;

    private final boolean mDisconnectAclOnSuspend;
    private final boolean mScanModeNoneOnSuspend;
    private final boolean mStopLeScanOnSuspend;
    private final boolean mPauseAdvertisementOnSuspend;

    private int mScanModeOnLastSuspend;
    private List<BluetoothDevice> mLastActiveAudioDevices = new ArrayList<>();

    private final Set<BluetoothDevice> mDisconnectProfileDevices = new HashSet<>();
    private boolean mAllowWakeByHid;
    private EnumSet<SuspendTasks> mDelayedSuspendTasks = EnumSet.noneOf(SuspendTasks.class);

    @VisibleForTesting
    void setLastScanModeForTest(int val) {
        mScanModeOnLastSuspend = val;
    }

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
                            mSuspendStateMachine.sendMessage(
                                    AdapterSuspendStateMachine.MSG_SCREEN_ON);
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

    private boolean isScreenOn() {
        Display[] displays = mDisplayManager.getDisplays();

        if (displays == null) {
            return false;
        }

        return Arrays.stream(displays).anyMatch(display -> display.getState() == Display.STATE_ON);
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    boolean interactive = mPowerManager.isInteractive();
                    boolean screenOn = isScreenOn();
                    Log.d(
                            TAG,
                            ("Display:" + displayId)
                                    + (" Screen=" + screenOn)
                                    + (" Interactive=" + interactive));

                    if (Flags.stopLeScanSystemSuspend()) {
                        final var scanController = mAdapterService.getBluetoothScanController();
                        if (scanController != null) {
                            scanController.doOnScanThread(
                                    () -> scanController.onDisplayChanged(screenOn));
                        }
                    }
                    if (interactive != screenOn) {
                        return;
                    }
                    if (screenOn) {
                        mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_SCREEN_ON);
                    } else {
                        mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_SCREEN_OFF);
                    }
                }
            };

    AdapterSuspend(
            AdapterService adapterService,
            Looper looper,
            DeviceStateManager deviceStateManager,
            PowerManager powerManager,
            DisplayManager displayManager) {
        mAdapterService = requireNonNull(adapterService);
        mAdapterNativeInterface = requireNonNull(adapterService.getNative());
        mPowerManager = requireNonNull(powerManager);

        mSuspendStateMachine =
                new AdapterSuspendStateMachine(adapterService, this, requireNonNull(looper));
        mDisplayManager = requireNonNull(displayManager);
        mHandler = new Handler(looper);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        mDeviceStateManager = requireNonNull(deviceStateManager);
        mDeviceStateManager.registerCallback(mHandler::post, mDeviceStateCallback);

        mDisconnectAclOnSuspend =
                SystemProperties.getBoolean(BLUETOOTH_SUSPEND_DISCONNECT_ACL, false);
        mScanModeNoneOnSuspend =
                SystemProperties.getBoolean(BLUETOOTH_SUSPEND_SCAN_MODE_NONE, false);
        mStopLeScanOnSuspend = SystemProperties.getBoolean(BLUETOOTH_SUSPEND_STOP_LE_SCAN, false);
        mPauseAdvertisementOnSuspend =
                SystemProperties.getBoolean(BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT, false);
    }

    void profileConnectionStateChanged(
            int profile, BluetoothDevice device, int fromState, int toState) {
        // The profile in this function matches with profiles in DISCONNECT_PROFILES.
        // Currently, only the ASHA hearing aid device needs to be disconnected by profile.
        // The other devices are disconnected by disconnecting ACLs. There is no need to
        // track profile connection state.
        if (profile == BluetoothProfile.HEARING_AID
                && toState == BluetoothProfile.STATE_DISCONNECTED
                && mDisconnectProfileDevices.contains(device)) {
            Log.d(TAG, "Device disconnected=" + device);
            mDisconnectProfileDevices.remove(device);
            if (mDisconnectProfileDevices.isEmpty()) {
                disconnectAllAcls();
                onSuspendTaskCompleted(SuspendTasks.PROFILE_DISCONNECTION);
            } else {
                Log.d(TAG, "Remaining devices to disconnect=" + mDisconnectProfileDevices);
            }
        }
    }

    void cleanup() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        mDeviceStateManager.unregisterCallback(mDeviceStateCallback);
    }

    void handleSuspend(boolean allowWakeByHid) {
        long mask = MASK_DISCONNECT_CMPLT | MASK_MODE_CHANGE;
        long leMask = 0;

        mDelayedSuspendTasks = EnumSet.noneOf(SuspendTasks.class);
        mAllowWakeByHid = allowWakeByHid;
        if (mScanModeNoneOnSuspend) {
            if (Flags.adapterSuspendDiscoverability()) {
                mAdapterService.setSuspendState(true /* suspend */);
            } else if (mScanModeOnLastSuspend != SCAN_MODE_NONE) {
                mScanModeOnLastSuspend = mAdapterService.getScanMode();
                mAdapterService.setScanMode(SCAN_MODE_NONE, "handleSuspend");
            }
        }

        if (Flags.stopLeScanSystemSuspend() && mStopLeScanOnSuspend) {
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
            mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);
            mAdapterNativeInterface.clearEventFilter();
            mAdapterNativeInterface.clearFilterAcceptList();
            storeActiveAudioDevices();
            getDisconnectProfileDevices();

            if (!mDisconnectProfileDevices.isEmpty()) {
                Log.d(TAG, "Disconnect profiles for=" + mDisconnectProfileDevices);
                mDelayedSuspendTasks.add(SuspendTasks.PROFILE_DISCONNECTION);
                disconnectProfiles();
            } else {
                disconnectAllAcls();
            }
        }

        if (mPauseAdvertisementOnSuspend && Flags.adapterSuspendAdvertisement()) {
            mAdapterService
                    .getGattService()
                    .ifPresent(
                            gattService -> {
                                mDelayedSuspendTasks.add(SuspendTasks.ADVERTISEMENT);
                                gattService.getAdvertiseManager().enterSuspend();
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
            mAdapterService
                    .getLeAudioService()
                    .ifPresent(leAudio -> leAudio.setSystemSuspended(false));
            mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);
            mAdapterNativeInterface.clearEventFilter();
            mAdapterNativeInterface.restoreFilterAcceptList();

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

        if (Flags.stopLeScanSystemSuspend() && mStopLeScanOnSuspend) {
            final var scanController = mAdapterService.getBluetoothScanController();
            if (scanController != null) {
                scanController.doOnScanThread(
                        () -> scanController.onSystemSuspendChanged(false /* suspend */));
            }
        }

        if (mScanModeNoneOnSuspend) {
            if (Flags.adapterSuspendDiscoverability()) {
                mAdapterService.setSuspendState(false /* suspend */);
            } else if (mAdapterService.getScanMode() != mScanModeOnLastSuspend) {
                mAdapterService.setScanMode(mScanModeOnLastSuspend, "handleResume");
            }
        }

        if (Flags.adapterSuspendAdvertisement()) {
            mAdapterService
                    .getGattService()
                    .ifPresent(gatt -> gatt.getAdvertiseManager().exitSuspend());
        }
    }

    void storeActiveAudioDevices() {
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

    void getDisconnectProfileDevices() {
        if (!mDisconnectProfileDevices.isEmpty()) {
            Log.w(TAG, "Disconnect devices have been collected=" + mDisconnectProfileDevices);
            return;
        }
        for (int profile : DISCONNECT_PROFILES) {
            Log.i(TAG, "Disconnect devices for profile=" + getProfileName(profile));
            mAdapterService.getConnectedMediaDevices(profile).stream()
                    .filter(Objects::nonNull)
                    .forEach(mDisconnectProfileDevices::add);
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
        for (BluetoothDevice device : mDisconnectProfileDevices) {
            if (Flags.addLocalDisconnectReason()) {
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

        mDelayedSuspendTasks.remove(task);
        Log.v(TAG, "Suspend remaining tasks=" + mDelayedSuspendTasks);
        if (isSuspendReady()) {
            Log.i(TAG, "suspend ready");
            mAdapterService.releaseWakeLock("bt_suspend_ready");
        }
    }

    private void disconnectAllAcls() {
        mAdapterNativeInterface.disconnectAllAcls();
        if (mAllowWakeByHid) {
            mAdapterNativeInterface.allowWakeByHid();
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
        writer.println();
        mSuspendStateMachine.dump(fd, writer, args);
    }
}
