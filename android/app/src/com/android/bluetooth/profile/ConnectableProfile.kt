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

package com.android.bluetooth.profile

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.A2DP
import android.bluetooth.BluetoothProfile.A2DP_SINK
import android.bluetooth.BluetoothProfile.BATTERY
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.CSIP_SET_COORDINATOR
import android.bluetooth.BluetoothProfile.HAP_CLIENT
import android.bluetooth.BluetoothProfile.HEADSET
import android.bluetooth.BluetoothProfile.HEADSET_CLIENT
import android.bluetooth.BluetoothProfile.HEARING_AID
import android.bluetooth.BluetoothProfile.HID_DEVICE
import android.bluetooth.BluetoothProfile.HID_HOST
import android.bluetooth.BluetoothProfile.LE_AUDIO
import android.bluetooth.BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT
import android.bluetooth.BluetoothProfile.MAP
import android.bluetooth.BluetoothProfile.MAP_CLIENT
import android.bluetooth.BluetoothProfile.PAN
import android.bluetooth.BluetoothProfile.PBAP
import android.bluetooth.BluetoothProfile.PBAP_CLIENT
import android.bluetooth.BluetoothProfile.SAP
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.VOLUME_CONTROL
import android.bluetooth.BluetoothProfile.getProfileName
import android.bluetooth.BluetoothUuid
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.Utils.arrayContains
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.hid.HidHostService
import com.android.bluetooth.storage.BluetoothStorageManager

private const val TAG = Utils.BT_PREFIX + "ConnectableProfile"

