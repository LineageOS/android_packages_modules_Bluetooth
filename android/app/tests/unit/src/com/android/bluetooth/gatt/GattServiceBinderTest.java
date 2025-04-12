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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.UUID;

/** Test cases for {@link GattServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GattServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private GattService mService;

    private final AttributionSource mAttributionSource = new AttributionSource.Builder(1).build();
    private final BluetoothDevice mDevice = getTestDevice(109);

    private GattServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).isAvailable();
        mBinder = new GattServiceBinder(mService);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states, mAttributionSource);
    }

    @Test
    public void registerClient() {
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        boolean eattSupport = true;

        mBinder.registerClient(new ParcelUuid(uuid), callback, eattSupport, mAttributionSource);
        verify(mService).registerClient(uuid, callback, eattSupport, mAttributionSource);
    }

    @Test
    public void unregisterClient() {
        int clientIf = 3;

        mBinder.unregisterClient(clientIf, mAttributionSource);
        verify(mService)
                .unregisterClient(
                        clientIf,
                        mAttributionSource,
                        ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
    }

    @Test
    public void clientConnect() throws Exception {
        int clientIf = 1;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;
        boolean opportunistic = true;
        int phy = 3;

        mBinder.clientConnect(
                clientIf,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                mAttributionSource);
        verify(mService)
                .clientConnect(
                        clientIf,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        mAttributionSource);
    }

    @Test
    public void clientDisconnect() throws Exception {
        int clientIf = 1;

        mBinder.clientDisconnect(clientIf, mDevice, mAttributionSource);
        verify(mService).clientDisconnect(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void clientSetPreferredPhy() throws Exception {
        int clientIf = 1;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mBinder.clientSetPreferredPhy(
                clientIf, mDevice, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mService)
                .clientSetPreferredPhy(
                        clientIf, mDevice, txPhy, rxPhy, phyOptions, mAttributionSource);
    }

    @Test
    public void clientReadPhy() throws Exception {
        int clientIf = 1;

        mBinder.clientReadPhy(clientIf, mDevice, mAttributionSource);
        verify(mService).clientReadPhy(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void refreshDevice() throws Exception {
        int clientIf = 1;

        mBinder.refreshDevice(clientIf, mDevice, mAttributionSource);
        verify(mService).refreshDevice(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void discoverServices() throws Exception {
        int clientIf = 1;

        mBinder.discoverServices(clientIf, mDevice, mAttributionSource);
        verify(mService).discoverServices(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void discoverServiceByUuid() throws Exception {
        int clientIf = 1;
        UUID uuid = UUID.randomUUID();

        mBinder.discoverServiceByUuid(clientIf, mDevice, new ParcelUuid(uuid), mAttributionSource);
        verify(mService).discoverServiceByUuid(clientIf, mDevice, uuid, mAttributionSource);
    }

    @Test
    public void readCharacteristic() throws Exception {
        int clientIf = 1;
        int handle = 2;
        int authReq = 3;

        mBinder.readCharacteristic(clientIf, mDevice, handle, authReq, mAttributionSource);
        verify(mService).readCharacteristic(clientIf, mDevice, handle, authReq, mAttributionSource);
    }

    @Test
    public void readUsingCharacteristicUuid() throws Exception {
        int clientIf = 1;
        UUID uuid = UUID.randomUUID();
        int startHandle = 2;
        int endHandle = 3;
        int authReq = 4;

        mBinder.readUsingCharacteristicUuid(
                clientIf,
                mDevice,
                new ParcelUuid(uuid),
                startHandle,
                endHandle,
                authReq,
                mAttributionSource);
        verify(mService)
                .readUsingCharacteristicUuid(
                        clientIf,
                        mDevice,
                        uuid,
                        startHandle,
                        endHandle,
                        authReq,
                        mAttributionSource);
    }

    @Test
    public void writeCharacteristic() throws Exception {
        int clientIf = 1;
        int handle = 2;
        int writeType = 3;
        int authReq = 4;
        byte[] value = new byte[] {5, 6};

        mBinder.writeCharacteristic(
                clientIf, mDevice, handle, writeType, authReq, value, mAttributionSource);
        verify(mService)
                .writeCharacteristic(
                        clientIf, mDevice, handle, writeType, authReq, value, mAttributionSource);
    }

    @Test
    public void readDescriptor() throws Exception {
        int clientIf = 1;
        int handle = 2;
        int authReq = 3;

        mBinder.readDescriptor(clientIf, mDevice, handle, authReq, mAttributionSource);
        verify(mService).readDescriptor(clientIf, mDevice, handle, authReq, mAttributionSource);
    }

    @Test
    public void writeDescriptor() throws Exception {
        int clientIf = 1;
        int handle = 2;
        int authReq = 3;
        byte[] value = new byte[] {4, 5};

        mBinder.writeDescriptor(clientIf, mDevice, handle, authReq, value, mAttributionSource);
        verify(mService)
                .writeDescriptor(clientIf, mDevice, handle, authReq, value, mAttributionSource);
    }

    @Test
    public void beginReliableWrite() throws Exception {
        int clientIf = 1;

        mBinder.beginReliableWrite(clientIf, mDevice, mAttributionSource);
        verify(mService).beginReliableWrite(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void endReliableWrite() throws Exception {
        int clientIf = 1;
        boolean execute = true;

        mBinder.endReliableWrite(clientIf, mDevice, execute, mAttributionSource);
        verify(mService).endReliableWrite(clientIf, mDevice, execute, mAttributionSource);
    }

    @Test
    public void registerForNotification() throws Exception {
        int clientIf = 1;
        int handle = 2;
        boolean enable = true;

        mBinder.registerForNotification(clientIf, mDevice, handle, enable, mAttributionSource);
        verify(mService)
                .registerForNotification(clientIf, mDevice, handle, enable, mAttributionSource);
    }

    @Test
    public void readRemoteRssi() throws Exception {
        int clientIf = 1;

        mBinder.readRemoteRssi(clientIf, mDevice, mAttributionSource);
        verify(mService).readRemoteRssi(clientIf, mDevice, mAttributionSource);
    }

    @Test
    public void configureMTU() throws Exception {
        int clientIf = 1;
        int mtu = 2;

        mBinder.configureMTU(clientIf, mDevice, mtu, mAttributionSource);
        verify(mService).configureMTU(clientIf, mDevice, mtu, mAttributionSource);
    }

    @Test
    public void connectionParameterUpdate() throws Exception {
        int clientIf = 1;
        int connectionPriority = 2;

        mBinder.connectionParameterUpdate(
                clientIf, mDevice, connectionPriority, mAttributionSource);
        verify(mService)
                .connectionParameterUpdate(
                        clientIf, mDevice, connectionPriority, mAttributionSource);
    }

    @Test
    public void leConnectionUpdate() throws Exception {
        int clientIf = 1;
        int minConnectionInterval = 3;
        int maxConnectionInterval = 4;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int minConnectionEventLen = 7;
        int maxConnectionEventLen = 8;

        mBinder.leConnectionUpdate(
                clientIf,
                mDevice,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
                mAttributionSource);
        verify(mService)
                .leConnectionUpdate(
                        clientIf,
                        mDevice,
                        minConnectionInterval,
                        maxConnectionInterval,
                        peripheralLatency,
                        supervisionTimeout,
                        minConnectionEventLen,
                        maxConnectionEventLen,
                        mAttributionSource);
    }

    @Test
    public void registerServer() {
        UUID uuid = UUID.randomUUID();
        IBluetoothGattServerCallback callback = mock(IBluetoothGattServerCallback.class);
        boolean eattSupport = true;

        mBinder.registerServer(new ParcelUuid(uuid), callback, eattSupport, mAttributionSource);
        verify(mService).registerServer(uuid, callback, eattSupport, mAttributionSource);
    }

    @Test
    public void unregisterServer() {
        int serverIf = 3;

        mBinder.unregisterServer(serverIf, mAttributionSource);
        verify(mService).unregisterServer(serverIf, mAttributionSource);
    }

    @Test
    public void serverConnect() {
        int serverIf = 1;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;

        mBinder.serverConnect(
                serverIf, mDevice, addressType, isDirect, transport, mAttributionSource);
        verify(mService)
                .serverConnect(
                        serverIf, mDevice, addressType, isDirect, transport, mAttributionSource);
    }

    @Test
    public void serverDisconnect() {
        int serverIf = 1;

        mBinder.serverDisconnect(serverIf, mDevice, mAttributionSource);
        verify(mService).serverDisconnect(serverIf, mDevice, mAttributionSource);
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int serverIf = 1;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mBinder.serverSetPreferredPhy(
                serverIf, mDevice, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mService)
                .serverSetPreferredPhy(
                        serverIf, mDevice, txPhy, rxPhy, phyOptions, mAttributionSource);
    }

    @Test
    public void serverReadPhy() throws Exception {
        int serverIf = 1;

        mBinder.serverReadPhy(serverIf, mDevice, mAttributionSource);
        verify(mService).serverReadPhy(serverIf, mDevice, mAttributionSource);
    }

    @Test
    public void addService() {
        int serverIf = 1;
        BluetoothGattService svc = mock(BluetoothGattService.class);

        mBinder.addService(serverIf, svc, mAttributionSource);
        verify(mService).addService(serverIf, svc, mAttributionSource);
    }

    @Test
    public void removeService() {
        int serverIf = 1;
        int handle = 2;

        mBinder.removeService(serverIf, handle, mAttributionSource);
        verify(mService).removeService(serverIf, handle, mAttributionSource);
    }

    @Test
    public void clearServices() {
        int serverIf = 1;

        mBinder.clearServices(serverIf, mAttributionSource);
        verify(mService).clearServices(serverIf, mAttributionSource);
    }

    @Test
    public void sendResponse() throws Exception {
        int serverIf = 1;
        int requestId = 2;
        int status = 3;
        int offset = 4;
        byte[] value = new byte[] {5, 6};

        mBinder.sendResponse(
                serverIf, mDevice, requestId, status, offset, value, mAttributionSource);
        verify(mService)
                .sendResponse(
                        serverIf, mDevice, requestId, status, offset, value, mAttributionSource);
    }

    @Test
    public void sendNotification() throws Exception {
        int serverIf = 1;
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        mBinder.sendNotification(serverIf, mDevice, handle, confirm, value, mAttributionSource);
        verify(mService)
                .sendNotification(serverIf, mDevice, handle, confirm, value, mAttributionSource);
    }

    @Test
    public void disconnectAll() throws Exception {
        mBinder.disconnectAll(mAttributionSource);
        verify(mService).disconnectAll(mAttributionSource);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
