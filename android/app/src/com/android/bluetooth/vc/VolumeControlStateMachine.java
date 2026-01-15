/*
 * Copyright 2019 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.vc;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothVolumeControl;
import android.content.Intent;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Scanner;

class VolumeControlStateMachine extends StateMachine {
    private static final String TAG = VolumeControlStateMachine.class.getSimpleName();

    static final int MESSAGE_CONNECT = 1;
    static final int MESSAGE_DISCONNECT = 2;
    static final int MESSAGE_STACK_EVENT = 101;
    @VisibleForTesting static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;
    private final VolumeControlService mService;
    private final VolumeControlNativeInterface mNativeInterface;
    private final BluetoothDevice mDevice;

    private State mCurrentState;
    private int mLastConnectionState = -1;

    VolumeControlStateMachine(
            VolumeControlService svc,
            BluetoothDevice device,
            VolumeControlNativeInterface nativeInterface,
            Looper looper) {
        super(TAG, looper);
        mDevice = device;
        mService = svc;
        mNativeInterface = nativeInterface;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();
        mCurrentState = mDisconnected;

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
            mCurrentState = this;
            Log.i(
                    TAG,
                    "Enter Disconnected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));

            removeDeferredMessages(MESSAGE_DISCONNECT);

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
                case MESSAGE_CONNECT -> {
                    log("Connecting to " + mDevice);
                    if (!mNativeInterface.connectVolumeControl(mDevice)) {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                        break;
                    }
                    transitionTo(mConnecting);
                }
                case MESSAGE_DISCONNECT -> {
                    Log.w(TAG, "Disconnected: DISCONNECT ignored: " + mDevice);
                }
                case MESSAGE_STACK_EVENT -> {
                    VolumeControlStackEvent event = (VolumeControlStackEvent) message.obj;
                    Log.d(TAG, "Disconnected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> {
                            Log.d(TAG, "Disconnected: forwarding stack event: " + event);
                            mService.handleStackEvent(event);
                        }
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
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(
                                TAG,
                                "Incoming VolumeControl Connecting request accepted: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(
                                TAG,
                                "Incoming Volume Control Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectVolumeControl(mDevice);
                    }
                }
                case STATE_CONNECTED -> {
                    Log.w(TAG, "VolumeControl Connected from Disconnected state: " + mDevice);
                    if (mService.okToConnect(mDevice)) {
                        Log.i(
                                TAG,
                                "Incoming Volume Control Connected request accepted: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming VolumeControl Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectVolumeControl(mDevice);
                    }
                }
                default -> Log.e(TAG, "Incorrect state: " + state + " device: " + mDevice);
            }
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.i(
                    TAG,
                    "Enter Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
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
                case MESSAGE_CONNECT -> {
                    Log.w(TAG, "Connecting: CONNECT ignored: " + mDevice);
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Connecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectVolumeControl(mDevice);
                    VolumeControlStackEvent disconnectEvent =
                            new VolumeControlStackEvent(
                                    VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, disconnectEvent);
                }
                case MESSAGE_DISCONNECT -> {
                    log("Connecting: connection canceled to " + mDevice);
                    mNativeInterface.disconnectVolumeControl(mDevice);
                    transitionTo(mDisconnected);
                }
                case MESSAGE_STACK_EVENT -> {
                    VolumeControlStackEvent event = (VolumeControlStackEvent) message.obj;
                    log("Connecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        case VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED -> {
                            Log.w(TAG, "Defer volume change received while connecting: " + mDevice);
                            deferMessage(message);
                        }
                        default -> {
                            Log.d(TAG, "Connecting: forwarding stack event: " + event);
                            mService.handleStackEvent(event);
                        }
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

    int getConnectionState() {
        return switch (mCurrentState.getName()) {
            case "Disconnected" -> STATE_DISCONNECTED;
            case "Connecting" -> STATE_CONNECTING;
            case "Connected" -> STATE_CONNECTED;
            case "Disconnecting" -> STATE_DISCONNECTING;
            default -> STATE_DISCONNECTED;
        };
    }

    @VisibleForTesting
    class Disconnecting extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.i(
                    TAG,
                    "Enter Disconnecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
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
                case MESSAGE_CONNECT -> {
                    if (!hasDeferredMessages(MESSAGE_CONNECT)) {
                        deferMessage(message);
                    } else {
                        log("Connect already scheduled for " + mDevice);
                    }
                }
                case MESSAGE_DISCONNECT -> {
                    log("Disconnect is ongoing for " + mDevice);
                    if (hasDeferredMessages(MESSAGE_CONNECT)) {
                        log("Removing scheduled connect for " + mDevice);
                        removeDeferredMessages(MESSAGE_CONNECT);
                    }
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Disconnecting connection timeout: " + mDevice);
                    mNativeInterface.disconnectVolumeControl(mDevice);
                    VolumeControlStackEvent disconnectEvent =
                            new VolumeControlStackEvent(
                                    VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, disconnectEvent);
                }
                case MESSAGE_STACK_EVENT -> {
                    VolumeControlStackEvent event = (VolumeControlStackEvent) message.obj;
                    log("Disconnecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> {
                            Log.d(TAG, "Disconnecting: forwarding stack event: " + event);
                            mService.handleStackEvent(event);
                        }
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
                        Log.w(TAG, "Incoming VolumeControl Connected request rejected: " + mDevice);
                        mNativeInterface.disconnectVolumeControl(mDevice);
                    }
                }
                case STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Disconnecting interrupted: try to reconnect: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(
                                TAG,
                                "Incoming VolumeControl Connecting request rejected: " + mDevice);
                        mNativeInterface.disconnectVolumeControl(mDevice);
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
            mCurrentState = this;
            Log.i(
                    TAG,
                    "Enter Connected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            removeDeferredMessages(MESSAGE_CONNECT);
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
                case MESSAGE_CONNECT -> {
                    Log.w(TAG, "Connected: CONNECT ignored: " + mDevice);
                }
                case MESSAGE_DISCONNECT -> {
                    log("Disconnecting from " + mDevice);
                    if (!mNativeInterface.disconnectVolumeControl(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected state.
                        Log.e(TAG, "Connected: error disconnecting from " + mDevice);
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case MESSAGE_STACK_EVENT -> {
                    VolumeControlStackEvent event = (VolumeControlStackEvent) message.obj;
                    log("Connected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> {
                            processConnectionEvent(event.valueInt1);
                        }
                        default -> {
                            Log.d(TAG, "Connected: forwarding stack event: " + event);
                            mService.handleStackEvent(event);
                        }
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
                    Log.i(TAG, "Disconnected from " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_DISCONNECTING -> {
                    Log.i(TAG, "Disconnecting from " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default ->
                        Log.e(TAG, "Connection State Device: " + mDevice + " bad state: " + state);
            }
        }
    }

    BluetoothDevice getDevice() {
        return mDevice;
    }

    synchronized boolean isConnected() {
        return mCurrentState == mConnected;
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

        mService.handleConnectionStateChanged(mDevice, prevState, newState);
        Intent intent = new Intent(BluetoothVolumeControl.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mService.sendBroadcast(intent, BLUETOOTH_CONNECT);
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
