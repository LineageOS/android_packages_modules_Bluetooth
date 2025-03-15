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

// Bluetooth HearingAid StateMachine. There is one instance per remote device.
//  - "Disconnected" and "Connected" are steady states.
//  - "Connecting" and "Disconnecting" are transient states until the
//     connection / disconnection is completed.
//
//
//                        (Disconnected)
//                           |       ^
//                   CONNECT |       | DISCONNECTED
//                           V       |
//                 (Connecting)<--->(Disconnecting)
//                           |       ^
//                 CONNECTED |       | DISCONNECT
//                           V       |
//                          (Connected)
// NOTES:
//  - If state machine is in "Connecting" state and the remote device sends
//    DISCONNECT request, the state machine transitions to "Disconnecting" state.
//  - Similarly, if the state machine is in "Disconnecting" state and the remote device
//    sends CONNECT request, the state machine transitions to "Connecting" state.
//
//                    DISCONNECT
//    (Connecting) ---------------> (Disconnecting)
//                 <---------------
//                      CONNECT

package com.android.bluetooth.hearingaid;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Scanner;

final class HearingAidStateMachine extends StateMachine {
    private static final String TAG = HearingAidStateMachine.class.getSimpleName();

    static final int MESSAGE_CONNECT = 1;
    static final int MESSAGE_DISCONNECT = 2;
    @VisibleForTesting static final int MESSAGE_STACK_EVENT = 101;
    private static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;
    private final HearingAidService mService;
    private final HearingAidNativeInterface mNativeInterface;
    private final BluetoothDevice mDevice;

    private int mConnectionState = STATE_DISCONNECTED;
    private int mLastConnectionState = -1;

    HearingAidStateMachine(
            HearingAidService svc,
            BluetoothDevice device,
            HearingAidNativeInterface nativeInterface,
            Looper looper) {
        super(TAG, looper);
        mDevice = device;
        mService = svc;
        mNativeInterface = nativeInterface;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);

