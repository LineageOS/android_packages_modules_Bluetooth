/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.a2dpsink;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAudioConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

class A2dpSinkStateMachine extends StateMachine {
    private static final String TAG = A2dpSinkStateMachine.class.getSimpleName();

    // 0->99 Events from Outside
    static final int MESSAGE_CONNECT = 1;
    @VisibleForTesting static final int MESSAGE_DISCONNECT = 2;

    // 100->199 Internal Events
    @VisibleForTesting static final int CLEANUP = 100;
    @VisibleForTesting static final int MESSAGE_CONNECT_TIMEOUT = 101;
    @VisibleForTesting static final int MESSAGE_DISCONNECT_TIMEOUT = 102;

    // 200->299 Events from Native
    static final int MESSAGE_CONNECTION_STATE_CHANGED = 200;
    static final int MESSAGE_AUDIO_STATE_CHANGED = 201;
    static final int MESSAGE_AUDIO_CONFIG_CHANGED = 202;

    static final int CONNECT_TIMEOUT_MS = 10000;
    static final int DISCONNECT_TIMEOUT_MS = 4000;

    protected final BluetoothDevice mDevice;
    protected final A2dpSinkService mService;
    protected final A2dpSinkNativeInterface mNativeInterface;
    protected final Disconnected mDisconnected;
    protected final Connecting mConnecting;
    protected final Connected mConnected;
    protected final Disconnecting mDisconnecting;

    protected int mMostRecentState = STATE_DISCONNECTED;
    protected BluetoothAudioConfig mAudioConfig = null;

    A2dpSinkStateMachine(
            A2dpSinkService service,
            BluetoothDevice device,
            Looper looper,
            A2dpSinkNativeInterface nativeInterface) {
        super(TAG, looper);
        mDevice = device;
        mService = service;
        mNativeInterface = nativeInterface;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mDisconnecting = new Disconnecting();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mConnected);
        addState(mDisconnecting);

