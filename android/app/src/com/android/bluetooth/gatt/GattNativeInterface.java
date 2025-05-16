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

import android.bluetooth.BluetoothDevice;
import android.os.RemoteException;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;

import java.util.List;
import java.util.UUID;

/** GATT Profile Native Interface to/from JNI. */
public class GattNativeInterface {
    private static final String TAG = GattNativeInterface.class.getSimpleName();

    private final AdapterService mAdapterService;
    private final GattService mGattService;

    GattNativeInterface(AdapterService adapterService, GattService gattService) {
        mAdapterService = adapterService;
        mGattService = gattService;
    }

    private BluetoothDevice getDevice(String address) {
        byte[] addressBytes = Utils.getBytesFromAddress(address);
        return mAdapterService.getDeviceFromByte(addressBytes);
    }

    /* Callbacks */

    void onClientRegistered(int status, int clientIf, long uuidLsb, long uuidMsb)
            throws RemoteException {
        mGattService.onClientRegisteredFromNative(status, clientIf, new UUID(uuidMsb, uuidLsb));
    }

    void onConnected(int clientIf, int connId, int transport, int status, String address)
            throws RemoteException {
        mGattService.onConnectedFromNative(clientIf, connId, transport, status, getDevice(address));
    }

    void onDisconnected(int clientIf, int connId, int transport, int status, String address)
            throws RemoteException {
        mGattService.onDisconnectedFromNative(
                clientIf, connId, transport, status, getDevice(address));
    }

    void onClientPhyUpdate(int connId, int txPhy, int rxPhy, int status) throws RemoteException {
        mGattService.onClientPhyUpdateFromNative(connId, txPhy, rxPhy, status);
    }

    void onClientPhyRead(int clientIf, String address, int txPhy, int rxPhy, int status)
            throws RemoteException {
        mGattService.onClientPhyReadFromNative(clientIf, getDevice(address), txPhy, rxPhy, status);
    }

    void onClientConnUpdate(int connId, int interval, int latency, int timeout, int status)
            throws RemoteException {
        mGattService.onClientConnUpdateFromNative(connId, interval, latency, timeout, status);
    }

    void onServiceChanged(int connId) throws RemoteException {
        mGattService.onServiceChangedFromNative(connId);
    }

