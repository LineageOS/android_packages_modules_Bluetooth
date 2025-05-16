/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils.MockitoRule;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_scan.ScanController;

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

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock private AttributionSource mAttributionSource;
    @Mock private IBluetoothGattCallback mGattCallback;
    @Mock private ContextMap<IBluetoothGattCallback> mClientMap;
    @Mock private IBluetoothGattServerCallback mGattServerCallback;
    @Mock private ContextMap<IBluetoothGattServerCallback> mServerMap;
    @Mock private ScanController mScanController;
    @Mock private Set<BluetoothDevice> mReliableQueue;
    @Mock private DistanceMeasurementManager mDistanceMeasurementManager;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseManagerNativeInterface;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private GattObjectsFactory mGattObjectsFactory;
    @Mock private GattNativeInterface mNativeInterface;

    private static final int SERVER_IF = 34;
    private static final int CLIENT_IF = 12;
    private static final int SERVER_CONN_ID = 84;
    private static final int CLIENT_CONN_ID = 42;
    private static final int TEST_RSSI = 43;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final CompanionDeviceManager mCompanionDeviceManager =
            mContext.getSystemService(CompanionDeviceManager.class);
    private final BluetoothDevice mDevice = getTestDevice(109);

    private MockContentResolver mMockContentResolver;
    private CompanionManager mBtCompanionManager;
    private GattService mService;

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

        doReturn(mContext.getPackageName()).when(mAttributionSource).getPackageName();
        doReturn(mContext.getPackageName()).when(mAttributionSource).getAttributionTag();
        doReturn(Binder.getCallingUid()).when(mAttributionSource).getUid();
        doReturn(SERVER_CONN_ID).when(mServerMap).connIdByDevice(SERVER_IF, mDevice);
        doReturn(CLIENT_CONN_ID).when(mClientMap).connIdByDevice(CLIENT_IF, mDevice);

        ContextMap<IBluetoothGattCallback>.App clientApp = mock(ContextMap.App.class);
        clientApp.callback = mGattCallback;
        clientApp.id = CLIENT_IF;
        doReturn(clientApp).when(mClientMap).getByCallbackId(mGattCallback);
        doReturn(clientApp).when(mClientMap).getById(CLIENT_IF);

        ContextMap<IBluetoothGattCallback>.App serverApp = mock(ContextMap.App.class);
        serverApp.id = SERVER_IF;
        doReturn(serverApp).when(mServerMap).getByCallbackId(mGattServerCallback);
        doReturn(mDistanceMeasurementManager)
                .when(mGattObjectsFactory)
                .createDistanceMeasurementManager(any(), any());
        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getSharedPreferences("GattServiceTestPrefs", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        doReturn(mResources).when(mAdapterService).getResources();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();

        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, LocationManager.class);
        mockGetSystemService(mAdapterService, ActivityManager.class);
        mockGetSystemService(
                mAdapterService, CompanionDeviceManager.class, mCompanionDeviceManager);

        mBtCompanionManager = new CompanionManager(mAdapterService, null);
        doReturn(mBtCompanionManager).when(mAdapterService).getCompanionManager();

        AdvertiseManagerNativeInterface.setInstance(mAdvertiseManagerNativeInterface);
        mService = new GattService(mAdapterService, mNativeInterface, mScanController);

        mService.mClientMap = mClientMap;
        mService.mReliableQueue = mReliableQueue;
        mService.mServerMap = mServerMap;

        mockGetRemoteDevice(mAdapterService, mDevice);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        AdvertiseManagerNativeInterface.setInstance(null);
        GattObjectsFactory.setInstanceForTesting(null);
    }

    @Test
    public void testServiceUpAndDown() throws Exception {
        for (int i = 0; i < 3; i++) {
            mService.cleanup();
            mService = new GattService(mAdapterService, mNativeInterface, mScanController);
        }
    }

    @Test
    public void emptyClearServices() {
        mService.clearServices(mGattServerCallback);
        verify(mNativeInterface, times(0)).gattServerDeleteService(eq(SERVER_IF), anyInt());
    }

    @Test
    public void clientReadPhy() {
        mService.clientReadPhy(mGattCallback, mDevice);
        verify(mNativeInterface).gattClientReadPhy(CLIENT_IF, mDevice);
    }

    @Test
    public void clientSetPreferredPhy() {
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mService.clientSetPreferredPhy(mGattCallback, mDevice, txPhy, rxPhy, phyOptions);
        verify(mNativeInterface)
                .gattClientSetPreferredPhy(CLIENT_IF, mDevice, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void connectionParameterUpdate() {
        int connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH;
        mService.connectionParameterUpdate(mGattCallback, mDevice, connectionPriority);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER;
        mService.connectionParameterUpdate(mGattCallback, mDevice, connectionPriority);

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
        mService.connectionParameterUpdate(mGattCallback, mDevice, connectionPriority);

        verify(mNativeInterface, times(3))
                .gattConnectionParameterUpdate(
                        eq(CLIENT_IF),
                        eq(mDevice),
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
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = false;
        int transport = 2;
        boolean opportunistic = true;
        int phy = 3;

        mService.clientConnect(
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                mAttributionSource);

        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        0,
                        false);
    }

    @Test
    public void clientConnectOverLeFailed() throws Exception {
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
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        0,
                        false);
        mService.onConnectedFromNative(
                CLIENT_IF, 0, transport, BluetoothGatt.GATT_CONNECTION_TIMEOUT, mDevice);
        verify(mAdapterService).notifyGattClientConnectFailed(anyInt(), any());
    }

    @Test
    public void clientConnectDisconnectOverLe() throws Exception {
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
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        0,
                        false);
        mService.onConnectedFromNative(
                CLIENT_IF, 15, transport, BluetoothGatt.GATT_SUCCESS, mDevice);
        mService.clientDisconnect(mGattCallback, mDevice, mAttributionSource);

        verify(mAdapterService).notifyGattClientDisconnect(anyInt(), any());
    }

    @Test
    public void clientConnectOverLeDisconnectedByRemote() throws Exception {
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
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                testAttributeSource);

        verify(mAdapterService).notifyDirectLeGattClientConnect(anyInt(), any());
        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        phy,
                        0,
                        false);
        mService.onConnectedFromNative(
                CLIENT_IF, 15, transport, BluetoothGatt.GATT_SUCCESS, mDevice);
        mService.onDisconnectedFromNative(CLIENT_IF, 15, transport, 1, mDevice);

        verify(mAdapterService).notifyGattClientDisconnect(anyInt(), any());
    }

    @Test
    public void disconnectAll() {
        Map<Integer, BluetoothDevice> connMap = new HashMap<>();
        connMap.put(CLIENT_IF, mDevice);
        doReturn(connMap).when(mClientMap).getConnectedMap();

        mService.disconnectAll(mAttributionSource);
        verify(mNativeInterface).gattClientDisconnect(CLIENT_IF, mDevice, CLIENT_CONN_ID);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_CONNECTED};

        BluetoothDevice testDevice = getTestDevice(90);
        BluetoothDevice[] bluetoothDevices = new BluetoothDevice[] {testDevice};
        doReturn(bluetoothDevices).when(mAdapterService).getBondedDevices();

        Set<BluetoothDevice> connectedDevices = new HashSet<>();
        connectedDevices.add(mDevice);
        doReturn(connectedDevices).when(mClientMap).getConnectedDevices();

        List<BluetoothDevice> deviceList = mService.getDevicesMatchingConnectionStates(states);

        assertThat(deviceList).containsExactly(mDevice);
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
                        mContext.getPackageName(),
                        eattSupport);
    }

    @Test
    public void registerClient_checkLimitPerApp() {
        doReturn(GattService.GATT_CLIENT_LIMIT_PER_APP).when(mClientMap).countByAppUid(anyInt());
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        mService.registerClient(uuid, callback, /* eattSupport= */ true, mAttributionSource);
        verify(mClientMap, never()).add(any(), any(), any(), any());
        verify(mNativeInterface, never())
                .gattClientRegisterApp(anyLong(), anyLong(), any(), anyBoolean());
    }

    @Test
    public void unregisterClient() {
        mService.unregisterClient(
                mGattCallback,
                mAttributionSource,
                ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mClientMap).remove(CLIENT_IF, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mNativeInterface).gattClientUnregisterApp(CLIENT_IF);
    }

    @Test
    public void readCharacteristic() {
        int handle = 2;
        int authReq = 3;

        mService.readCharacteristic(mGattCallback, mDevice, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadCharacteristic(CLIENT_CONN_ID, handle, authReq);
    }

    @Test
    public void readUsingCharacteristicUuid() {
        UUID uuid = UUID.randomUUID();
        int startHandle = 2;
        int endHandle = 3;
        int authReq = 4;

        mService.readUsingCharacteristicUuid(
                mGattCallback, mDevice, uuid, startHandle, endHandle, authReq);
        verify(mNativeInterface)
                .gattClientReadUsingCharacteristicUuid(
                        CLIENT_CONN_ID,
                        uuid.getLeastSignificantBits(),
                        uuid.getMostSignificantBits(),
                        startHandle,
                        endHandle,
                        authReq);
    }

    @Test
    public void writeCharacteristic() {
        int handle = 2;
        int writeType = 3;
        int authReq = 4;
        byte[] value = new byte[] {5, 6};

        int writeCharacteristicResult =
                mService.writeCharacteristic(
                        mGattCallback, mDevice, handle, writeType, authReq, value);
        assertThat(writeCharacteristicResult)
                .isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED);
    }

    @Test
    public void readDescriptor() throws Exception {
        int handle = 2;
        int authReq = 3;

        mService.readDescriptor(mGattCallback, mDevice, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadDescriptor(CLIENT_CONN_ID, handle, authReq);
    }

    @Test
    public void beginReliableWrite() {
        mService.beginReliableWrite(mDevice);
        verify(mReliableQueue).add(mDevice);
    }

    @Test
    public void endReliableWrite() {
        boolean execute = true;

        mService.endReliableWrite(mGattCallback, mDevice, execute);
        verify(mReliableQueue).remove(mDevice);
        verify(mNativeInterface).gattClientExecuteWrite(CLIENT_CONN_ID, execute);
    }

    @Test
    public void registerForNotification() throws Exception {
        int handle = 2;
        boolean enable = true;

        mService.registerForNotification(
                mGattCallback, mDevice, handle, enable, mAttributionSource);

        verify(mNativeInterface)
                .gattClientRegisterForNotifications(CLIENT_IF, mDevice, handle, enable);
    }

    @Test
    public void readRemoteRssi_entryIsEmpty() {
        mService.readRemoteRssi(mGattCallback, mDevice);

        verify(mNativeInterface).gattClientReadRemoteRssi(CLIENT_IF, mDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_READ_RSSI_THROTTLING)
    public void readRemoteRssi_entryIsNotEmpty() throws Exception {
        mService.mRssiReadThrottleMs = mService.RSSI_READ_THROTTLE_MS_MAX;
        mService.mRssiCache.put(
                mDevice.getAddress(),
                new com.android.bluetooth.gatt.GattService.RssiCacheEntry(
                        SystemClock.elapsedRealtime(), TEST_RSSI));

        mService.readRemoteRssi(mGattCallback, mDevice);

        verify(mGattCallback).onReadRemoteRssi(mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);
    }

    @Test
    @EnableFlags(Flags.FLAG_READ_RSSI_THROTTLING)
    public void onReadRemoteRssiFromNative() throws Exception {
        mService.onReadRemoteRssiFromNative(
                CLIENT_IF, mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);

        assertThat(mService.mRssiCache.get(mDevice.getAddress()).rssi()).isEqualTo(TEST_RSSI);
        verify(mGattCallback).onReadRemoteRssi(mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);
    }

    @Test
    public void configureMTU() {
        int mtu = 2;

        mService.configureMTU(mGattCallback, mDevice, mtu);
        verify(mNativeInterface).gattClientConfigureMTU(CLIENT_CONN_ID, mtu);
    }

    @Test
    public void leConnectionUpdate() throws Exception {
        int minInterval = 3;
        int maxInterval = 4;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int minConnectionEventLen = 7;
        int maxConnectionEventLen = 8;

        mService.leConnectionUpdate(
                mGattCallback,
                mDevice,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen);

        verify(mNativeInterface)
                .gattConnectionParameterUpdate(
                        CLIENT_IF,
                        mDevice,
                        minInterval,
                        maxInterval,
                        peripheralLatency,
                        supervisionTimeout,
                        minConnectionEventLen,
                        maxConnectionEventLen);
    }

    @Test
    public void serverConnect() {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = 2;

        mService.serverConnect(
                mGattServerCallback, mDevice, addressType, isDirect, transport, mAttributionSource);
        verify(mNativeInterface)
                .gattServerConnect(SERVER_IF, mDevice, addressType, isDirect, transport);
    }

    @Test
    public void serverDisconnect() {
        mService.serverDisconnect(mGattServerCallback, mDevice);
        verify(mNativeInterface).gattServerDisconnect(SERVER_IF, mDevice, SERVER_CONN_ID);
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        mService.serverSetPreferredPhy(mGattServerCallback, mDevice, txPhy, rxPhy, phyOptions);
        verify(mNativeInterface)
                .gattServerSetPreferredPhy(SERVER_IF, mDevice, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void serverReadPhy() {
        mService.serverReadPhy(mGattServerCallback, mDevice);
        verify(mNativeInterface).gattServerReadPhy(SERVER_IF, mDevice);
    }

    @Test
    public void sendNotification() throws Exception {
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);
        verify(mNativeInterface).gattServerSendIndication(SERVER_IF, handle, SERVER_CONN_ID, value);

        confirm = false;

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);
        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    public void unregAll() throws Exception {
        int appId = 1;
        ContextMap<IBluetoothGattCallback>.App app = mock(ContextMap.App.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        app.id = appId;
        app.callback = callback;
        doReturn(app).when(mClientMap).getByCallbackId(callback);

        List<IBluetoothGattCallback> callbacks = new ArrayList<>();
        callbacks.add(callback);
        doReturn(callbacks).when(mClientMap).getAllAppsCallbackId();

        mService.unregAll();
        verify(mClientMap).remove(appId, ContextMap.RemoveReason.REASON_UNREGISTER_ALL);
        verify(mNativeInterface).gattClientUnregisterApp(appId);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mService.cleanup();
    }

    @Test
    public void restrictedHandles() throws Exception {
        ArrayList<GattDbElement> db = new ArrayList<>();

        ContextMap<IBluetoothGattCallback>.App app = mock(ContextMap.App.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        doReturn(app).when(mClientMap).getByConnId(CLIENT_CONN_ID);
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

        mService.onGetGattDbFromNative(CLIENT_CONN_ID, db);
        // HID characteristics should be restricted
        assertThat(mService.mRestrictedHandles.get(CLIENT_CONN_ID)).contains(hidInfoChar.id);
        assertThat(mService.mRestrictedHandles.get(CLIENT_CONN_ID)).doesNotContain(randomChar.id);

        mService.onDisconnectedFromNative(
                CLIENT_IF,
                CLIENT_CONN_ID,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothGatt.GATT_SUCCESS,
                mDevice);
        assertThat(mService.mRestrictedHandles).doesNotContainKey(CLIENT_CONN_ID);
    }
}
