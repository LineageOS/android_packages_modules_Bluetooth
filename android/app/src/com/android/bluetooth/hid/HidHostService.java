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
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHidHost;
import android.content.AttributionSource;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Provides Bluetooth Hid Host profile, as a service in the Bluetooth application. */
public class HidHostService extends ProfileService {
    private static final String TAG = HidHostService.class.getSimpleName();

    public static final String ANDROID_HEADTRACKER_UUID_STR =
            "109b862f-50e3-45cc-8ea1-ac62de4846d1";

    public static final ParcelUuid ANDROID_HEADTRACKER_UUID =
            ParcelUuid.fromString(ANDROID_HEADTRACKER_UUID_STR);

    private static class InputDevice {
        int mSelectedTransport = BluetoothDevice.TRANSPORT_AUTO;
        private int mHidState = STATE_DISCONNECTED;
        private int mHogpState = STATE_DISCONNECTED;

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

        @Override
        public String toString() {
            return ("Selected transport=" + mSelectedTransport)
                    + (" HID connection state=" + mHidState)
                    + (" HOGP connection state=" + mHogpState);
        }
    }

    private static HidHostService sHidHostService;

    private final Map<BluetoothDevice, InputDevice> mInputDevices =
            Collections.synchronizedMap(new HashMap<>());

    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
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
    private static final int MESSAGE_SEND_DATA = 18;

    public static final int STATE_ACCEPTING = STATE_DISCONNECTING + 1;

    public HidHostService(AdapterService adapterService) {
        super(adapterService);

        mAdapterService = requireNonNull(adapterService);
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mNativeInterface = requireNonNull(HidHostNativeInterface.getInstance());

        mNativeInterface.init(this);
        setHidHostService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileHidHostEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHidHostBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup HidHost Service");

        mNativeInterface.cleanup();

        if (mInputDevices != null) {
            for (BluetoothDevice device : mInputDevices.keySet()) {
                // Set both HID and HOGP connection states to disconnected
                updateConnectionState(device, BluetoothDevice.TRANSPORT_LE, STATE_DISCONNECTED);
                updateConnectionState(device, BluetoothDevice.TRANSPORT_BREDR, STATE_DISCONNECTED);
            }
            mInputDevices.clear();
        }
        // TODO(b/72948646): this should be moved to stop()
        setHidHostService(null);
    }

    private byte[] getIdentityAddress(BluetoothDevice device) {
        if (Flags.identityAddressNullIfNotKnown()) {
            return Utils.getByteBrEdrAddress(mAdapterService, device);
        } else {
            return mAdapterService.getByteIdentityAddress(device);
        }
    }

    private byte[] getByteAddress(BluetoothDevice device, int transport) {
        final ParcelUuid[] uuids = mAdapterService.getRemoteUuids(device);

        if (transport == BluetoothDevice.TRANSPORT_LE) {
            // Use pseudo address when HOGP is to be used
            return Utils.getByteAddress(device);
        } else if (transport == BluetoothDevice.TRANSPORT_BREDR) {
            // Use identity address if HID is to be used
            return getIdentityAddress(device);
        } else { // BluetoothDevice.TRANSPORT_AUTO
            boolean hidSupported = Utils.arrayContains(uuids, BluetoothUuid.HID);
            // Prefer HID over HOGP
            if (hidSupported) {
                // Use identity address if HID is available
                return getIdentityAddress(device);
            } else {
                // Otherwise use pseudo address
                return Utils.getByteAddress(device);
            }
        }
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return getByteAddress(device, getTransport(device));
    }

