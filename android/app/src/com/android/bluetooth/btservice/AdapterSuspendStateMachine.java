/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;

// There are three steady states: "Active", "Busy", "Suspended".
//  - Active: Bluetooth running with screen on
//  - Busy: Wake lock is acquired from Bluetooth service. Bluetooth running
//    with screen on or screen off.
//  - Suspended: Kernel is suspended. Bluetooth connections are disconnected.
//    Bluetooth HID devices can wake up the system and reconnect if the lid is
//    not closed and the device is not in tablet mode.
//
// Transitions between steady states:
//
//            wake lock is acquired
// (Active) --------------------------> (Busy)
//          <--------------------------
//            wake lock is released
//
//            screen is off or lid is closed
// (Active) -------------------------------> (Suspended)
//          <-------------------------------
//            screen is on, no wake lock
//
//            screen is off, wake lock is released
//                     or lid is closed
// (Busy)   --------------------------------------> (Suspended)
//          <--------------------------------------
//            screen is on, wake lock is acquired
//
//               BT wake is enabled, lid is closed
// (Suspended) -------------------------------------> (Suspended)

/** Bluetooth StateMachine to handle system suspend and wake by Bluetooth. */
final class AdapterSuspendStateMachine extends StateMachine {
    private static final String TAG =
            Util.BT_PREFIX + AdapterSuspendStateMachine.class.getSimpleName();

    // Messages used to communicate with the state machine.
    static final int MSG_SCREEN_ON = 1;
    static final int MSG_SCREEN_OFF = 2;
    static final int MSG_WAKELOCK_ACQUIRED = 3;
    static final int MSG_WAKELOCK_RELEASED = 4;
    static final int MSG_CLOSED = 5;

    private final ActiveState mActiveState = new ActiveState();
    private final BusyState mBusyState = new BusyState();
    private final SuspendedState mSuspendedState = new SuspendedState();

    private final AdapterSuspend mAdapterSuspend;
    private final AdapterService mAdapterService;

    private boolean mScreenOn = true;
    private boolean mWakeLockHeld = false;
    // This value means if wake-by-hid is allowed.
    private boolean mWakeByHidAllowed = true;
    private boolean mTabletMode = false;

    public void setTabletMode(boolean tabletMode) {
        mTabletMode = tabletMode;
    }

    AdapterSuspendStateMachine(AdapterService service, AdapterSuspend suspend, Looper looper) {
        super(TAG, looper);
        addState(mActiveState);
        addState(mBusyState);
        addState(mSuspendedState);
        mAdapterSuspend = suspend;
        mAdapterService = service;
        setInitialState(mActiveState); // Always start from ActiveState.
        start();
    }

    public void doQuit() {
        quitNow();
    }

    String msgToString(int msg) {
        return switch (msg) {
            case MSG_SCREEN_ON -> "SCREEN_ON";
            case MSG_SCREEN_OFF -> "SCREEN_OFF";
            case MSG_WAKELOCK_ACQUIRED -> "WAKELOCK_ACQUIRED";
            case MSG_WAKELOCK_RELEASED -> "WAKELOCK_RELEASED";
            case MSG_CLOSED -> "LID_CLOSED";
            default -> "UNKNOWN_MSG_" + msg;
        };
    }

    String stateToString(Object state) {
        return switch (state) {
            case ActiveState a -> a.toString();
            case BusyState b -> b.toString();
            case SuspendedState s -> s.toString();
            default -> "UNKNOWN";
        };
    }

    private abstract class BaseSuspendState extends State {
        @Override
        public void enter() {
            infoLog("entered");
        }

        void handleSuspendLocked(boolean wakeByHidAllowed) {
            mAdapterService.acquireWakeLock("bluetooth_suspend");
            mWakeByHidAllowed = wakeByHidAllowed;
            mAdapterSuspend.handleSuspend(wakeByHidAllowed);
            mAdapterService.releaseWakeLock("bluetooth_suspend");
        }

        void infoLog(String msg) {
            Log.i(TAG, stateToString(this) + " : " + msg);
        }

        void debugLog(String msg) {
            Log.d(TAG, stateToString(this) + " : " + msg);
        }
    }

    @VisibleForTesting
    class ActiveState extends BaseSuspendState {
        @Override
        public String toString() {
            return "ACTIVE";
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case MSG_CLOSED -> {
                    handleSuspendLocked(false);
                    transitionTo(mSuspendedState);
                }
                case MSG_SCREEN_ON -> {
                    mScreenOn = true;
                }
                case MSG_SCREEN_OFF -> {
                    mScreenOn = false;
                    handleSuspendLocked(!mTabletMode);
                    transitionTo(mSuspendedState);
                }
                case MSG_WAKELOCK_ACQUIRED -> {
                    mWakeLockHeld = true;
                    transitionTo(mBusyState);
                }
                case MSG_WAKELOCK_RELEASED -> {
                    mWakeLockHeld = false;
                }
                default -> {
                    debugLog("Unhandled msg: " + msgToString(msg.what));
                    return false;
                }
            }
            return true;
        }
    }

    @VisibleForTesting
    class BusyState extends BaseSuspendState {
        @Override
        public String toString() {
            return "BUSY";
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case MSG_CLOSED -> {
                    handleSuspendLocked(false);
                    transitionTo(mSuspendedState);
                }
                case MSG_SCREEN_ON -> {
                    mScreenOn = true;
                }
                case MSG_SCREEN_OFF -> {
                    mScreenOn = false;
                }
                case MSG_WAKELOCK_ACQUIRED -> {
                    mWakeLockHeld = true;
                }
                case MSG_WAKELOCK_RELEASED -> {
                    mWakeLockHeld = false;
                    if (mScreenOn) {
                        transitionTo(mActiveState);
                    } else {
                        handleSuspendLocked(!mTabletMode);
                        transitionTo(mSuspendedState);
                    }
                }
                default -> {
                    debugLog("Unhandled msg: " + msgToString(msg.what));
                    return false;
                }
            }
            return true;
        }
    }

    @VisibleForTesting
    class SuspendedState extends BaseSuspendState {
        @Override
        public String toString() {
            return "SUSPENDED(" + mWakeByHidAllowed + ")";
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case MSG_SCREEN_ON -> {
                    mScreenOn = true;
                    mAdapterService.acquireWakeLock("bluetooth_resume");
                    mAdapterSuspend.handleResume();
                    mAdapterService.releaseWakeLock("bluetooth_resume");
                    if (!mWakeLockHeld) {
                        transitionTo(mActiveState);
                    } else {
                        transitionTo(mBusyState);
                    }
                }
                case MSG_SCREEN_OFF -> {
                    mScreenOn = false;
                }
                case MSG_CLOSED -> {
                    if (mWakeByHidAllowed) {
                        handleSuspendLocked(false);
                    }
                }
                case MSG_WAKELOCK_ACQUIRED -> {
                    mWakeLockHeld = true;
                }
                case MSG_WAKELOCK_RELEASED -> {
                    mWakeLockHeld = false;
                }
                default -> {
                    debugLog("Unhandled msg: " + msgToString(msg.what));
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);

        writer.println("  " + " Screen on: " + mScreenOn);
        writer.println("  " + " Wake lock held: " + mWakeLockHeld);
        writer.println("  " + " Wake-by-HID allowed: " + mWakeByHidAllowed);
        writer.println("  " + " Tablet mode: " + mTabletMode);
    }
}
