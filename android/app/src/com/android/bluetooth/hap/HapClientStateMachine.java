/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

// Bluetooth Hap Client StateMachine. There is one instance per remote device.
//  - "Disconnected" and "Connected" are steady states.
//  - "Connecting" and "Disconnecting" are transient states until the
//     connection / disconnection is completed.

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

//                    DISCONNECT
//    (Connecting) ---------------> (Disconnecting)
//                 <---------------
//                      CONNECT
package com.android.bluetooth.hap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Scanner;

final class HapClientStateMachine extends StateMachine {
    private static final String TAG = HapClientStateMachine.class.getSimpleName();

    static final int MESSAGE_CONNECT = 1;
    static final int MESSAGE_DISCONNECT = 2;
    static final int MESSAGE_CONNECTION_STATE_CHANGED = 102;
    @VisibleForTesting static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;

    private int mConnectionState = STATE_DISCONNECTED;
    private int mLastConnectionState = -1;

    private final HapClientService mService;
    private final HapClientNativeInterface mNativeInterface;
    private final BluetoothDevice mDevice;

    HapClientStateMachine(
            HapClientService svc,
            BluetoothDevice device,
            HapClientNativeInterface gattInterface,
            Looper looper) {
        super(TAG, looper);
        mDevice = device;
        mService = svc;
        mNativeInterface = gattInterface;
        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);

