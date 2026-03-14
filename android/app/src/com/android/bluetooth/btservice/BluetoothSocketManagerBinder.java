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

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothSocketSettings;
import android.bluetooth.IBluetoothSocketManager;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.metrics.MetricsLogger;

class BluetoothSocketManagerBinder extends IBluetoothSocketManager.Stub {
    private static final String TAG = BluetoothSocketManagerBinder.class.getSimpleName();

    private static final int INVALID_FD = -1;

    private AdapterService mService;

    BluetoothSocketManagerBinder(AdapterService service) {
        mService = service;
    }

    void cleanUp() {
        mService = null;
    }

    @Override
    public ParcelFileDescriptor connectSocket(
            BluetoothDevice device,
            int type,
            ParcelUuid uuid,
            int port,
            int flag,
            AttributionSource source) {
        enforceActiveUser();

        if (!Util.enforceConnectPermissionForPreflight(mService, source)) {
            return null;
        }

        String brEdrAddress = mService.getBrEdrAddress(device);
        String leDeviceAddr = device.getAddress();
        if (Flags.addAddressMappingForLecoc()) {
            if (type == BluetoothSocket.TYPE_LE) {
                leDeviceAddr = mService.getIdentityAddress(device.getAddress());
                if (leDeviceAddr == null) {
                    leDeviceAddr = device.getAddress();
                }
            }
        }

        if (type == BluetoothSocket.TYPE_RFCOMM) {
            logRfcommConnectStartEvent(device);
        }

        Log.i(
                TAG,
                "connectSocket: device="
                        + device
                        + ", type="
                        + type
                        + ", uuid="
                        + uuid
                        + ", port="
                        + port
                        + ", from "
                        + Util.getUidPidString());

        return marshalFd(
                mService.getNative()
                        .connectSocket(
                                Util.getBytesFromAddress(
                                        type == BluetoothSocket.TYPE_LE
                                                ? leDeviceAddr
                                                : brEdrAddress),
                                type,
                                Utils.uuidToByteArray(uuid),
                                port,
                                flag,
                                Binder.getCallingUid(),
                                0,
                                "",
                                0,
                                0,
                                0));
    }

    @Override
    public ParcelFileDescriptor connectSocketWithOffload(
            BluetoothDevice device,
            int type,
            ParcelUuid uuid,
            int port,
            int flag,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize,
            AttributionSource source) {
        enforceActiveUser();

        if (!Util.enforceConnectPermissionForPreflight(mService, source)) {
            return null;
        }

        if (dataPath != BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD) {
            mService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            enforceSocketOffloadSupport(type);
        }

        String brEdrAddress = mService.getBrEdrAddress(device);

        if (type == BluetoothSocket.TYPE_RFCOMM) {
            logRfcommConnectStartEvent(device);
        }

        Log.i(
                TAG,
                "connectSocketWithOffload:"
                        + (" device=" + device)
                        + (" type=" + type)
                        + (" uuid=" + uuid)
                        + (" port=" + port)
                        + (" from " + Util.getUidPidString())
                        + (" dataPath=" + dataPath)
                        + (" socketName=" + socketName)
                        + (" hubId=" + hubId)
                        + (" endpointId=" + endpointId)
                        + (" maximumPacketSize=" + maximumPacketSize));

        return marshalFd(
                mService.getNative()
                        .connectSocket(
                                Util.getBytesFromAddress(
                                        type == BluetoothSocket.TYPE_LE
                                                ? device.getAddress()
                                                : brEdrAddress),
                                type,
                                Utils.uuidToByteArray(uuid),
                                port,
                                flag,
                                Binder.getCallingUid(),
                                dataPath,
                                socketName,
                                hubId,
                                endpointId,
                                maximumPacketSize));
    }

    @Override
    public ParcelFileDescriptor createSocketChannel(
            int type,
            String serviceName,
            ParcelUuid uuid,
            int port,
            int flag,
            AttributionSource source) {
        enforceActiveUser();

        if (!Util.enforceConnectPermissionForPreflight(mService, source)) {
            return null;
        }

        if ((Flags.lecocWithFixedPsm()
                && type == BluetoothSocket.TYPE_LE
                && !Util.checkCallerHasPrivilegedPermission(mService))) {
            // for non privileged app, ignore the input LE CoC Psm
            port = BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP;
        }

        Log.i(
                TAG,
                "createSocketChannel: type="
                        + type
                        + ", serviceName="
                        + serviceName
                        + ", uuid="
                        + uuid
                        + ", port="
                        + port
                        + ", from "
                        + Util.getUidPidString());

        return marshalFd(
                mService.getNative()
                        .createSocketChannel(
                                type,
                                serviceName,
                                Utils.uuidToByteArray(uuid),
                                port,
                                flag,
                                Binder.getCallingUid(),
                                0,
                                "",
                                0,
                                0,
                                0));
    }

    @Override
    public ParcelFileDescriptor createSocketChannelWithOffload(
            int type,
            String serviceName,
            ParcelUuid uuid,
            int port,
            int flag,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize,
            AttributionSource source) {
        enforceActiveUser();

        if (!Util.enforceConnectPermissionForPreflight(mService, source)) {
            return null;
        }

        if (dataPath != BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD) {
            mService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            enforceSocketOffloadSupport(type);
        }

        if ((Flags.fixedPsmForOffloadSocket()
                && type == BluetoothSocket.TYPE_LE
                && !Util.checkCallerHasPrivilegedPermission(mService))) {
            // for non privileged app, ignore the input LE CoC Psm
            port = BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP;
        }

        Log.i(
                TAG,
                "createSocketChannelWithOffload: type="
                        + type
                        + ", serviceName="
                        + serviceName
                        + ", uuid="
                        + uuid
                        + ", port="
                        + port
                        + ", from "
                        + Util.getUidPidString()
                        + ", dataPath="
                        + dataPath
                        + ", socketName="
                        + socketName
                        + ", hubId="
                        + hubId
                        + ", endpointId="
                        + endpointId
                        + ", maximumPacketSize="
                        + maximumPacketSize);

        return marshalFd(
                mService.getNative()
                        .createSocketChannel(
                                type,
                                serviceName,
                                Utils.uuidToByteArray(uuid),
                                port,
                                flag,
                                Binder.getCallingUid(),
                                dataPath,
                                socketName,
                                hubId,
                                endpointId,
                                maximumPacketSize));
    }

    @Override
    public void requestMaximumTxDataLength(BluetoothDevice device, AttributionSource source) {
        enforceActiveUser();

        if (!Util.enforceConnectPermissionForPreflight(mService, source)) {
            return;
        }

        mService.getNative()
                .requestMaximumTxDataLength(Util.getBytesFromAddress(device.getAddress()));
    }

    private void enforceActiveUser() {
        if (!Util.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)) {
            throw new SecurityException("Not allowed for non-active user");
        }
    }

    private void enforceSocketOffloadSupport(int type) {
        if (!(type == BluetoothSocket.TYPE_LE && mService.isLeCocSocketOffloadSupported())
                && !(type == BluetoothSocket.TYPE_RFCOMM
                        && mService.isRfcommSocketOffloadSupported())) {
            throw new IllegalStateException("Unsupported socket type for offload " + type);
        }
    }

    private static ParcelFileDescriptor marshalFd(int fd) {
        if (fd == INVALID_FD) {
            return null;
        }
        return ParcelFileDescriptor.adoptFd(fd);
    }

    private static void logRfcommConnectStartEvent(BluetoothDevice device) {
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__RFCOMM_SOCKET_JAVA_CONNECTION,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__START,
                        Binder.getCallingUid());
    }
}