    /**
     * Retrieves device address type
     *
     * @param device remote device
     * @return address type
     */
    private static int getAddressType(BluetoothDevice device) {
        return device.getAddressType();
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

        return STATE_DISCONNECTED;
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
        Log.d(TAG, "setHidHostService(): set to: " + instance);
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
                getByteAddress(device, transport), getAddressType(device), transport)) {
            Log.w(
                    TAG,
                    "nativeConnect: Connection attempt failed."
                            + (" device=" + device)
                            + (" transport=" + transport));
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
                getByteAddress(device, transport),
                getAddressType(device),
                transport,
                reconnectAllowed)) {
            Log.w(
                    TAG,
                    "nativeDisconnect: Disconnection attempt failed."
                            + (" device=" + device)
                            + (" transport=" + transport));
            return false;
        }
        return true;
    }

    private final Handler mHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    Log.v(TAG, "handleMessage(): msg.what=" + msg.what);

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
                            handleMessageSetReport(msg);
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
                        case MESSAGE_SEND_DATA:
                            handleMessageSendData(msg);
                            break;
                    }
                }
            };

    private void handleMessageSendData(Message msg) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte((byte[]) msg.obj);

        Bundle data = msg.getData();
        String report = data.getString(BluetoothHidHost.EXTRA_REPORT);

        if (!mNativeInterface.sendData(
                getByteAddress(device), getAddressType(device), getTransport(device), report)) {
            Log.e(TAG, "handleMessageSendData: Failed to send data");
        }
    }

    private void handleMessageSetPreferredTransport(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        int transport = msg.arg1;

        int prevTransport = getTransport(device);
        Log.i(
                TAG,
                "handleMessageSetPreferredTransport: Transport changed"
                        + (" device=" + device)
                        + (" transport: prev=" + prevTransport + " -> new=" + transport));

        InputDevice inputDevice = getOrCreateInputDevice(device);
        if (!Flags.ignoreUnselectedHidTransportStates()) {
            inputDevice.mSelectedTransport = transport;
        }

        /* If connections are allowed, ensure that the previous transport is disconnected and the
        new transport is connected */
        if (getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
            if (prevTransport != transport) {
                Log.i(
                        TAG,
                        "handleMessageSetPreferredTransport: Connection switch"
                                + (" device=" + device)
                                + (" transport: prev=" + prevTransport + " -> new=" + transport));
                // Disconnect the other transport and disallow reconnections
                nativeDisconnect(device, prevTransport, false);
                if (Flags.ignoreUnselectedHidTransportStates()) {
                    // Immediately update the connection state to disconnected. From now on,
                    // the connection state will be updated only for the selected transport.
                    updateConnectionState(device, prevTransport, STATE_DISCONNECTED);
                }
                // Request to connect the preferred transport
                nativeConnect(device, transport);
            }
        }

        if (Flags.ignoreUnselectedHidTransportStates()) {
            // Save the preferred transport
            inputDevice.mSelectedTransport = transport;
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

        updateConnectionState(device, getTransport(device), STATE_DISCONNECTED);
        mInputDevices.remove(device);

        int status = msg.arg2;
        broadcastVirtualUnplugStatus(device, status);
    }

    private void handleMessageSetReport(Message msg) {
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
                        "handleMessageConnectStateChanged: Disconnect and unknown inputDevice"
                                + (" device=" + device)
                                + (" state=" + state));
                nativeDisconnect(device, transport, false);
                return;
            }
        }

        if (transport != getTransport(device)) {
            Log.w(
                    TAG,
                    "handleMessageConnectStateChanged: Not preferred transport in message"
                            + (" device=" + device)
                            + (" transport=" + transport)
                            + (" newState=" + state)
                            + (" prevState=" + prevState));
            if (Flags.ignoreUnselectedHidTransportStates()) {
                return;
            }
        }

        Log.d(
                TAG,
                "handleMessageConnectStateChanged:"
                        + (" device=" + device)
                        + (" transport=" + transport)
                        + (" newState=" + state)
                        + (" prevState=" + prevState));

        // Process connection
        if (prevState == STATE_DISCONNECTED && state == STATE_CONNECTED) {
            processConnection(device, transport);
        }

        // ACCEPTING state has to be treated as DISCONNECTED state
        int reportedState = state;
        if (state == STATE_ACCEPTING) {
            reportedState = STATE_DISCONNECTED;
        }
        updateConnectionState(device, transport, reportedState);
    }

    private void handleMessageDisconnect(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        int connectionPolicy = msg.arg1;

        boolean reconnectAllowed = true;
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            reconnectAllowed = false;
        }
        nativeDisconnect(device, getTransport(device), reconnectAllowed);
    }

    private void handleMessageConnect(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        InputDevice inputDevice = getOrCreateInputDevice(device);

        int connectionPolicy = getConnectionPolicy(device);
        if (connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            Log.e(
                    TAG,
                    "handleMessageConnect: Connection not allowed."
                            + (" device=" + device)
                            + (" connectionPolicy=" + connectionPolicy));

            return;
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
        if (getTransport(device) != transport) {
            Log.w(
                    TAG,
                    "checkTransport:"
                            + (" message= " + message)
                            + (" reported transport(" + transport + ")")
                            + (" doesn't match selected transport(" + getTransport(device) + ")"));
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
                    "processConnection: Incoming HID connection rejected."
                            + (" device=" + device)
                            + (" connectionPolicy=" + getConnectionPolicy(device)));

            nativeDisconnect(device, transport, false);
            return false;
        }
        return true;
    }

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

        @RequiresPermission(BLUETOOTH_CONNECT)
        private HidHostService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            HidHostService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            return service;
        }

        @Override
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.disconnect(device);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            return getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}, source);
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getConnectionPolicy(device);
        }

        @Override
        public boolean setPreferredTransport(
                BluetoothDevice device, int transport, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.setPreferredTransport(device, transport);
        }

        @Override
        public int getPreferredTransport(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return BluetoothDevice.TRANSPORT_AUTO;
            }
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getPreferredTransport(device);
        }

        /* The following APIs regarding test app for compliance */
        @Override
        public boolean getProtocolMode(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.getProtocolMode(device);
        }

        @Override
        public boolean virtualUnplug(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.virtualUnplug(device);
        }

        @Override
        public boolean setProtocolMode(
                BluetoothDevice device, int protocolMode, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.setProtocolMode(device, protocolMode);
        }

        @Override
        public boolean getReport(
                BluetoothDevice device,
                byte reportType,
                byte reportId,
                int bufferSize,
                AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.getReport(device, reportType, reportId, bufferSize);
        }

        @Override
        public boolean setReport(
                BluetoothDevice device, byte reportType, String report, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.setReport(device, reportType, report);
        }

        @Override
        public boolean sendData(BluetoothDevice device, String report, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.sendData(device, report);
        }

        @Override
        public boolean setIdleTime(
                BluetoothDevice device, byte idleTime, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.setIdleTime(device, idleTime);
        }

        @Override
        public boolean getIdleTime(BluetoothDevice device, AttributionSource source) {
            HidHostService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.getIdleTime(device);
        }
    }
    ;

    // APIs

    /**
     * Connects the hid host profile for the passed in device
     *
     * @param device is the device with which to connect the hid host profile
     * @return true if connection request is passed down to mHandler.
     */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect: device=" + device);
        int state = getConnectionState(device);
        if (state != STATE_DISCONNECTED) {
            Log.e(TAG, "Device " + device + " not disconnected. state=" + state);
            return false;
        }
        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "Device " + device + " CONNECTION_POLICY_FORBIDDEN");
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
        Log.d(TAG, "disconnect: device=" + device + " connectionPolicy=" + connectionPolicy);
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
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        Log.d(TAG, "getConnectionState: device=" + device);
        InputDevice inputDevice = mInputDevices.get(device);
        if (inputDevice != null) {
            return inputDevice.getState();
        }
        return STATE_DISCONNECTED;
    }

    @VisibleForTesting
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.d(TAG, "getDevicesMatchingConnectionStates()");
        return mInputDevices.entrySet().stream()
                .filter(e -> IntStream.of(states).anyMatch(x -> x == e.getValue().getState()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "setConnectionPolicy: device=" + device);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.HID_HOST, connectionPolicy)) {
            return false;
        }
        Log.d(TAG, "Saved connectionPolicy=" + connectionPolicy + " for device=" + device);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device, CONNECTION_POLICY_FORBIDDEN);
            MetricsLogger.getInstance()
                    .count(BluetoothProtoEnums.HIDH_COUNT_CONNECTION_POLICY_DISABLED, 1);
        }
        return true;
    }

    /**
     * @see BluetoothHidHost#setPreferredTransport
     */
    boolean setPreferredTransport(BluetoothDevice device, int transport) {
        Log.i(TAG, "setPreferredTransport: device=" + device + " transport=" + transport);

        if (mAdapterService.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "Device " + device + " not bonded");
            return false;
        }

        final ParcelUuid[] uuids = mAdapterService.getRemoteUuids(device);
        boolean hidSupported = Utils.arrayContains(uuids, BluetoothUuid.HID);
        boolean hogpSupported = Utils.arrayContains(uuids, BluetoothUuid.HOGP);
        boolean headtrackerSupported =
                Utils.arrayContains(uuids, HidHostService.ANDROID_HEADTRACKER_UUID);
        if (transport == BluetoothDevice.TRANSPORT_BREDR && !hidSupported) {
            Log.w(TAG, "device " + device + " does not support HID");
            return false;
        } else if (transport == BluetoothDevice.TRANSPORT_LE
                && !(hogpSupported || headtrackerSupported)) {
            Log.w(TAG, "device " + device + " does not support HOGP");
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
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        Log.d(TAG, "getConnectionPolicy: device=" + device);
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HID_HOST);
    }

    /**
     * @see BluetoothHidHost#getPreferredTransport
     */
    @VisibleForTesting
    int getPreferredTransport(BluetoothDevice device) {
        Log.d(TAG, "getPreferredTransport: device=" + device);

        // TODO: Access to mInputDevices should be protected in binder thread
        return getTransport(device);
    }

    /* The following APIs regarding test app for compliance */
    @VisibleForTesting
    boolean getProtocolMode(BluetoothDevice device) {
        Log.d(TAG, "getProtocolMode: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_PROTOCOL_MODE, device);
        mHandler.sendMessage(msg);
        return true;
    }

    @VisibleForTesting
    boolean virtualUnplug(BluetoothDevice device) {
        Log.d(TAG, "virtualUnplug: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_VIRTUAL_UNPLUG, device);
        mHandler.sendMessage(msg);
        return true;
    }

    @VisibleForTesting
    boolean setProtocolMode(BluetoothDevice device, int protocolMode) {
        Log.d(TAG, "setProtocolMode: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_SET_PROTOCOL_MODE);
        msg.obj = device;
        msg.arg1 = protocolMode;
        mHandler.sendMessage(msg);
        return true;
    }

    @VisibleForTesting
    boolean getReport(BluetoothDevice device, byte reportType, byte reportId, int bufferSize) {
        Log.d(TAG, "getReport: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
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

    @VisibleForTesting
    boolean setReport(BluetoothDevice device, byte reportType, String report) {
        Log.d(TAG, "setReport: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
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

    @VisibleForTesting
    boolean sendData(BluetoothDevice device, String report) {
        Log.d(TAG, "sendData: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
            return false;
        }

        Message msg = mHandler.obtainMessage(MESSAGE_SEND_DATA, device);
        Bundle data = new Bundle();
        data.putString(BluetoothHidHost.EXTRA_REPORT, report);
        msg.setData(data);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean getIdleTime(BluetoothDevice device) {
        Log.d(TAG, "getIdleTime: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_IDLE_TIME, device);
        mHandler.sendMessage(msg);
        return true;
    }

    boolean setIdleTime(BluetoothDevice device, byte idleTime) {
        Log.d(TAG, "setIdleTime: device=" + device);
        int state = this.getConnectionState(device);
        if (state != STATE_CONNECTED) {
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
        Log.d(TAG, "onGetProtocolMode()");
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_PROTOCOL_MODE);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = mode;
        mHandler.sendMessage(msg);
    }

    void onGetIdleTime(byte[] address, int addressType, int transport, int idleTime) {
        Log.d(TAG, "onGetIdleTime()");
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_IDLE_TIME);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = idleTime;
        mHandler.sendMessage(msg);
    }

    void onGetReport(byte[] address, int addressType, int transport, byte[] report, int rptSize) {
        Log.d(TAG, "onGetReport()");
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
        Log.d(TAG, "onHandshake: status=" + status);
        Message msg = mHandler.obtainMessage(MESSAGE_ON_HANDSHAKE);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = status;
        mHandler.sendMessage(msg);
    }

    void onVirtualUnplug(byte[] address, int addressType, int transport, int status) {
        Log.d(TAG, "onVirtualUnplug: status=" + status);
        Message msg = mHandler.obtainMessage(MESSAGE_ON_VIRTUAL_UNPLUG);
        msg.obj = address;
        msg.arg1 = transport;
        msg.arg2 = status;
        mHandler.sendMessage(msg);
    }

    void onConnectStateChanged(byte[] address, int addressType, int transport, int state) {
        Log.d(TAG, "onConnectStateChanged: state=" + state);
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

        if (inputDevice == null) {
            Log.w(
                    TAG,
                    "updateConnectionState: requested on unknown inputDevice"
                            + (" device=" + device)
                            + (" newState=" + newState)
                            + (" transport=" + transport));
            return;
        }

        if (transport == BluetoothDevice.TRANSPORT_AUTO) {
            Log.w(
                    TAG,
                    "updateConnectionState: requested with AUTO transport"
                            + (" device=" + device)
                            + (" newState=" + newState));
            return;
        }

        int prevState = inputDevice.getState(transport);
        inputDevice.setState(transport, newState);

        if (prevState == newState) {
            Log.d(
                    TAG,
                    "updateConnectionState: No state change for"
                            + (" device=" + device)
                            + (" newState=" + newState)
                            + (" transport=" + transport));
            return;
        }

        if (newState == STATE_CONNECTED) {
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
                "broadcastConnectionState:"
                        + (" device= " + device)
                        + (" transport= " + transport)
                        + (" prevState=" + prevState + " -> newState=" + newState));

        mAdapterService.updateProfileConnectionAdapterProperties(
                device, BluetoothProfile.HID_HOST, newState, prevState);

        Intent intent = new Intent(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_TRANSPORT, transport);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcastAsUser(
                intent,
                UserHandle.ALL,
                BLUETOOTH_CONNECT,
                Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastHandshake(BluetoothDevice device, int status) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_HANDSHAKE);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_STATUS, status);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastProtocolMode(BluetoothDevice device, int protocolMode) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_PROTOCOL_MODE, protocolMode);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
        Log.d(TAG, "broadcastProtocolMode: device=" + device + " protocolMode=" + protocolMode);
    }

    private void broadcastReport(BluetoothDevice device, byte[] report, int rptSize) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_REPORT);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_REPORT, report);
        intent.putExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, rptSize);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastVirtualUnplugStatus(BluetoothDevice device, int status) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS, status);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastIdleTime(BluetoothDevice device, int idleTime) {
        Intent intent = new Intent(BluetoothHidHost.ACTION_IDLE_TIME_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHidHost.EXTRA_IDLE_TIME, idleTime);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
        Log.d(TAG, "broadcastIdleTime: device=" + device + " idleTime=" + idleTime);
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public boolean okToConnect(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.w(TAG, "okToConnect: return false because of quiet mode enabled. device=" + device);
            return false;
        }
        // Check connection policy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        if (!Flags.donotValidateBondStateFromProfiles()) {
            int bondState = mAdapterService.getBondState(device);
            // Allow this connection only if the device is bonded. Any attempt to connect
            // while bonding would potentially lead to an unauthorized connection.
            if (bondState != BluetoothDevice.BOND_BONDED) {
                Log.w(TAG, "okToConnect: return false, device=" + device + " bondState="
                    + bondState);
                return false;
            }
        }
        if (connectionPolicy != CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(
                    TAG,
                    "okToConnect: return false,"
                            + (" device=" + device)
                            + (" connectionPolicy=" + connectionPolicy));
            return false;
        }
        return true;
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        println(sb, "mInputDevices:");
        mInputDevices.forEach(
                (k, v) -> sb.append(" ").append(k).append(" : ").append(v).append("\n"));
    }
}
