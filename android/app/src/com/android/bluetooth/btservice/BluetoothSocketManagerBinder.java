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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothSocketSettings;
import android.bluetooth.IBluetoothSocketManager;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;

class BluetoothSocketManagerBinder extends IBluetoothSocketManager.Stub {
    private static final String TAG = BluetoothSocketManagerBinder.class.getSimpleName();

    private static final int INVALID_FD = -1;

    private static final int INVALID_CID = -1;

    private AdapterService mService;

    BluetoothSocketManagerBinder(AdapterService service) {
        mService = service;
    }

    void cleanUp() {
        mService = null;
    }

    @Override
    public ParcelFileDescriptor connectSocket(
            BluetoothDevice device, int type, ParcelUuid uuid, int port, int flag) {

        enforceActiveUser();

        if (!Utils.checkConnectPermissionForPreflight(mService)) {
            return null;
        }

        String brEdrAddress =
                Flags.identityAddressNullIfNotKnown()
                        ? Utils.getBrEdrAddress(device)
                        : mService.getIdentityAddress(device.getAddress());

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
                        + Utils.getUidPidString());

        return marshalFd(
                mService.getNative()
                        .connectSocket(
                                Utils.getBytesFromAddress(
                                        type == BluetoothSocket.TYPE_L2CAP_LE
                                                ? device.getAddress()
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
    public ParcelFileDescriptor connectSocketwithOffload(
            BluetoothDevice device,
            int type,
            ParcelUuid uuid,
            int port,
            int flag,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize) {

        enforceActiveUser();

        if (!Utils.checkConnectPermissionForPreflight(mService)) {
            return null;
        }

        if (dataPath != BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD) {
            mService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            enforceSocketOffloadSupport(type);
        }
        String brEdrAddress =
                Flags.identityAddressNullIfNotKnown()
                        ? Utils.getBrEdrAddress(device)
                        : mService.getIdentityAddress(device.getAddress());

        Log.i(
                TAG,
                "connectSocketwithOffload: device="
                        + device
                        + ", type="
                        + type
                        + ", uuid="
                        + uuid
                        + ", port="
                        + port
                        + ", from "
                        + Utils.getUidPidString()
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
                        .connectSocket(
                                Utils.getBytesFromAddress(
                                        type == BluetoothSocket.TYPE_L2CAP_LE
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
            int type, String serviceName, ParcelUuid uuid, int port, int flag) {

        enforceActiveUser();

        if (!Utils.checkConnectPermissionForPreflight(mService)) {
            return null;
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
                        + Utils.getUidPidString());

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
            int maximumPacketSize) {

        enforceActiveUser();

        if (!Utils.checkConnectPermissionForPreflight(mService)) {
            return null;
        }

        if (dataPath != BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD) {
            mService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            enforceSocketOffloadSupport(type);
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
                        + Utils.getUidPidString()
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
    public void requestMaximumTxDataLength(BluetoothDevice device) {
        enforceActiveUser();

        if (!Utils.checkConnectPermissionForPreflight(mService)) {
            return;
        }

        mService.getNative()
                .requestMaximumTxDataLength(Utils.getBytesFromAddress(device.getAddress()));
    }

    @Override
    public int getL2capLocalChannelId(ParcelUuid connectionUuid, AttributionSource source) {
        AdapterService service = mService;
        if (service == null
                || !Utils.callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "getL2capLocalChannelId")
                || !Utils.checkConnectPermissionForDataDelivery(
                        service, source, "BluetoothSocketManagerBinder getL2capLocalChannelId")) {
            return INVALID_CID;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getNative().getSocketL2capLocalChannelId(connectionUuid);
    }

    @Override
    public int getL2capRemoteChannelId(ParcelUuid connectionUuid, AttributionSource source) {
        AdapterService service = mService;
        if (service == null
                || !Utils.callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "getL2capRemoteChannelId")
                || !Utils.checkConnectPermissionForDataDelivery(
                        service, source, "BluetoothSocketManagerBinder getL2capRemoteChannelId")) {
            return INVALID_CID;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getNative().getSocketL2capRemoteChannelId(connectionUuid);
    }

    private void enforceActiveUser() {
        if (!Utils.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)) {
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
}
