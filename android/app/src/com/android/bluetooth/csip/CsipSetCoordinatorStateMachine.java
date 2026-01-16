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

package com.android.bluetooth.csip;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
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
import java.util.Scanner;

/** CSIP Set Coordinator role device state machine */
public class CsipSetCoordinatorStateMachine extends StateMachine {
    private static final String TAG = CsipSetCoordinatorStateMachine.class.getSimpleName();

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int STACK_EVENT = 101;
    @VisibleForTesting static final int CONNECT_TIMEOUT = 201;

    static final int sConnectTimeoutMs = 30000; // 30s

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;
    private State mCurrentState;
    private int mLastConnectionState = -1;

    private final CsipSetCoordinatorService mService;
    private final CsipSetCoordinatorNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice;

    CsipSetCoordinatorStateMachine(
            BluetoothDevice device,
            CsipSetCoordinatorService svc,
            CsipSetCoordinatorNativeInterface nativeInterface,
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

        start();
    }

    @VisibleForTesting
    boolean doesSuperHaveDeferredMessages(int what) {
        return super.hasDeferredMessages(what);
    }

    /** Quit state machine execution */
    public void doQuit() {
        log("doQuit for device " + mDevice);
        quitNow();
    }

    /** Clean up */
    public void cleanup() {
        log("cleanup for device " + mDevice);
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

            removeDeferredMessages(DISCONNECT);

            if (mLastConnectionState != -1) {
                csipConnectionState(STATE_DISCONNECTED, mLastConnectionState);
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
                    if (!mNativeInterface.connect(mDevice)) {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                        break;
                    }
                    transitionTo(mConnecting);
                }
                case DISCONNECT -> Log.w(TAG, "Disconnected: DISCONNECT ignored: " + mDevice);
                case STACK_EVENT -> {
                    CsipSetCoordinatorStackEvent event = (CsipSetCoordinatorStackEvent) message.obj;
                    Log.d(TAG, "Disconnected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
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
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED ->
                        Log.w(TAG, "Ignore CsipSetCoordinator DISCONNECTED event: " + mDevice);
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(
                                TAG,
                                "Incoming CsipSetCoordinator Connecting request accepted: "
                                        + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(
                                TAG,
                                "Incoming CsipSetCoordinator Connecting request rejected: "
                                        + mDevice);
                        mNativeInterface.disconnect(mDevice);
                    }
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED -> {
                    Log.w(TAG, "CsipSetCoordinator Connected from Disconnected state: " + mDevice);
                    if (mService.okToConnect(mDevice)) {
                        Log.i(
                                TAG,
                                "Incoming CsipSetCoordinator Connected request accepted: "
                                        + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(
                                TAG,
                                "Incoming CsipSetCoordinator Connected request rejected: "
                                        + mDevice);
                        mNativeInterface.disconnect(mDevice);
                    }
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING ->
                        Log.w(TAG, "Ignore CsipSetCoordinator DISCONNECTING event: " + mDevice);
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
            sendMessageDelayed(CONNECT_TIMEOUT, sConnectTimeoutMs);
            csipConnectionState(STATE_CONNECTING, mLastConnectionState);
        }

        @Override
        public void exit() {
            log(
                    "Exit Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTING;
            removeMessages(CONNECT_TIMEOUT);
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
                case CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Connecting connection timeout: " + mDevice);
                    mNativeInterface.disconnect(mDevice);
                    CsipSetCoordinatorStackEvent disconnectEvent =
                            new CsipSetCoordinatorStackEvent(
                                    CsipSetCoordinatorStackEvent
                                            .EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 =
                            CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED;
                    sendMessage(STACK_EVENT, disconnectEvent);
                }
                case DISCONNECT -> {
                    log("Connecting: connection canceled to " + mDevice);
                    mNativeInterface.disconnect(mDevice);
                    transitionTo(mDisconnected);
                }
                case STACK_EVENT -> {
                    CsipSetCoordinatorStackEvent event = (CsipSetCoordinatorStackEvent) message.obj;
                    log("Connecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
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
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.w(TAG, "Connecting device disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED ->
                        transitionTo(mConnected);
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING -> {}
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING -> {
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
            mCurrentState = this;
            Log.i(
                    TAG,
                    "Enter Disconnecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(CONNECT_TIMEOUT, sConnectTimeoutMs);
            csipConnectionState(STATE_DISCONNECTING, mLastConnectionState);
        }

        @Override
        public void exit() {
            log(
                    "Exit Disconnecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTING;
            removeMessages(CONNECT_TIMEOUT);
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
                case CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Disconnecting connection timeout: " + mDevice);
                    mNativeInterface.disconnect(mDevice);
                    CsipSetCoordinatorStackEvent disconnectEvent =
                            new CsipSetCoordinatorStackEvent(
                                    CsipSetCoordinatorStackEvent
                                            .EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    disconnectEvent.device = mDevice;
                    disconnectEvent.valueInt1 =
                            CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED;
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
                    CsipSetCoordinatorStackEvent event = (CsipSetCoordinatorStackEvent) message.obj;
                    log("Disconnecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt1);
                            break;
                        default:
                            Log.e(TAG, "Disconnecting: ignoring stack event: " + event);
                            break;
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
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.w(TAG, "Disconnecting interrupted: device is connected: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(
                                TAG,
                                "Incoming CsipSetCoordinator Connected request rejected: "
                                        + mDevice);
                        mNativeInterface.disconnect(mDevice);
                    }
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING -> {
                    if (mService.okToConnect(mDevice)) {
                        Log.i(TAG, "Disconnecting interrupted: try to reconnect: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(
                                TAG,
                                "Incoming CsipSetCoordinator Connecting request rejected: "
                                        + mDevice);
                        mNativeInterface.disconnect(mDevice);
                    }
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING -> {}
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
            removeDeferredMessages(CONNECT);
            csipConnectionState(STATE_CONNECTED, mLastConnectionState);
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
                    if (!mNativeInterface.disconnect(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected state.
                        Log.e(TAG, "Connected: error disconnecting from " + mDevice);
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case STACK_EVENT -> {
                    CsipSetCoordinatorStackEvent event = (CsipSetCoordinatorStackEvent) message.obj;
                    log("Connected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
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
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from " + mDevice);
                    transitionTo(mDisconnected);
                }
                case CsipSetCoordinatorStackEvent.CONNECTION_STATE_DISCONNECTING -> {
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

    int getConnectionState() {
        String currentState = mCurrentState.getName();
        return switch (currentState) {
            case "Disconnected" -> STATE_DISCONNECTED;
            case "Connecting" -> STATE_CONNECTING;
            case "Connected" -> STATE_CONNECTED;
            case "Disconnecting" -> STATE_DISCONNECTING;
            default -> {
                Log.e(TAG, "Bad currentState: " + currentState);
                yield STATE_DISCONNECTED;
            }
        };
    }

    // This method does not check for error condition (newState == prevState)
    private void csipConnectionState(int newState, int prevState) {
        log(
                "Connection state "
                        + mDevice
                        + ": "
                        + getConnectionStateName(prevState)
                        + "->"
                        + getConnectionStateName(newState));

        Intent intent =
                new Intent(BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mService.sendBroadcast(intent, BLUETOOTH_CONNECT);

        mService.handleConnectionStateChanged(mDevice, prevState, newState);
    }

    private static String messageWhatToString(int what) {
        return switch (what) {
            case CONNECT -> "CONNECT";
            case DISCONNECT -> "DISCONNECT";
            case STACK_EVENT -> "STACK_EVENT";
            case CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            default -> Integer.toString(what);
        };
    }

    /** Dump the state machine logs */
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
