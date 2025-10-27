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

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

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

    @Mock private AttributionSource mSource;
    @Mock private IBluetoothGattServerCallback mGattServerCallback;
    @Mock private IBluetoothGattCallback mGattCallback;
    @Mock private GattService mService;

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

        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void registerClient() {
        UUID uuid = UUID.randomUUID();
        boolean eattSupport = true;
        int transport = TRANSPORT_LE;

        mBinder.registerClient(
                new ParcelUuid(uuid), mGattCallback, eattSupport, transport, mSource);
        verify(mService).registerClient(uuid, mGattCallback, eattSupport, transport, mSource);
    }

    @Test
    public void unregisterClient() {
        mBinder.unregisterClient(mGattCallback, mSource);
        verify(mService)
                .unregisterClient(
                        mGattCallback, mSource, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
    }

    @Test
    public void clientConnect() throws Exception {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;
        boolean opportunistic = true;
        int phy = 3;

        mBinder.clientConnect(
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                mSource);
        verify(mService)
                .clientConnect(
                        mGattCallback,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        mSource);
    }

    @Test
    public void clientDisconnect() throws Exception {
        mBinder.clientDisconnect(mGattCallback, mDevice, mSource);
        verify(mService).clientDisconnect(mGattCallback, mDevice, mSource);
    }

    @Test
    public void clientSetPreferredPhy() throws Exception {
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mBinder.clientSetPreferredPhy(mGattCallback, mDevice, txPhy, rxPhy, phyOptions, mSource);
        verify(mService).clientSetPreferredPhy(mGattCallback, mDevice, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void clientReadPhy() throws Exception {
        mBinder.clientReadPhy(mGattCallback, mDevice, mSource);
        verify(mService).clientReadPhy(mGattCallback, mDevice);
    }

    @Test
    public void refreshDevice() throws Exception {
        mBinder.refreshDevice(mGattCallback, mDevice, mSource);
        verify(mService).refreshDevice(mGattCallback, mDevice);
    }

    @Test
    public void discoverServices() throws Exception {
        mBinder.discoverServices(mGattCallback, mDevice, mSource);
        verify(mService).discoverServices(mGattCallback, mDevice);
    }

    @Test
    public void discoverServiceByUuid() throws Exception {
        UUID uuid = UUID.randomUUID();

        mBinder.discoverServiceByUuid(mGattCallback, mDevice, new ParcelUuid(uuid), mSource);
        verify(mService).discoverServiceByUuid(mGattCallback, mDevice, uuid);
    }

    @Test
    public void readCharacteristic() throws Exception {
        int handle = 2;
        int authReq = 3;

        mBinder.readCharacteristic(mGattCallback, mDevice, handle, authReq, mSource);
        verify(mService).readCharacteristic(mGattCallback, mDevice, handle, authReq);
    }

    @Test
    public void readUsingCharacteristicUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        int startHandle = 2;
        int endHandle = 3;
        int authReq = 4;

        mBinder.readUsingCharacteristicUuid(
                mGattCallback,
                mDevice,
                new ParcelUuid(uuid),
                startHandle,
                endHandle,
                authReq,
                mSource);
        verify(mService)
                .readUsingCharacteristicUuid(
                        mGattCallback, mDevice, uuid, startHandle, endHandle, authReq);
    }

    @Test
    public void writeCharacteristic() throws Exception {
        int handle = 2;
        int writeType = 3;
        int authReq = 4;
        byte[] value = new byte[] {5, 6};

        mBinder.writeCharacteristic(
                mGattCallback, mDevice, handle, writeType, authReq, value, mSource);
        verify(mService)
                .writeCharacteristic(mGattCallback, mDevice, handle, writeType, authReq, value);
    }

    @Test
    public void readDescriptor() throws Exception {
        int handle = 2;
        int authReq = 3;

        mBinder.readDescriptor(mGattCallback, mDevice, handle, authReq, mSource);
        verify(mService).readDescriptor(mGattCallback, mDevice, handle, authReq);
    }

    @Test
    public void writeDescriptor() throws Exception {
        int handle = 2;
        int authReq = 3;
        byte[] value = new byte[] {4, 5};

        mBinder.writeDescriptor(mGattCallback, mDevice, handle, authReq, value, mSource);
        verify(mService).writeDescriptor(mGattCallback, mDevice, handle, authReq, value);
    }

    @Test
    public void beginReliableWrite() throws Exception {
        mBinder.beginReliableWrite(mDevice, mSource);
        verify(mService).beginReliableWrite(mDevice);
    }

    @Test
    public void endReliableWrite() throws Exception {
        boolean execute = true;

        mBinder.endReliableWrite(mGattCallback, mDevice, execute, mSource);
        verify(mService).endReliableWrite(mGattCallback, mDevice, execute);
    }

    @Test
    public void registerForNotification() throws Exception {
        int handle = 2;
        boolean enable = true;

        mBinder.registerForNotification(mGattCallback, mDevice, handle, enable, mSource);
        verify(mService).registerForNotification(mGattCallback, mDevice, handle, enable);
    }

    @Test
    public void readRemoteRssi() throws Exception {
        mBinder.readRemoteRssi(mGattCallback, mDevice, mSource);
        verify(mService).readRemoteRssi(mGattCallback, mDevice);
    }

    @Test
    public void configureMTU() throws Exception {
        int mtu = 2;

        mBinder.configureMTU(mGattCallback, mDevice, mtu, mSource);
        verify(mService).configureMTU(mGattCallback, mDevice, mtu);
    }

    @Test
    public void connectionParameterUpdate() throws Exception {
        int connectionPriority = 2;

        mBinder.connectionParameterUpdate(mGattCallback, mDevice, connectionPriority, mSource);
        verify(mService).connectionParameterUpdate(mGattCallback, mDevice, connectionPriority);
    }

    @Test
    public void leConnectionUpdate() throws Exception {
        int minConnectionInterval = 3;
        int maxConnectionInterval = 4;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int minConnectionEventLen = 7;
        int maxConnectionEventLen = 8;

        mBinder.leConnectionUpdate(
                mGattCallback,
                mDevice,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
                mSource);
        verify(mService)
                .leConnectionUpdate(
                        mGattCallback,
                        mDevice,
                        minConnectionInterval,
                        maxConnectionInterval,
                        peripheralLatency,
                        supervisionTimeout,
                        minConnectionEventLen,
                        maxConnectionEventLen);
    }

    @Test
    public void subrateModeRequest() throws Exception {
        BluetoothDevice testDevice = getTestDevice(5);
        int subrateMode = 0;

        mBinder.subrateModeRequest(mGattCallback, testDevice, subrateMode, mSource);

        verify(mService).subrateModeRequest(mGattCallback, testDevice, subrateMode);
    }

    @Test
    public void registerServer() {
        UUID uuid = UUID.randomUUID();
        boolean eattSupport = true;
        int transport = TRANSPORT_LE;

        mBinder.registerServer(
                new ParcelUuid(uuid), mGattServerCallback, eattSupport, transport, mSource);
        verify(mService).registerServer(uuid, mGattServerCallback, eattSupport, transport, mSource);
    }

    @Test
    public void unregisterServer() {
        mBinder.unregisterServer(mGattServerCallback, mSource);
        verify(mService).unregisterServer(mGattServerCallback);
    }

    @Test
    public void serverConnect() {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;

        mBinder.serverConnect(
                mGattServerCallback, mDevice, addressType, isDirect, transport, mSource);
        verify(mService)
                .serverConnect(
                        mGattServerCallback, mDevice, addressType, isDirect, transport, mSource);
    }

    @Test
    public void serverDisconnect() {
        mBinder.serverDisconnect(mGattServerCallback, mDevice, mSource);
        verify(mService).serverDisconnect(mGattServerCallback, mDevice);
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mBinder.serverSetPreferredPhy(
                mGattServerCallback, mDevice, txPhy, rxPhy, phyOptions, mSource);
        verify(mService)
                .serverSetPreferredPhy(mGattServerCallback, mDevice, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void serverReadPhy() throws Exception {
        mBinder.serverReadPhy(mGattServerCallback, mDevice, mSource);
        verify(mService).serverReadPhy(mGattServerCallback, mDevice);
    }

    @Test
    public void addService() {
        BluetoothGattService svc = mock(BluetoothGattService.class);

        mBinder.addService(mGattServerCallback, svc, mSource);
        verify(mService).addService(mGattServerCallback, svc);
    }

    @Test
    public void removeService() {
        int handle = 2;

        mBinder.removeService(mGattServerCallback, handle, mSource);
        verify(mService).removeService(mGattServerCallback, handle);
    }

    @Test
    public void clearServices() {
        mBinder.clearServices(mGattServerCallback, mSource);
        verify(mService).clearServices(mGattServerCallback);
    }

    @Test
    public void sendResponse() throws Exception {
        int requestId = 2;
        int status = 3;
        int offset = 4;
        byte[] value = new byte[] {5, 6};

        mBinder.sendResponse(
                mGattServerCallback, mDevice, requestId, status, offset, value, mSource);
        verify(mService)
                .sendResponse(mGattServerCallback, mDevice, requestId, status, offset, value);
    }

    @Test
    public void sendNotification() throws Exception {
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        mBinder.sendNotification(mGattServerCallback, mDevice, handle, confirm, value, mSource);
        verify(mService).sendNotification(mGattServerCallback, mDevice, handle, confirm, value);
    }

    @Test
    public void disconnectAll() throws Exception {
        mBinder.disconnectAll(mSource);
        verify(mService).disconnectAll(mSource);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
