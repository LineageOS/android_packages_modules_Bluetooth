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

import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.checkConnectPermissionForDataDelivery;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

/** Handlers for incoming service calls */
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

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states, source);
    }

    @Override
    public void registerClient(
            ParcelUuid uuid,
            IBluetoothGattCallback callback,
            boolean eattSupport,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.registerClient(uuid.getUuid(), callback, eattSupport, source);
    }

    @Override
    public void unregisterClient(int clientIf, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.unregisterClient(
                clientIf, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
    }

    @Override
    public void clientConnect(
            int clientIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int phy,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.clientConnect(
                clientIf, address, addressType, isDirect, transport, opportunistic, phy, source);
    }

    @Override
    public void clientDisconnect(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.clientDisconnect(clientIf, address, source);
    }

    @Override
    public void clientSetPreferredPhy(
            int clientIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.clientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions, source);
    }

    @Override
    public void clientReadPhy(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.clientReadPhy(clientIf, address, source);
    }

    @Override
    public void refreshDevice(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.refreshDevice(clientIf, address, source);
    }

    @Override
    public void discoverServices(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.discoverServices(clientIf, address, source);
    }

    @Override
    public void discoverServiceByUuid(
            int clientIf, String address, ParcelUuid uuid, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.discoverServiceByUuid(clientIf, address, uuid.getUuid(), source);
    }

    @Override
    public void readCharacteristic(
            int clientIf, String address, int handle, int authReq, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.readCharacteristic(clientIf, address, handle, authReq, source);
    }

    @Override
    public void readUsingCharacteristicUuid(
            int clientIf,
            String address,
            ParcelUuid uuid,
            int startHandle,
            int endHandle,
            int authReq,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.readUsingCharacteristicUuid(
                clientIf, address, uuid.getUuid(), startHandle, endHandle, authReq, source);
    }

    @Override
    public int writeCharacteristic(
            int clientIf,
            String address,
            int handle,
            int writeType,
            int authReq,
            byte[] value,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        return service.writeCharacteristic(
                clientIf, address, handle, writeType, authReq, value, source);
    }

    @Override
    public void readDescriptor(
            int clientIf, String address, int handle, int authReq, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.readDescriptor(clientIf, address, handle, authReq, source);
    }

    @Override
    public int writeDescriptor(
            int clientIf,
            String address,
            int handle,
            int authReq,
            byte[] value,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        return service.writeDescriptor(clientIf, address, handle, authReq, value, source);
    }

    @Override
    public void beginReliableWrite(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.beginReliableWrite(clientIf, address, source);
    }

    @Override
    public void endReliableWrite(
            int clientIf, String address, boolean execute, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.endReliableWrite(clientIf, address, execute, source);
    }

    @Override
    public void registerForNotification(
            int clientIf, String address, int handle, boolean enable, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.registerForNotification(clientIf, address, handle, enable, source);
    }

    @Override
    public void readRemoteRssi(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.readRemoteRssi(clientIf, address, source);
    }

    @Override
    public void configureMTU(int clientIf, String address, int mtu, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.configureMTU(clientIf, address, mtu, source);
    }

    @Override
    public void connectionParameterUpdate(
            int clientIf, String address, int connectionPriority, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.connectionParameterUpdate(clientIf, address, connectionPriority, source);
    }

    @Override
    public void leConnectionUpdate(
            int clientIf,
            String address,
            int minConnectionInterval,
            int maxConnectionInterval,
            int peripheralLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.leConnectionUpdate(
                clientIf,
                address,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
                source);
    }

    @Override
    public int subrateModeRequest(
            int clientIf, BluetoothDevice device, int subrateMode, AttributionSource source) {
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
        String address = device.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Invalid device address: " + address);
        }

        return service.subrateModeRequest(clientIf, device, subrateMode);
    }

    @Override
    public void registerServer(
            ParcelUuid uuid,
            IBluetoothGattServerCallback callback,
            boolean eattSupport,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.registerServer(uuid.getUuid(), callback, eattSupport, source);
    }

    @Override
    public void unregisterServer(int serverIf, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.unregisterServer(serverIf, source);
    }

    @Override
    public void serverConnect(
            int serverIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.serverConnect(serverIf, address, addressType, isDirect, transport, source);
    }

    @Override
    public void serverDisconnect(int serverIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.serverDisconnect(serverIf, address, source);
    }

    @Override
    public void serverSetPreferredPhy(
            int serverIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.serverSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions, source);
    }

    @Override
    public void serverReadPhy(int clientIf, String address, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.serverReadPhy(clientIf, address, source);
    }

    @Override
    public void addService(int serverIf, BluetoothGattService svc, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.addService(serverIf, svc, source);
    }

    @Override
    public void removeService(int serverIf, int handle, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.removeService(serverIf, handle, source);
    }

    @Override
    public void clearServices(int serverIf, AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.clearServices(serverIf, source);
    }

    @Override
    public void sendResponse(
            int serverIf,
            String address,
            int requestId,
            int status,
            int offset,
            byte[] value,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.sendResponse(serverIf, address, requestId, status, offset, value, source);
    }

    @Override
    public int sendNotification(
            int serverIf,
            String address,
            int handle,
            boolean confirm,
            byte[] value,
            AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }
        return service.sendNotification(serverIf, address, handle, confirm, value, source);
    }

    @Override
    public void disconnectAll(AttributionSource source) {
        GattService service = getService();
        if (service == null) {
            return;
        }
        service.disconnectAll(source);
    }
}
