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

package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__BOND_RETRY;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__FAIL;

import static java.util.Objects.requireNonNull;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.OobData;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This state machine handles the bond process in the Java layer part of the Bluetooth stack.
 *
 * <p>{@link StateIdle} : No device is in bonding / unbonding state. {@link StateBonding} : Some
 * device is in bonding / unbonding state.
 */
public final class BondStateMachine extends StateMachine {
    private static final String TAG = Util.BT_PREFIX + BondStateMachine.class.getSimpleName();

    // State machine command messages (Java -> Native)
    static final int MESSAGE_CREATE_BOND = 1;
    static final int MESSAGE_CANCEL_BOND = 2;
    static final int MESSAGE_REMOVE_BOND = 3;

    // State machine native callback messages (Native -> Java)
    static final int MESSAGE_BOND_STATE_CHANGE = 4;
    static final int MESSAGE_PAIRING_REQUEST = 5;
    static final int MESSAGE_PIN_REQUEST = 6;

    // State machine UUIDs update messages
    static final int MESSAGE_UUID_UPDATE = 7;
    static final int MESSAGE_SERVICE_DISCOVERY_TIMEOUT = 8;

    // Message value keys
    static final String KEY_OOBDATAP192 = "oobdatap192";
    static final String KEY_OOBDATAP256 = "oobdatap256";
    static final String KEY_DISPLAY_PASSKEY = "display_passkey";
    static final String KEY_DELAY_RETRY_COUNT = "delay_retry_count";
    static final String KEY_BOND_TRANSPORT = "bond_transport";
    static final String KEY_PAIRING_ALGORITHM = "pairing_algorithm";
    static final String KEY_PAIRING_VARIANT = "pairing_variant";
    static final String KEY_PAIRING_CONTEXT = "pairing_context";

    // Bond retry values
    private static final int BOND_MAX_RETRIES = 30;
    private static final int BOND_RETRY_DELAY_MS = 500;

    // In some cases, the UUIDs are not retrieved before the remote is bonded so we wait for the
    // UUIDs callback from native before sending the bonded intent. If no UUIDs were received at
    // the end of the timeout, we send the bonded intent.
    private static final int SERVICE_DISCOVERY_TIMEOUT_MS = 3000;

    // List of devices that are bonded and waiting for UUIDs before Intent broadcast.
    @VisibleForTesting final Set<BluetoothDevice> mDevicesWaitingForUuids = new HashSet<>();

    // State when a bond change is being processed.
    private final StateBonding mStateBonding = new StateBonding();
    // State when no bond changes are ongoing.
    private final StateIdle mStateIdle = new StateIdle();

    private final AdapterService mAdapterService;
    private final AdapterProperties mAdapterProperties;
    private final RemoteDevices mRemoteDevices;
    private final BluetoothAdapter mAdapter;

    BondStateMachine(
            AdapterService service,
            Looper looper,
            AdapterProperties prop,
            RemoteDevices remoteDevices) {
        super("BondStateMachine:", looper);

        addState(mStateIdle);
        addState(mStateBonding);
        mAdapterService = service;
        mRemoteDevices = remoteDevices;
        mAdapterProperties = prop;
        mAdapter = mAdapterService.getSystemService(BluetoothManager.class).getAdapter();
        setInitialState(mStateIdle);

        start(false);
    }

    public synchronized void doQuit() {
        quitNow(false);
    }

    private class StateIdle extends State {
        @Override
        public void enter() {
            logD("Entering StateIdle");
        }

