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

import static android.bluetooth.BluetoothProfile.A2DP;
import static android.bluetooth.BluetoothProfile.A2DP_SINK;
import static android.bluetooth.BluetoothProfile.BATTERY;
import static android.bluetooth.BluetoothProfile.CSIP_SET_COORDINATOR;
import static android.bluetooth.BluetoothProfile.HAP_CLIENT;
import static android.bluetooth.BluetoothProfile.HEADSET;
import static android.bluetooth.BluetoothProfile.HEADSET_CLIENT;
import static android.bluetooth.BluetoothProfile.HEARING_AID;
import static android.bluetooth.BluetoothProfile.HID_DEVICE;
import static android.bluetooth.BluetoothProfile.HID_HOST;
import static android.bluetooth.BluetoothProfile.LE_AUDIO;
import static android.bluetooth.BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT;
import static android.bluetooth.BluetoothProfile.MAP;
import static android.bluetooth.BluetoothProfile.MAP_CLIENT;
import static android.bluetooth.BluetoothProfile.PAN;
import static android.bluetooth.BluetoothProfile.PBAP;
import static android.bluetooth.BluetoothProfile.PBAP_CLIENT;
import static android.bluetooth.BluetoothProfile.SAP;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.VOLUME_CONTROL;
import static android.bluetooth.BluetoothProfile.getProfileName;

import static com.android.bluetooth.Utils.arrayContains;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hid.HidHostService;

import java.util.Arrays;

/** Base class for a Bluetooth profile that supports connection semantics. */
public abstract class ConnectableProfile extends ProfileService {
    private static final String TAG = Utils.BT_PREFIX + ConnectableProfile.class.getSimpleName();

    protected final DatabaseManager mDatabaseManager;

    protected ConnectableProfile(int id, AdapterService adapterService) {
        super(id, adapterService);
        mDatabaseManager = requireNonNull(mAdapterService.getDatabaseManager());
    }

    static boolean isSupported(AdapterService adapterService, BluetoothDevice device, int id) {
        final ParcelUuid[] remoteDeviceUuids = adapterService.getRemoteUuids(device);
        if (remoteDeviceUuids == null || remoteDeviceUuids.length == 0) {
            Log.e(TAG, "isSupported(): remoteUuids is null for device: " + device);
        }

        final ParcelUuid[] localDeviceUuids = adapterService.getAdapterProperties().getUuids();
        Log.v(
                TAG,
                "isSupported("
                        + ("device=" + device)
                        + (", profile=" + getProfileName(id) + "):")
                        + (" local_uuids=" + Arrays.toString(localDeviceUuids))
                        + (", remote_uuids=" + Arrays.toString(remoteDeviceUuids)));

        return switch (id) {
            case A2DP ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST)
                            || arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SINK);
            case A2DP_SINK ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST)
                            || arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SOURCE);
            case BATTERY -> arrayContains(remoteDeviceUuids, BluetoothUuid.BATTERY);
            case CSIP_SET_COORDINATOR ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.COORDINATED_SET);
            case HAP_CLIENT -> arrayContains(remoteDeviceUuids, BluetoothUuid.HAS);
            case HEADSET ->
                    (arrayContains(localDeviceUuids, BluetoothUuid.HSP_AG)
                                    && arrayContains(remoteDeviceUuids, BluetoothUuid.HSP))
                            || (arrayContains(localDeviceUuids, BluetoothUuid.HFP_AG)
                                    && arrayContains(remoteDeviceUuids, BluetoothUuid.HFP));
            case HEADSET_CLIENT ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.HFP_AG)
                            && arrayContains(localDeviceUuids, BluetoothUuid.HFP);
            case HEARING_AID -> arrayContains(remoteDeviceUuids, BluetoothUuid.HEARING_AID);
            case HID_HOST ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.HID)
                            || arrayContains(remoteDeviceUuids, BluetoothUuid.HOGP)
                            || arrayContains(
                                    remoteDeviceUuids, HidHostService.ANDROID_HEADTRACKER_UUID);
            case LE_AUDIO -> arrayContains(remoteDeviceUuids, BluetoothUuid.LE_AUDIO);
            case LE_AUDIO_BROADCAST_ASSISTANT ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.BASS);
            case MAP_CLIENT ->
                    arrayContains(localDeviceUuids, BluetoothUuid.MNS)
                            && arrayContains(remoteDeviceUuids, BluetoothUuid.MAS);
            case PAN -> arrayContains(remoteDeviceUuids, BluetoothUuid.NAP);
            case PBAP_CLIENT ->
                    arrayContains(localDeviceUuids, BluetoothUuid.PBAP_PCE)
                            && arrayContains(remoteDeviceUuids, BluetoothUuid.PBAP_PSE);
            case SAP -> arrayContains(remoteDeviceUuids, BluetoothUuid.SAP);
            case VOLUME_CONTROL -> arrayContains(remoteDeviceUuids, BluetoothUuid.VOLUME_CONTROL);
            case HID_DEVICE -> {
                yield adapterService
                        .getStartedProfile(id)
                        .filter(profile -> profile.getConnectionState(device) == STATE_DISCONNECTED)
                        .isPresent();
            }
            case MAP, PBAP -> {
                yield adapterService
                        .getStartedProfile(id)
                        .filter(profile -> profile.getConnectionState(device) == STATE_CONNECTED)
                        .isPresent();
            }
            default -> {
                Log.w(TAG, "isSupported() was called but not implemented");
                yield false;
            }
        };
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
