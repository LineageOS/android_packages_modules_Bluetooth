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

package com.android.bluetooth.pan;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.BluetoothUtils.logRemoteException;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPan.LocalPanRole;
import android.bluetooth.BluetoothPan.RemotePanRole;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothPan;
import android.bluetooth.IBluetoothPanCallback;
import android.content.AttributionSource;
import android.content.Intent;
import android.content.res.Resources.NotFoundException;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserManager;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.HandlerExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth Pan Device profile, as a service in the Bluetooth application. */
public class PanService extends ProfileService {
    private static final String TAG = PanService.class.getSimpleName();

    private static PanService sPanService;

    private static final int BLUETOOTH_MAX_PAN_CONNECTIONS = 5;

    private static final int MESSAGE_CONNECT = 1;
    private static final int MESSAGE_DISCONNECT = 2;
    private static final int MESSAGE_CONNECT_STATE_CHANGED = 11;

    @VisibleForTesting
    final ConcurrentHashMap<BluetoothDevice, BluetoothPanDevice> mPanDevices =
            new ConcurrentHashMap<>();

    private final Map<String, IBluetoothPanCallback> mBluetoothTetheringCallbacks = new HashMap<>();
    private final AdapterService mAdapterService;
    private final PanNativeInterface mNativeInterface;
    private final DatabaseManager mDatabaseManager;
    private final TetheringManager mTetheringManager;
    private final UserManager mUserManager;
    private final int mMaxPanDevices;

    private String mPanIfName;
    @VisibleForTesting boolean mIsTethering = false;
    private boolean mTetherOn = false;
    private BluetoothTetheringNetworkFactory mNetworkFactory;

