/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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

//  Bluetooth LeAudio StateMachine. There is one instance per remote device's ASE.
//   - "Disconnected" and "Connected" are steady states.
//   - "Connecting" and "Disconnecting" are transient states until the
//      connection / disconnection is completed.
//
//
//                         (Disconnected)
//                            |       ^
//                    CONNECT |       | DISCONNECTED
//                            V       |
//                  (Connecting)<--->(Disconnecting)
//                            |       ^
//                  CONNECTED |       | DISCONNECT
//                            V       |
//                           (Connected)
//  NOTES:
//   - If state machine is in "Connecting" state and the remote device sends
//     DISCONNECT request, the state machine transitions to "Disconnecting" state.
//   - Similarly, if the state machine is in "Disconnecting" state and the remote device
//     sends CONNECT request, the state machine transitions to "Connecting" state.
//
//                     DISCONNECT
//     (Connecting) ---------------> (Disconnecting)
//                  <---------------
//                       CONNECT

package com.android.bluetooth.le_audio;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothDevice;
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

final class LeAudioStateMachine extends StateMachine {
    private static final String TAG = LeAudioStateMachine.class.getSimpleName();

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int STACK_EVENT = 101;
    private static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;
    private int mConnectionState = STATE_DISCONNECTED;

    private int mLastConnectionState = -1;

    private final LeAudioService mService;
    private final LeAudioNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice;

    LeAudioStateMachine(
            BluetoothDevice device,
            LeAudioService svc,
            LeAudioNativeInterface nativeInterface,
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

        start();
    }

    public void doQuit() {
        log("doQuit for device " + mDevice);
        if (Flags.leaudioIntentBroadcastInStateMachineCleanup()
                && mConnectionState != STATE_DISCONNECTED
                && mLastConnectionState != -1) {
            // Broadcast CONNECTION_STATE_CHANGED when state machine is turned off while
            // the device is connected
            broadcastConnectionState(STATE_DISCONNECTED, mConnectionState);
        }
        quitNow();
    }

    public void cleanup() {
        log("cleanup for device " + mDevice);
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

            removeDeferredMessages(DISCONNECT);

            if (mLastConnectionState != -1) {
                // Don't broadcast during startup
                broadcastConnectionState(STATE_DISCONNECTED, mLastConnectionState);
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
                case CONNECT -> {
                    log("Connecting to " + mDevice);
                    if (!mNativeInterface.connectLeAudio(mDevice)) {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                        break;
                    }
                    transitionTo(mConnecting);
                }
                case DISCONNECT -> {
                    Log.d(TAG, "Disconnected: " + mDevice);
                    mNativeInterface.disconnectLeAudio(mDevice);
                }
                case STACK_EVENT -> {
                    LeAudioStackEvent event = (LeAudioStackEvent) message.obj;
                    Log.d(TAG, "Disconnected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt1);
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
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.w(TAG, "Ignore LeAudio DISCONNECTED event: " + mDevice);
                }
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Incoming LeAudio Connecting request accepted: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming LeAudio Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectLeAudio(mDevice);
                    }
                }
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTED -> {
                    Log.w(TAG, "LeAudio Connected from Disconnected state: " + mDevice);
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Incoming LeAudio Connected request accepted: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming LeAudio Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectLeAudio(mDevice);
                    }
                }
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING ->
                        Log.w(TAG, "Ignore LeAudio DISCONNECTING event: " + mDevice);
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
            broadcastConnectionState(STATE_CONNECTING, mLastConnectionState);
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
                case CONNECT -> {
                    Log.w(TAG, "Connecting: CONNECT ignored: " + mDevice);
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Connecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectLeAudio(mDevice);
                    LeAudioStackEvent disconnectEvent =
                            new LeAudioStackEvent(
                                    LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
                    sendMessage(STACK_EVENT, disconnectEvent);
                }
                case DISCONNECT -> {
                    log("Connecting: connection canceled to " + mDevice);
                    mNativeInterface.disconnectLeAudio(mDevice);
                    transitionTo(mDisconnected);
                }
                case STACK_EVENT -> {
                    LeAudioStackEvent event = (LeAudioStackEvent) message.obj;
                    log("Connecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt1);
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
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.w(TAG, "Connecting device disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTED -> transitionTo(mConnected);
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTING -> {}
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING -> {
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
            broadcastConnectionState(STATE_DISCONNECTING, mLastConnectionState);
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
                case CONNECT -> {
                    if (!hasDeferredMessages(CONNECT)) {
                        deferMessage(message);
                    } else {
                        log("Connect already scheduled for " + mDevice);
                    }
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Disconnecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectLeAudio(mDevice);
                    LeAudioStackEvent disconnectEvent =
                            new LeAudioStackEvent(
                                    LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
                    sendMessage(STACK_EVENT, disconnectEvent);
                }
                case DISCONNECT -> {
                    log("Disconnect is ongoing for " + mDevice);
                    if (hasDeferredMessages(CONNECT)) {
                        log("Removing scheduled connect for " + mDevice);
                        removeDeferredMessages(CONNECT);
                    }
                }
                case STACK_EVENT -> {
                    LeAudioStackEvent event = (LeAudioStackEvent) message.obj;
                    log("Disconnecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt1);
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
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTED -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.w(TAG, "Disconnecting interrupted: device is connected: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming LeAudio Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectLeAudio(mDevice);
                    }
                }
                case LeAudioStackEvent.CONNECTION_STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Disconnecting interrupted: try to reconnect: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming LeAudio Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectLeAudio(mDevice);
                    }
                }
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING -> {}
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
            removeDeferredMessages(CONNECT);
            broadcastConnectionState(STATE_CONNECTED, mLastConnectionState);
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
                case CONNECT -> Log.w(TAG, "Connected: CONNECT ignored: " + mDevice);
                case DISCONNECT -> {
                    log("Disconnecting from " + mDevice);
                    if (!mNativeInterface.disconnectLeAudio(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected state.
                        Log.e(TAG, "Connected: error disconnecting from " + mDevice);
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case STACK_EVENT -> {
                    LeAudioStackEvent event = (LeAudioStackEvent) message.obj;
                    log("Connected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt1);
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
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from " + mDevice);
                    transitionTo(mDisconnected);
                }
                case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING -> {
                    Log.i(TAG, "Disconnecting from " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default ->
                        Log.e(TAG, "Connection State Device: " + mDevice + " bad state: " + state);
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

    // This method does not check for error condition (newState == prevState)
    private void broadcastConnectionState(int newState, int prevState) {
        log(
                "Connection state "
                        + mDevice
                        + ": "
                        + getConnectionStateName(prevState)
                        + "->"
                        + getConnectionStateName(newState));
        mService.notifyConnectionStateChanged(mDevice, newState, prevState);
    }

    private static String messageWhatToString(int what) {
        return switch (what) {
            case CONNECT -> "CONNECT";
            case DISCONNECT -> "DISCONNECT";
            case STACK_EVENT -> "STACK_EVENT";
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

    @Override
    protected void log(String msg) {
        super.log(msg);
    }
}
