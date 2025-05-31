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

package com.android.bluetooth.gatt;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.checkConnectPermissionForDataDelivery;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.content.AttributionSource;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class GattServiceBinder extends IBluetoothGatt.Stub implements IProfileServiceBinder {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + GattServiceBinder.class.getSimpleName();

    private GattService mService;

    GattServiceBinder(GattService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    private GattService getService() {
        GattService service = mService;

        if (!Utils.checkServiceAvailable(service, TAG)) {
            return null;
        }

        return service;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private GattService getServiceAndEnforceConnect(AttributionSource source) {
        GattService service = mService;

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        return service;
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public void registerClient(
            ParcelUuid uuid,
            IBluetoothGattCallback callback,
            boolean eattSupport,
            int transport,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.registerClient(uuid.getUuid(), callback, eattSupport, transport, source);
    }

    @Override
    public void unregisterClient(IBluetoothGattCallback callback, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.unregisterClient(
                callback, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
    }

    @Override
    public void clientConnect(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int phy,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.clientConnect(
                callback, device, addressType, isDirect, transport, opportunistic, phy, source);
    }

    @Override
    public void clientDisconnect(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.clientDisconnect(callback, device, source);
    }

    @Override
    public void clientSetPreferredPhy(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.clientSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions);
    }

    @Override
    public void clientReadPhy(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.clientReadPhy(callback, device);
    }

    @Override
    public void refreshDevice(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.refreshDevice(callback, device);
    }

    @Override
    public void discoverServices(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.discoverServices(callback, device);
    }

    @Override
    public void discoverServiceByUuid(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            ParcelUuid uuid,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.discoverServiceByUuid(callback, device, uuid.getUuid());
    }

    @Override
    public void readCharacteristic(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        try {
            service.permissionCheck(callback, device, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readCharacteristic() - permission check failed!");
            return;
        }

        service.readCharacteristic(callback, device, handle, authReq, source);
    }

    @Override
    public void readUsingCharacteristicUuid(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            ParcelUuid uuid,
            int startHandle,
            int endHandle,
            int authReq,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        try {
            if (service.isHidCharUuid(uuid.getUuid())) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readUsingCharacteristicUuid() - permission check failed!");
            return;
        }
        service.readUsingCharacteristicUuid(
                callback, device, uuid.getUuid(), startHandle, endHandle, authReq);
    }

    @Override
    public int writeCharacteristic(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int writeType,
            int authReq,
            byte[] value,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        service.permissionCheck(callback, device, handle);
        return service.writeCharacteristic(callback, device, handle, writeType, authReq, value);
    }

    @Override
    public void readDescriptor(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        try {
            service.permissionCheck(callback, device, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readDescriptor() - permission check failed!");
            return;
        }

        service.readDescriptor(callback, device, handle, authReq, source);
    }

    @Override
    public int writeDescriptor(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            byte[] value,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        service.permissionCheck(callback, device, handle);
        return service.writeDescriptor(callback, device, handle, authReq, value);
    }

    @Override
    public void beginReliableWrite(BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.beginReliableWrite(device);
    }

    @Override
    public void endReliableWrite(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            boolean execute,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.endReliableWrite(callback, device, execute);
    }

    @Override
    public void registerForNotification(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            boolean enable,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        try {
            service.permissionCheck(callback, device, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "registerForNotification() - permission check failed!");
            return;
        }

        service.registerForNotification(callback, device, handle, enable, source);
    }

    @Override
    public void readRemoteRssi(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.readRemoteRssi(callback, device);
    }

    @Override
    public void configureMTU(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int mtu,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.configureMTU(callback, device, mtu);
    }

    @Override
    public void connectionParameterUpdate(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int connectionPriority,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.connectionParameterUpdate(callback, device, connectionPriority);
    }

    @Override
    public void leConnectionUpdate(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int minConnectionInterval,
            int maxConnectionInterval,
            int peripheralLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.leConnectionUpdate(
                callback,
                device,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen);
    }

    @Override
    public int subrateModeRequest(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int subrateMode,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "subrateModeRequest")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (!checkConnectPermissionForDataDelivery(service, source, TAG, "subrateModeRequest")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                service, service.getCompanionDeviceManager(), source, device);

        if (subrateMode < BluetoothGatt.SUBRATE_REQUEST_MODE_BALANCED
                || subrateMode > BluetoothGatt.SUBRATE_REQUEST_MODE_LOW_POWER) {
            throw new IllegalArgumentException("Subrate Mode not within valid range");
        }

        requireNonNull(device);
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("Invalid device address: " + device.getAddress());
        }

        return service.subrateModeRequest(callback, device, subrateMode);
    }

    @Override
    public void registerServer(
            ParcelUuid uuid,
            IBluetoothGattServerCallback callback,
            boolean eattSupport,
            int transport,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.registerServer(uuid.getUuid(), callback, eattSupport, transport, source);
    }

    @Override
    public void unregisterServer(IBluetoothGattServerCallback callback, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.unregisterServer(callback);
    }

    @Override
    public void serverConnect(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.serverConnect(callback, device, addressType, isDirect, transport, source);
    }

    @Override
    public void serverDisconnect(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.serverDisconnect(callback, device);
    }

    @Override
    public void serverSetPreferredPhy(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.serverSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions);
    }

    @Override
    public void serverReadPhy(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.serverReadPhy(callback, device);
    }

    @Override
    public void addService(
            IBluetoothGattServerCallback callback,
            BluetoothGattService svc,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.addService(callback, svc);
    }

    @Override
    public void removeService(
            IBluetoothGattServerCallback callback, int handle, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.removeService(callback, handle);
    }

    @Override
    public void clearServices(IBluetoothGattServerCallback callback, AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.clearServices(callback);
    }

    @Override
    public void sendResponse(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int requestId,
            int status,
            int offset,
            byte[] value,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.sendResponse(callback, device, requestId, status, offset, value);
    }

    @Override
    public int sendNotification(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int handle,
            boolean confirm,
            byte[] value,
            AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        return service.sendNotification(callback, device, handle, confirm, value);
    }

    @Override
    public void disconnectAll(AttributionSource source) {
        GattService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }
        service.disconnectAll(source);
    }
}
