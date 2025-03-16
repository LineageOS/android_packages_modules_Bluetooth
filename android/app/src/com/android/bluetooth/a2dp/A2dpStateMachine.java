/*
 * Copyright (C) 2012 The Android Open Source Project
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

// Bluetooth A2DP StateMachine. There is one instance per remote device.
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
package com.android.bluetooth.a2dp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.content.Intent;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

final class A2dpStateMachine extends StateMachine {
    private static final String TAG = A2dpStateMachine.class.getSimpleName();

    static final int MESSAGE_CONNECT = 1;
    static final int MESSAGE_DISCONNECT = 2;
    static final int MESSAGE_STACK_EVENT = 101;
    private static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final A2dpService mA2dpService;
    private final A2dpNativeInterface mA2dpNativeInterface;
    private final BluetoothDevice mDevice;
    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Disconnecting mDisconnecting;
    private final Connected mConnected;
    private final boolean mA2dpOffloadEnabled;

    private boolean mIsPlaying = false;
    private int mConnectionState = STATE_DISCONNECTED;
    private int mLastConnectionState = -1;
    private BluetoothCodecStatus mCodecStatus;

    A2dpStateMachine(
            A2dpService a2dpService,
            BluetoothDevice device,
            A2dpNativeInterface a2dpNativeInterface,
            boolean a2dpOffloadEnabled,
            Looper looper) {
        super(TAG, looper);

        // Let the logging framework enforce the log level. TAG is set above in the parent
        // constructor.
        setDbg(true);

        mDevice = device;
        mA2dpService = a2dpService;
        mA2dpNativeInterface = a2dpNativeInterface;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);
        mA2dpOffloadEnabled = a2dpOffloadEnabled;

        setInitialState(mDisconnected);
        start();
    }

    public void doQuit() {
        log("doQuit for device " + mDevice);
        if (mConnectionState != STATE_DISCONNECTED && mLastConnectionState != -1) {
            // Broadcast CONNECTION_STATE_CHANGED when A2dpService is turned off while
            // the device is connected
            broadcastConnectionState(STATE_DISCONNECTED, mConnectionState);
        }
        if (mIsPlaying) {
            // Broadcast AUDIO_STATE_CHANGED when A2dpService is turned off while
            // the device is playing
            mIsPlaying = false;
            broadcastAudioState(BluetoothA2dp.STATE_NOT_PLAYING, BluetoothA2dp.STATE_PLAYING);
        }
        quitNow();
    }

    @VisibleForTesting
    class Disconnected extends State {
        @Override
        public void enter() {
            Message currentMessage = getCurrentMessage();
            Log.i(
                    TAG,
                    "Enter Disconnected("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
            mConnectionState = STATE_DISCONNECTED;

            removeDeferredMessages(MESSAGE_DISCONNECT);

            if (mLastConnectionState != -1) {
                // Don't broadcast during startup
                broadcastConnectionState(mConnectionState, mLastConnectionState);
                if (mIsPlaying) {
                    Log.i(TAG, "Disconnected: stopped playing: " + mDevice);
                    mIsPlaying = false;
                    broadcastAudioState(
                            BluetoothA2dp.STATE_NOT_PLAYING, BluetoothA2dp.STATE_PLAYING);
                }
            }

            logFailureIfNeeded();
        }

        @Override
        public void exit() {
            Message currentMessage = getCurrentMessage();
            log(
                    "Exit Disconnected("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
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
                    Log.i(TAG, "Connecting to " + mDevice);
                    if (!mA2dpNativeInterface.connectA2dp(mDevice)) {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                        break;
                    }
                    if (mA2dpService.okToConnect(mDevice, true)) {
                        transitionTo(mConnecting);
                    } else {
                        // Reject the request and stay in Disconnected state
                        Log.w(TAG, "Outgoing A2DP Connecting request rejected: " + mDevice);
                    }
                }
                case MESSAGE_DISCONNECT ->
                        Log.w(TAG, "Disconnected: DISCONNECT ignored: " + mDevice);
                case MESSAGE_STACK_EVENT -> {
                    A2dpStackEvent event = (A2dpStackEvent) message.obj;
                    log("Disconnected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt);
                        case A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED ->
                                processCodecConfigEvent(event.codecStatus);
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
        private void processConnectionEvent(int event) {
            switch (event) {
                case STATE_CONNECTING -> {
                    if (mA2dpService.okToConnect(mDevice, false)) {
                        Log.i(TAG, "Incoming A2DP Connecting request accepted: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming A2DP Connecting request rejected: " + mDevice);
                        mA2dpNativeInterface.disconnectA2dp(mDevice);
                    }
                }
                case STATE_CONNECTED -> {
                    Log.w(TAG, "A2DP Connected from Disconnected state: " + mDevice);
                    if (mA2dpService.okToConnect(mDevice, false)) {
                        Log.i(TAG, "Incoming A2DP Connected request accepted: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnected state itself
                        Log.w(TAG, "Incoming A2DP Connected request rejected: " + mDevice);
                        mA2dpNativeInterface.disconnectA2dp(mDevice);
                    }
                }
                default -> Log.e(TAG, "Incorrect event: " + event + " device: " + mDevice);
            }
        }

        private void logFailureIfNeeded() {
            if (mLastConnectionState == STATE_CONNECTING
                    || mLastConnectionState == STATE_DISCONNECTED) {
                // Result for disconnected -> disconnected is unknown as it should
                // not have occurred.
                int result =
                        (mLastConnectionState == STATE_CONNECTING)
                                ? BluetoothProtoEnums.RESULT_FAILURE
                                : BluetoothProtoEnums.RESULT_UNKNOWN;

                BluetoothStatsLog.write(
                        BluetoothStatsLog.BLUETOOTH_PROFILE_CONNECTION_ATTEMPTED,
                        BluetoothProfile.A2DP,
                        result,
                        mLastConnectionState,
                        STATE_DISCONNECTED,
                        BluetoothProtoEnums.REASON_UNEXPECTED_STATE,
                        MetricsLogger.getInstance().getRemoteDeviceInfoProto(mDevice));
            }
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        @Override
        public void enter() {
            Message currentMessage = getCurrentMessage();
            Log.i(
                    TAG,
                    "Enter Connecting("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_CONNECTING;
            broadcastConnectionState(mConnectionState, mLastConnectionState);
        }

        @Override
        public void exit() {
            Message currentMessage = getCurrentMessage();
            log(
                    "Exit Connecting("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
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
                    if (Flags.a2dpSmIgnoreConnectEventsInConnectingState()
                            && !hasDeferredMessages(MESSAGE_DISCONNECT)) {
                        Log.w(TAG, "Connecting: CONNECT ignored: " + mDevice);
                    } else {
                        deferMessage(message);
                    }
                }
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Connecting connection timeout: " + mDevice);
                    mA2dpNativeInterface.disconnectA2dp(mDevice);
                    A2dpStackEvent event =
                            new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    event.device = mDevice;
                    event.valueInt = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, event);
                    MetricsLogger.getInstance()
                            .count(BluetoothProtoEnums.A2DP_CONNECTION_TIMEOUT, 1);
                }
                case MESSAGE_DISCONNECT -> {
                    // Cancel connection
                    Log.i(TAG, "Connecting: connection canceled to " + mDevice);
                    mA2dpNativeInterface.disconnectA2dp(mDevice);
                    transitionTo(mDisconnected);
                }
                case MESSAGE_STACK_EVENT -> {
                    A2dpStackEvent event = (A2dpStackEvent) message.obj;
                    log("Connecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt);
                        case A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED ->
                                processCodecConfigEvent(event.codecStatus);
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
        private void processConnectionEvent(int event) {
            switch (event) {
                case STATE_DISCONNECTED -> {
                    Log.w(TAG, "Connecting device disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> transitionTo(mConnected);
                case STATE_DISCONNECTING -> {
                    Log.w(TAG, "Connecting interrupted: device is disconnecting: " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default -> Log.e(TAG, "Incorrect event: " + event);
            }
        }
    }

    @VisibleForTesting
    class Disconnecting extends State {
        @Override
        public void enter() {
            Message currentMessage = getCurrentMessage();
            Log.i(
                    TAG,
                    "Enter Disconnecting("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            mConnectionState = STATE_DISCONNECTING;
            broadcastConnectionState(mConnectionState, mLastConnectionState);
        }

        @Override
        public void exit() {
            Message currentMessage = getCurrentMessage();
            log(
                    "Exit Disconnecting("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
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
                    mA2dpNativeInterface.disconnectA2dp(mDevice);
                    A2dpStackEvent event =
                            new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                    event.device = mDevice;
                    event.valueInt = STATE_DISCONNECTED;
                    sendMessage(MESSAGE_STACK_EVENT, event);
                }
                case MESSAGE_STACK_EVENT -> {
                    A2dpStackEvent event = (A2dpStackEvent) message.obj;
                    log("Disconnecting: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt);
                        case A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED ->
                                processCodecConfigEvent(event.codecStatus);
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
        private void processConnectionEvent(int event) {
            switch (event) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> {
                    if (mA2dpService.okToConnect(mDevice, false)) {
                        Log.w(TAG, "Disconnecting interrupted: device is connected: " + mDevice);
                        transitionTo(mConnected);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming A2DP Connected request rejected: " + mDevice);
                        mA2dpNativeInterface.disconnectA2dp(mDevice);
                    }
                }
                case STATE_CONNECTING -> {
                    if (mA2dpService.okToConnect(mDevice, false)) {
                        Log.i(TAG, "Disconnecting interrupted: try to reconnect: " + mDevice);
                        transitionTo(mConnecting);
                    } else {
                        // Reject the connection and stay in Disconnecting state
                        Log.w(TAG, "Incoming A2DP Connecting request rejected: " + mDevice);
                        mA2dpNativeInterface.disconnectA2dp(mDevice);
                    }
                }
                default -> Log.e(TAG, "Incorrect event: " + event);
            }
        }
    }

    @VisibleForTesting
    class Connected extends State {
        @Override
        public void enter() {
            Message currentMessage = getCurrentMessage();
            Log.i(
                    TAG,
                    "Enter Connected("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
            mConnectionState = STATE_CONNECTED;

            removeDeferredMessages(MESSAGE_CONNECT);

            // Each time a device connects, we want to re-check if it supports optional
            // codecs (perhaps it's had a firmware update, etc.) and save that state if
            // it differs from what we had saved before.
            mA2dpService.updateOptionalCodecsSupport(mDevice);
            mA2dpService.updateLowLatencyAudioSupport(mDevice);

            broadcastConnectionState(mConnectionState, mLastConnectionState);
            // Upon connected, the audio starts out as stopped
            broadcastAudioState(BluetoothA2dp.STATE_NOT_PLAYING, BluetoothA2dp.STATE_PLAYING);
            logSuccessIfNeeded();
        }

        @Override
        public void exit() {
            Message currentMessage = getCurrentMessage();
            log(
                    "Exit Connected("
                            + mDevice
                            + "): "
                            + (currentMessage == null
                                    ? "null"
                                    : messageWhatToString(currentMessage.what)));
            mLastConnectionState = STATE_CONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            log("Connected process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_DISCONNECT -> {
                    Log.i(TAG, "Disconnecting from " + mDevice);
                    if (!mA2dpNativeInterface.disconnectA2dp(mDevice)) {
                        // If error in the native stack, transition directly to Disconnected
                        // state.
                        Log.e(TAG, "Connected: error disconnecting from " + mDevice);
                        transitionTo(mDisconnected);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                case MESSAGE_STACK_EVENT -> {
                    A2dpStackEvent event = (A2dpStackEvent) message.obj;
                    log("Connected: stack event: " + event);
                    if (!mDevice.equals(event.device)) {
                        Log.wtf(TAG, "Device(" + mDevice + "): event mismatch: " + event);
                    }
                    switch (event.type) {
                        case A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                                processConnectionEvent(event.valueInt);
                        case A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED ->
                                processAudioStateEvent(event.valueInt);
                        case A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED ->
                                processCodecConfigEvent(event.codecStatus);
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
        private void processConnectionEvent(int event) {
            switch (event) {
                case STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_DISCONNECTING -> {
                    Log.i(TAG, "Disconnecting from " + mDevice);
                    transitionTo(mDisconnecting);
                }
                default ->
                        Log.e(TAG, "Connection State Device: " + mDevice + " bad event: " + event);
            }
        }

        // in Connected state
        private void processAudioStateEvent(int state) {
            switch (state) {
                case A2dpStackEvent.AUDIO_STATE_STARTED -> {
                    synchronized (this) {
                        if (!mIsPlaying) {
                            Log.i(TAG, "Connected: started playing: " + mDevice);
                            mIsPlaying = true;
                            broadcastAudioState(
                                    BluetoothA2dp.STATE_PLAYING, BluetoothA2dp.STATE_NOT_PLAYING);
                        }
                    }
                }
                case A2dpStackEvent.AUDIO_STATE_REMOTE_SUSPEND,
                        A2dpStackEvent.AUDIO_STATE_STOPPED -> {
                    synchronized (this) {
                        if (mIsPlaying) {
                            Log.i(TAG, "Connected: stopped playing: " + mDevice);
                            mIsPlaying = false;
                            broadcastAudioState(
                                    BluetoothA2dp.STATE_NOT_PLAYING, BluetoothA2dp.STATE_PLAYING);
                        }
                    }
                }
                default -> Log.e(TAG, "Audio State Device: " + mDevice + " bad state: " + state);
            }
        }

        private void logSuccessIfNeeded() {
            if (mLastConnectionState == STATE_CONNECTING
                    || mLastConnectionState == STATE_DISCONNECTED) {
                BluetoothStatsLog.write(
                        BluetoothStatsLog.BLUETOOTH_PROFILE_CONNECTION_ATTEMPTED,
                        BluetoothProfile.A2DP,
                        BluetoothProtoEnums.RESULT_SUCCESS,
                        mLastConnectionState,
                        STATE_CONNECTED,
                        BluetoothProtoEnums.REASON_SUCCESS,
                        MetricsLogger.getInstance().getRemoteDeviceInfoProto(mDevice));
            }
        }
    }

    int getConnectionState() {
        return mConnectionState;
    }

    BluetoothDevice getDevice() {
        return mDevice;
    }

    boolean isConnected() {
        synchronized (this) {
            return (getConnectionState() == STATE_CONNECTED);
        }
    }

    boolean isPlaying() {
        synchronized (this) {
            return mIsPlaying;
        }
    }

    BluetoothCodecStatus getCodecStatus() {
        synchronized (this) {
            return mCodecStatus;
        }
    }

    // NOTE: This event is processed in any state
    @VisibleForTesting
    void processCodecConfigEvent(BluetoothCodecStatus newCodecStatus) {
        BluetoothCodecConfig prevCodecConfig = null;
        BluetoothCodecStatus prevCodecStatus = mCodecStatus;

        synchronized (this) {
            if (mCodecStatus != null) {
                prevCodecConfig = mCodecStatus.getCodecConfig();
            }
            mCodecStatus = newCodecStatus;
        }

        // The following is a large enough debug operation such that we want to guard it was an
        // isLoggable check
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                    TAG,
                    "A2DP Codec Config: "
                            + prevCodecConfig
                            + "->"
                            + newCodecStatus.getCodecConfig());
            for (BluetoothCodecConfig codecConfig : newCodecStatus.getCodecsLocalCapabilities()) {
                Log.d(TAG, "A2DP Codec Local Capability: " + codecConfig);
            }
            for (BluetoothCodecConfig codecConfig :
                    newCodecStatus.getCodecsSelectableCapabilities()) {
                Log.d(TAG, "A2DP Codec Selectable Capability: " + codecConfig);
            }
        }

        if (isConnected() && !sameSelectableCodec(prevCodecStatus, mCodecStatus)) {
            // Remote selectable codec could be changed if codec config changed
            // in connected state, we need to re-check optional codec status
            // for this codec change event.
            mA2dpService.updateOptionalCodecsSupport(mDevice);
        }
        mA2dpService.updateLowLatencyAudioSupport(mDevice);
        if (mA2dpOffloadEnabled) {
            boolean update = false;
            BluetoothCodecConfig newCodecConfig = mCodecStatus.getCodecConfig();
            if ((prevCodecConfig != null)
                    && (prevCodecConfig.getCodecType() != newCodecConfig.getCodecType())) {
                update = true;
            } else if (!newCodecConfig.sameAudioFeedingParameters(prevCodecConfig)) {
                update = true;
            } else if ((newCodecConfig.getCodecType()
                            == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC)
                    && (prevCodecConfig != null)
                    && (prevCodecConfig.getCodecSpecific1()
                            != newCodecConfig.getCodecSpecific1())) {
                update = true;
            } else if ((newCodecConfig.getCodecType()
                            == BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS)
                    && (prevCodecConfig != null)
                    // check framesize field
                    && (prevCodecConfig.getCodecSpecific1()
                            != newCodecConfig.getCodecSpecific1())) {
                update = true;
            }
            if (update) {
                mA2dpService.codecConfigUpdated(mDevice, mCodecStatus, false);
            }
            return;
        }

        boolean sameAudioFeedingParameters =
                newCodecStatus.getCodecConfig().sameAudioFeedingParameters(prevCodecConfig);
        mA2dpService.codecConfigUpdated(mDevice, mCodecStatus, sameAudioFeedingParameters);
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

        Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mA2dpService.handleConnectionStateChanged(mDevice, prevState, newState);
        mA2dpService.sendBroadcast(
                intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastAudioState(int newState, int prevState) {
        log(
                "A2DP Playing state : device: "
                        + mDevice
                        + " State:"
                        + audioStateToString(prevState)
                        + "->"
                        + audioStateToString(newState));
        Intent intent = new Intent(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mA2dpService.sendBroadcast(
                intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    @Override
    protected String getLogRecString(Message msg) {
        StringBuilder builder = new StringBuilder();
        builder.append(messageWhatToString(msg.what));
        builder.append(": ");
        builder.append("arg1=")
                .append(msg.arg1)
                .append(", arg2=")
                .append(msg.arg2)
                .append(", obj=")
                .append(msg.obj);
        return builder.toString();
    }

    private static boolean sameSelectableCodec(
            BluetoothCodecStatus prevCodecStatus, BluetoothCodecStatus newCodecStatus) {
        if (prevCodecStatus == null || newCodecStatus == null) {
            return false;
        }
        List<BluetoothCodecConfig> c1 = prevCodecStatus.getCodecsSelectableCapabilities();
        List<BluetoothCodecConfig> c2 = newCodecStatus.getCodecsSelectableCapabilities();
        if (c1 == null) {
            return (c2 == null);
        }
        if (c2 == null) {
            return false;
        }
        if (c1.size() != c2.size()) {
            return false;
        }
        return c1.containsAll(c2);
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

    private static String audioStateToString(int state) {
        return switch (state) {
            case BluetoothA2dp.STATE_PLAYING -> "PLAYING";
            case BluetoothA2dp.STATE_NOT_PLAYING -> "NOT_PLAYING";
            default -> Integer.toString(state);
        };
    }

    public void dump(StringBuilder sb) {
        boolean isActive = Objects.equals(mDevice, mA2dpService.getActiveDevice());
        ProfileService.println(
                sb, "=== A2dpStateMachine for " + mDevice + (isActive ? " (Active) ===" : " ==="));
        ProfileService.println(
                sb, "  getConnectionPolicy: " + mA2dpService.getConnectionPolicy(mDevice));
        ProfileService.println(
                sb,
                "  mConnectionState: "
                        + getConnectionStateName(mConnectionState)
                        + ", mLastConnectionState: "
                        + getConnectionStateName(mLastConnectionState));
        ProfileService.println(sb, "  mIsPlaying: " + mIsPlaying);
        ProfileService.println(
                sb,
                "  getSupportsOptionalCodecs: "
                        + mA2dpService.getSupportsOptionalCodecs(mDevice)
                        + ", getOptionalCodecsEnabled: "
                        + mA2dpService.getOptionalCodecsEnabled(mDevice));
        synchronized (this) {
            if (mCodecStatus != null) {
                ProfileService.println(sb, "  mCodecConfig: " + mCodecStatus.getCodecConfig());
                ProfileService.println(sb, "  mCodecsSelectableCapabilities:");
                for (BluetoothCodecConfig config : mCodecStatus.getCodecsSelectableCapabilities()) {
                    ProfileService.println(sb, "    " + config);
                }
            }
        }
        ProfileService.println(sb, "  StateMachine: " + this.toString());
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
