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

import android.bluetooth.BluetoothDevice;
import android.util.Log;

/** Base class for a Bluetooth profile that supports connection semantics. */
public abstract class ConnectableProfile extends ProfileService {

    protected ConnectableProfile(int id, AdapterService adapterService) {
        super(id, adapterService);
    }

    /**
     * Connects the given Bluetooth device to the profile.
     *
     * @return {@code true} if the connection was successful, {@code false} otherwise.
     */
    public boolean connect(BluetoothDevice device) {
        Log.w(getName(), "connect() was called but not overridden for device: " + device);
        return false;
    }

    /** Disconnects the given device from the profile. */
    public abstract boolean disconnect(BluetoothDevice device);

    /**
     * Gets the connection state of the profile for the given Bluetooth device.
     *
     * <p>Implementations should typically return one of the connection state constants defined in
     * {@link android.bluetooth.BluetoothProfile}, such as {@link
     * android.bluetooth.BluetoothProfile#STATE_DISCONNECTED}, {@link
     * android.bluetooth.BluetoothProfile#STATE_CONNECTING}, {@link
     * android.bluetooth.BluetoothProfile#STATE_CONNECTED}, or {@link
     * android.bluetooth.BluetoothProfile#STATE_DISCONNECTING}.
     *
     * @param device The Bluetooth device for which to get the connection state. May be {@code
     *     null}, in which case implementations should typically return {@link
     *     android.bluetooth.BluetoothProfile#STATE_DISCONNECTED}.
     * @return The current connection state for the device with this profile.
     */
    public abstract int getConnectionState(BluetoothDevice device);
}