        setInitialState(mDisconnected);
        start(false);
    }

    // ---------------------------------------------------------------------------------------------
    // State Management
    // ---------------------------------------------------------------------------------------------

    /**
     * Get the underlying device tracked by this state machine
     *
     * @return device in focus
     */
    public synchronized BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Get the current connection state
     *
     * @return current State
     */
    public int getState() {
        return mMostRecentState;
    }

    /** Set the current connection state */
    protected void setMostRecentState(int currentState) {
        if (mMostRecentState == currentState) {
            return;
        }

        debug(
                "Connection state changed: "
                        + BluetoothProfile.getConnectionStateName(mMostRecentState)
                        + "->"
                        + BluetoothProfile.getConnectionStateName(currentState));

        Intent intent = new Intent(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, mMostRecentState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, currentState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mService.connectionStateChanged(mDevice, mMostRecentState, currentState);
        mMostRecentState = currentState;
        mService.sendBroadcast(intent, BLUETOOTH_CONNECT, Util.getTempBroadcastBundle());
    }

    /** Get current audio config */
    BluetoothAudioConfig getAudioConfig() {
        return mAudioConfig;
    }

    // ---------------------------------------------------------------------------------------------
    // Commands and Events
    // ---------------------------------------------------------------------------------------------

    /** Send the Connect command */
    final void connect() {
        dispatchMessage(MESSAGE_CONNECT);
    }

    /** Send the Disconnect command asynchronously */
    final void disconnect() {
        sendMessage(MESSAGE_DISCONNECT);
    }

    final void onConnectionStateChanged(int state) {
        dispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, state);
    }

    final void onAudioStateChanged(int state) {
        sendMessage(MESSAGE_AUDIO_STATE_CHANGED, state);
    }

    final void onAudioConfigChanged(int sampleRate, int channelCount) {
        sendMessage(MESSAGE_AUDIO_CONFIG_CHANGED, sampleRate, channelCount);
    }

    // ---------------------------------------------------------------------------------------------
    // States and Message Handling
    // ---------------------------------------------------------------------------------------------

    class Disconnected extends State {
        @Override
        public void enter() {
            debug("Enter");
            if (mMostRecentState != STATE_DISCONNECTED) {
                sendMessage(CLEANUP);
            }
            setMostRecentState(STATE_DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("Handle msg=" + messageToString(msg.what));
            switch (msg.what) {
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(msg.arg1);
                case MESSAGE_CONNECT -> {
                    debug("Placing outgoing connection request");
                    mNativeInterface.connectA2dpSink(mDevice);
                    transitionTo(mConnecting);
                }
                case CLEANUP -> {
                    mService.removeStateMachine(A2dpSinkStateMachine.this);
                    debug("State machine removed");
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        void processConnectionEvent(int state) {
            debug("Handle state=" + BluetoothProfile.getConnectionStateName(state));
            switch (state) {
                case STATE_CONNECTING -> {
                    if (mService.getConnectionPolicy(mDevice) == CONNECTION_POLICY_FORBIDDEN) {
                        warn("Reject connection, policy=CONNECTION_POLICY_FORBIDDEN");
                        mNativeInterface.disconnectA2dpSink(mDevice);
                    } else {
                        transitionTo(mConnecting);
                    }
                }
                case STATE_CONNECTED -> {
                    if (mService.getConnectionPolicy(mDevice) == CONNECTION_POLICY_FORBIDDEN) {
                        warn("Reject connection, policy=CONNECTION_POLICY_FORBIDDEN");
                        mNativeInterface.disconnectA2dpSink(mDevice);
                    } else {
                        setMostRecentState(STATE_CONNECTING);
                        transitionTo(mConnected);
                    }
                }
                case STATE_DISCONNECTED -> sendMessage(CLEANUP);
                default -> {} // Nothing to do
            }
        }
    }

    class Connecting extends State {
        @Override
        public void enter() {
            debug("Enter");
            setMostRecentState(STATE_CONNECTING);
            removeMessages(CLEANUP);
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT_MS);
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("Handle msg=" + messageToString(msg.what));
            switch (msg.what) {
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(msg.arg1);
                case MESSAGE_CONNECT_TIMEOUT -> transitionTo(mDisconnected);
                case MESSAGE_DISCONNECT -> {
                    debug("Received disconnect message while connecting. Deferred");
                    deferMessage(msg);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        void processConnectionEvent(int state) {
            debug("Handle state=" + BluetoothProfile.getConnectionStateName(state));
            switch (state) {
                case STATE_CONNECTED -> transitionTo(mConnected);
                case STATE_DISCONNECTED -> transitionTo(mDisconnected);
                default -> {} // Nothing to do
            }
        }

        @Override
        public void exit() {
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }
    }

    class Connected extends State {
        @Override
        public void enter() {
            debug("Enter");
            removeMessages(CLEANUP);
            setMostRecentState(STATE_CONNECTED);
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("Handle msg=" + messageToString(msg.what));
            switch (msg.what) {
                case MESSAGE_DISCONNECT -> {
                    transitionTo(mDisconnecting);
                    mNativeInterface.disconnectA2dpSink(mDevice);
                }
                case MESSAGE_AUDIO_STATE_CHANGED -> processAudioStateEvent(msg.arg1);
                case MESSAGE_AUDIO_CONFIG_CHANGED -> processAudioConfigEvent(msg.arg1, msg.arg2);
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(msg.arg1);
                default -> {
                    return false;
                }
            }
            return true;
        }

        void processConnectionEvent(int state) {
            debug("Handle state=" + BluetoothProfile.getConnectionStateName(state));
            switch (state) {
                case STATE_DISCONNECTING -> transitionTo(mDisconnecting);
                case STATE_DISCONNECTED -> {
                    setMostRecentState(STATE_DISCONNECTING);
                    transitionTo(mDisconnected);
                }
                default -> {} // Nothing to do
            }
        }

        void processAudioStateEvent(int event) {
            debug(
                    "Audio state changed, event="
                            + A2dpSinkNativeInterface.audioStateToString(event));
        }

        void processAudioConfigEvent(int rate, int channels) {
            debug("Config changed, sampleRate=" + rate + ", channelCount=" + channels);
            mAudioConfig = new BluetoothAudioConfig(rate, channels, AudioFormat.ENCODING_PCM_16BIT);
        }
    }

    protected class Disconnecting extends State {
        @Override
        public void enter() {
            debug("Enter");
            setMostRecentState(STATE_DISCONNECTING);
            sendMessageDelayed(MESSAGE_DISCONNECT_TIMEOUT, DISCONNECT_TIMEOUT_MS);
        }

        @Override
        public boolean processMessage(Message msg) {
            debug("Handle msg=" + messageToString(msg.what));
            switch (msg.what) {
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(msg.arg1);
                case MESSAGE_DISCONNECT_TIMEOUT -> transitionTo(mDisconnected);
                case MESSAGE_CONNECT, MESSAGE_DISCONNECT -> deferMessage(msg);
                default -> {
                    return false;
                }
            }
            return true;
        }

        void processConnectionEvent(int state) {
            debug("Handle state=" + BluetoothProfile.getConnectionStateName(state));
            switch (state) {
                case STATE_DISCONNECTED -> transitionTo(mDisconnected);
                default -> {} // Nothing to do
            }
        }

        @Override
        public void exit() {
            removeMessages(MESSAGE_DISCONNECT_TIMEOUT);
        }
    }

    @Override
    protected void unhandledMessage(Message msg) {
        warn("Unhandled msg=" + messageToString(msg.what));
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    private void warn(String msg) {
        Log.w(TAG, "[" + mDevice + "] " + getCurrentState().getName() + ": " + msg);
    }

    private void debug(String msg) {
        Log.d(TAG, "[" + mDevice + "] " + getCurrentState().getName() + ": " + msg);
    }

    private static final String messageToString(int what) {
        return switch (what) {
            case -1 /* SM_QUIT_CMD */ -> "SM_QUIT_CMD";
            case -2 /* SM_INIT_CMD */ -> "SM_INIT_CMD";
            case MESSAGE_CONNECT -> "MESSAGE_CONNECT";
            case MESSAGE_DISCONNECT -> "MESSAGE_DISCONNECT";
            case MESSAGE_CONNECT_TIMEOUT -> "MESSAGE_CONNECT_TIMEOUT";
            case MESSAGE_DISCONNECT_TIMEOUT -> "MESSAGE_DISCONNECT_TIMEOUT";
            case MESSAGE_CONNECTION_STATE_CHANGED -> "MESSAGE_CONNECTION_STATE_CHANGED";
            case MESSAGE_AUDIO_STATE_CHANGED -> "MESSAGE_AUDIO_STATE_CHANGED";
            case MESSAGE_AUDIO_CONFIG_CHANGED -> "MESSAGE_AUDIO_CONFIG_CHANGED";
            default -> "MESSAGE_UNKNOWN_" + what;
        };
    }

    /**
     * Dump the current State Machine to the string builder.
     *
     * @param sb output string
     */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice + " " + this.toString());
    }
}