        @Override
        public synchronized boolean processMessage(Message msg) {
            BluetoothDevice dev = (BluetoothDevice) msg.obj;

            switch (msg.what) {
                case MESSAGE_CREATE_BOND:
                    // Native is using bonding control blocks until service discovery is finished
                    // so we delay the new bond start if that's the case.
                    if (mAdapterService.getNative().pairingIsBusy()) {
                        // getData() auto creates Bundle, getInt() defaults to 0 if no value.
                        int retryCount = msg.getData().getInt(KEY_DELAY_RETRY_COUNT);
                        logD("StateIdle: Create bond - Native is busy, attempt:" + retryCount);
                        if (retryCount < BOND_MAX_RETRIES) {
                            Message retryMsg = obtainMessage();
                            retryMsg.copyFrom(msg);
                            retryMsg.getData().putInt(KEY_DELAY_RETRY_COUNT, retryCount + 1);
                            sendMessageDelayed(retryMsg, BOND_RETRY_DELAY_MS);
                            return true;
                        } else {
                            MetricsLogger.getInstance()
                                    .logBluetoothEvent(
                                            dev,
                                            BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__BOND_RETRY,
                                            BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__FAIL,
                                            0);
                            logW("StateIdle: Create bond - Stop retrying, bonding will fail!");
                        }
                    }
                    // getParcelable() defaults to null if no value.
                    OobData p192Data = msg.getData().getParcelable(KEY_OOBDATAP192);
                    OobData p256Data = msg.getData().getParcelable(KEY_OOBDATAP256);
                    createBond(dev, msg.arg1, p192Data, p256Data, true);
                    break;
                case MESSAGE_REMOVE_BOND:
                    removeBond(dev, true);
                    break;
                case MESSAGE_BOND_STATE_CHANGE:
                    int newState = msg.arg1;
                    logI("StateIdle: Bond state change - To " + bondStateToString(newState));
                    // Incoming pairing, transition to bonding state
                    if (newState == BluetoothDevice.BOND_BONDING) {
                        deferMessage(msg);
                        transitionTo(mStateBonding);
                    } else if (newState == BluetoothDevice.BOND_NONE) {
                        // The link key was deleted by the stack
                        handleBondStateChanged(
                                dev, BluetoothDevice.TRANSPORT_AUTO, newState, 0, 0, 0);
                    } else {
                        logW("StateIdle: Bond state change - Invalid state, ignoring.");
                    }
                    break;
                case MESSAGE_SERVICE_DISCOVERY_TIMEOUT:
                case MESSAGE_UUID_UPDATE:
                    if (mDevicesWaitingForUuids.contains(dev)) {
                        // Device is waiting for UUIDs, update & send Intent
                        handlePendingUuids(dev);
                    }
                    break;
                case MESSAGE_CANCEL_BOND:
                default:
                    logE("StateIdle: Received unhandled message: " + msg.what);
                    return false;
            }
            return true;
        }
    }

    private class StateBonding extends State {
        private final ArrayList<BluetoothDevice> mDevices = new ArrayList<>();

        @Override
        public void enter() {
            logD("Entering StateBonding");
        }