        setInitialState(mDisconnected);
        start(!Flags.hapOnMainLooper());
    }

    private static String messageWhatToString(int what) {
        return switch (what) {
            case MESSAGE_CONNECT -> "CONNECT";
            case MESSAGE_DISCONNECT -> "DISCONNECT";
            case MESSAGE_CONNECTION_STATE_CHANGED -> "CONNECTION_STATE_CHANGED";
            case MESSAGE_CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            default -> Integer.toString(what);
        };
    }

    public void doQuit() {
        Log.d(TAG, "doQuit for " + mDevice);
        quitNow(!Flags.hapOnMainLooper());
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

    private void broadcastConnectionState() {
        Log.d(
                TAG,
                ("Connection state " + mDevice + ": ")
                        + getConnectionStateName(mLastConnectionState)
                        + "->"
                        + getConnectionStateName(mConnectionState));

        mService.connectionStateChanged(mDevice, mLastConnectionState, mConnectionState);
        Intent intent =
                new Intent(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED)
                        .putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, mLastConnectionState)
                        .putExtra(BluetoothProfile.EXTRA_STATE, mConnectionState)
                        .putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice)
                        .addFlags(
                                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mService.getBaseContext()
                .sendBroadcastWithMultiplePermissions(
                        intent, new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED});
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

    @VisibleForTesting
    class Disconnected extends State {
        private final String mStateLog = "Disconnected(" + mDevice + "): ";

        @Override
        public void enter() {
            Log.i(TAG, "Enter " + mStateLog + messageWhatToString(getCurrentMessage().what));

            removeDeferredMessages(MESSAGE_DISCONNECT);

            mConnectionState = STATE_DISCONNECTED;
            if (mLastConnectionState != -1) { // Don't broadcast during startup
                broadcastConnectionState();
            }
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit " + mStateLog + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, mStateLog + "processMessage: " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> {
                    if (!mNativeInterface.connectHapClient(mDevice)) {
                        Log.e(TAG, mStateLog + "native error during connection");
                        break;
                    }
                    transitionTo(mConnecting);
                }
                case MESSAGE_DISCONNECT -> mNativeInterface.disconnectHapClient(mDevice);
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(message.arg1);
                default -> {
                    Log.e(TAG, mStateLog + "not handled: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Disconnected state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, mStateLog + "Incoming connecting request accepted");
                        transitionTo(mConnecting);
                    } else {
                        Log.w(TAG, mStateLog + "Incoming connecting request rejected");
                        mNativeInterface.disconnectHapClient(mDevice);
                    }
                }
                case STATE_CONNECTED -> {
                    Log.w(TAG, mStateLog + "HearingAccess Connected from Disconnected state");
                    if (mService.okToConnect(mDevice)) {
                        Log.w(TAG, mStateLog + "Incoming connected transition accepted");
                        transitionTo(mConnected);
                    } else {
                        Log.w(TAG, mStateLog + "Incoming connected transition rejected");
                        mNativeInterface.disconnectHapClient(mDevice);
                    }
                }
                default -> Log.e(TAG, mStateLog + "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        private final String mStateLog = "Connecting(" + mDevice + "): ";

        @Override
        public void enter() {
            Log.i(TAG, "Enter " + mStateLog + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_CONNECTING;
            broadcastConnectionState();
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit " + mStateLog + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTING;
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, mStateLog + "processMessage: " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> Log.w(TAG, mStateLog + "CONNECT ignored ");

                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, mStateLog + "connection timeout");
                    mNativeInterface.disconnectHapClient(mDevice);
                    if (Flags.hapOnMainLooper()) {
                        transitionTo(mDisconnected);
                        break;
                    }
                    sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED);
                }
                case MESSAGE_DISCONNECT -> {
                    Log.d(TAG, mStateLog + "connection canceled");
                    mNativeInterface.disconnectHapClient(mDevice);
                    transitionTo(mDisconnected);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(message.arg1);
                default -> {
                    Log.e(TAG, mStateLog + "not handled: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Connecting state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, mStateLog + "device disconnected");
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> transitionTo(mConnected);
                case STATE_DISCONNECTING -> {
                    Log.i(TAG, mStateLog + "device disconnecting");
                    transitionTo(mDisconnecting);
                }
                default -> Log.e(TAG, mStateLog + "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Disconnecting extends State {
        private final String mStateLog = "Disconnecting(" + mDevice + "): ";

        @Override
        public void enter() {
            Log.i(TAG, "Enter " + mStateLog + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_DISCONNECTING;
            broadcastConnectionState();
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit " + mStateLog + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTING;
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, mStateLog + "processMessage: " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> {
                    if (!hasDeferredMessages(MESSAGE_CONNECT)) {
                        deferMessage(message);
                    } else {
                        Log.w(TAG, mStateLog + "CONNECT already scheduled");
                    }
                }
                case MESSAGE_DISCONNECT -> {
                    if (hasDeferredMessages(MESSAGE_CONNECT)) {
                        Log.w(TAG, mStateLog + "removing scheduled CONNECT");
                        removeDeferredMessages(MESSAGE_CONNECT);
                    } else {
                        Log.w(TAG, mStateLog + "ignore DISCONNECT");
                    }
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, mStateLog + "connection timeout");
                    mNativeInterface.disconnectHapClient(mDevice);
                    if (Flags.hapOnMainLooper()) {
                        transitionTo(mDisconnected);
                        break;
                    }
                    sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(message.arg1);
                default -> {
                    Log.e(TAG, mStateLog + "not handled: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Disconnecting state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, mStateLog + "Disconnected");
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.w(TAG, mStateLog + "interrupted: device is connected");
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, mStateLog + "Incoming connect request rejected");
                        mNativeInterface.disconnectHapClient(mDevice);
                    }
                }
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, mStateLog + "interrupted: device try to reconnect");
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, mStateLog + "Incoming connecting request rejected");
                        mNativeInterface.disconnectHapClient(mDevice);
                    }
                }
                default -> Log.e(TAG, mStateLog + "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Connected extends State {
        private final String mStateLog = "Connected(" + mDevice + "): ";

        @Override
        public void enter() {
            Log.i(TAG, "Enter " + mStateLog + messageWhatToString(getCurrentMessage().what));
            removeDeferredMessages(MESSAGE_CONNECT);
            mConnectionState = STATE_CONNECTED;
            broadcastConnectionState();
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit " + mStateLog + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(TAG, mStateLog + "processMessage: " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_DISCONNECT -> {
                    if (!mNativeInterface.disconnectHapClient(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected state.
                        Log.e(TAG, mStateLog + "native cannot disconnect");
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(message.arg1);
                default -> {
                    Log.e(TAG, mStateLog + "not handled: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Connected state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, mStateLog + "Disconnected but still in allowlist");
                    transitionTo(mDisconnected);
                }
                case STATE_DISCONNECTING -> {
                    Log.i(TAG, mStateLog + "Disconnecting");
                    transitionTo(mDisconnecting);
                }
                default -> Log.e(TAG, mStateLog + "Incorrect state: " + state);
            }
        }
    }
}