    void onClientSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status)
            throws RemoteException {
        mGattService.onClientSubrateChangeFromNative(
                connId, subrateFactor, latency, contNum, timeout, status);
    }

    void onServerPhyUpdate(int connId, int txPhy, int rxPhy, int status) throws RemoteException {
        mGattService.onServerPhyUpdateFromNative(connId, txPhy, rxPhy, status);
    }

    void onServerPhyRead(int serverIf, String address, int txPhy, int rxPhy, int status)
            throws RemoteException {
        mGattService.onServerPhyReadFromNative(serverIf, getDevice(address), txPhy, rxPhy, status);
    }

    void onServerConnUpdate(int connId, int interval, int latency, int timeout, int status)
            throws RemoteException {
        mGattService.onServerConnUpdateFromNative(connId, interval, latency, timeout, status);
    }

    void onServerSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status)
            throws RemoteException {
        mGattService.onServerSubrateChangeFromNative(
                connId, subrateFactor, latency, contNum, timeout, status);
    }

    GattDbElement getSampleGattDbElement() {
        return mGattService.getSampleGattDbElement();
    }

    void onGetGattDb(int connId, List<GattDbElement> db) throws RemoteException {
        mGattService.onGetGattDbFromNative(connId, db);
    }

    void onRegisterForNotifications(int connId, int status, int registered, int handle) {
        mGattService.onRegisterForNotificationsFromNative(connId, status, registered, handle);
    }

    void onNotify(int connId, String address, int handle, boolean isNotify, byte[] data)
            throws RemoteException {
        mGattService.onNotifyFromNative(connId, getDevice(address), handle, isNotify, data);
    }

    void onReadCharacteristic(int connId, int status, int handle, byte[] data)
            throws RemoteException {
        mGattService.onReadCharacteristicFromNative(connId, status, handle, data);
    }

    void onWriteCharacteristic(int connId, int status, int handle, byte[] data)
            throws RemoteException {
        mGattService.onWriteCharacteristicFromNative(connId, status, handle, data);
    }

    void onExecuteCompleted(int connId, int status) throws RemoteException {
        mGattService.onExecuteCompletedFromNative(connId, status);
    }

    void onReadDescriptor(int connId, int status, int handle, byte[] data) throws RemoteException {
        mGattService.onReadDescriptorFromNative(connId, status, handle, data);
    }

    void onWriteDescriptor(int connId, int status, int handle, byte[] data) throws RemoteException {
        mGattService.onWriteDescriptorFromNative(connId, status, handle, data);
    }

    void onReadRemoteRssi(int clientIf, String address, int rssi, int status)
            throws RemoteException {
        mGattService.onReadRemoteRssiFromNative(clientIf, getDevice(address), rssi, status);
    }

    void onConfigureMTU(int connId, int status, int mtu) throws RemoteException {
        mGattService.onConfigureMTUFromNative(connId, status, mtu);
    }

    void onClientCongestion(int connId, boolean congested) throws RemoteException {
        mGattService.onClientCongestionFromNative(connId, congested);
    }

    /* Server callbacks */

    void onServerRegistered(int status, int serverIf, long uuidLsb, long uuidMsb)
            throws RemoteException {
        mGattService.onServerRegisteredFromNative(status, serverIf, new UUID(uuidMsb, uuidLsb));
    }

    void onServiceAdded(int status, int serverIf, List<GattDbElement> service)
            throws RemoteException {
        mGattService.onServiceAddedFromNative(status, serverIf, service);
    }

    void onServiceStopped(int status, int serverIf, int srvcHandle) throws RemoteException {
        mGattService.onServiceStoppedFromNative(status, serverIf, srvcHandle);
    }

    void onServiceDeleted(int status, int serverIf, int srvcHandle) {
        mGattService.onServiceDeletedFromNative(status, serverIf, srvcHandle);
    }

    void onClientConnected(
            String address, int transport, boolean connected, int connId, int serverIf)
            throws RemoteException {
        mGattService.onClientConnectedFromNative(
                getDevice(address), transport, connected, connId, serverIf);
    }

    void onServerReadCharacteristic(
            String address, int connId, int transId, int handle, int offset, boolean isLong)
            throws RemoteException {
        mGattService.onServerReadCharacteristicFromNative(
                getDevice(address), connId, transId, handle, offset, isLong);
    }

    void onServerReadDescriptor(
            String address, int connId, int transId, int handle, int offset, boolean isLong)
            throws RemoteException {
        mGattService.onServerReadDescriptorFromNative(
                getDevice(address), connId, transId, handle, offset, isLong);
    }

    void onServerWriteCharacteristic(
            String address,
            int connId,
            int transId,
            int handle,
            int offset,
            int length,
            boolean needRsp,
            boolean isPrep,
            byte[] data)
            throws RemoteException {
        mGattService.onServerWriteCharacteristicFromNative(
                getDevice(address), connId, transId, handle, offset, length, needRsp, isPrep, data);
    }

    void onServerWriteDescriptor(
            String address,
            int connId,
            int transId,
            int handle,
            int offset,
            int length,
            boolean needRsp,
            boolean isPrep,
            byte[] data)
            throws RemoteException {
        mGattService.onServerWriteDescriptorFromNative(
                getDevice(address), connId, transId, handle, offset, length, needRsp, isPrep, data);
    }

    void onExecuteWrite(String address, int connId, int transId, int execWrite)
            throws RemoteException {
        mGattService.onExecuteWriteFromNative(getDevice(address), connId, transId, execWrite);
    }

    void onResponseSendCompleted(int status, int attrHandle) {
        mGattService.onResponseSendCompletedFromNative(status, attrHandle);
    }

    void onNotificationSent(int connId, int status) throws RemoteException {
        mGattService.onNotificationSentFromNative(connId, status);
    }

    void onServerCongestion(int connId, boolean congested) throws RemoteException {
        mGattService.onServerCongestionFromNative(connId, congested);
    }

    void onMtuChanged(int connId, int mtu) throws RemoteException {
        mGattService.onMtuChangedFromNative(connId, mtu);
    }

    /**********************************************************************************************/
    /******************************************* native *******************************************/
    /**********************************************************************************************/

    private native void initializeNative();

    private native void cleanupNative();

    private native int gattClientGetDeviceTypeNative(String address);

    private native void gattClientRegisterAppNative(
            long appUuidLsb, long appUuidMsb, String name, boolean eattSupport);

    private native void gattClientUnregisterAppNative(int clientIf);

    private native void gattClientConnectNative(
            int clientIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int initiatingPhys,
            int preferredMtu,
            boolean preferRelaxMode);

    private native void gattClientDisconnectNative(int clientIf, String address, int connId);

    private native void gattClientSetPreferredPhyNative(
            int clientIf, String address, int txPhy, int rxPhy, int phyOptions);

    private native void gattClientReadPhyNative(int clientIf, String address);

    private native void gattClientRefreshNative(int clientIf, String address);

    private native void gattClientSearchServiceNative(
            int connId, boolean searchAll, long serviceUuidLsb, long serviceUuidMsb);

    private native void gattClientDiscoverServiceByUuidNative(
            int connId, long serviceUuidLsb, long serviceUuidMsb);

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
            long appUuidLsb, long appUuidMsb, boolean eattSupport);

    private native void gattServerUnregisterAppNative(int serverIf);

    private native void gattServerConnectNative(
            int serverIf, String address, int addressType, boolean isDirect, int transport);

    private native void gattServerDisconnectNative(int serverIf, String address, int connId);

    private native void gattServerSetPreferredPhyNative(
            int clientIf, String address, int txPhy, int rxPhy, int phyOptions);

    private native void gattServerReadPhyNative(int clientIf, String address);

    private native void gattServerAddServiceNative(int serverIf, List<GattDbElement> service);

    private native void gattServerStopServiceNative(int serverIf, int svcHandle);

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

    private native void gattTestNative(
            int command,
            long uuid1Lsb,
            long uuid1Msb,
            String bda1,
            int p1,
            int p2,
            int p3,
            int p4,
            int p5);

    /** Initialize the native interface and native components */
    void init() {
        initializeNative();
    }

    /** Clean up the native interface and native components */
    void cleanup() {
        cleanupNative();
    }

    /**
     * @return type of Bluetooth device 0 for BR/EDR, 1 for BLE, 2 for DUAL mode (To be confirmed)
     */
    public int gattClientGetDeviceType(BluetoothDevice device) {
        return gattClientGetDeviceTypeNative(device.getAddress());
    }

    /**
     * Register the given client It will invoke {@link #onClientRegistered(int, int, long, long)}.
     */
    void gattClientRegisterApp(long appUuidLsb, long appUuidMsb, String name, boolean eattSupport) {
        gattClientRegisterAppNative(appUuidLsb, appUuidMsb, name, eattSupport);
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
            int initiatingPhys,
            int preferredMtu,
            boolean preferRelaxMode) {
        gattClientConnectNative(
                clientIf,
                device.getAddress(),
                addressType,
                isDirect,
                transport,
                opportunistic,
                initiatingPhys,
                preferredMtu,
                preferRelaxMode);
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
    void gattClientSearchService(
            int connId, boolean searchAll, long serviceUuidLsb, long serviceUuidMsb) {
        gattClientSearchServiceNative(connId, searchAll, serviceUuidLsb, serviceUuidMsb);
    }

    /** Discover the GATT service by the given UUID */
    void gattClientDiscoverServiceByUuid(int connId, long serviceUuidLsb, long serviceUuidMsb) {
        gattClientDiscoverServiceByUuidNative(connId, serviceUuidLsb, serviceUuidMsb);
    }

    /** Read a characteristic by the given handle */
    void gattClientReadCharacteristic(int connId, int handle, int authReq) {
        gattClientReadCharacteristicNative(connId, handle, authReq);
    }

    /** Read a characteristic by the given UUID */
    void gattClientReadUsingCharacteristicUuid(
            int connId, long uuidMsb, long uuidLsb, int sHandle, int eHandle, int authReq) {
        gattClientReadUsingCharacteristicUuidNative(
                connId, uuidMsb, uuidLsb, sHandle, eHandle, authReq);
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

    /** Update connection parameter. */
    public int gattSubrateRequest(
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

    /** Register GATT server */
    void gattServerRegisterApp(long appUuidLsb, long appUuidMsb, boolean eattSupport) {
        gattServerRegisterAppNative(appUuidLsb, appUuidMsb, eattSupport);
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

    /** Stop a service */
    void gattServerStopService(int serverIf, int svcHandle) {
        gattServerStopServiceNative(serverIf, svcHandle);
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

    /** Send a test command */
    void gattTest(
            int command,
            long uuid1Lsb,
            long uuid1Msb,
            String bda1,
            int p1,
            int p2,
            int p3,
            int p4,
            int p5) {
        gattTestNative(command, uuid1Lsb, uuid1Msb, bda1, p1, p2, p3, p4, p5);
    }
}