        @Override
        public synchronized boolean processMessage(Message msg) {
            BluetoothDevice dev = (BluetoothDevice) msg.obj;
            DeviceProperties devProp = mRemoteDevices.getDeviceProperties(dev);
            boolean result = false;

            if ((mDevices.contains(dev) || mDevicesWaitingForUuids.contains(dev))
                    && msg.what != MESSAGE_CANCEL_BOND
                    && msg.what != MESSAGE_BOND_STATE_CHANGE
                    && msg.what != MESSAGE_PAIRING_REQUEST
                    && msg.what != MESSAGE_PIN_REQUEST) {
                deferMessage(msg);
                return true;
            }

            switch (msg.what) {
                case MESSAGE_CREATE_BOND:
                    // /!\ Bond can't be created as native is busy.
                    // We are calling it anyway to log the event and broadcast the unbond Intent.
                    OobData p192Data = msg.getData().getParcelable(KEY_OOBDATAP192);
                    OobData p256Data = msg.getData().getParcelable(KEY_OOBDATAP256);
                    result = createBond(dev, msg.arg1, p192Data, p256Data, false);
                    break;
                case MESSAGE_REMOVE_BOND:
                    result = removeBond(dev, false);
                    break;
                case MESSAGE_CANCEL_BOND:
                    result = cancelBond(dev);
                    break;
                case MESSAGE_BOND_STATE_CHANGE:
                    int newState = msg.arg1;
                    int reason = convertBondStateChangeReason(msg.arg2);
                    int transport = msg.getData().getInt(KEY_BOND_TRANSPORT);
                    int pairingAlgorithm = msg.getData().getInt(KEY_PAIRING_ALGORITHM);
                    int pairingVariant = msg.getData().getInt(KEY_PAIRING_VARIANT);

                    if (newState != BluetoothDevice.BOND_BONDING) {
                        mDevices.remove(dev);
                        if (newState == BluetoothDevice.BOND_NONE) {
                            if (reason == BluetoothDevice.BOND_SUCCESS) {
                                reason = BluetoothDevice.UNBOND_REASON_REMOVED;
                            }
                            clearPermissionsAndPolicies(dev);
                        }
                        if (mDevices.isEmpty()) {
                            transitionTo(mStateIdle);
                        }
                    } else if (!mDevices.contains(dev)) {
                        result = true;
                    }
                    handleBondStateChanged(
                            dev, transport, newState, pairingAlgorithm, pairingVariant, reason);
                    break;
                case MESSAGE_PAIRING_REQUEST:
                    if (devProp == null) {
                        logW("StateBonding: Pairing request - no device properties for" + dev);
                        break;
                    }

                    int passkey = msg.arg1;
                    int variant = msg.arg2;
                    boolean displayPasskey = msg.getData().getByte(KEY_DISPLAY_PASSKEY) == 1;
                    sendPairingRequestIntent(
                            devProp.getDevice(),
                            displayPasskey ? Optional.of(passkey) : Optional.empty(),
                            variant,
                            msg.getData().getInt(KEY_PAIRING_CONTEXT),
                            msg.getData().getInt(KEY_PAIRING_ALGORITHM));
                    break;
                case MESSAGE_PIN_REQUEST:
                    if (devProp == null) {
                        logW("StateBonding: Pin request - no device properties for" + dev);
                        break;
                    }
                    int btDeviceClass =
                            new BluetoothClass(mRemoteDevices.getBluetoothClass(dev))
                                    .getDeviceClass();
                    if (btDeviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD
                            || btDeviceClass
                                    == BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING) {
                        // Its a keyboard. Follow the HID spec recommendation of creating the
                        // passkey and displaying it to the user. If the keyboard doesn't follow
                        // the spec recommendation, check if the keyboard has a fixed PIN zero
                        // and pair.
                        // TODO: Maintain list of devices that have fixed pin
                        // Generate a variable 6-digit PIN in range of 100000-999999
                        // This is not truly random but good enough.
                        int pin = 100000 + (int) Math.floor((Math.random() * (999999 - 100000)));
                        sendPairingRequestIntent(
                                devProp.getDevice(),
                                Optional.of(pin),
                                BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN,
                                msg.getData().getInt(KEY_PAIRING_CONTEXT),
                                msg.getData().getInt(KEY_PAIRING_ALGORITHM));
                        break;
                    }

                    sendPairingRequestIntent(
                            devProp.getDevice(),
                            Optional.empty(),
                            (msg.arg2 == 1)
                                    ? BluetoothDevice.PAIRING_VARIANT_PIN_16_DIGITS
                                    : BluetoothDevice.PAIRING_VARIANT_PIN,
                            msg.getData().getInt(KEY_PAIRING_CONTEXT),
                            msg.getData().getInt(KEY_PAIRING_ALGORITHM));
                    break;
                case MESSAGE_UUID_UPDATE:
                case MESSAGE_SERVICE_DISCOVERY_TIMEOUT:
                default:
                    logE("StateBonding: Received unhandled message:" + msg.what);
                    return false;
            }
            if (result) {
                mDevices.add(dev);
            }
            return true;
        }
    }

    /** Check whether has the specific message in message queue */
    @VisibleForTesting
    public boolean hasMessage(int what) {
        return hasMessages(what);
    }

    /** Remove the specific message from message queue */
    @VisibleForTesting
    public void removeMessage(int what) {
        removeMessages(what);
    }

    private boolean cancelBond(BluetoothDevice dev) {
        if (mRemoteDevices.getBondState(dev) != BluetoothDevice.BOND_BONDING) {
            logW(
                    "cancelBond: "
                            + dev
                            + " is not in bonding state, state: "
                            + bondStateToString(mRemoteDevices.getBondState(dev)));
            return false;
        }

        if (!mAdapterService.getNative().cancelBond(Utils.getByteAddress(dev))) {
            logW("cancelBond: Unexpected error while cancelling bond:" + dev);
            return false;
        }

        return true;
    }

    /** Removes bond, transition to bonding state if needed */
    private boolean removeBond(BluetoothDevice dev, boolean transition) {
        DeviceProperties devProp = mRemoteDevices.getDeviceProperties(dev);
        if (devProp == null) {
            logW("removeBond: " + dev + " is unknown device");
            return false;
        }

        if (devProp.getBondState() == BluetoothDevice.BOND_NONE) {
            logW("removeBond: " + dev + " is not bonded");
            return false;
        }

        if (!mAdapterService.getNative().removeBond(Utils.getByteAddress(dev))) {
            logW("removeBond: Unexpected error while removing " + dev);
            return false;
        }

        // Reset the bond-loss state when the bond is removed.
        mAdapterService.updateKeyMissingCount(dev, false);

        if (transition) {
            transitionTo(mStateBonding);
        }
        return true;
    }