// Base class for a Bluetooth profile that supports connection semantics
abstract class ConnectableProfile
@JvmOverloads
constructor(
    id: Int,
    adapterService: AdapterService,
    protected val storage: BluetoothStorageManager? = null,
) : ProfileService(id, adapterService) {

    protected val databaseManager =
        if (Flags.mainlineBetaStorage()) {
            null
        } else {
            mAdapterService.databaseManager
        }

    /**
     * Connects the given Bluetooth device to the profile.
     *
     * @return `true` if the connection was successful, `false` otherwise
     */
    abstract fun connect(device: BluetoothDevice?): Boolean

    /** Disconnects the given device from the profile. */
    abstract fun disconnect(device: BluetoothDevice?): Boolean

    /** @return `true` if connection to remote device is allowed, otherwise `false` */
    open fun okToConnect(device: BluetoothDevice): Boolean {
        val log = "okToConnect($device): Connect rejected: "
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled) {
            Log.e(name, "${log}quiet mode enabled")
            return false
        }
        // Allow this connection only if the device is bonded.
        // Any attempt to connect while bonding would lead to an unauthorized connection.
        val bondState = mAdapterService.getBondState(device)
        if (bondState != BOND_BONDED) {
            Log.e(name, "${log}invalid bond state: $bondState")
            return false
        }
        // Check connectionPolicy and reject the connection if it is not valid.
        val connectionPolicy = getConnectionPolicy(device)
        if (
            connectionPolicy != CONNECTION_POLICY_UNKNOWN &&
                connectionPolicy != CONNECTION_POLICY_ALLOWED
        ) {
            Log.e(name, "${log}invalid connection policy: $connectionPolicy")
            return false
        }
        return true
    }

    /**
     * Gets the connection state of the profile for the given Bluetooth device.
     *
     * Implementations should typically return one of the connection state constants defined in
     * [BluetoothProfile], such as [BluetoothProfile.STATE_DISCONNECTED],
     * [BluetoothProfile.STATE_CONNECTING], [BluetoothProfile.STATE_CONNECTED], or
     * [BluetoothProfile.STATE_DISCONNECTING].
     *
     * @param device The Bluetooth device for which to get the connection state. May be `null`, in
     *   which case implementations should typically return [BluetoothProfile.STATE_DISCONNECTED].
     * @return The current connection state for the device with this profile.
     */
    abstract fun getConnectionState(device: BluetoothDevice?): Int

    /**
     * Get the connection policy of the profile.
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    @BluetoothProfile.ConnectionPolicy
    fun getConnectionPolicy(device: BluetoothDevice) =
        mAdapterService.getProfileConnectionPolicy(device, mProfileId)

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is
     * [BluetoothProfile.CONNECTION_POLICY_ALLOWED] or disconnects if connectionPolicy is
     * [BluetoothProfile.CONNECTION_POLICY_FORBIDDEN]
     *
     * The device should already be paired.
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    abstract fun setConnectionPolicy(
        device: BluetoothDevice?,
        @BluetoothProfile.ConnectionPolicy connectionPolicy: Int,
    ): Boolean

    /** Process a change in the bonding state for a device */
    open fun handleBondStateChanged(device: BluetoothDevice?, fromState: Int, toState: Int) {
        Log.w(name, "handleBondStateChanged(): Called but not implemented")
    }

    companion object {
        @JvmStatic
        fun isSupported(
            adapterService: AdapterService,
            device: BluetoothDevice?,
            id: Int,
        ): Boolean {
            val remoteDeviceUuids = adapterService.getRemoteUuids(device)
            if (remoteDeviceUuids.isNullOrEmpty()) {
                Log.e(TAG, "isSupported(): remoteUuids is null for device: $device")
            }

            val profile = getProfileName(id)
            val localDeviceUuids = adapterService.getAdapterProperties().getUuids()
            Log.v(
                TAG,
                "isSupported(device=$device, profile=$profile): " +
                    "local_uuids=${localDeviceUuids.contentToString()}, " +
                    "remote_uuids=${remoteDeviceUuids.contentToString()}",
            )

            return when (id) {
                A2DP ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST) ||
                        arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SINK)
                A2DP_SINK ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST) ||
                        arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SOURCE)
                BATTERY -> arrayContains(remoteDeviceUuids, BluetoothUuid.BATTERY)
                CSIP_SET_COORDINATOR ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.COORDINATED_SET)
                HAP_CLIENT -> arrayContains(remoteDeviceUuids, BluetoothUuid.HAS)
                HEADSET ->
                    arrayContains(localDeviceUuids, BluetoothUuid.HSP_AG) &&
                        arrayContains(remoteDeviceUuids, BluetoothUuid.HSP) ||
                        (arrayContains(localDeviceUuids, BluetoothUuid.HFP_AG) &&
                            arrayContains(remoteDeviceUuids, BluetoothUuid.HFP))
                HEADSET_CLIENT ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.HFP_AG) &&
                        arrayContains(localDeviceUuids, BluetoothUuid.HFP)
                HEARING_AID -> arrayContains(remoteDeviceUuids, BluetoothUuid.HEARING_AID)
                HID_HOST ->
                    arrayContains(remoteDeviceUuids, BluetoothUuid.HID) ||
                        arrayContains(remoteDeviceUuids, BluetoothUuid.HOGP) ||
                        arrayContains(remoteDeviceUuids, HidHostService.ANDROID_HEADTRACKER_UUID)
                LE_AUDIO -> arrayContains(remoteDeviceUuids, BluetoothUuid.LE_AUDIO)
                LE_AUDIO_BROADCAST_ASSISTANT -> arrayContains(remoteDeviceUuids, BluetoothUuid.BASS)
                MAP_CLIENT ->
                    arrayContains(localDeviceUuids, BluetoothUuid.MNS) &&
                        arrayContains(remoteDeviceUuids, BluetoothUuid.MAS)
                PAN -> arrayContains(remoteDeviceUuids, BluetoothUuid.NAP)
                PBAP_CLIENT ->
                    arrayContains(localDeviceUuids, BluetoothUuid.PBAP_PCE) &&
                        arrayContains(remoteDeviceUuids, BluetoothUuid.PBAP_PSE)
                SAP -> arrayContains(remoteDeviceUuids, BluetoothUuid.SAP)
                VOLUME_CONTROL -> arrayContains(remoteDeviceUuids, BluetoothUuid.VOLUME_CONTROL)
                HID_DEVICE ->
                    adapterService
                        .getStartedConnectableProfile(id)
                        .filter { p -> p.getConnectionState(device) == STATE_DISCONNECTED }
                        .isPresent
                MAP,
                PBAP ->
                    adapterService
                        .getStartedConnectableProfile(id)
                        .filter { p -> p.getConnectionState(device) == STATE_CONNECTED }
                        .isPresent
                else -> {
                    Log.wtf(TAG, "isSupported(): Called by $profile but not implemented")
                    false
                }
            }
        }
    }
}
