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

package com.android.bluetooth.gatt;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.GattOffloadSession;

import com.android.bluetooth.profile.NativeInterface;

import java.util.List;
import java.util.UUID;

public class GattNativeInterface extends NativeInterface<GattNativeCallback> {

    GattNativeInterface(GattNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    private native void initializeNative();

    private native void cleanupNative();

    private native int gattClientGetDeviceTypeNative(String address);

    private native void gattClientRegisterAppNative(
            long appUuidMsb, long appUuidLsb, String name, boolean eattSupport);

    private native void gattClientUnregisterAppNative(int clientIf);

    private native void gattClientConnectNative(
            int clientIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int preferredMtu,
            boolean preferRelaxMode,
            boolean autoMtuEnabled);

    private native void gattClientDisconnectNative(int clientIf, String address, int connId);

    private native void gattClientSetPreferredPhyNative(
            int clientIf, String address, int txPhy, int rxPhy, int phyOptions);

    private native void gattClientReadPhyNative(int clientIf, String address);

    private native void gattClientRefreshNative(int clientIf, String address);

    private native void gattClientSearchServiceNative(
            int connId, boolean searchAll, long serviceUuidMsb, long serviceUuidLsb);

    private native void gattClientDiscoverServiceByUuidNative(
            int connId, long serviceUuidMsb, long serviceUuidLsb);

    private native void gattClientReadCharacteristicNative(int connId, int handle, int authReq);

    private native void gattClientReadUsingCharacteristicUuidNative(
            int connId, long uuidMsb, long uuidLsb, int sHandle, int eHandle, int authReq);

    private native void gattClientReadDescriptorNative(int connId, int handle, int authReq);

    private native void gattClientWriteCharacteristicNative(
            int connId, int handle, int writeType, int authReq, byte[] value);

    private native void gattClientWriteDescriptorNative(
            int connId, int handle, int authReq, byte[] value);

    private native void gattClientExecuteWriteNative(int connId, boolean execute);

    private native void gattClientRegisterForNotificationsNative(
            int clientIf, String address, int handle, boolean enable);

    private native void gattClientReadRemoteRssiNative(int clientIf, String address);

    private native void gattClientConfigureMTUNative(int connId, int mtu);

    private native void gattConnectionParameterUpdateNative(
            int clientIf,
            String address,
            int minInterval,
            int maxInterval,
            int latency,
            int timeout,
            int minConnectionEventLen,
            int maxConnectionEventLen);

    private native void gattServerRegisterAppNative(
            long appUuidMsb, long appUuidLsb, boolean eattSupport);

    private native void gattServerUnregisterAppNative(int serverIf);

    private native void gattServerConnectNative(
            int serverIf, String address, int addressType, boolean isDirect, int transport);

    private native void gattServerDisconnectNative(int serverIf, String address, int connId);

    private native void gattServerSetPreferredPhyNative(
            int clientIf, String address, int txPhy, int rxPhy, int phyOptions);

    private native void gattServerReadPhyNative(int clientIf, String address);

    private native void gattServerAddServiceNative(int serverIf, List<GattDbElement> service);

    private native void gattServerDeleteServiceNative(int serverIf, int svcHandle);

    private native void gattServerSendIndicationNative(
            int serverIf, int attrHandle, int connId, byte[] val);

    private native void gattServerSendNotificationNative(
            int serverIf, int attrHandle, int connId, byte[] val);

    private native void gattServerSendResponseNative(
            int serverIf,
            int connId,
            int transId,
            int status,
            int handle,
            int offset,
            byte[] val,
            int authReq);

    private native int gattSubrateRequestNative(
            int clientIf,
            String address,
            int subrateMin,
            int subrateMax,
            int maxLatency,
            int contNumber,
            int supervisionTimeout);

    private native int gattSubrateModeRequestNative(int clientIf, String address, int subrateMode);

    private native GattOffloadSession.InnerParcel gattClientOffloadCharacteristicsNative(
            int connId,
            List<GattDbElement> characteristics,
            long endpointId,
            long hubId,
            int uid,
            String attributionTag);

    private native GattOffloadSession.InnerParcel gattServerOffloadCharacteristicsNative(
            int connId,
            List<GattDbElement> characteristics,
            long endpointId,
            long hubId,
            int uid,
            String attributionTag);

    private native void gattClientUnoffloadCharacteristicsNative(int connId, int sessionId);

    private native void gattServerUnoffloadCharacteristicsNative(int connId, int sessionId);

    /** Initialize the native interface and native components */
    void init() {
        initializeNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    /**
     * @return type of Bluetooth device 0 for BR/EDR, 1 for BLE, 2 for DUAL mode (To be confirmed)
     */
    int gattClientGetDeviceType(BluetoothDevice device) {
        return gattClientGetDeviceTypeNative(device.getAddress());
    }

    /**
     * Register the given client It will invoke {@link GattNativeCallback#onClientRegistered(int,
     * int, long, long)}.
     */
    void gattClientRegisterApp(@NonNull UUID uuid, String name, boolean eattSupport) {
        gattClientRegisterAppNative(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), name, eattSupport);
    }

    /** Unregister the client */
    void gattClientUnregisterApp(int clientIf) {
        gattClientUnregisterAppNative(clientIf);
    }

    /**
     * Connect to the remote Gatt server
     *
     * @see BluetoothDevice#connectGatt for parameters.
     */
    void gattClientConnect(
            int clientIf,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int preferredMtu,
            boolean preferRelaxMode,
            boolean autoMtuEnabled) {
        gattClientConnectNative(
                clientIf,
                device.getAddress(),
                addressType,
                isDirect,
                transport,
                opportunistic,
                preferredMtu,
                preferRelaxMode,
                autoMtuEnabled);
    }

    /** Disconnect from the remote Gatt server */
    void gattClientDisconnect(int clientIf, BluetoothDevice device, int connId) {
        gattClientDisconnectNative(clientIf, device.getAddress(), connId);
    }

    /** Set the preferred connection PHY for the client */
    void gattClientSetPreferredPhy(
            int clientIf, BluetoothDevice device, int txPhy, int rxPhy, int phyOptions) {
        gattClientSetPreferredPhyNative(clientIf, device.getAddress(), txPhy, rxPhy, phyOptions);
    }

    /** Read the current transmitter PHY and receiver PHY of the client */
    void gattClientReadPhy(int clientIf, BluetoothDevice device) {
        gattClientReadPhyNative(clientIf, device.getAddress());
    }

    /** Clear the internal cache and force a refresh of the services from the remote device */
    void gattClientRefresh(int clientIf, BluetoothDevice device) {
        gattClientRefreshNative(clientIf, device.getAddress());
    }

    /** Discover GATT services */
    void gattClientSearchService(int connId, boolean searchAll, @NonNull UUID uuid) {
        gattClientSearchServiceNative(
                connId, searchAll, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /** Discover the GATT service by the given UUID */
    void gattClientDiscoverServiceByUuid(int connId, @NonNull UUID uuid) {
        gattClientDiscoverServiceByUuidNative(
                connId, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /** Read a characteristic by the given handle */
    void gattClientReadCharacteristic(int connId, int handle, int authReq) {
        gattClientReadCharacteristicNative(connId, handle, authReq);
    }

    /** Read a characteristic by the given UUID */
    void gattClientReadUsingCharacteristicUuid(
            int connId, @NonNull UUID uuid, int sHandle, int eHandle, int authReq) {
        gattClientReadUsingCharacteristicUuidNative(
                connId,
                uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits(),
                sHandle,
                eHandle,
                authReq);
    }

    /** Read a descriptor by the given handle */
    void gattClientReadDescriptor(int connId, int handle, int authReq) {
        gattClientReadDescriptorNative(connId, handle, authReq);
    }

    /** Write a characteristic by the given handle */
    void gattClientWriteCharacteristic(
            int connId, int handle, int writeType, int authReq, byte[] value) {
        gattClientWriteCharacteristicNative(connId, handle, writeType, authReq, value);
    }

    /** Write a descriptor by the given handle */
    void gattClientWriteDescriptor(int connId, int handle, int authReq, byte[] value) {
        gattClientWriteDescriptorNative(connId, handle, authReq, value);
    }

    /** Execute a reliable write transaction */
    void gattClientExecuteWrite(int connId, boolean execute) {
        gattClientExecuteWriteNative(connId, execute);
    }

    /** Register notification for the characteristic */
    void gattClientRegisterForNotifications(
            int clientIf, BluetoothDevice device, int handle, boolean enable) {
        gattClientRegisterForNotificationsNative(clientIf, device.getAddress(), handle, enable);
    }

    /** Read the RSSI for a connected remote device */
    void gattClientReadRemoteRssi(int clientIf, BluetoothDevice device) {
        gattClientReadRemoteRssiNative(clientIf, device.getAddress());
    }

    /** Configure MTU size used for the connection */
    void gattClientConfigureMTU(int connId, int mtu) {
        gattClientConfigureMTUNative(connId, mtu);
    }

    /** Update connection parameter. */
    void gattConnectionParameterUpdate(
            int clientIf,
            BluetoothDevice device,
            int minInterval,
            int maxInterval,
            int latency,
            int timeout,
            int minConnectionEventLen,
            int maxConnectionEventLen) {
        gattConnectionParameterUpdateNative(
                clientIf,
                device.getAddress(),
                minInterval,
                maxInterval,
                latency,
                timeout,
                minConnectionEventLen,
                maxConnectionEventLen);
    }

    /** Update subrate parameter. */
    int gattSubrateRequest(
            int clientIf,
            BluetoothDevice device,
            int subrateMin,
            int subrateMax,
            int maxLatency,
            int contNumber,
            int supervisionTimeout) {
        return gattSubrateRequestNative(
                clientIf,
                device.getAddress(),
                subrateMin,
                subrateMax,
                maxLatency,
                contNumber,
                supervisionTimeout);
    }

    /** Update subrate mode. */
    int gattSubrateModeRequest(int clientIf, BluetoothDevice device, int subrateMode) {
        return gattSubrateModeRequestNative(clientIf, device.getAddress(), subrateMode);
    }

    /** Register GATT server */
    void gattServerRegisterApp(@NonNull UUID uuid, boolean eattSupport) {
        gattServerRegisterAppNative(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), eattSupport);
    }

    /** Unregister GATT server */
    void gattServerUnregisterApp(int serverIf) {
        gattServerUnregisterAppNative(serverIf);
    }

    /** Connect to a remote device as a GATT server role */
    void gattServerConnect(
            int serverIf,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport) {
        gattServerConnectNative(serverIf, device.getAddress(), addressType, isDirect, transport);
    }

    /** Disconnects from a remote device as a GATT server role */
    void gattServerDisconnect(int serverIf, BluetoothDevice device, int connId) {
        gattServerDisconnectNative(serverIf, device.getAddress(), connId);
    }

    /** Set the preferred connection PHY as a GATT server role */
    void gattServerSetPreferredPhy(
            int clientIf, BluetoothDevice device, int txPhy, int rxPhy, int phyOptions) {
        gattServerSetPreferredPhyNative(clientIf, device.getAddress(), txPhy, rxPhy, phyOptions);
    }

    /** Read the current transmitter PHY and receiver PHY of the connection */
    void gattServerReadPhy(int clientIf, BluetoothDevice device) {
        gattServerReadPhyNative(clientIf, device.getAddress());
    }

    /** Add a service to the list of services to be hosted. */
    void gattServerAddService(int serverIf, List<GattDbElement> service) {
        gattServerAddServiceNative(serverIf, service);
    }

    /** Removes a service from the list of services to be provided */
    void gattServerDeleteService(int serverIf, int svcHandle) {
        gattServerDeleteServiceNative(serverIf, svcHandle);
    }

    /** Send an indication of the characteristic */
    void gattServerSendIndication(int serverIf, int attrHandle, int connId, byte[] val) {
        gattServerSendIndicationNative(serverIf, attrHandle, connId, val);
    }

    /** Send a notification of the characteristic */
    void gattServerSendNotification(int serverIf, int attrHandle, int connId, byte[] val) {
        gattServerSendNotificationNative(serverIf, attrHandle, connId, val);
    }

    /** Send a response as a GATT server role */
    void gattServerSendResponse(
            int serverIf,
            int connId,
            int transId,
            int status,
            int handle,
            int offset,
            byte[] val,
            int authReq) {
        gattServerSendResponseNative(
                serverIf, connId, transId, status, handle, offset, val, authReq);
    }

    /** Offload client characteristics */
    GattOffloadSession.InnerParcel gattClientOffloadCharacteristics(
            int connId,
            List<GattDbElement> characteristics,
            long endpointId,
            long hubId,
            int uid,
            String attributionTag) {
        return gattClientOffloadCharacteristicsNative(
                connId, characteristics, endpointId, hubId, uid, attributionTag);
    }

    /** Offload server characteristics */
    GattOffloadSession.InnerParcel gattServerOffloadCharacteristics(
            int connId,
            List<GattDbElement> characteristics,
            long endpointId,
            long hubId,
            int uid,
            String attributionTag) {
        return gattServerOffloadCharacteristicsNative(
                connId, characteristics, endpointId, hubId, uid, attributionTag);
    }

    /** Unoffload client characteristics */
    void gattClientUnoffloadCharacteristics(int connId, int sessionId) {
        gattClientUnoffloadCharacteristicsNative(connId, sessionId);
    }

    /** Unoffload server characteristics */
    void gattServerUnoffloadCharacteristics(int connId, int sessionId) {
        gattServerUnoffloadCharacteristicsNative(connId, sessionId);
    }
}
