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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A profile service that connects to the Battery service (BAS) of BLE devices */
public class BatteryService extends ProfileService {
    private static final String TAG = BatteryService.class.getSimpleName();

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1_000;

    private static BatteryService sBatteryService;

    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final HandlerThread mStateMachinesThread;
    private final Handler mHandler;

    @GuardedBy("mStateMachines")
    private final Map<BluetoothDevice, BatteryStateMachine> mStateMachines = new HashMap<>();

    public BatteryService(AdapterService adapterService) {
        this(adapterService, Looper.getMainLooper());
    }

    @VisibleForTesting
    BatteryService(AdapterService adapterService, Looper looper) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mHandler = new Handler(requireNonNull(looper));

        mStateMachinesThread = new HandlerThread("BatteryService.StateMachines");
        mStateMachinesThread.start();
        setBatteryService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileBasClientEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Battery Service");

        setBatteryService(null);

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (BatteryStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
                sm.cleanup();
            }
            mStateMachines.clear();
        }

        try {
            mStateMachinesThread.quitSafely();
            mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            // Do not rethrow as we are shutting down anyway
        }

        mHandler.removeCallbacksAndMessages(null);
    }

    /** Gets the BatteryService instance */
    public static synchronized BatteryService getBatteryService() {
        if (sBatteryService == null) {
            Log.w(TAG, "getBatteryService(): service is NULL");
            return null;
        }

        if (!sBatteryService.isAvailable()) {
            Log.w(TAG, "getBatteryService(): service is not available");
            return null;
        }
        return sBatteryService;
    }

    /** Sets the battery service instance. It should be called only for testing purpose. */
    @VisibleForTesting
    public static synchronized void setBatteryService(BatteryService instance) {
        Log.d(TAG, "setBatteryService(): set to: " + instance);
        sBatteryService = instance;
    }

    /** Connects to the battery service of the given device. */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            Log.w(TAG, "Ignore connecting to null device");
            return false;
        }

        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.w(TAG, "Cannot connect to " + device + " : policy forbidden");
            return false;
        }
        final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.BATTERY)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have Battery UUID");
            return false;
        }

        synchronized (mStateMachines) {
            BatteryStateMachine sm = getOrCreateStateMachine(device);
            if (sm == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
                return false;
            }
            sm.sendMessage(BatteryStateMachine.MESSAGE_CONNECT);
        }

        return true;
    }

    /**
     * Connects to the battery service of the given device if possible. If it's impossible, it
     * doesn't try without logging errors.
     */
    public boolean connectIfPossible(BluetoothDevice device) {
        if (device == null
                || getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN
                || !Utils.arrayContains(
                        mAdapterService.getRemoteUuids(device), BluetoothUuid.BATTERY)) {
            return false;
        }
        return connect(device);
    }

    /** Disconnects from the battery service of the given device. */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            Log.w(TAG, "Ignore disconnecting to null device");
            return false;
        }
        synchronized (mStateMachines) {
            BatteryStateMachine sm = getOrCreateStateMachine(device);
            if (sm != null) {
                sm.sendMessage(BatteryStateMachine.MESSAGE_DISCONNECT);
            }
        }

        return true;
    }

    /** Gets devices that battery service is connected. */
    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (BatteryStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether it can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     */
    boolean canConnect(BluetoothDevice device) {
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "canConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "canConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    /** Called when the connection state of a state machine is changed */
    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (fromState == toState) {
            Log.e(
                    TAG,
                    "connectionStateChanged: unexpected invocation. device="
                            + device
                            + " fromState="
                            + fromState
                            + " toState="
                            + toState);
            return;
        }

        // Check if the device is disconnected - if unbonded, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, device + " is unbonded. Remove state machine");
                removeStateMachine(device);
            }
        }
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                int connectionState = STATE_DISCONNECTED;
                BatteryStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (BatteryStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    /** Gets the connection state of the given device's battery service */
    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            BatteryStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
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
     * @param device the remote device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true on success, otherwise false
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.BATTERY, connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /** Gets the connection policy for the battery service of the given device. */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.BATTERY);
    }

    /** Called when the battery level of the device is notified. */
    void handleBatteryChanged(BluetoothDevice device, int batteryLevel) {
        mAdapterService.setBatteryLevel(device, batteryLevel, /* isBas= */ true);
    }

    private BatteryStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            BatteryStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }

            Log.d(TAG, "Creating a new state machine for " + device);
            sm = new BatteryStateMachine(this, device, mStateMachinesThread.getLooper());
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /** Process a change in the bonding state for a device */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /** Remove state machine if the bonding for a device is removed */
    private void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            BatteryStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != STATE_DISCONNECTED) {
                return;
            }
            removeStateMachine(device);
        }
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            BatteryStateMachine sm = mStateMachines.remove(device);
            if (sm == null) {
                Log.w(TAG, "removeStateMachine: " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeGatt: removing bluetooth gatt for device: " + device);
            sm.doQuit();
            sm.cleanup();
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        synchronized (mStateMachines) {
            for (BatteryStateMachine sm : mStateMachines.values()) {
                sm.dump(sb);
            }
        }
    }
}
