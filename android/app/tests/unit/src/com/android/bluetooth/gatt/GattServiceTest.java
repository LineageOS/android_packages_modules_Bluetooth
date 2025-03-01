/*
 * Copyright 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_scan.PeriodicScanManager;
import com.android.bluetooth.le_scan.ScanManager;
import com.android.bluetooth.le_scan.ScanObjectsFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test cases for {@link GattService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GattServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private ContextMap<IBluetoothGattCallback> mClientMap;
    @Mock private ScanManager mScanManager;
    @Mock private PeriodicScanManager mPeriodicScanManager;
    @Mock private Set<String> mReliableQueue;
    @Mock private ContextMap<IBluetoothGattServerCallback> mServerMap;
    @Mock private DistanceMeasurementManager mDistanceMeasurementManager;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseManagerNativeInterface;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private GattObjectsFactory mGattObjectsFactory;
    @Mock private ScanObjectsFactory mScanObjectsFactory;
    @Mock private GattNativeInterface mNativeInterface;

    private static final String REMOTE_DEVICE_ADDRESS = "00:00:00:00:00:00";
    private static final int TIMES_UP_AND_DOWN = 3;

    private final BluetoothAdapter mAdapter =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter();
    private final AttributionSource mAttributionSource = mAdapter.getAttributionSource();
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CompanionDeviceManager mCompanionDeviceManager =
            mContext.getSystemService(CompanionDeviceManager.class);

    private MockContentResolver mMockContentResolver;

    private GattService mService;
    private CompanionManager mBtCompanionManager;

    @Before
    public void setUp() throws Exception {
        mMockContentResolver = new MockContentResolver(mContext);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });

        GattObjectsFactory.setInstanceForTesting(mGattObjectsFactory);
        ScanObjectsFactory.setInstanceForTesting(mScanObjectsFactory);

        doReturn(mNativeInterface).when(mGattObjectsFactory).getNativeInterface();
        doReturn(mDistanceMeasurementManager)
                .when(mGattObjectsFactory)
                .createDistanceMeasurementManager(any());
        doReturn(mScanManager)
                .when(mScanObjectsFactory)
                .createScanManager(any(), any(), any(), any());
        doReturn(mPeriodicScanManager).when(mScanObjectsFactory).createPeriodicScanManager();
        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getSharedPreferences("GattServiceTestPrefs", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        doReturn(mResources).when(mAdapterService).getResources();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();

        TestUtils.mockGetSystemService(
                mAdapterService, Context.LOCATION_SERVICE, LocationManager.class);
        TestUtils.mockGetSystemService(
                mAdapterService, Context.ACTIVITY_SERVICE, ActivityManager.class);
        TestUtils.mockGetSystemService(
                mAdapterService,
                Context.COMPANION_DEVICE_SERVICE,
                CompanionDeviceManager.class,
                mCompanionDeviceManager);

        mBtCompanionManager = new CompanionManager(mAdapterService, null);
        doReturn(mBtCompanionManager).when(mAdapterService).getCompanionManager();

        AdvertiseManagerNativeInterface.setInstance(mAdvertiseManagerNativeInterface);
        mService = new GattService(mAdapterService);

        mService.mClientMap = mClientMap;
        mService.mReliableQueue = mReliableQueue;
        mService.mServerMap = mServerMap;
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        AdvertiseManagerNativeInterface.setInstance(null);

        GattObjectsFactory.setInstanceForTesting(null);
        ScanObjectsFactory.setInstanceForTesting(null);
    }

    @Test
    public void testServiceUpAndDown() throws Exception {
        for (int i = 0; i < TIMES_UP_AND_DOWN; i++) {
            mService.cleanup();
            mService = new GattService(mAdapterService);
        }
    }

    @Test
    public void emptyClearServices() {
        int serverIf = 1;

        mService.clearServices(serverIf, mAttributionSource);
        verify(mNativeInterface, times(0)).gattServerDeleteService(eq(serverIf), anyInt());
    }

    @Test
    public void clientReadPhy() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.clientReadPhy(clientIf, address, mAttributionSource);
        verify(mNativeInterface).gattClientReadPhy(clientIf, address);
    }

    @Test
    public void clientSetPreferredPhy() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.clientSetPreferredPhy(
                clientIf, address, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mNativeInterface)
                .gattClientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void connectionParameterUpdate() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        int connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
        mService.connectionParameterUpdate(
                clientIf, address, connectionPriority, mAttributionSource);

        verify(mNativeInterface, times(3))
                .gattConnectionParameterUpdate(
                        eq(clientIf),
                        eq(address),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0));
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }

    @Test
    public void clientConnect() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = false;
        int transport = 2;
        boolean opportunistic = true;
        int phy = 3;

        mService.clientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                mAttributionSource);

        verify(mNativeInterface)
                .gattClientConnect(
                        clientIf, address, addressType, isDirect, transport, opportunistic, phy, 0);
    }

    @Test
    public void clientConnectOverLeFailed() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = BluetoothDevice.TRANSPORT_LE;
        boolean opportunistic = false;
        int phy = 3;

        AttributionSource testAttributeSource =
                new AttributionSource.Builder(Process.SYSTEM_UID)
                        .setPid(Process.myPid())
                        .setDeviceId(Context.DEVICE_ID_DEFAULT)
                        .setPackageName("com.google.android.gms")
                        .setAttributionTag("com.google.android.gms.findmydevice")
                        .build();

        mService.clientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        clientIf, address, addressType, isDirect, transport, opportunistic, phy, 0);
        mService.onConnected(clientIf, 0, BluetoothGatt.GATT_CONNECTION_TIMEOUT, address);
        verify(mAdapterService).notifyGattClientConnectFailed(anyInt(), any());
    }

    @Test
    public void clientConnectDisconnectOverLe() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = BluetoothDevice.TRANSPORT_LE;
        boolean opportunistic = false;
        int phy = 3;

        AttributionSource testAttributeSource =
                new AttributionSource.Builder(Process.SYSTEM_UID)
                        .setPid(Process.myPid())
                        .setDeviceId(Context.DEVICE_ID_DEFAULT)
                        .setPackageName("com.google.android.gms")
                        .setAttributionTag("com.google.android.gms.findmydevice")
                        .build();

        mService.clientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        clientIf, address, addressType, isDirect, transport, opportunistic, phy, 0);
        mService.onConnected(clientIf, 15, BluetoothGatt.GATT_SUCCESS, address);
        mService.clientDisconnect(clientIf, address, mAttributionSource);

        verify(mAdapterService).notifyGattClientDisconnect(anyInt(), any());
    }

    @Test
    public void clientConnectOverLeDisconnectedByRemote() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = BluetoothDevice.TRANSPORT_LE;
        boolean opportunistic = false;
        int phy = 3;

        AttributionSource testAttributeSource =
                new AttributionSource.Builder(Process.SYSTEM_UID)
                        .setPid(Process.myPid())
                        .setDeviceId(Context.DEVICE_ID_DEFAULT)
                        .setPackageName("com.google.android.gms")
                        .setAttributionTag("com.google.android.gms.findmydevice")
                        .build();

        mService.clientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        clientIf, address, addressType, isDirect, transport, opportunistic, phy, 0);
        mService.onConnected(clientIf, 15, BluetoothGatt.GATT_SUCCESS, address);
        mService.onDisconnected(clientIf, 15, 1, address);

        verify(mAdapterService).notifyGattClientDisconnect(anyInt(), any());
    }

    @Test
    public void disconnectAll() {
        Map<Integer, String> connMap = new HashMap<>();
        int clientIf = 1;
        String address = "02:00:00:00:00:00";
        connMap.put(clientIf, address);
        doReturn(connMap).when(mClientMap).getConnectedMap();
        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.disconnectAll(mAttributionSource);
        verify(mNativeInterface).gattClientDisconnect(clientIf, address, connId);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        BluetoothDevice testDevice = getTestDevice(90);
        BluetoothDevice[] bluetoothDevices = new BluetoothDevice[] {testDevice};
        doReturn(bluetoothDevices).when(mAdapterService).getBondedDevices();

        Set<String> connectedDevices = new HashSet<>();
        String address = "02:00:00:00:00:00";
        connectedDevices.add(address);
        doReturn(connectedDevices).when(mClientMap).getConnectedDevices();

        List<BluetoothDevice> deviceList =
                mService.getDevicesMatchingConnectionStates(states, mAttributionSource);

        int expectedSize = 1;
        assertThat(deviceList).hasSize(expectedSize);

        BluetoothDevice bluetoothDevice = deviceList.get(0);
        assertThat(bluetoothDevice.getAddress()).isEqualTo(address);
    }

    @Test
    public void registerClient() {
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        boolean eattSupport = true;

        mService.registerClient(uuid, callback, eattSupport, mAttributionSource);
        verify(mNativeInterface)
                .gattClientRegisterApp(
                        uuid.getLeastSignificantBits(),
                        uuid.getMostSignificantBits(),
                        mAttributionSource.getPackageName(),
                        eattSupport);
    }

    @Test
    public void registerClient_checkLimitPerApp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_GATT_CLIENT_DYNAMIC_ALLOCATION);
        doReturn(GattService.GATT_CLIENT_LIMIT_PER_APP).when(mClientMap).countByAppUid(anyInt());
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        mService.registerClient(uuid, callback, /* eattSupport= */ true, mAttributionSource);
        verify(mClientMap, never()).add(any(), any(), any(), any());
        verify(mNativeInterface, never())
                .gattClientRegisterApp(anyLong(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    public void unregisterClient() {
        int clientIf = 3;

        mService.unregisterClient(
                clientIf, mAttributionSource, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mClientMap).remove(clientIf, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mNativeInterface).gattClientUnregisterApp(clientIf);
    }

    @Test
    public void readCharacteristic() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int authReq = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readCharacteristic(clientIf, address, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadCharacteristic(connId, handle, authReq);
    }

    @Test
    public void readUsingCharacteristicUuid() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        UUID uuid = UUID.randomUUID();
        int startHandle = 2;
        int endHandle = 3;
        int authReq = 4;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readUsingCharacteristicUuid(
                clientIf, address, uuid, startHandle, endHandle, authReq, mAttributionSource);
        verify(mNativeInterface)
                .gattClientReadUsingCharacteristicUuid(
                        connId,
                        uuid.getLeastSignificantBits(),
                        uuid.getMostSignificantBits(),
                        startHandle,
                        endHandle,
                        authReq);
    }

    @Test
    public void writeCharacteristic() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int writeType = 3;
        int authReq = 4;
        byte[] value = new byte[] {5, 6};

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        int writeCharacteristicResult =
                mService.writeCharacteristic(
                        clientIf, address, handle, writeType, authReq, value, mAttributionSource);
        assertThat(writeCharacteristicResult)
                .isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED);
    }

    @Test
    public void readDescriptor() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        int authReq = 3;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.readDescriptor(clientIf, address, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadDescriptor(connId, handle, authReq);
    }

    @Test
    public void beginReliableWrite() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.beginReliableWrite(clientIf, address, mAttributionSource);
        verify(mReliableQueue).add(address);
    }

    @Test
    public void endReliableWrite() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        boolean execute = true;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.endReliableWrite(clientIf, address, execute, mAttributionSource);
        verify(mReliableQueue).remove(address);
        verify(mNativeInterface).gattClientExecuteWrite(connId, execute);
    }

    @Test
    public void registerForNotification() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        boolean enable = true;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.registerForNotification(clientIf, address, handle, enable, mAttributionSource);

        verify(mNativeInterface)
                .gattClientRegisterForNotifications(clientIf, address, handle, enable);
    }

    @Test
    public void readRemoteRssi() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.readRemoteRssi(clientIf, address, mAttributionSource);
        verify(mNativeInterface).gattClientReadRemoteRssi(clientIf, address);
    }

    @Test
    public void configureMTU() {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int mtu = 2;

        Integer connId = 1;
        doReturn(connId).when(mClientMap).connIdByAddress(clientIf, address);

        mService.configureMTU(clientIf, address, mtu, mAttributionSource);
        verify(mNativeInterface).gattClientConfigureMTU(connId, mtu);
    }

    @Test
    public void leConnectionUpdate() throws Exception {
        int clientIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int minInterval = 3;
        int maxInterval = 4;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int minConnectionEventLen = 7;
        int maxConnectionEventLen = 8;

        mService.leConnectionUpdate(
                clientIf,
                address,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
                mAttributionSource);

        verify(mNativeInterface)
                .gattConnectionParameterUpdate(
                        clientIf,
                        address,
                        minInterval,
                        maxInterval,
                        peripheralLatency,
                        supervisionTimeout,
                        minConnectionEventLen,
                        maxConnectionEventLen);
    }

    @Test
    public void serverConnect() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;

        mService.serverConnect(
                serverIf, address, addressType, isDirect, transport, mAttributionSource);
        verify(mNativeInterface)
                .gattServerConnect(serverIf, address, addressType, isDirect, transport);
    }

    @Test
    public void serverDisconnect() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        Integer connId = 1;
        doReturn(connId).when(mServerMap).connIdByAddress(serverIf, address);

        mService.serverDisconnect(serverIf, address, mAttributionSource);
        verify(mNativeInterface).gattServerDisconnect(serverIf, address, connId);
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mService.serverSetPreferredPhy(
                serverIf, address, txPhy, rxPhy, phyOptions, mAttributionSource);
        verify(mNativeInterface)
                .gattServerSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void serverReadPhy() {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;

        mService.serverReadPhy(serverIf, address, mAttributionSource);
        verify(mNativeInterface).gattServerReadPhy(serverIf, address);
    }

    @Test
    public void sendNotification() throws Exception {
        int serverIf = 1;
        String address = REMOTE_DEVICE_ADDRESS;
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        Integer connId = 1;
        doReturn(connId).when(mServerMap).connIdByAddress(serverIf, address);

        mService.sendNotification(serverIf, address, handle, confirm, value, mAttributionSource);
        verify(mNativeInterface).gattServerSendIndication(serverIf, handle, connId, value);

        confirm = false;

        mService.sendNotification(serverIf, address, handle, confirm, value, mAttributionSource);
        verify(mNativeInterface).gattServerSendNotification(serverIf, handle, connId, value);
    }

    @Test
    public void unregAll() throws Exception {
        int appId = 1;
        List<Integer> appIds = new ArrayList<>();
        appIds.add(appId);
        doReturn(appIds).when(mClientMap).getAllAppsIds();

        mService.unregAll(mAttributionSource);
        verify(mClientMap).remove(appId, ContextMap.RemoveReason.REASON_UNREGISTER_ALL);
        verify(mNativeInterface).gattClientUnregisterApp(appId);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mService.cleanup();
    }

    @Test
    public void restrictedHandles() throws Exception {
        int clientIf = 1;
        int connId = 1;
        ArrayList<GattDbElement> db = new ArrayList<>();

        ContextMap<IBluetoothGattCallback>.App app = mock(ContextMap.App.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        doReturn(app).when(mClientMap).getByConnId(connId);
        app.callback = callback;

        GattDbElement hidService =
                GattDbElement.createPrimaryService(
                        UUID.fromString("00001812-0000-1000-8000-00805F9B34FB"));
        hidService.id = 1;

        GattDbElement hidInfoChar =
                GattDbElement.createCharacteristic(
                        UUID.fromString("00002A4A-0000-1000-8000-00805F9B34FB"), 0, 0);
        hidInfoChar.id = 2;

        GattDbElement randomChar =
                GattDbElement.createCharacteristic(
                        UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB"), 0, 0);
        randomChar.id = 3;

        db.add(hidService);
        db.add(hidInfoChar);
        db.add(randomChar);

        mService.onGetGattDb(connId, db);
        // HID characteristics should be restricted
        assertThat(mService.mRestrictedHandles.get(connId)).contains(hidInfoChar.id);
        assertThat(mService.mRestrictedHandles.get(connId)).doesNotContain(randomChar.id);

        mService.onDisconnected(
                clientIf, connId, BluetoothGatt.GATT_SUCCESS, REMOTE_DEVICE_ADDRESS);
        assertThat(mService.mRestrictedHandles).doesNotContainKey(connId);
    }
}
