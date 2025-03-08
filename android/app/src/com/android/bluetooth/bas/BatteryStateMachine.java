/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.bas;

import static android.bluetooth.BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
import static android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK;
import static android.bluetooth.BluetoothDevice.PHY_LE_2M_MASK;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Scanner;
import java.util.UUID;

/** It manages Battery service of a BLE device */
public class BatteryStateMachine extends StateMachine {
    private static final String TAG = BatteryStateMachine.class.getSimpleName();

    static final UUID GATT_BATTERY_SERVICE_UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID GATT_BATTERY_LEVEL_CHARACTERISTIC_UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static final UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int MESSAGE_CONNECT = 1;
    static final int MESSAGE_DISCONNECT = 2;
    @VisibleForTesting static final int MESSAGE_CONNECTION_STATE_CHANGED = 3;
    private static final int MESSAGE_CONNECT_TIMEOUT = 201;

    @VisibleForTesting static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Connected mConnected;
    private final Disconnecting mDisconnecting;
    private int mLastConnectionState = STATE_DISCONNECTED;

    private final BatteryService mService;

    BluetoothGatt mBluetoothGatt;
    private final GattCallback mGattCallback = new GattCallback();
    final BluetoothDevice mDevice;

    BatteryStateMachine(BatteryService service, BluetoothDevice device, Looper looper) {
        super(TAG, looper);
        mService = requireNonNull(service);
        mDevice = device;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mDisconnecting = new Disconnecting();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);

