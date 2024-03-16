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

package com.android.bluetooth.hid;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import static com.android.bluetooth.Utils.enforceBluetoothPrivilegedPermission;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHidHost;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.modules.utils.SynchronousResultReceiver;

import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides Bluetooth Hid Host profile, as a service in
 * the Bluetooth application.
 */
public class HidHostService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "BluetoothHidHostService";

    private static class InputDevice {
        int mSelectedTransport = BluetoothDevice.TRANSPORT_AUTO;
        int mHidState = BluetoothProfile.STATE_DISCONNECTED;
        int mHogpState = BluetoothProfile.STATE_DISCONNECTED;

        int getState(int transport) {
            return (transport == BluetoothDevice.TRANSPORT_LE) ? mHogpState : mHidState;
        }

        int getState() {
            return getState(mSelectedTransport);
        }

        void setState(int transport, int state) {
            if (transport == BluetoothDevice.TRANSPORT_LE) {
                mHogpState = state;
            } else {
                mHidState = state;
            }
        }

        void setState(int state) {
            setState(mSelectedTransport, state);
        }

        @Override
        public String toString() {
            return "Preferred transport: "
                    + mSelectedTransport
                    + ", HID connection state: "
                    + mHidState
                    + ", HOGP connection state: "
                    + mHogpState;
        }
    }

    private final Map<BluetoothDevice, InputDevice> mInputDevices =
            Collections.synchronizedMap(new HashMap<>());
    private boolean mNativeAvailable;
    private static HidHostService sHidHostService;
    private BluetoothDevice mTargetDevice = null;

    private DatabaseManager mDatabaseManager;
    private AdapterService mAdapterService;
    private final HidHostNativeInterface mNativeInterface;

    private static final int MESSAGE_CONNECT = 1;
    private static final int MESSAGE_DISCONNECT = 2;
    private static final int MESSAGE_CONNECT_STATE_CHANGED = 3;
    private static final int MESSAGE_GET_PROTOCOL_MODE = 4;
    private static final int MESSAGE_VIRTUAL_UNPLUG = 5;
    private static final int MESSAGE_ON_GET_PROTOCOL_MODE = 6;
    private static final int MESSAGE_SET_PROTOCOL_MODE = 7;
    private static final int MESSAGE_GET_REPORT = 8;
    private static final int MESSAGE_ON_GET_REPORT = 9;
    private static final int MESSAGE_SET_REPORT = 10;
    private static final int MESSAGE_ON_VIRTUAL_UNPLUG = 12;
    private static final int MESSAGE_ON_HANDSHAKE = 13;
    private static final int MESSAGE_GET_IDLE_TIME = 14;
    private static final int MESSAGE_ON_GET_IDLE_TIME = 15;
    private static final int MESSAGE_SET_IDLE_TIME = 16;
    private static final int MESSAGE_SET_PREFERRED_TRANSPORT = 17;

    public static final int STATE_DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED;
    public static final int STATE_CONNECTING = BluetoothProfile.STATE_CONNECTING;
    public static final int STATE_CONNECTED = BluetoothProfile.STATE_CONNECTED;
    public static final int STATE_DISCONNECTING = BluetoothProfile.STATE_DISCONNECTING;
    public static final int STATE_ACCEPTING = BluetoothProfile.STATE_DISCONNECTING + 1;

    public HidHostService(Context ctx) {
        super(ctx);
        mNativeInterface = requireNonNull(HidHostNativeInterface.getInstance());
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileHidHostEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHidHostBinder(this);
    }

    @Override
    public void start() {
        mDatabaseManager =
                requireNonNull(
                        AdapterService.getAdapterService().getDatabase(),
                        "DatabaseManager cannot be null when HidHostService starts");
        mAdapterService =
                requireNonNull(
                        AdapterService.getAdapterService(),
                        "AdapterService cannot be null when HidHostService starts");

        mNativeInterface.init(this);
        mNativeAvailable = true;
        setHidHostService(this);
    }

    @Override
    public void stop() {
        if (DBG) {
            Log.d(TAG, "Stopping Bluetooth HidHostService");
        }
    }

    @Override
    public void cleanup() {
        if (DBG) Log.d(TAG, "Stopping Bluetooth HidHostService");
        if (mNativeAvailable) {
            mNativeInterface.cleanup();
            mNativeAvailable = false;
        }

        if (mInputDevices != null) {
            for (BluetoothDevice device : mInputDevices.keySet()) {
                // Set both HID and HOGP connection states to disconnected
                updateConnectionState(
                        device, BluetoothDevice.TRANSPORT_LE, BluetoothProfile.STATE_DISCONNECTED);
                updateConnectionState(
                        device,
                        BluetoothDevice.TRANSPORT_BREDR,
                        BluetoothProfile.STATE_DISCONNECTED);
            }
            mInputDevices.clear();
        }
        // TODO(b/72948646): this should be moved to stop()
        setHidHostService(null);
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        if (Utils.arrayContains(device.getUuids(), BluetoothUuid.HOGP)) {
            // Use pseudo address when HOGP is available
            return Utils.getByteAddress(device);
        } else {
            // Use BR/EDR address if only HID is available
            if (Flags.identityAddressNullIfUnknown()) {
                return Utils.getByteBrEdrAddress(device);
            } else {
                return mAdapterService.getByteIdentityAddress(device);
            }
        }
    }

    /**
     * Retrieves device address type
     *
     * @param device remote device
     * @return address type
     */
    private int getAddressType(BluetoothDevice device) {
        if (Flags.getAddressTypeApi()) {
            return device.getAddressType();
        }

        return BluetoothDevice.ADDRESS_TYPE_PUBLIC;
    }

    /**
     * Retrieves preferred transport for the device
     *
     * @param device remote device
     * @return transport
     */
    private int getTransport(BluetoothDevice device) {
        InputDevice inputDevice = mInputDevices.get(device);
        if (inputDevice != null) {
            return inputDevice.mSelectedTransport;
        }

        return BluetoothDevice.TRANSPORT_AUTO;
    }

    /**
     * Saves the preferred transport for the input device. Adds an input device entry if not present
     *
     * @param device remote device
     * @param transport preferred transport
     */
    private void setTransport(BluetoothDevice device, int transport) {
        InputDevice inputDevice = getOrCreateInputDevice(device);
        if (inputDevice.mSelectedTransport != transport) {
            inputDevice.mSelectedTransport = transport;
        }
    }

    /**
     * Retrieves the input device object. Creates a new one if it does not exist
     *
     * @param device remote device
     * @return input device object
     */
    private InputDevice getOrCreateInputDevice(BluetoothDevice device) {
        return mInputDevices.computeIfAbsent(device, k -> new InputDevice());
    }

    /**
     * Retrieves the connection state
     *
     * @param device remote device
     * @param transport transport
     * @return connection state
     */
    private int getState(BluetoothDevice device, int transport) {
        InputDevice inputDevice = mInputDevices.get(device);
        if (inputDevice != null) {
            return inputDevice.getState(transport);
        }

        return BluetoothProfile.STATE_DISCONNECTED;
    }

    public static synchronized HidHostService getHidHostService() {
        if (sHidHostService == null) {
            Log.w(TAG, "getHidHostService(): service is null");
            return null;
        }
        if (!sHidHostService.isAvailable()) {
            Log.w(TAG, "getHidHostService(): service is not available ");
            return null;
        }
        return sHidHostService;
    }

    private static synchronized void setHidHostService(HidHostService instance) {
        if (DBG) {
            Log.d(TAG, "setHidHostService(): set to: " + instance);
        }
        sHidHostService = instance;
    }

    /**
     * Requests the native stack to start HID connection
     *
     * @param device remote device
     * @param transport transport to be used
     * @return true if successfully requested, else false
     */
    private boolean nativeConnect(BluetoothDevice device, int transport) {
        if (!mNativeInterface.connectHid(
                getByteAddress(device), getAddressType(device), transport)) {
            Log.w(
                    TAG,
                    "nativeConnect: "
                            + "Connection attempt failed for device: "
                            + device
                            + ", transport: "
                            + transport);

            if (!Flags.allowSwitchingHidAndHogp()) {
                updateConnectionState(device, transport, BluetoothProfile.STATE_DISCONNECTING);
                updateConnectionState(device, transport, BluetoothProfile.STATE_DISCONNECTED);
            }
            return false;
        }
        return true;
    }

    /**
     * Requests the native stack to start HID disconnection
     *
     * @param device remote device
     * @param transport transport
     * @param reconnectAllowed true if remote device is allowed to initiate reconnections, else
     *     false
     * @return true if successfully requested, else false
     */
    private boolean nativeDisconnect(
            BluetoothDevice device, int transport, boolean reconnectAllowed) {
        if (!mNativeInterface.disconnectHid(
                getByteAddress(device), getAddressType(device), transport, reconnectAllowed)) {
            Log.w(
                    TAG,
                    "nativeDisconnect: "
                            + "Disconnection attempt failed for device: "
                            + device
                            + ", transport: "
                            + transport);
            if (!Flags.allowSwitchingHidAndHogp()) {
                updateConnectionState(device, transport, BluetoothProfile.STATE_DISCONNECTING);
                updateConnectionState(device, transport, BluetoothProfile.STATE_DISCONNECTED);
            }
            return false;
        }
        return true;
    }

    private final Handler mHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) Log.v(TAG, "handleMessage(): msg.what=" + msg.what);
                    switch (msg.what) {
                        case MESSAGE_CONNECT:
                            handleMessageConnect(msg);
                            break;
                        case MESSAGE_DISCONNECT:
                            handleMessageDisconnect(msg);
                            break;
                        case MESSAGE_CONNECT_STATE_CHANGED:
                            handleMessageConnectStateChanged(msg);
                            break;
                        case MESSAGE_GET_PROTOCOL_MODE:
                            handleMessageGetProtocolMode(msg);
                            break;
                        case MESSAGE_ON_GET_PROTOCOL_MODE:
                            handleMessageOnGetProtocolMode(msg);
                            break;
                        case MESSAGE_VIRTUAL_UNPLUG:
                            handleMessageVirtualUnplug(msg);
                            break;
                        case MESSAGE_SET_PROTOCOL_MODE:
                            handleMessageSetProtocolMode(msg);
                            break;
                        case MESSAGE_GET_REPORT:
                            handleMessageGetReport(msg);
                            break;
                        case MESSAGE_ON_GET_REPORT:
                            handleMessageOnGetReport(msg);
                            break;
                        case MESSAGE_ON_HANDSHAKE:
                            handleMessageOnHandshake(msg);
                            break;
                        case MESSAGE_SET_REPORT:
                            handleMessageSetProtocol(msg);
                            break;
                        case MESSAGE_ON_VIRTUAL_UNPLUG:
                            handleMessageOnVirtualUnplug(msg);
                            break;
                        case MESSAGE_GET_IDLE_TIME:
                            handleMessageGetIdleTime(msg);
                            break;
                        case MESSAGE_ON_GET_IDLE_TIME:
                            handleMessageOnGetIdleTime(msg);
                            break;
                        case MESSAGE_SET_IDLE_TIME:
                            handleMessageSetIdleTime(msg);
                            break;
                        case MESSAGE_SET_PREFERRED_TRANSPORT:
                            handleMessageSetPreferredTransport(msg);
                            break;
                    }
                }
            };

    private void handleMessageSetPreferredTransport(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;

        int prevTransport = getTransport(device);
        Log.i(
                TAG,
                "handleMessageSetPreferredTransport: "
                        + "Preferred transport changed from "
                        + prevTransport
                        + " to "
                        + transport
                        + " for device: "
                        + device);

        // Save the preferred transport
        InputDevice inputDevice = getOrCreateInputDevice(device);
        inputDevice.mSelectedTransport = transport;

        /* If connections are allowed, ensure that the previous transport is disconnected and the
        new transport is connected */
        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            if (prevTransport != transport) {
                Log.i(
                        TAG,
                        "handleMessageSetPreferredTransport: "
                                + "switching connection from "
                                + prevTransport
                                + " to "
                                + transport
                                + " for device: "
                                + device);
                // Disconnect the other transport and disallow reconnections
                nativeDisconnect(device, prevTransport, false);

                // Request to connect the preferred transport
                nativeConnect(device, transport);
            }
        }
    }

    private void handleMessageSetIdleTime(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        Bundle data = msg.getData();
        byte idleTime = data.getByte(BluetoothHidHost.EXTRA_IDLE_TIME);
        if (!mNativeInterface.setIdleTime(
                getByteAddress(device), getAddressType(device), getTransport(device), idleTime)) {
            Log.e(TAG, "Error: get idle time native returns false");
        }
    }

    private void handleMessageOnGetIdleTime(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;

        if (!checkTransport(device, transport, msg.what)) {
            return;
        }

        int idleTime = msg.arg2;
        broadcastIdleTime(device, idleTime);
    }

    private void handleMessageGetIdleTime(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        if (!mNativeInterface.getIdleTime(
                getByteAddress(device), getAddressType(device), getTransport(device))) {
            Log.e(TAG, "Error: get idle time native returns false");
        }
    }

    private void handleMessageOnVirtualUnplug(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;
        if (!checkTransport(device, transport, msg.what)) {
            return;
        }
        int status = msg.arg2;
        broadcastVirtualUnplugStatus(device, status);
    }

    private void handleMessageSetProtocol(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        Bundle data = msg.getData();
        byte reportType = data.getByte(BluetoothHidHost.EXTRA_REPORT_TYPE);
        String report = data.getString(BluetoothHidHost.EXTRA_REPORT);
        if (!mNativeInterface.setReport(
                getByteAddress(device),
                getAddressType(device),
                getTransport(device),
                reportType,
                report)) {
            Log.e(TAG, "Error: set report native returns false");
        }
    }

    private void handleMessageOnHandshake(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;
        if (!checkTransport(device, transport, msg.what)) {
            return;
        }

        int status = msg.arg2;
        broadcastHandshake(device, status);
    }

    private void handleMessageOnGetReport(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;
        if (!checkTransport(device, transport, msg.what)) {
            return;
        }

        Bundle data = msg.getData();
        byte[] report = data.getByteArray(BluetoothHidHost.EXTRA_REPORT);
        int bufferSize = data.getInt(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE);
        broadcastReport(device, report, bufferSize);
    }

    private void handleMessageGetReport(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        Bundle data = msg.getData();
        byte reportType = data.getByte(BluetoothHidHost.EXTRA_REPORT_TYPE);
        byte reportId = data.getByte(BluetoothHidHost.EXTRA_REPORT_ID);
        int bufferSize = data.getInt(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE);
        if (!mNativeInterface.getReport(
                getByteAddress(device),
                getAddressType(device),
                getTransport(device),
                reportType,
                reportId,
                bufferSize)) {
            Log.e(TAG, "Error: get report native returns false");
        }
    }

    private void handleMessageSetProtocolMode(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        byte protocolMode = (byte) msg.arg1;
        Log.d(TAG, "sending set protocol mode(" + protocolMode + ")");
        if (!mNativeInterface.setProtocolMode(
                getByteAddress(device),
                getAddressType(device),
                getTransport(device),
                protocolMode)) {
            Log.e(TAG, "Error: set protocol mode native returns false");
        }
    }

    private void handleMessageVirtualUnplug(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        if (!mNativeInterface.virtualUnPlug(
                getByteAddress(device), getAddressType(device), getTransport(device))) {
            Log.e(TAG, "Error: virtual unplug native returns false");
        }
    }

    private void handleMessageOnGetProtocolMode(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;
        int protocolMode = msg.arg2;

        if (!checkTransport(device, transport, msg.what)) {
            return;
        }

        broadcastProtocolMode(device, protocolMode);
    }

    private void handleMessageGetProtocolMode(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        if (!mNativeInterface.getProtocolMode(
                getByteAddress(device), getAddressType(device), getTransport(device))) {
            Log.e(TAG, "Error: get protocol mode native returns false");
        }
    }

    private void handleMessageConnectStateChanged(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);
        int transport = msg.arg1;
        int state = msg.arg2;
        int prevState = getState(device, transport);

        if (Flags.allowSwitchingHidAndHogp()) {
            InputDevice inputDevice = mInputDevices.get(device);
            if (inputDevice != null) {
                // Update transport if it was not resolved already
                if (inputDevice.mSelectedTransport == BluetoothDevice.TRANSPORT_AUTO) {
                    inputDevice.mSelectedTransport = transport;
                    setTransport(device, transport);
                }
            } else {
                // ACCEPTING state for unknown device indicates that this device
                // was loaded from storage. Add it in the record.
                if (state == STATE_ACCEPTING) {
                    setTransport(device, transport);
                } else {
                    Log.e(
                            TAG,
                            "handleMessageConnectStateChanged: "
                                    + "remove unknown device: "
                                    + device
                                    + " state: "
                                    + state);
                    mNativeInterface.virtualUnPlug(
                            getByteAddress(device), getAddressType(device), getTransport(device));
                    return;
                }
            }

            if (transport != getTransport(device)) {
                Log.w(
                        TAG,
                        "handleMessageConnectStateChanged: "
                                + " state change received for the not-preferred transport: "
                                + transport
                                + (" newState: " + state)
                                + (", prevState: " + prevState));
            }
        } else {
            // Only TRANSPORT_AUTO should be used when allowSwitchingHidAndHogp is disabled
            transport = BluetoothDevice.TRANSPORT_AUTO;
            setTransport(device, BluetoothDevice.TRANSPORT_AUTO);
        }

        Log.d(
                TAG,
                "handleMessageConnectStateChanged: "
                        + (" newState=" + state)
                        + (" prevState=" + prevState));

        boolean connectionAllowed = true;
        // Process connection
        if (prevState == BluetoothProfile.STATE_DISCONNECTED
                && state == BluetoothProfile.STATE_CONNECTED) {
            connectionAllowed = processConnection(device, transport);
        }

        // ACCEPTING state has to be treated as DISCONNECTED state
        int reportedState = state;
        if (state == STATE_ACCEPTING) {
            reportedState = BluetoothProfile.STATE_DISCONNECTED;
        }

        if (Flags.allowSwitchingHidAndHogp() || connectionAllowed) {
            updateConnectionState(device, transport, reportedState);
        }
        updateQuiteMode(device, reportedState);
    }

    private void handleMessageDisconnect(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        int connectionPolicy = msg.arg1;

        boolean reconnectAllowed = true;
        if (Flags.allowSwitchingHidAndHogp()) {
            if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                reconnectAllowed = false;
            }
        }

        nativeDisconnect(device, getTransport(device), reconnectAllowed);
    }

    private void handleMessageConnect(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        InputDevice inputDevice = getOrCreateInputDevice(device);

        if (Flags.allowSwitchingHidAndHogp()) {
            int connectionPolicy = getConnectionPolicy(device);
            if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                Log.e(
                        TAG,
                        "handleMessageConnect: "
                                + "Connection not allowed, device: "
                                + device
                                + ", connection policy: "
                                + connectionPolicy);

                return;
            }
        }

        nativeConnect(device, inputDevice.mSelectedTransport);
    }

    /**
     * Checks if the reported transport does not match the selected transport
     *
     * @param device remote device
     * @param transport reported transport
     * @param message message ID for logging purpose
     * @return true if transport matches, otherwise false
     */
    private boolean checkTransport(BluetoothDevice device, int transport, int message) {
        if (Flags.allowSwitchingHidAndHogp() && getTransport(device) != transport) {
            Log.w(
                    TAG,
                    "message: "
                            + message
                            + ", reported transport("
                            + transport
                            + ") does not match the selected transport("
                            + getTransport(device)
                            + ")");
            return false;
        }
        return true;
    }

    /**
     * Handles connection complete
     *
     * @param device remote device
     * @return true if the connection is being retained, otherwise false
     */
    private boolean processConnection(BluetoothDevice device, int transport) {
        if (!okToConnect(device)) {
            Log.w(
                    TAG,
                    "processConnection: "
                            + "Incoming HID connection rejected for device: "
                            + device
                            + ", connection policy: "
                            + getConnectionPolicy(device));

            if (Flags.allowSwitchingHidAndHogp()) {
                nativeDisconnect(device, transport, false);
            } else {
                mNativeInterface.virtualUnPlug(
                        getByteAddress(device), getAddressType(device), getTransport(device));
            }
            return false;
        }
        return true;
    }

    /**
     * Disables the quite mode if target device gets connected
     *
     * @param device remote device
     * @param state connection state
     */
    private void updateQuiteMode(BluetoothDevice device, int state) {
        if (state == BluetoothProfile.STATE_CONNECTED
                && mTargetDevice != null
                && mTargetDevice.equals(device)) {
            // Locally initiated connection, move out of quiet mode
            Log.i(TAG, "updateQuiteMode: " + " Move out of quite mode for device: " + device);
            mTargetDevice = null;
            AdapterService adapterService = AdapterService.getAdapterService();
            adapterService.enable(false);
        }
    }

    /**
     * Handlers for incoming service calls
     */
    @VisibleForTesting
    static class BluetoothHidHostBinder extends IBluetoothHidHost.Stub
            implements IProfileServiceBinder {
        private HidHostService mService;

        BluetoothHidHostBinder(HidHostService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private HidHostService getService(AttributionSource source) {
            if (Utils.isInstrumentationTestMode()) {
                return mService;
            }
            if (!Utils.checkServiceAvailable(mService, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(mService, source, TAG)) {
                return null;
            }
            return mService;
        }

        @Override
        public void connect(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.connect(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void disconnect(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.disconnect(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionState(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                int defaultValue = BluetoothProfile.STATE_DISCONNECTED;
                if (service != null) {
                    defaultValue = service.getConnectionState(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectedDevices(AttributionSource source,
                SynchronousResultReceiver receiver) {
            getDevicesMatchingConnectionStates(new int[] { BluetoothProfile.STATE_CONNECTED },
                    source, receiver);
        }

        @Override
        public void getDevicesMatchingConnectionStates(int[] states,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>(0);
                if (service != null) {
                    defaultValue = service.getDevicesMatchingConnectionStates(states);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setConnectionPolicy(BluetoothDevice device, int connectionPolicy,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.setConnectionPolicy(device, connectionPolicy);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionPolicy(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                int defaultValue = BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.getConnectionPolicy(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setPreferredTransport(
                BluetoothDevice device,
                int transport,
                AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.setPreferredTransport(device, transport);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getPreferredTransport(
                BluetoothDevice device,
                AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                int defaultValue = BluetoothDevice.TRANSPORT_AUTO;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    defaultValue = service.getPreferredTransport(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        /* The following APIs regarding test app for compliance */
        @Override
        public void getProtocolMode(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.getProtocolMode(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void virtualUnplug(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.virtualUnplug(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setProtocolMode(BluetoothDevice device, int protocolMode,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.setProtocolMode(device, protocolMode);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getReport(BluetoothDevice device, byte reportType, byte reportId,
                int bufferSize, AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.getReport(device, reportType, reportId, bufferSize);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setReport(BluetoothDevice device, byte reportType, String report,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.setReport(device, reportType, report);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void sendData(BluetoothDevice device, String report, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.sendData(device, report);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setIdleTime(BluetoothDevice device, byte idleTime,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.setIdleTime(device, idleTime);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getIdleTime(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HidHostService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.getIdleTime(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }
    }

    ;

    //APIs

    /**
     * Connects the hid host profile for the passed in device
     *
     * @param device is the device with which to connect the hid host profile
     * @return true if connection request is passed down to mHandler.
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "connect: " + device);
        int state = getConnectionState(device);
        if (state != BluetoothProfile.STATE_DISCONNECTED) {
            Log.e(TAG, "Hid Device not disconnected: " + device + ", state: " + state);
            return false;
        }
        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "Hid Device CONNECTION_POLICY_FORBIDDEN: " + device);
            return false;
        }

        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT, device);
        mHandler.sendMessage(msg);
        return true;
    }

    /**
     * Disconnects the hid host profile from the passed in device
     *
     * @param device is the device with which to disconnect the hid host profile
     * @return true
     */
    private boolean disconnect(BluetoothDevice device, int connectionPolicy) {
        if (DBG) {
            Log.d(TAG, "disconnect: " + device + ", connection policy: " + connectionPolicy);
        }
        Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT, device);
        msg.arg1 = connectionPolicy;
        mHandler.sendMessage(msg);
        return true;
    }

    /**
     * Disconnects the hid host profile from the passed in device
     *
     * @param device is the device with which to disconnect the hid host profile
     * @return true
     */
    public boolean disconnect(BluetoothDevice device) {
        disconnect(device, getConnectionPolicy(device));
        return true;
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected,
     * {@link BluetoothProfile#STATE_CONNECTING} if this profile is being connected,
     * {@link BluetoothProfile#STATE_CONNECTED} if this profile is connected, or
     * {@link BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getConnectionState: " + device);
        InputDevice inputDevice = mInputDevices.get(device);
        if (inputDevice != null) {
            return inputDevice.getState();
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) Log.d(TAG, "getDevicesMatchingConnectionStates()");

        return mInputDevices.entrySet().stream()
                .filter(entry -> Ints.asList(states).contains(entry.getValue().getState()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p> The device should already be paired.
     * Connection policy can be one of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        if (DBG) {
            Log.d(TAG, "setConnectionPolicy: " + device);
        }

        if (!mDatabaseManager.setProfileConnectionPolicy(device, BluetoothProfile.HID_HOST,
                  connectionPolicy)) {
            return false;
        }
        if (DBG) {
            Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        }
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        }
        return true;
    }

    /**
     * @see BluetoothHidHost#setPreferredTransport
     */
    boolean setPreferredTransport(BluetoothDevice device, int transport) {
        if (DBG) {
            Log.i(TAG, "setPreferredTransport: " + device + " transport: " + transport);
        }

        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "Device not bonded" + device);
            return false;
        }

        boolean hidSupported = Utils.arrayContains(device.getUuids(), BluetoothUuid.HID);
        boolean hogpSupported = Utils.arrayContains(device.getUuids(), BluetoothUuid.HOGP);
        if (transport == BluetoothDevice.TRANSPORT_BREDR && !hidSupported) {
            Log.w(TAG, "HID not supported: " + device);
            return false;
        } else if (transport == BluetoothDevice.TRANSPORT_LE && !hogpSupported) {
            Log.w(TAG, "HOGP not supported: " + device);
            return false;
        }

        Message msg = mHandler.obtainMessage(MESSAGE_SET_PREFERRED_TRANSPORT, device);
        msg.arg1 = transport;
        mHandler.sendMessage(msg);

        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "getConnectionPolicy: " + device);
        }
        return mDatabaseManager
                .getProfileConnectionPolicy(device, BluetoothProfile.HID_HOST);
    }

    /**
     * @see BluetoothHidHost#getPreferredTransport
     */
    int getPreferredTransport(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "getPreferredTransport: " + device);
        }

        // TODO: Access to mInputDevices should be protected in binder thread
        return getTransport(device);
    }

    /* The following APIs regarding test app for compliance */
    boolean getProtocolMode(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "getProtocolMode: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_PROTOCOL_MODE, device);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean virtualUnplug(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "virtualUnplug: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_VIRTUAL_UNPLUG, device);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean setProtocolMode(BluetoothDevice device, int protocolMode) {
        if (DBG) {
            Log.d(TAG, "setProtocolMode: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_SET_PROTOCOL_MODE);
        msg.obj = device;
        msg.arg1 = protocolMode;
        mHandler.sendMessage(msg);
        return true;
    }

    boolean getReport(BluetoothDevice device, byte reportType, byte reportId, int bufferSize) {
        if (DBG) {
            Log.d(TAG, "getReport: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_REPORT);
        msg.obj = device;
        Bundle data = new Bundle();
        data.putByte(BluetoothHidHost.EXTRA_REPORT_TYPE, reportType);
        data.putByte(BluetoothHidHost.EXTRA_REPORT_ID, reportId);
        data.putInt(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, bufferSize);
        msg.setData(data);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean setReport(BluetoothDevice device, byte reportType, String report) {
        if (DBG) {
            Log.d(TAG, "setReport: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_SET_REPORT);
        msg.obj = device;
        Bundle data = new Bundle();
        data.putByte(BluetoothHidHost.EXTRA_REPORT_TYPE, reportType);
        data.putString(BluetoothHidHost.EXTRA_REPORT, report);
        msg.setData(data);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean sendData(BluetoothDevice device, String report) {
        if (DBG) {
            Log.d(TAG, "sendData: " + device);
        }
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }

        return mNativeInterface.sendData(
                getByteAddress(device),
                (byte) BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                (byte) BluetoothDevice.TRANSPORT_AUTO,
                report);
    }

    boolean getIdleTime(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getIdleTime: " + device);
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_IDLE_TIME, device);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean setIdleTime(BluetoothDevice device, byte idleTime) {
        if (DBG) Log.d(TAG, "setIdleTime: " + device);
        int state = this.getConnectionState(device);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_SET_IDLE_TIME);
        msg.obj = device;
        Bundle data = new Bundle();
        data.putByte(BluetoothHidHost.EXTRA_IDLE_TIME, idleTime);
        msg.setData(data);
        mHandler.sendMessage(msg);
        return true;
    }

    void onGetProtocolMode(byte[] address, int addressType, int transport, int mode) {
        if (DBG) Log.d(TAG, "onGetProtocolMode()");
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_PROTOCOL_MODE);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = mode;
        mHandler.sendMessage(msg);
    }

    void onGetIdleTime(byte[] address, int addressType, int transport, int idleTime) {
        if (DBG) Log.d(TAG, "onGetIdleTime()");
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_IDLE_TIME);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = idleTime;
        mHandler.sendMessage(msg);
    }

    void onGetReport(byte[] address, int addressType, int transport, byte[] report, int rptSize) {
        if (DBG) Log.d(TAG, "onGetReport()");
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_REPORT);
        msg.obj = address;
        msg.arg1 = transport;
        Bundle data = new Bundle();
        data.putByteArray(BluetoothHidHost.EXTRA_REPORT, report);
        data.putInt(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, rptSize);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    void onHandshake(byte[] address, int addressType, int transport, int status) {
        if (DBG) Log.d(TAG, "onHandshake: status=" + status);
        Message msg = mHandler.obtainMessage(MESSAGE_ON_HANDSHAKE);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = status;
        mHandler.sendMessage(msg);
    }

    void onVirtualUnplug(byte[] address, int addressType, int transport, int status) {
        if (DBG) Log.d(TAG, "onVirtualUnplug: status=" + status);
        Message msg = mHandler.obtainMessage(MESSAGE_ON_VIRTUAL_UNPLUG);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = status;
        mHandler.sendMessage(msg);
    }

    void onConnectStateChanged(byte[] address, int addressType, int transport, int state) {
        if (DBG) Log.d(TAG, "onConnectStateChanged: state=" + state);
        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_STATE_CHANGED, address);
        msg.arg1 = transport;
        msg.arg2 = state;
        mHandler.sendMessage(msg);
    }

    /**
     * Saves new connection state. Broadcasts any change from previous state
     *
     * @param device remote device
     * @param transport transport
     * @param newState new connection state
     */
    private void updateConnectionState(BluetoothDevice device, int transport, int newState) {
        InputDevice inputDevice = mInputDevices.get(device);
        int prevState = BluetoothProfile.STATE_DISCONNECTED;

        if (Flags.allowSwitchingHidAndHogp()) {
            if (inputDevice == null) {
                Log.w(
                        TAG,
                        "updateConnectionState: "
                                + "state change ("
                                + newState
                                + ") requested for unknown device: "
                                + device
                                + ", transport: "
                                + transport);
                return;
            }

            if (transport == BluetoothDevice.TRANSPORT_AUTO) {
                Log.w(
                        TAG,
                        "updateConnectionState: "
                                + "state change ("
                                + newState
                                + ") requested for unknown transport on device: "
                                + device);
                return;
            }

            prevState = inputDevice.getState(transport);
            inputDevice.setState(transport, newState);
        } else {
            if (inputDevice == null) {
                inputDevice = getOrCreateInputDevice(device);
            }
            prevState = inputDevice.getState();
            setTransport(device, transport);
            inputDevice.setState(newState);
        }

        if (prevState == newState) {
            Log.d(
                    TAG,
                    "updateConnectionState: "
                            + "no state change ("
                            + newState
                            + ") for device: "
                            + device
                            + ", transport: "
                            + transport);
            return;
        }

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.HID_HOST);
        }

        mInputDevices.put(device, inputDevice);

        broadcastConnectionState(device, transport, prevState, newState);
    }

    // This method does not check for error condition (newState == prevState)
    private void broadcastConnectionState(
            BluetoothDevice device, int transport, int prevState, int newState) {
        // Notifying the connection state change of the profile before sending the intent for
        // connection state change, as it was causing a race condition, with the UI not being
        // updated with the correct connection state.
        Log.i(
                TAG,
                "broadcastConnectionState: device: "
                        + device
                        + ", transport: "
                        + transport
                        + ", Connection state: "
                        + prevState
                        + "->"
                        + newState);

        AdapterService adapterService = AdapterService.getAdapterService();
        if (adapterService != null) {
            adapterService.updateProfileConnectionAdapterProperties(
                    device, BluetoothProfile.HID_HOST, newState, prevState);
        }

        Intent intent = new Intent(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        if (Flags.allowSwitchingHidAndHogp()) {
            intent.putExtra(BluetoothDevice.EXTRA_TRANSPORT, transport);
        }
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcastAsUser(
                intent,
                UserHandle.ALL,
                BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
    }

    private void broadcastHandshake(BluetoothDevice device, int status) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_HANDSHAKE);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_STATUS, status);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Utils.sendBroadcast(this, intent, BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
    }

    private void broadcastProtocolMode(BluetoothDevice device, int protocolMode) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_PROTOCOL_MODE, protocolMode);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Utils.sendBroadcast(this, intent, BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
        if (DBG) {
            Log.d(TAG, "Protocol Mode (" + device + "): " + protocolMode);
        }
    }

    private void broadcastReport(BluetoothDevice device, byte[] report, int rptSize) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_REPORT);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_REPORT, report);
        intent.putExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, rptSize);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Utils.sendBroadcast(this, intent, BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
    }

    private void broadcastVirtualUnplugStatus(BluetoothDevice device, int status) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS, status);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Utils.sendBroadcast(this, intent, BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
    }

    private void broadcastIdleTime(BluetoothDevice device, int idleTime) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_IDLE_TIME_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_IDLE_TIME, idleTime);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Utils.sendBroadcast(this, intent, BLUETOOTH_CONNECT,
                Utils.getTempAllowlistBroadcastOptions());
        if (DBG) {
            Log.d(TAG, "Idle time (" + device + "): " + idleTime);
        }
    }

    /**
     * Check whether can connect to a peer device.
     * The check considers a number of factors during the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public boolean okToConnect(BluetoothDevice device) {
        AdapterService adapterService = AdapterService.getAdapterService();
        // Check if adapter service is null.
        if (adapterService == null) {
            Log.w(TAG, "okToConnect: adapter service is null");
            return false;
        }
        // Check if this is an incoming connection in Quiet mode.
        if (adapterService.isQuietModeEnabled() && mTargetDevice == null) {
            Log.w(TAG, "okToConnect: return false as quiet mode enabled");
            return false;
        }
        // Check connection policy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = adapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        println(sb, "mTargetDevice: " + mTargetDevice);
        println(sb, "mInputDevices:");
        mInputDevices.forEach(
                (k, v) -> sb.append(" " + k.getAddressForLogging() + " : " + v + "\n"));
    }
}