        setInitialState(mDisconnected);
    }

    public void doQuit() {
        log("doQuit for device " + mDevice);
        quitNow();
    }

    @VisibleForTesting
    class Disconnected extends State {
        @Override
        public void enter() {
            Log.i(
                    TAG,
                    "Enter Disconnected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mConnectionState = STATE_DISCONNECTED;

            removeDeferredMessages(MESSAGE_DISCONNECT);

            if (mLastConnectionState != -1) {
                // Don't broadcast during startup
                broadcastConnectionState(STATE_DISCONNECTED);
            }
        }

        @Override
        public void exit() {
            log(
                    "Exit Disconnected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            log(
                    "Disconnected process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> {
                    log("Connecting to " + mDevice);
                    if (!mNativeInterface.connectHearingAid(mDevice)) {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                        break;
                    }
                    if (mService.okToConnect(mDevice)) {
                        transitionTo(mConnecting);
                    } else {
                        // Reject the request and stay in Disconnected state
                        Log.w(TAG, "Outgoing HearingAid Connecting request rejected: " + mDevice);
                    }
                }
                case MESSAGE_DISCONNECT -> {
                    Log.d(TAG, "Disconnected: DISCONNECT: call native disconnect for " + mDevice);
                    mNativeInterface.disconnectHearingAid(mDevice);
                }
                case MESSAGE_STACK_EVENT -> {
                    HearingAidStackEvent event = (HearingAidStackEvent) message.obj;
                    Log.d(TAG, "Disconnected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> Log.e(TAG, "Disconnected: ignoring stack event: " + event);
                    }
                }
                default -> {
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Disconnected state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.w(TAG, "Ignore HearingAid DISCONNECTED event: " + mDevice);
                }
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Incoming HearingAid Connecting request accepted: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming HearingAid Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectHearingAid(mDevice);
                    }
                }
                case STATE_CONNECTED -> {
                    Log.w(TAG, "HearingAid Connected from Disconnected state: " + mDevice);
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Incoming HearingAid Connected request accepted: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming HearingAid Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectHearingAid(mDevice);
                    }
                }
                case STATE_DISCONNECTING -> {
                    Log.w(TAG, "Ignore HearingAid DISCONNECTING event: " + mDevice);
                }
                default -> Log.e(TAG, "Incorrect state: " + state + " device: " + mDevice);
            }
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        @Override
        public void enter() {
            Log.i(
                    TAG,
                    "Enter Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_CONNECTING;
            broadcastConnectionState(STATE_CONNECTING);
        }

        @Override
        public void exit() {
            log(
                    "Exit Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTING;
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            log(
                    "Connecting process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> deferMessage(message);
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Connecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectHearingAid(mDevice);
                    if (mService.isConnectedPeerDevices(mDevice)) {
                        Log.w(TAG, "One side connection timeout: " + mDevice + ". Try acceptlist");
                        mNativeInterface.addToAcceptlist(mDevice);
                    }
                    HearingAidStackEvent disconnectEvent =
                            new HearingAidStackEvent(
                                    HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, disconnectEvent);
                }
                case MESSAGE_DISCONNECT -> {
                    log("Connecting: connection canceled to " + mDevice);
                    mNativeInterface.disconnectHearingAid(mDevice);
                    transitionTo(mDisconnected);
                }
                case MESSAGE_STACK_EVENT -> {
                    HearingAidStackEvent event = (HearingAidStackEvent) message.obj;
                    log("Connecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> Log.e(TAG, "Connecting: ignoring stack event: " + event);
                    }
                }
                default -> {
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Connecting state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.w(TAG, "Connecting device disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> transitionTo(mConnected);
                case STATE_DISCONNECTING -> {
                    Log.w(TAG, "Connecting interrupted: device is disconnecting: " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default -> Log.e(TAG, "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Disconnecting extends State {
        @Override
        public void enter() {
            Log.i(
                    TAG,
                    "Enter Disconnecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_DISCONNECTING;
            broadcastConnectionState(STATE_DISCONNECTING);
        }

        @Override
        public void exit() {
            log(
                    "Exit Disconnecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTING;
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            log(
                    "Disconnecting process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT, MESSAGE_DISCONNECT -> deferMessage(message);
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Disconnecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectHearingAid(mDevice);
                    HearingAidStackEvent disconnectEvent =
                            new HearingAidStackEvent(
                                    HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, disconnectEvent);
                }
                case MESSAGE_STACK_EVENT -> {
                    HearingAidStackEvent event = (HearingAidStackEvent) message.obj;
                    log("Disconnecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> Log.e(TAG, "Disconnecting: ignoring stack event: " + event);
                    }
                }
                default -> {
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Disconnecting state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.w(TAG, "Disconnecting interrupted: device is connected: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming HearingAid Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectHearingAid(mDevice);
                    }
                }
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Disconnecting interrupted: try to reconnect: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming HearingAid Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectHearingAid(mDevice);
                    }
                }
                default -> Log.e(TAG, "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Connected extends State {
        @Override
        public void enter() {
            Log.i(
                    TAG,
                    "Enter Connected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mConnectionState = STATE_CONNECTED;
            removeDeferredMessages(MESSAGE_CONNECT);
            broadcastConnectionState(STATE_CONNECTED);
        }

        @Override
        public void exit() {
            log(
                    "Exit Connected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            log("Connected process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> Log.w(TAG, "Connected: CONNECT ignored: " + mDevice);
                case MESSAGE_DISCONNECT -> {
                    log("Disconnecting from " + mDevice);
                    if (!mNativeInterface.disconnectHearingAid(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected state.
                        Log.e(TAG, "Connected: error disconnecting from " + mDevice);
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case MESSAGE_STACK_EVENT -> {
                    HearingAidStackEvent event = (HearingAidStackEvent) message.obj;
                    log("Connected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> Log.e(TAG, "Connected: ignoring stack event: " + event);
                    }
                }
                default -> {
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Connected state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from " + mDevice + " but still in Acceptlist");
                    transitionTo(mDisconnected);
                }
                case STATE_DISCONNECTING -> {
                    Log.i(TAG, "Disconnecting from " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default -> Log.e(TAG, "Connection State: " + mDevice + " bad state: " + state);
            }
        }
    }

    int getConnectionState() {
        return mConnectionState;
    }

    BluetoothDevice getDevice() {
        return mDevice;
    }

    synchronized boolean isConnected() {
        return (getConnectionState() == STATE_CONNECTED);
    }

    private void broadcastConnectionState(int newState) {
        log(
                "Connection state "
                        + mDevice
                        + ": "
                        + getConnectionStateName(mLastConnectionState)
                        + "->"
                        + getConnectionStateName(newState));

        mService.connectionStateChanged(mDevice, mLastConnectionState, newState);

        Intent intent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, mLastConnectionState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mService.sendBroadcastAsUser(
                intent,
                UserHandle.ALL,
                BLUETOOTH_CONNECT,
                Utils.getTempBroadcastOptions().toBundle());
    }

    private static String messageWhatToString(int what) {
        return switch (what) {
            case MESSAGE_CONNECT -> "CONNECT";
            case MESSAGE_DISCONNECT -> "DISCONNECT";
            case MESSAGE_STACK_EVENT -> "STACK_EVENT";
            case MESSAGE_CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            default -> Integer.toString(what);
        };
    }

    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice);
        ProfileService.println(sb, "  StateMachine: " + this);
        // Dump the state machine logs
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        super.dump(new FileDescriptor(), printWriter, new String[] {});
        printWriter.flush();
        stringWriter.flush();
        ProfileService.println(sb, "  StateMachineLog:");
        Scanner scanner = new Scanner(stringWriter.toString());
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            ProfileService.println(sb, "    " + line);
        }
        scanner.close();
    }
}