        setInitialState(mDisconnected);
        start();
    }

    /** Quits the state machine */
    public void doQuit() {
        log("doQuit for device " + mDevice);
        quitNow();
    }

    /** Cleans up the resources the state machine held. */
    @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt interface
    public void cleanup() {
        log("cleanup for device " + mDevice);
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    BluetoothDevice getDevice() {
        return mDevice;
    }

    synchronized boolean isConnected() {
        return mLastConnectionState == STATE_CONNECTED;
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

    /** Dumps battery state machine state. */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice);
        ProfileService.println(sb, "  StateMachine: " + this);
        ProfileService.println(sb, "  BluetoothGatt: " + mBluetoothGatt);
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

    @BluetoothProfile.BtProfileState
    int getConnectionState() {
        return mLastConnectionState;
    }

    void dispatchConnectionStateChanged(int toState) {
        log(
                "Connection state change "
                        + mDevice
                        + ": "
                        + getConnectionStateName(mLastConnectionState)
                        + "->"
                        + getConnectionStateName(toState));

        mService.handleConnectionStateChanged(mDevice, mLastConnectionState, toState);
    }

    // Allow test to abstract the unmockable mBluetoothGatt
    @VisibleForTesting
    @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt interface
    boolean connectGatt() {
        mDevice.setAttributionSource(
                (new AttributionSource.Builder(AttributionSource.myAttributionSource()))
                        .setAttributionTag("BatteryService")
                        .build());
        mBluetoothGatt =
                mDevice.connectGatt(
                        mService,
                        /* autoConnect= */ false,
                        mGattCallback,
                        TRANSPORT_LE,
                        /* opportunistic= */ true,
                        PHY_LE_1M_MASK | PHY_LE_2M_MASK,
                        getHandler());
        return mBluetoothGatt != null;
    }

    // Allow test to abstract the unmockable BluetoothGatt
    @VisibleForTesting
    @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt interface
    void disconnectGatt() {
        mBluetoothGatt.disconnect();
    }

    // Allow test to abstract the unmockable BluetoothGatt
    @VisibleForTesting
    @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt interface
    void discoverServicesGatt() {
        mBluetoothGatt.discoverServices();
    }

    @VisibleForTesting
    void updateBatteryLevel(byte[] value) {
        if (value.length == 0) {
            return;
        }
        int batteryLevel = value[0] & 0xFF;

        mService.handleBatteryChanged(mDevice, batteryLevel);
    }

    @VisibleForTesting
    void resetBatteryLevel() {
        mService.handleBatteryChanged(mDevice, BATTERY_LEVEL_UNKNOWN);
    }

    static void log(String tag, String msg) {
        Log.d(tag, msg);
    }

    @VisibleForTesting
    class Disconnected extends State {
        private static final String TAG =
                BatteryStateMachine.TAG + "." + Disconnected.class.getSimpleName();

        @Override
        public void enter() {
            log(TAG, "Enter (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));

            if (mBluetoothGatt != null) {
                mBluetoothGatt.close();
                mBluetoothGatt = null;
            }

            if (mLastConnectionState != STATE_DISCONNECTED) {
                // Don't broadcast during startup
                dispatchConnectionStateChanged(STATE_DISCONNECTED);
            }
            mLastConnectionState = STATE_DISCONNECTED;
        }

        @Override
        public void exit() {
            log(TAG, "Exit (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
        }

        @Override
        public boolean processMessage(Message message) {
            log(TAG, "Process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT -> {
                    log(TAG, "Connecting to " + mDevice);
                    if (!mService.canConnect(mDevice)) {
                        Log.w(TAG, "Battery connecting request rejected: " + mDevice);
                    } else {
                        if (connectGatt()) {
                            transitionTo(mConnecting);
                        } else {
                            Log.w(
                                    TAG,
                                    "Battery connecting request rejected due to "
                                            + "GATT connection rejection: "
                                            + mDevice);
                        }
                    }
                }
                default -> {
                    Log.e(TAG, "Unexpected message: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        private static final String TAG =
                BatteryStateMachine.TAG + "." + Connecting.class.getSimpleName();

        @Override
        public void enter() {
            log(TAG, "Enter (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
            dispatchConnectionStateChanged(STATE_CONNECTING);
            mLastConnectionState = STATE_CONNECTING;
        }

        @Override
        public void exit() {
            log(TAG, "Exit (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
        }

        @Override
        public boolean processMessage(Message message) {
            log(TAG, "process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_DISCONNECT -> {
                    log(TAG, "Connection canceled to " + mDevice);
                    disconnectGatt();
                    // As we're not yet connected we don't need to wait for callbacks.
                    transitionTo(mDisconnected);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> processConnectionEvent(message.arg1);
                default -> {
                    Log.e(TAG, "Unexpected message: " + messageWhatToString(message.what));
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }

        // in Connecting state
        private void processConnectionEvent(int state) {
            switch (state) {
                case STATE_DISCONNECTED -> {
                    Log.w(TAG, "Device disconnected: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case STATE_CONNECTED -> transitionTo(mConnected);
                default -> Log.e(TAG, "Incorrect state: " + state);
            }
        }
    }

    @VisibleForTesting
    class Disconnecting extends State {
        private static final String TAG =
                BatteryStateMachine.TAG + "." + Disconnecting.class.getSimpleName();

        @Override
        public void enter() {
            log(TAG, "Enter (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(MESSAGE_CONNECT_TIMEOUT, CONNECT_TIMEOUT.toMillis());
            dispatchConnectionStateChanged(STATE_DISCONNECTING);
            mLastConnectionState = STATE_DISCONNECTING;
        }

        @Override
        public void exit() {
            log(TAG, "Exit (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
            removeMessages(MESSAGE_CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            log(TAG, "Process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_CONNECT_TIMEOUT -> {
                    Log.w(TAG, "Disconnection timeout: " + mDevice);
                    transitionTo(mDisconnected);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1);
                }
                default -> {
                    Log.e(TAG, "Unexpected message: " + messageWhatToString(message.what));
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
                    // TODO: Check if connect while disconnecting is okay. It is related to
                    // MESSAGE_CONNECT_TIMEOUT as well.

                    // Reject the connection and stay in Disconnecting state
                    Log.w(TAG, "Incoming Battery connected request rejected: " + mDevice);
                    disconnectGatt();
                }
                default -> {
                    Log.e(TAG, "Incorrect state: " + state);
                }
            }
        }
    }

    @VisibleForTesting
    class Connected extends State {
        private static final String TAG =
                BatteryStateMachine.TAG + "." + Connected.class.getSimpleName();

        @Override
        public void enter() {
            log(TAG, "Enter (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
            dispatchConnectionStateChanged(STATE_CONNECTED);
            mLastConnectionState = STATE_CONNECTED;

            discoverServicesGatt();
        }

        @Override
        public void exit() {
            log(TAG, "Exit (" + mDevice + "): " + messageWhatToString(getCurrentMessage().what));
            // Reset the battery level only after connected
            resetBatteryLevel();
        }

        @Override
        public boolean processMessage(Message message) {
            log(TAG, "Process message(" + mDevice + "): " + messageWhatToString(message.what));

            switch (message.what) {
                case MESSAGE_DISCONNECT -> {
                    log(TAG, "Disconnecting from " + mDevice);
                    disconnectGatt();
                    transitionTo(mDisconnecting);
                }
                case MESSAGE_CONNECTION_STATE_CHANGED -> {
                    processConnectionEvent(message.arg1);
                }
                default -> {
                    Log.e(TAG, "Unexpected message: " + messageWhatToString(message.what));
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
                default -> {
                    Log.e(TAG, "Connection State Device: " + mDevice + " bad state: " + state);
                }
            }
        }
    }

    final class GattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            sendMessage(MESSAGE_CONNECTION_STATE_CHANGED, newState);
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt itf
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "No gatt service");
                return;
            }

            final BluetoothGattService batteryService = gatt.getService(GATT_BATTERY_SERVICE_UUID);
            if (batteryService == null) {
                Log.e(TAG, "No battery service");
                return;
            }

            final BluetoothGattCharacteristic batteryLevel =
                    batteryService.getCharacteristic(GATT_BATTERY_LEVEL_CHARACTERISTIC_UUID);
            if (batteryLevel == null) {
                Log.e(TAG, "No battery level characteristic");
                return;
            }

            // This may not trigger onCharacteristicRead if CCCD is already set but then
            // onCharacteristicChanged will be triggered soon.
            gatt.readCharacteristic(batteryLevel);
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (GATT_BATTERY_LEVEL_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                updateBatteryLevel(value);
            }
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission") // We should call internal gatt itf
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Read characteristic failure on " + gatt + " " + characteristic);
                return;
            }

            if (GATT_BATTERY_LEVEL_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                updateBatteryLevel(value);
                BluetoothGattDescriptor cccd =
                        characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
                if (cccd != null) {
                    gatt.setCharacteristicNotification(characteristic, /* enable= */ true);
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    Log.w(
                            TAG,
                            "No CCCD for battery level characteristic, " + "it won't be notified");
                }
            }
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Failed to write descriptor " + descriptor.getUuid());
            }
        }
    }
}