    /** Create bond, log and transition to bonding state */
    private boolean createBond(
            BluetoothDevice dev,
            int transport,
            OobData remoteP192Data,
            OobData remoteP256Data,
            boolean transition) {
        int bondState = mRemoteDevices.getBondState(dev);
        if (bondState != BluetoothDevice.BOND_NONE
                && !(Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(dev))) {
            logW("createBond: " + dev + " already in " + bondStateToString(bondState) + " state");
            return false;
        }

        logD("createBond: " + dev + ", transport: " + transport);
        byte[] addr = Utils.getByteAddress(dev);
        int addrType = dev.getAddressType();
        boolean initiated;

        if (remoteP192Data != null || remoteP256Data != null) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                    mAdapterService.obfuscateAddress(dev),
                    transport,
                    mRemoteDevices.getType(dev),
                    BluetoothDevice.BOND_BONDING,
                    BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_START_PAIRING_OOB,
                    BluetoothProtoEnums.UNBOND_REASON_UNKNOWN,
                    mAdapterService.getMetricId(dev));

            initiated =
                    mAdapterService
                            .getNative()
                            .createBondOutOfBand(addr, transport, remoteP192Data, remoteP256Data);
        } else {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                    mAdapterService.obfuscateAddress(dev),
                    transport,
                    mRemoteDevices.getType(dev),
                    BluetoothDevice.BOND_BONDING,
                    BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_START_PAIRING,
                    BluetoothProtoEnums.UNBOND_REASON_UNKNOWN,
                    mAdapterService.getMetricId(dev));

            initiated = mAdapterService.getNative().createBond(addr, addrType, transport);
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_DEVICE_NAME_REPORTED,
                mAdapterService.getMetricId(dev),
                mRemoteDevices.getName(dev));
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                mAdapterService.obfuscateAddress(dev),
                transport,
                mRemoteDevices.getType(dev),
                BluetoothDevice.BOND_BONDING,
                remoteP192Data == null && remoteP256Data == null
                        ? BluetoothProtoEnums.BOND_SUB_STATE_UNKNOWN
                        : BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_OOB_DATA_PROVIDED,
                BluetoothProtoEnums.UNBOND_REASON_UNKNOWN);

        if (!initiated) {
            logW("createBond: Failed to initiate pairing for " + dev);
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                    mAdapterService.obfuscateAddress(dev),
                    transport,
                    mRemoteDevices.getType(dev),
                    BluetoothDevice.BOND_NONE,
                    BluetoothProtoEnums.BOND_SUB_STATE_UNKNOWN,
                    BluetoothDevice.UNBOND_REASON_REPEATED_ATTEMPTS);

            // Using UNBOND_REASON_REMOVED for legacy reason
            handleBondStateChanged(
                    dev,
                    BluetoothDevice.TRANSPORT_AUTO,
                    BluetoothDevice.BOND_NONE,
                    0,
                    0,
                    BluetoothDevice.UNBOND_REASON_REMOVED);

            if (Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(dev)) {
                // If it's a bond-loss scenario, disconnect the ACL.
                // TODO (b/440298497): It is possible that createBond() is called on the device by
                // any 1P/3P app and the bond loss was already detected. In this case, we should not
                // disconnect the ACL, fix this.
                mAdapterService.getNative().disconnectAcl(dev, transport);
            }
            return false;
        }

        if (transition) {
            transitionTo(mStateBonding);
        }
        return true;
    }

    private void sendPairingRequestIntent(
            BluetoothDevice device,
            Optional<Integer> maybePin,
            int variant,
            int pairingContext,
            int pairingAlgo) {
        Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        maybePin.ifPresent(pin -> intent.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, pin));
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant);
        if (Utils.isAutonomousRepairingSupported()) {
            intent.putExtra(BluetoothDevice.EXTRA_PAIRING_CONTEXT, pairingContext);
        }
        if (Flags.providePairingAlgo()) {
            intent.putExtra(BluetoothDevice.EXTRA_PAIRING_ALGORITHM, pairingAlgo);
        }
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        // Workaround for Android Auto until pre-accepting pairing requests is added.
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        logI(
                "sendPairingRequestIntent: ACTION_PAIRING_REQUEST device="
                        + device
                        + ", variant="
                        + variant
                        + ", pairingContext="
                        + pairingContext
                        + ", pairingAlgo="
                        + pairingAlgo);
        mAdapterService.sendOrderedBroadcast(
                intent,
                BLUETOOTH_CONNECT,
                Utils.getTempBroadcastBundle(),
                null /* resultReceiver */,
                null /* scheduler */,
                Activity.RESULT_OK /* initialCode */,
                null /* initialData */,
                null /* initialExtras */);
    }

    /**
     * Ensures everything is correct before broadcasting the new state
     *
     * <p>This verifies the new and old states and checks if the service discovery is done or if we
     * should wait before broadcasting the new state. This also logs the bond state changes.
     */
    @VisibleForTesting
    void handleBondStateChanged(
            BluetoothDevice device,
            int transport,
            int newState,
            int pairingAlgorithm,
            int pairingVariant,
            int reason) {
        // If new bond state is invalid, immediately return.
        if (newState < BluetoothDevice.BOND_NONE || newState > BluetoothDevice.BOND_BONDED) {
            logE("handleBondStateChanged: Invalid new state: " + newState);
            return;
        }

        // Retrieve the previous state.
        DeviceProperties devProp = mRemoteDevices.getDeviceProperties(device);
        int oldState = devProp != null ? devProp.getBondState() : BluetoothDevice.BOND_NONE;

        // Internal bond state update.
        if (!(Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(device))) {
            // Skip updating the bond state to RemoteDevices to protect updating the bonded devices
            // list.
            mRemoteDevices.onBondStateChange(
                    device, transport, newState, pairingAlgorithm, pairingVariant);
        }

        // If the device is waiting for UUIDs the last state was bonded.
        // As the state is now different, stop waiting.
        if (mDevicesWaitingForUuids.contains(device)) {
            mDevicesWaitingForUuids.remove(device);
            // This should never happen.
            // A device is only waiting for UUIDs when the state changed to bonded.
            if (oldState != BluetoothDevice.BOND_BONDED) {
                throw new IllegalArgumentException(
                        "handleBondStateChanged: Invalid old state " + oldState);
            }
            // Previous state is bonded and device was waiting for UUIDs.
            // The last intent sent was bonding, so if the new state is bonding
            // it'll get caught in the state comparison under this.
            oldState = BluetoothDevice.BOND_BONDING;
        }

        // Internal bond state update.
        mAdapterProperties.onBondStateChanged(device, newState);

        // No state update, return.
        if (oldState == newState) {
            return;
        }

        int deviceType = mRemoteDevices.getType(device);
        int deviceClass = mRemoteDevices.getBluetoothClass(device);

        MetricsLogger.getInstance().logBondStateMachineEvent(device, newState);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                mAdapterService.obfuscateAddress(device),
                0,
                deviceType,
                newState,
                BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_BOND_STATE_INTENT_SENT,
                reason,
                mAdapterService.getMetricId(device));
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_CLASS_OF_DEVICE_REPORTED,
                mAdapterService.obfuscateAddress(device),
                deviceClass,
                mAdapterService.getMetricId(device));

        // Check if we should wait for service discovery UUIDs or not.
        boolean skipWaitingForServiceUUIDs = false;
        if (Flags.immediateSdpResultsLe()) {
            skipWaitingForServiceUUIDs =
                    isLeOnlyDeviceWithoutAudioSupport(device, deviceType, deviceClass);
        }

        // Bonded but UUIDs are missing, wait for them if needed.
        if (newState == BluetoothDevice.BOND_BONDED
                && devProp != null
                && devProp.getUuids() == null
                && !skipWaitingForServiceUUIDs) {
            logD(
                    "handleBondStateChanged: "
                            + device
                            + " is bonded, wait for service discovery UUIDs");

            mDevicesWaitingForUuids.add(device);

            Message msg = obtainMessage(MESSAGE_SERVICE_DISCOVERY_TIMEOUT);
            msg.obj = device;
            sendMessageDelayed(msg, SERVICE_DISCOVERY_TIMEOUT_MS);

            if (oldState == BluetoothDevice.BOND_NONE) {
                // Broadcast NONE->BONDING for NONE->BONDED case, as we wouldn't broadcast bonded
                // now anyway.
                newState = BluetoothDevice.BOND_BONDING;
            } else {
                return;
            }
        }

        // Inform AdapterService of the state change & send Intent
        mAdapterService.handleBondStateChanged(device, oldState, newState);

        // Skip broadcasting the bond state changed if the device is in bond-loss state.
        if (!(Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(device))) {
            broadcastBondStateChangeIntent(device, oldState, newState, reason);
        }
    }

    /** UUIDs received or timeout, send bonded intent */
    void handlePendingUuids(BluetoothDevice device) {
        if (!mDevicesWaitingForUuids.contains(device)) {
            logW("handlePendingUuids: " + device + " was not waiting for UUIDs, abort.");
            return;
        }

        // Done waiting for UUIDs whether we send the change or not.
        mDevicesWaitingForUuids.remove(device);

        // Retrieve the previous state.
        DeviceProperties devProp = mRemoteDevices.getDeviceProperties(device);
        int oldState = devProp != null ? devProp.getBondState() : BluetoothDevice.BOND_NONE;

        // Ensure device is still bonded.
        if (oldState != BluetoothDevice.BOND_BONDED) {
            logW("handlePendingUuids: Device bond state was changed before update, abort.");
            return;
        }

        // Inform AdapterService of the state change & send Intent.
        mAdapterService.handleBondStateChanged(device, BluetoothDevice.BOND_BONDING, oldState);

        broadcastBondStateChangeIntent(device, BluetoothDevice.BOND_BONDING, oldState, 0);
    }

    /** Broadcasts the bond state change Intent */
    private void broadcastBondStateChangeIntent(
            BluetoothDevice device, int oldState, int newState, int reason) {
        logD(
                "broadcastBondStateChangeIntent: "
                        + device
                        + " "
                        + bondStateToString(oldState)
                        + " => "
                        + bondStateToString(newState));

        Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, oldState);
        if (newState == BluetoothDevice.BOND_NONE) {
            intent.putExtra(BluetoothDevice.EXTRA_UNBOND_REASON, reason);
        }
        if (Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(device)) {
            intent.putExtra(
                    BluetoothDevice.EXTRA_PAIRING_CONTEXT,
                    BluetoothDevice.PAIRING_CONTEXT_REPAIRING);
        }

        if (Flags.onlyBroadcastToLocalUser()) {
            mAdapterService.sendBroadcast(
                    intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastBundle());
        } else {
            mAdapterService.sendBroadcastAsUser(
                    intent, UserHandle.ALL, BLUETOOTH_CONNECT, Utils.getTempBroadcastBundle());
        }
    }

    /** Callback from native indicating a bond state change */
    void bondStateChangeCallback(
            int status,
            byte[] address,
            int transport,
            int newState,
            int pairingAlgorithm,
            int nativePairingVariant,
            int hciReason) {
        BluetoothDevice device = mRemoteDevices.getDevice(address);

        if (device == null) {
            // This device will be added before sending ACTION_BOND_STATE_CHANGE intent
            device = mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
            logD("bondStateChangeCallback: Unknown device:" + device);
        }

        int pairingVariant = getPairingVariant(transport, pairingAlgorithm, nativePairingVariant);

        Message msg = obtainMessage(MESSAGE_BOND_STATE_CHANGE);
        msg.obj = device;

        // Convert from native bond state to Java bond state
        if (newState == AbstractionLayer.BT_BOND_STATE_BONDED) {
            msg.arg1 = BluetoothDevice.BOND_BONDED;
        } else if (newState == AbstractionLayer.BT_BOND_STATE_BONDING) {
            msg.arg1 = BluetoothDevice.BOND_BONDING;
        } else {
            msg.arg1 = BluetoothDevice.BOND_NONE;
        }
        msg.arg2 = status;
        msg.getData().putInt(KEY_BOND_TRANSPORT, transport);
        msg.getData().putInt(KEY_PAIRING_ALGORITHM, pairingAlgorithm);
        msg.getData().putInt(KEY_PAIRING_VARIANT, pairingVariant);

        logI(
                "bondStateChangeCallback: Status: "
                        + status
                        + " Address: "
                        + device
                        + " Transport: "
                        + transport
                        + " newState: "
                        + bondStateToString(msg.arg1)
                        + " pairingAlgorithm: "
                        + pairingAlgorithm
                        + " hciReason: "
                        + hciReason);

        sendMessage(msg);
    }

    /** Callback from native indicating an incoming pairing request */
    void sspRequestCallback(byte[] address, int pairingVariant, int passkey, int pairingAlgorithm) {
        int variant;
        boolean displayPasskey = false;
        int context = BluetoothDevice.PAIRING_CONTEXT_USER_APPROVAL_REQUESTED;
        switch (pairingVariant) {
            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                variant = BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION;
                displayPasskey = true;
            }

            case AbstractionLayer.BT_PAIRING_VARIANT_CONSENT ->
                    variant = BluetoothDevice.PAIRING_VARIANT_CONSENT;

            case AbstractionLayer.BT_PAIRING_VARIANT_PARTICIPATION -> {
                variant = BluetoothDevice.PAIRING_VARIANT_CONSENT;
                context = BluetoothDevice.PAIRING_CONTEXT_USER_PARTICIPATION_REQUESTED;
            }

            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_ENTRY ->
                    variant = BluetoothDevice.PAIRING_VARIANT_PASSKEY;

            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_NOTIFICATION -> {
                variant = BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY;
                displayPasskey = true;
            }

            default -> {
                logE(
                        "sspRequestCallback: Unknown pairing variant("
                                + pairingVariant
                                + ") for "
                                + Utils.getRedactedAddressStringFromByte(address));
                return;
            }
        }

        logD(
                "sspRequestCallback: "
                        + Utils.getRedactedAddressStringFromByte(address)
                        + " pairingVariant "
                        + pairingVariant
                        + " passkey: "
                        + (Build.isDebuggable() ? passkey : "******")
                        + "pairingAlgorithm: "
                        + pairingAlgorithm);

        BluetoothDevice device = mRemoteDevices.getDevice(address);
        if (device == null) {
            mRemoteDevices.addDeviceProperties(address);
            device = requireNonNull(mRemoteDevices.getDevice(address));
            logW("sspRequestCallback: Unknown device:" + device);
        }

        if (context == BluetoothDevice.PAIRING_CONTEXT_USER_APPROVAL_REQUESTED) {
            // Identify whether its re-pairing or pairing
            if (device != null
                    && Utils.isAutonomousRepairingSupported()
                    && mAdapterService.isBondLost(device)) {
                context = BluetoothDevice.PAIRING_CONTEXT_REPAIRING;
            }
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                mAdapterService.obfuscateAddress(device),
                0,
                mRemoteDevices.getType(device),
                BluetoothDevice.BOND_BONDING,
                BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_SSP_REQUESTED,
                0);

        Message msg = obtainMessage(MESSAGE_PAIRING_REQUEST);
        msg.obj = device;
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_PAIRING_CONTEXT, context);
        bundle.putInt(KEY_PAIRING_ALGORITHM, pairingAlgorithm);
        if (displayPasskey) {
            msg.arg1 = passkey;
            bundle.putByte(KEY_DISPLAY_PASSKEY, (byte) 1 /* true */);
        }
        msg.setData(bundle);
        msg.arg2 = variant;
        sendMessage(msg);
    }

    /** Callback from native indicating a pin confirmation request is needed */
    void pinRequestCallback(
            byte[] address,
            byte[] name,
            int deviceClass,
            boolean min16Digits,
            int pairingAlgorithm) {
        // TODO(BT): Get wakelock and update name and class of device

        BluetoothDevice bdDevice = mRemoteDevices.getDevice(address);
        if (bdDevice == null) {
            mRemoteDevices.addDeviceProperties(address);
            bdDevice = requireNonNull(mRemoteDevices.getDevice(address));
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                mAdapterService.obfuscateAddress(bdDevice),
                0,
                mRemoteDevices.getType(bdDevice),
                BluetoothDevice.BOND_BONDING,
                BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_PIN_REQUESTED,
                0);

        logD(
                "pinRequestCallback: "
                        + bdDevice
                        + " deviceClass:"
                        + new BluetoothClass(deviceClass)
                        + " pairingAlgorithm: "
                        + pairingAlgorithm);

        Message msg = obtainMessage(MESSAGE_PIN_REQUEST);
        msg.obj = bdDevice;
        Bundle bundle = new Bundle();
        bundle.putInt(
                KEY_PAIRING_CONTEXT,
                (Utils.isAutonomousRepairingSupported() && mAdapterService.isBondLost(bdDevice))
                        ? BluetoothDevice.PAIRING_CONTEXT_REPAIRING
                        : BluetoothDevice.PAIRING_CONTEXT_USER_APPROVAL_REQUESTED);
        bundle.putInt(KEY_PAIRING_ALGORITHM, pairingAlgorithm);
        msg.setData(bundle);
        msg.arg2 = min16Digits ? 1 : 0; // Use arg2 to pass the min16Digit boolean

        sendMessage(msg);
    }

    /**
     * Reset the connection policy of all profiles to default and remove access permissions.
     *
     * <p>This is used when the bond is removed.
     */
    @VisibleForTesting
    void clearPermissionsAndPolicies(BluetoothDevice device) {
        // Clear access permissions
        mAdapterService.setPhonebookAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN);
        mAdapterService.setMessageAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN);
        mAdapterService.setSimAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN);

        // Clear profile policies
        Stream.of(
                        mAdapterService.getHidHostService(),
                        mAdapterService.getA2dpService(),
                        mAdapterService.getHeadsetService(),
                        mAdapterService.getHeadsetClientService(),
                        mAdapterService.getA2dpSinkService(),
                        mAdapterService.getPbapClientService(),
                        mAdapterService.getLeAudioService(),
                        mAdapterService.getCsipSetCoordinatorService(),
                        mAdapterService.getVolumeControlService(),
                        mAdapterService.getHapClientService())
                .flatMap(Optional::stream)
                .forEach(
                        profile -> {
                            profile.setConnectionPolicy(device, CONNECTION_POLICY_UNKNOWN);
                        });
    }

    /**
     * Checks for device type, class and transport used to determine if device is LE without Audio
     * support.
     */
    private boolean isLeOnlyDeviceWithoutAudioSupport(
            BluetoothDevice device, int deviceType, int deviceClass) {
        return (deviceType == BluetoothDevice.DEVICE_TYPE_LE
                && mAdapterService.getConnectionHandle(device, BluetoothDevice.TRANSPORT_LE)
                        != BluetoothDevice.ERROR
                && ((deviceClass & BluetoothClass.Service.LE_AUDIO) == 0));
    }

    /** Converts HAL bond change reason to Java reason */
    private static int convertBondStateChangeReason(int reason) {
        if (reason == AbstractionLayer.BT_STATUS_SUCCESS) {
            return BluetoothDevice.BOND_SUCCESS;
        } else if (reason == AbstractionLayer.BT_STATUS_RMT_DEV_DOWN) {
            return BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN;
        } else if (reason == AbstractionLayer.BT_STATUS_AUTH_FAILURE) {
            return BluetoothDevice.UNBOND_REASON_AUTH_FAILED;
        } else if (reason == AbstractionLayer.BT_STATUS_AUTH_REJECTED) {
            return BluetoothDevice.UNBOND_REASON_AUTH_REJECTED;
        } else if (reason == AbstractionLayer.BT_STATUS_AUTH_TIMEOUT) {
            return BluetoothDevice.UNBOND_REASON_AUTH_TIMEOUT;
        }

        /* default */
        return BluetoothDevice.UNBOND_REASON_REMOVED;
    }

    /** Bond states as string */
    public static String bondStateToString(int state) {
        if (state == BluetoothDevice.BOND_NONE) {
            return "BOND_NONE";
        } else if (state == BluetoothDevice.BOND_BONDING) {
            return "BOND_BONDING";
        } else if (state == BluetoothDevice.BOND_BONDED) {
            return "BOND_BONDED";
        } else return "UNKNOWN(" + state + ")";
    }

    /** Converts native pairing variant to Java pairing variant */
    public static int getPairingVariant(
            int transport, int pairingAlgorithm, int nativePairingVariant) {
        if (transport == BluetoothDevice.TRANSPORT_BREDR
                && pairingAlgorithm == BluetoothDevice.PAIRING_ALGORITHM_BREDR_LEGACY) {
            if (nativePairingVariant == AbstractionLayer.BT_LEGACY_PAIRING_VARIANT_PIN) {
                return BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN;
            } else if (nativePairingVariant == AbstractionLayer.BT_LEGACY_PAIRING_VARIANT_PIN_16) {
                return BluetoothDevice.PAIRING_VARIANT_PIN_16_DIGITS;
            } else {
                logE(
                        "getPairingVariant: Unknown legacy pairing variant("
                                + nativePairingVariant
                                + ") for "
                                + transport
                                + " "
                                + pairingAlgorithm);
                return BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN;
            }
        }

        return switch (nativePairingVariant) {
            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION;
            case AbstractionLayer.BT_PAIRING_VARIANT_CONSENT ->
                    BluetoothDevice.PAIRING_VARIANT_CONSENT;
            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_ENTRY ->
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY;
            case AbstractionLayer.BT_PAIRING_VARIANT_PASSKEY_NOTIFICATION ->
                    BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY;
            default -> BluetoothDevice.PAIRING_VARIANT_CONSENT;
        };
    }

    private static void logI(String log) {
        Log.i(TAG, log);
    }

    private static void logD(String log) {
        Log.d(TAG, log);
    }

    private static void logW(String log) {
        Log.w(TAG, log);
    }

    private static void logE(String log) {
        Log.e(TAG, log);
    }
}
