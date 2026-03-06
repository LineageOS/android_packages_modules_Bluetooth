/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.le_audio;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import com.android.bluetooth.btservice.Config;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.UUID;

/** A GATT server for Telephony and Media Audio Profile (TMAP) */
public class LeAudioTmapGattServer implements AutoCloseable {
    private static final String TAG = LeAudioTmapGattServer.class.getSimpleName();

    /* Telephony and Media Audio Profile Role Characteristic UUID */
    @VisibleForTesting
    static final UUID UUID_TMAP_ROLE = UUID.fromString("00002B51-0000-1000-8000-00805f9b34fb");

    /* TMAP Role: Call Gateway */
    public static final int TMAP_ROLE_FLAG_CG = 1;
    /* TMAP Role: Call Terminal */
    public static final int TMAP_ROLE_FLAG_CT = 1 << 1;
    /* TMAP Role: Unicast Media Sender */
    public static final int TMAP_ROLE_FLAG_UMS = 1 << 2;
    /* TMAP Role: Unicast Media Receiver */
    public static final int TMAP_ROLE_FLAG_UMR = 1 << 3;
    /* TMAP Role: Broadcast Media Sender */
    public static final int TMAP_ROLE_FLAG_BMS = 1 << 4;
    /* TMAP Role: Broadcast Media Receiver */
    public static final int TMAP_ROLE_FLAG_BMR = 1 << 5;

    private final BluetoothGattServerProxy mBluetoothGattServer;
    private int mRoleMask;

    public LeAudioTmapGattServer(BluetoothGattServerProxy gattServer) {
        mBluetoothGattServer = gattServer;
        mRoleMask = calculateTmapRoleMask();
        start(mRoleMask);
    }

    public int getRoleMask() {
        return mRoleMask;
    }

    @VisibleForTesting
    /* package*/ static int calculateTmapRoleMask() {
        int mask = 0;
        if (Config.isProfileSupported(BluetoothProfile.LE_CALL_CONTROL)) {
            // Table 3.5 of TMAP v1.0: CCP Server is mandatory for the TMAP CG role.
            mask |= TMAP_ROLE_FLAG_CG;
        }
        if (Config.isProfileSupported(BluetoothProfile.MCP_SERVER)) {
            // Table 3.5 of TMAP v1.0: MCP Server is mandatory for the TMAP UMS role.
            mask |= TMAP_ROLE_FLAG_UMS;
        }
        if (Config.isProfileSupported(BluetoothProfile.LE_AUDIO_BROADCAST)) {
            mask |= TMAP_ROLE_FLAG_BMS;
        }
        if (Config.isProfileSupported(BluetoothProfile.LE_AUDIO_PERIPHERAL)) {
            if (SystemProperties.getBoolean(
                    "bluetooth.profile.tmap.call_terminal.enabled", false)) {
                mask |= TMAP_ROLE_FLAG_CT;
            }
            if (SystemProperties.getBoolean(
                    "bluetooth.profile.tmap.unicast_media_receiver.enabled", false)) {
                mask |= TMAP_ROLE_FLAG_UMR;
            }
        }
        return mask;
    }

    private void start(int roleMask) {
        Log.d(TAG, "start(roleMask:" + roleMask + ")");

        if (!mBluetoothGattServer.open(mBluetoothGattServerCallback)) {
            throw new IllegalStateException("Could not open Gatt server");
        }

        BluetoothGattService service =
                new BluetoothGattService(
                        BluetoothUuid.TMAP.getUuid(), BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic characteristic =
                new BluetoothGattCharacteristic(
                        UUID_TMAP_ROLE,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        characteristic.setValue(roleMask, BluetoothGattCharacteristic.FORMAT_UINT16, 0);
        service.addCharacteristic(characteristic);

        if (!mBluetoothGattServer.addService(service)) {
            throw new IllegalStateException("Failed to add service for TMAP");
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "stop()");
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "mBluetoothGattServer should not be null when stop() is called");
            return;
        }
        mBluetoothGattServer.close();
    }

    /**
     * Callback to handle incoming requests to the GATT server. All read/write requests for
     * characteristics and descriptors are handled here.
     */
    private final BluetoothGattServerCallback mBluetoothGattServerCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {
                    byte[] value = characteristic.getValue();
                    Log.d(TAG, "value " + Arrays.toString(value));
                    if (value != null) {
                        Log.e(TAG, "value null");
                        value = Arrays.copyOfRange(value, offset, value.length);
                    }
                    mBluetoothGattServer.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                }
            };

    /**
     * A proxy class that facilitates testing.
     *
     * <p>This is necessary due to the "final" attribute of the BluetoothGattServer class.
     */
    static class BluetoothGattServerProxy {
        private final Context mContext;
        private final BluetoothManager mBluetoothManager;

        private BluetoothGattServer mBluetoothGattServer;

        /**
         * Create a new GATT server proxy object
         *
         * @param context context to use
         */
        BluetoothGattServerProxy(Context context) {
            mContext = context;
            mBluetoothManager = context.getSystemService(BluetoothManager.class);
        }

        /**
         * Open with GATT server callback
         *
         * @param callback callback to invoke
         * @return true on success
         */
        boolean open(BluetoothGattServerCallback callback) {
            mBluetoothGattServer = mBluetoothManager.openGattServer(mContext, callback);
            return mBluetoothGattServer != null;
        }

        /** Close the GATT server, should be called as soon as the server is not needed */
        void close() {
            if (mBluetoothGattServer == null) {
                Log.w(TAG, "BluetoothGattServerProxy.close() called without open()");
                return;
            }
            mBluetoothGattServer.close();
            mBluetoothGattServer = null;
        }

        /**
         * Add a GATT service
         *
         * @param service added service
         * @return true on success
         */
        boolean addService(BluetoothGattService service) {
            return mBluetoothGattServer.addService(service);
        }

        /**
         * Send GATT response to remote
         *
         * @param device remote device
         * @param requestId request id
         * @param status status of response
         * @param offset offset of the value
         * @param value value content
         * @return true on success
         */
        boolean sendResponse(
                BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
            return mBluetoothGattServer.sendResponse(device, requestId, status, offset, value);
        }
    }
}