    final TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onError(TetheringInterface iface, int error) {
                    if (mIsTethering && iface.getType() == TetheringManager.TETHERING_BLUETOOTH) {
                        // Tethering fail because of @TetheringIfaceError error.
                        Log.e(TAG, "Error setting up tether interface: " + error);
                        for (BluetoothDevice device : mPanDevices.keySet()) {
                            mNativeInterface.disconnect(
                                    Flags.panUseIdentityAddress()
                                            ? Utils.getByteBrEdrAddress(mAdapterService, device)
                                            : Utils.getByteAddress(device));
                        }
                        mPanDevices.clear();
                        mIsTethering = false;
                    }
                }
            };

    public PanService(AdapterService adapterService) {
        this(adapterService, null);
    }

    @VisibleForTesting
    PanService(AdapterService adapterService, PanNativeInterface nativeInterface) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mNativeInterface =
                requireNonNullElseGet(nativeInterface, () -> new PanNativeInterface(this));
        mUserManager = requireNonNull(getSystemService(UserManager.class));
        mTetheringManager = requireNonNull(getSystemService(TetheringManager.class));

        int maxPanDevice;
        try {
            maxPanDevice =
                    getResources()
                            .getInteger(com.android.bluetooth.R.integer.config_max_pan_devices);
        } catch (NotFoundException e) {
            maxPanDevice = BLUETOOTH_MAX_PAN_CONNECTIONS;
        }
        mMaxPanDevices = maxPanDevice;

        mNativeInterface.init();

        mTetheringManager.registerTetheringEventCallback(
                new HandlerExecutor(new Handler(Looper.getMainLooper())), mTetheringCallback);
        setPanService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfilePanNapEnabled().orElse(false)
                || BluetoothProperties.isProfilePanPanuEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothPanBinder(this);
    }

    public static synchronized PanService getPanService() {
        if (sPanService == null) {
            Log.w(TAG, "getPanService(): service is null");
            return null;
        }
        if (!sPanService.isAvailable()) {
            Log.w(TAG, "getPanService(): service is not available ");
            return null;
        }
        return sPanService;
    }

    private static synchronized void setPanService(PanService instance) {
        Log.d(TAG, "setPanService(): set to: " + instance);
        sPanService = instance;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Pan Service");

        mTetheringManager.unregisterTetheringEventCallback(mTetheringCallback);
        mNativeInterface.cleanup();
        mHandler.removeCallbacksAndMessages(null);

        setPanService(null);

        int[] desiredStates = {STATE_CONNECTING, STATE_CONNECTED, STATE_DISCONNECTING};
        List<BluetoothDevice> devList = getDevicesMatchingConnectionStates(desiredStates);
        for (BluetoothDevice device : devList) {
            BluetoothPanDevice panDevice = mPanDevices.get(device);
            Log.d(TAG, "panDevice: " + panDevice + " device address: " + device);
            if (panDevice != null) {
                handlePanDeviceStateChange(
                        device,
                        mPanIfName,
                        STATE_DISCONNECTED,
                        panDevice.mLocalRole,
                        panDevice.mRemoteRole);
            }
        }
        mPanDevices.clear();
        mHandler.removeCallbacksAndMessages(null);
    }

    private final Handler mHandler =
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MESSAGE_CONNECT:
                            BluetoothDevice connectDevice = (BluetoothDevice) msg.obj;
                            if (!mNativeInterface.connect(
                                    Flags.identityAddressNullIfNotKnown()
                                            ? Utils.getByteBrEdrAddress(
                                                    mAdapterService, connectDevice)
                                            : mAdapterService.getByteIdentityAddress(
                                                    connectDevice))) {
                                handlePanDeviceStateChange(
                                        connectDevice,
                                        null,
                                        STATE_CONNECTING,
                                        BluetoothPan.LOCAL_PANU_ROLE,
                                        BluetoothPan.REMOTE_NAP_ROLE);
                                handlePanDeviceStateChange(
                                        connectDevice,
                                        null,
                                        STATE_DISCONNECTED,
                                        BluetoothPan.LOCAL_PANU_ROLE,
                                        BluetoothPan.REMOTE_NAP_ROLE);
                            }
                            break;
                        case MESSAGE_DISCONNECT:
                            BluetoothDevice disconnectDevice = (BluetoothDevice) msg.obj;
                            if (!mNativeInterface.disconnect(
                                    Flags.identityAddressNullIfNotKnown()
                                            ? Utils.getByteBrEdrAddress(
                                                    mAdapterService, disconnectDevice)
                                            : mAdapterService.getByteIdentityAddress(
                                                    disconnectDevice))) {
                                handlePanDeviceStateChange(
                                        disconnectDevice,
                                        mPanIfName,
                                        STATE_DISCONNECTING,
                                        BluetoothPan.LOCAL_PANU_ROLE,
                                        BluetoothPan.REMOTE_NAP_ROLE);
                                handlePanDeviceStateChange(
                                        disconnectDevice,
                                        mPanIfName,
                                        STATE_DISCONNECTED,
                                        BluetoothPan.LOCAL_PANU_ROLE,
                                        BluetoothPan.REMOTE_NAP_ROLE);
                            }
                            break;
                        case MESSAGE_CONNECT_STATE_CHANGED:
                            ConnectState cs = (ConnectState) msg.obj;
                            final BluetoothDevice device =
                                    mAdapterService.getDeviceFromByte(cs.addr);
                            // TBD get iface from the msg
                            Log.d(
                                    TAG,
                                    "MESSAGE_CONNECT_STATE_CHANGED: "
                                            + device
                                            + " state: "
                                            + cs.state);
                            // It could be null if the connection up is coming when the
                            // Bluetooth is turning off.
                            if (device == null) {
                                break;
                            }
                            handlePanDeviceStateChange(
                                    device,
                                    mPanIfName /* iface */,
                                    cs.state,
                                    cs.local_role,
                                    cs.remote_role);
                            break;
                    }
                }
            };

    /** Handlers for incoming service calls */
    @VisibleForTesting
    static class BluetoothPanBinder extends IBluetoothPan.Stub implements IProfileServiceBinder {
        private PanService mService;

        BluetoothPanBinder(PanService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private PanService getService(AttributionSource source) {
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
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return BluetoothPan.STATE_DISCONNECTED;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectionState(device);
        }

        @Override
        public boolean isTetheringOn(AttributionSource source) {
            // TODO(BT) have a variable marking the on/off state
            PanService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.isTetheringOn();
        }

        @Override
        public void setBluetoothTethering(
                IBluetoothPanCallback callback, int id, boolean value, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return;
            }

            Log.d(
                    TAG,
                    "setBluetoothTethering:"
                            + (" value=" + value)
                            + (" pkgName= " + source.getPackageName())
                            + (" mTetherOn= " + service.mTetherOn));

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.enforceCallingOrSelfPermission(TETHER_PRIVILEGED, null);

            service.setBluetoothTethering(callback, id, source.getUid(), value);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            PanService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getDevicesMatchingConnectionStates(states);
        }
    }

    public boolean connect(BluetoothDevice device) {
        if (mUserManager.isGuestUser()) {
            Log.w(TAG, "Guest user does not have the permission to change the WiFi network");
            return false;
        }
        if (getConnectionState(device) != STATE_DISCONNECTED) {
            Log.e(TAG, "Pan Device not disconnected: " + device);
            return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT, device);
        mHandler.sendMessage(msg);
        return true;
    }

    public boolean disconnect(BluetoothDevice device) {
        Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT, device);
        mHandler.sendMessage(msg);
        return true;
    }

    public int getConnectionState(BluetoothDevice device) {
        BluetoothPanDevice panDevice = mPanDevices.get(device);
        if (panDevice == null) {
            return BluetoothPan.STATE_DISCONNECTED;
        }
        return panDevice.mState;
    }

    public boolean isTetheringOn() {
        // TODO(BT) have a variable marking the on/off state
        return mTetherOn;
    }

    void setBluetoothTethering(
            IBluetoothPanCallback callback, int id, int callerUid, boolean value) {
        Log.d(TAG, "setBluetoothTethering: " + value + ", mTetherOn: " + mTetherOn);

        UserManager um = getSystemService(UserManager.class);
        if (um.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING) && value) {
            throw new SecurityException("DISALLOW_CONFIG_TETHERING is enabled for this user.");
        }
        final String key = id + "/" + callerUid;
        if (callback != null) {
            boolean keyExists = mBluetoothTetheringCallbacks.containsKey(key);
            if (value) {
                if (!keyExists) {
                    mBluetoothTetheringCallbacks.put(key, callback);
                } else {
                    Log.e(TAG, "setBluetoothTethering Error: Callback already registered.");
                    return;
                }
            } else {
                if (keyExists) {
                    mBluetoothTetheringCallbacks.remove(key);
                } else {
                    Log.e(TAG, "setBluetoothTethering Error: Callback not registered.");
                    return;
                }
            }
        }
        if (mTetherOn != value) {
            // drop any existing panu or pan-nap connection when changing the tethering state
            mTetherOn = value;
            List<BluetoothDevice> devList = getConnectedDevices();
            for (BluetoothDevice dev : devList) {
                disconnect(dev);
            }
            Intent intent = new Intent(BluetoothPan.ACTION_TETHERING_STATE_CHANGED);
            intent.putExtra(
                    BluetoothPan.EXTRA_TETHERING_STATE,
                    mTetherOn ? BluetoothPan.TETHERING_STATE_ON : BluetoothPan.TETHERING_STATE_OFF);
            sendBroadcast(intent, null, Utils.getTempBroadcastOptions().toBundle());
        }
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
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.PAN, connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
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
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.PAN);
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices =
                getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED});
        return devices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> panDevices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device : mPanDevices.keySet()) {
            int panDeviceState = getConnectionState(device);
            for (int state : states) {
                if (state == panDeviceState) {
                    panDevices.add(device);
                    break;
                }
            }
        }
        return panDevices;
    }

    protected static class ConnectState {
        public ConnectState(byte[] address, int state, int error, int localRole, int remoteRole) {
            this.addr = address;
            this.state = state;
            this.error = error;
            this.local_role = localRole;
            this.remote_role = remoteRole;
        }

        public byte[] addr;
        public int state;
        public int error;
        public int local_role;
        public int remote_role;
    }

    void onConnectStateChanged(
            byte[] address, int state, int error, int localRole, int remoteRole) {
        Log.d(
                TAG,
                "onConnectStateChanged: "
                        + state
                        + ", local role:"
                        + localRole
                        + ", remoteRole: "
                        + remoteRole);
        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_STATE_CHANGED);
        msg.obj = new ConnectState(address, state, error, localRole, remoteRole);
        mHandler.sendMessage(msg);
    }

    @VisibleForTesting
    void onControlStateChanged(int localRole, int state, int error, String ifname) {
        Log.d(TAG, "onControlStateChanged: " + state + ", error: " + error + ", ifname: " + ifname);
        if (error == 0) {
            mPanIfName = ifname;
        }
    }

    void handlePanDeviceStateChange(
            BluetoothDevice device,
            String iface,
            int state,
            @LocalPanRole int localRole,
            @RemotePanRole int remoteRole) {
        Log.d(
                TAG,
                "handlePanDeviceStateChange: device: "
                        + device
                        + ", iface: "
                        + iface
                        + ", state: "
                        + state
                        + ", localRole:"
                        + localRole
                        + ", remoteRole:"
                        + remoteRole);
        int prevState;

        BluetoothPanDevice panDevice = mPanDevices.get(device);
        if (panDevice == null) {
            Log.i(TAG, "state " + state + " Num of connected pan devices: " + mPanDevices.size());
            prevState = STATE_DISCONNECTED;
            panDevice = new BluetoothPanDevice(state, localRole, remoteRole);
            mPanDevices.put(device, panDevice);
        } else {
            prevState = panDevice.mState;
            panDevice.mState = state;
            panDevice.mLocalRole = localRole;
            panDevice.mRemoteRole = remoteRole;
        }

        // Avoid race condition that gets this class stuck in STATE_DISCONNECTING. While we
        // are in STATE_CONNECTING, if a BluetoothPan#disconnect call comes in, the original
        // connect call will put us in STATE_DISCONNECTED. Then, the disconnect completes and
        // changes the state to STATE_DISCONNECTING. All future calls to BluetoothPan#connect
        // will fail until the caller explicitly calls BluetoothPan#disconnect.
        if (prevState == STATE_DISCONNECTED && state == STATE_DISCONNECTING) {
            Log.d(TAG, "Ignoring state change from " + prevState + " to " + state);
            mPanDevices.remove(device);
            return;
        }

        Log.d(TAG, "handlePanDeviceStateChange preState: " + prevState + " state: " + state);
        if (prevState == state) {
            return;
        }
        if (remoteRole == BluetoothPan.LOCAL_PANU_ROLE) {
            if (state == STATE_CONNECTED) {
                if ((!mTetherOn) || (localRole == BluetoothPan.LOCAL_PANU_ROLE)) {
                    Log.d(
                            TAG,
                            "handlePanDeviceStateChange BT tethering is off/Local role"
                                    + " is PANU drop the connection");
                    mPanDevices.remove(device);
                    mNativeInterface.disconnect(
                            Flags.panUseIdentityAddress()
                                    ? Utils.getByteBrEdrAddress(mAdapterService, device)
                                    : Utils.getByteAddress(device));
                    return;
                }
                Log.d(TAG, "handlePanDeviceStateChange LOCAL_NAP_ROLE:REMOTE_PANU_ROLE");
                if (!mIsTethering) {
                    mIsTethering = true;
                    try {
                        for (IBluetoothPanCallback cb : mBluetoothTetheringCallbacks.values()) {
                            cb.onAvailable(iface);
                        }
                    } catch (RemoteException e) {
                        logRemoteException(TAG, e);
                    }
                }
            } else if (state == STATE_DISCONNECTED) {
                mPanDevices.remove(device);
                Log.i(
                        TAG,
                        "remote(PANU) is disconnected, Remaining connected PANU devices: "
                                + mPanDevices.size());
                if (mIsTethering && mPanDevices.size() == 0) {
                    try {
                        for (IBluetoothPanCallback cb : mBluetoothTetheringCallbacks.values()) {
                            cb.onUnavailable();
                        }
                    } catch (RemoteException e) {
                        logRemoteException(TAG, e);
                    }
                    mIsTethering = false;
                }
            }
        } else {
            // PANU Role = reverse Tether
            Log.d(
                    TAG,
                    "handlePanDeviceStateChange LOCAL_PANU_ROLE:REMOTE_NAP_ROLE state = "
                            + state
                            + ", prevState = "
                            + prevState);
            if (state == STATE_CONNECTED) {
                mNetworkFactory =
                        new BluetoothTetheringNetworkFactory(
                                getBaseContext(), getMainLooper(), this);
                mNetworkFactory.startReverseTether(iface);
            } else if (state == STATE_DISCONNECTED) {
                if (mNetworkFactory != null) {
                    mNetworkFactory.stopReverseTether();
                    mNetworkFactory = null;
                }
                mPanDevices.remove(device);
            }
        }
        if (state == STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.PAN);
        }
        mAdapterService.updateProfileConnectionAdapterProperties(
                device, BluetoothProfile.PAN, state, prevState);

        /* Notifying the connection state change of the profile before sending the intent for
        connection state change, as it was causing a race condition, with the UI not being
        updated with the correct connection state. */
        Log.d(TAG, "Pan Device state : device: " + device + " State:" + prevState + "->" + state);
        Intent intent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothPan.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothPan.EXTRA_STATE, state);
        intent.putExtra(BluetoothPan.EXTRA_LOCAL_ROLE, localRole);
        sendBroadcast(intent, BLUETOOTH_CONNECT);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        println(sb, "mMaxPanDevices: " + mMaxPanDevices);
        println(sb, "mPanIfName: " + mPanIfName);
        println(sb, "mTetherOn: " + mTetherOn);
        println(sb, "mPanDevices:");
        for (BluetoothDevice device : mPanDevices.keySet()) {
            println(sb, "  " + device + " : " + mPanDevices.get(device));
        }
    }

    @VisibleForTesting
    static class BluetoothPanDevice {
        private int mState;
        private int mLocalRole; // Which local role is this PAN device bound to
        private int mRemoteRole; // Which remote role is this PAN device bound to

        BluetoothPanDevice(int state, int localRole, int remoteRole) {
            mState = state;
            mLocalRole = localRole;
            mRemoteRole = remoteRole;
        }
    }
}
