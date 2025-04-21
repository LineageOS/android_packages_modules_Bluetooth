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

import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_DOCKED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_SLATE;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;

import com.android.bluetooth.Utils;

import java.util.Arrays;

public class AdapterSuspend {
    private static final String TAG =
            Utils.TAG_PREFIX_BLUETOOTH + AdapterSuspend.class.getSimpleName();

    // Event mask bits corresponding to specific HCI events
    // as defined in Bluetooth core v5.4, Vol 4, Part E, 7.3.1.
    private static final long MASK_DISCONNECT_CMPLT = 1 << 4;
    private static final long MASK_MODE_CHANGE = 1 << 19;

    private static final int DEVICE_STATE_NONE = 0;
    private static final int DEVICE_STATE_DOCKED = 1;
    private static final int DEVICE_STATE_LID_CLOSED = 2;
    private static final int DEVICE_STATE_LID_OPEN = 3;
    private static final int DEVICE_STATE_TABLET = 4;

    private final DeviceStateManager mDeviceStateManager;
    private final PowerManager mPowerManager;
    private final AdapterSuspendStateMachine mSuspendStateMachine;
    private final DisplayManager mDisplayManager;

    public final DeviceStateManager.DeviceStateCallback mDeviceStateCallback =
            new DeviceStateManager.DeviceStateCallback() {
                @Override
                public void onDeviceStateChanged(@NonNull DeviceState state) {
                    int nextState = DEVICE_STATE_NONE;
                    if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_DOCKED)) {
                        nextState = DEVICE_STATE_DOCKED;
                    }
                    if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_CLOSED)) {
                        nextState = DEVICE_STATE_LID_CLOSED;
                    }
                    if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_LID_OPEN)) {
                        nextState = DEVICE_STATE_LID_OPEN;
                    }
                    if (state.hasProperty(PROPERTY_LAPTOP_HARDWARE_CONFIGURATION_SLATE)) {
                        nextState = DEVICE_STATE_TABLET;
                    }

                    switch (nextState) {
                        case DEVICE_STATE_LID_OPEN, DEVICE_STATE_DOCKED, DEVICE_STATE_TABLET -> {
                            switch (nextState) {
                                case DEVICE_STATE_LID_OPEN, DEVICE_STATE_DOCKED ->
                                        mSuspendStateMachine.setTabletMode(false);
                                case DEVICE_STATE_TABLET ->
                                        mSuspendStateMachine.setTabletMode(true);
                                default -> Log.e(TAG, "Unknown form factor " + nextState);
                            }
                        }
                        case DEVICE_STATE_LID_CLOSED ->
                                mSuspendStateMachine.sendMessage(
                                        AdapterSuspendStateMachine.MSG_CLOSED);
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
                            "Display:"
                                    + displayId
                                    + " Screen="
                                    + screenOn
                                    + " Interactive="
                                    + interactive);
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

    private final AdapterNativeInterface mAdapterNativeInterface;

    public AdapterSuspend(
            AdapterService adapterService,
            AdapterNativeInterface adapterNativeInterface,
            Looper looper,
            DeviceStateManager deviceStateManager,
            PowerManager powerManager,
            DisplayManager displayManager) {
        mAdapterNativeInterface = requireNonNull(adapterNativeInterface);
        mPowerManager = requireNonNull(powerManager);

        mSuspendStateMachine =
                new AdapterSuspendStateMachine(adapterService, this, requireNonNull(looper));
        mDisplayManager = requireNonNull(displayManager);
        Handler handler = new Handler(looper);
        mDisplayManager.registerDisplayListener(mDisplayListener, handler);
        mDeviceStateManager = requireNonNull(deviceStateManager);
        mDeviceStateManager.registerCallback(handler::post, mDeviceStateCallback);
    }

    void cleanup() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        mDeviceStateManager.unregisterCallback(mDeviceStateCallback);
    }

    void handleSuspend(boolean allowWakeByHid) {
        long mask = MASK_DISCONNECT_CMPLT | MASK_MODE_CHANGE;
        long leMask = 0;

        // Avoid unexpected interrupt during suspend.
        mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);

        // Disable inquiry scan and page scan.
        mAdapterNativeInterface.setScanMode(AdapterService.convertScanModeToHal(SCAN_MODE_NONE));

        mAdapterNativeInterface.clearEventFilter();
        mAdapterNativeInterface.clearFilterAcceptList();
        mAdapterNativeInterface.disconnectAllAcls();

        if (allowWakeByHid) {
            mAdapterNativeInterface.allowWakeByHid();
            Log.i(TAG, "configure wake by hid");
        }
        Log.i(TAG, "ready to suspend");
    }

    void handleResume() {
        long mask = 0;
        long leMask = 0;
        mAdapterNativeInterface.setDefaultEventMaskExcept(mask, leMask);
        mAdapterNativeInterface.clearEventFilter();
        mAdapterNativeInterface.restoreFilterAcceptList();
        mAdapterNativeInterface.setScanMode(
                AdapterService.convertScanModeToHal(SCAN_MODE_CONNECTABLE));
        Log.i(TAG, "resumed");
    }

    /**
     * This function is to update the state of Bluetooth wakelock and send message to state machine.
     */
    public void updateWakeLockState(boolean enabled) {
        Log.d(TAG, "Wakelock state: " + enabled);
        if (enabled) {
            mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_WAKELOCK_ACQUIRED);
        } else {
            mSuspendStateMachine.sendMessage(AdapterSuspendStateMachine.MSG_WAKELOCK_RELEASED);
        }
    }
}
