/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.util.Log;

import com.android.bluetooth.btservice.storage.DatabaseManager;

/** Base class for a Bluetooth profile that supports connection semantics. */
public abstract class ConnectableProfile extends ProfileService {

    protected final DatabaseManager mDatabaseManager;

    protected ConnectableProfile(int id, AdapterService adapterService) {
        super(id, adapterService);
        mDatabaseManager = requireNonNull(mAdapterService.getDatabaseManager());
    }

    /**
     * Connects the given Bluetooth device to the profile.
     *
     * @return {@code true} if the connection was successful, {@code false} otherwise.
     */
    public boolean connect(BluetoothDevice device) {
        Log.w(getName(), "connect() was called but not implemented");
        return false;
    }

    /** Disconnects the given device from the profile. */
    public abstract boolean disconnect(BluetoothDevice device);

    /**
     * Gets the connection state of the profile for the given Bluetooth device.
     *
     * <p>Implementations should typically return one of the connection state constants defined in
     * {@link BluetoothProfile}, such as {@link BluetoothProfile#STATE_DISCONNECTED}, {@link
     * BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_CONNECTED}, or {@link
     * BluetoothProfile#STATE_DISCONNECTING}.
     *
     * @param device The Bluetooth device for which to get the connection state. May be {@code
     *     null}, in which case implementations should typically return {@link
     *     BluetoothProfile#STATE_DISCONNECTED}.
     * @return The current connection state for the device with this profile.
     */
    public abstract int getConnectionState(BluetoothDevice device);

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
        return mDatabaseManager.getProfileConnectionPolicy(device, mProfileId);
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
        Log.w(getName(), "setConnectionPolicy() was called but not implemented");
        return false;
    }
}
