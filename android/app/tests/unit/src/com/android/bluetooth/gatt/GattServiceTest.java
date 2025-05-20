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
import static org.mockito.Mockito.doAnswer;
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
import android.platform.test.flag.junit.SetFlagsRule;
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
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
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

    @Mock private AttributionSource mAttributionSource;
    @Mock private IBluetoothGattCallback mGattCallback;
    @Mock private ContextMap<IBluetoothGattCallback> mClientMap;
    @Mock private IBluetoothGattServerCallback mGattServerCallback;
    @Mock private ContextMap<IBluetoothGattServerCallback> mServerMap;
    @Mock private ScanController mScanController;
    @Mock private Set<BluetoothDevice> mReliableQueue;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseManagerNativeInterface;
    @Mock private DistanceMeasurementNativeInterface mDistanceMeasurementNativeInterface;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private GattNativeInterface mNativeInterface;

    private GattService mService;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final CompanionDeviceManager mCompanionDeviceManager =
            mContext.getSystemService(CompanionDeviceManager.class);
    private CompanionManager mBtCompanionManager;
    private final BluetoothDevice mDevice = getTestDevice(109);
    private MockContentResolver mMockContentResolver;

    private static final int TEST_RSSI = 43;

    private static final int CLIENT_IF = 12;
    private static final int CLIENT_CONN_ID = 42;

    private final ContextMap.Connection CLIENT_CONN =
            new ContextMap.Connection(
                    CLIENT_CONN_ID, mDevice, BluetoothDevice.TRANSPORT_LE, CLIENT_IF);

    private final List<ContextMap.Connection> CLIENT_CONN_LIST = Arrays.asList(CLIENT_CONN);

    private static final int SERVER_IF = 34;
    private static final int SERVER_CONN_ID = 84;
    private static final int SERVER_CONN_ID_2 = 85;
    private final List<ContextMap.Connection> mServerConnections = new ArrayList<>();

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

        doReturn(mContext.getPackageName()).when(mAttributionSource).getPackageName();
        doReturn(mContext.getPackageName()).when(mAttributionSource).getAttributionTag();
        doReturn(Binder.getCallingUid()).when(mAttributionSource).getUid();

        doReturn(CLIENT_CONN_LIST).when(mClientMap).getConnectionsByDevice(CLIENT_IF, mDevice);
        ContextMap<IBluetoothGattCallback>.App clientApp = mock(ContextMap.App.class);
        clientApp.callback = mGattCallback;
        clientApp.id = CLIENT_IF;
        doReturn(clientApp).when(mClientMap).getByCallbackId(mGattCallback);
        doReturn(clientApp).when(mClientMap).getById(CLIENT_IF);

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_LE, mGattServerCallback);
        doAnswer(
                        (Answer<Void>)
                                invocation -> {
                                    Object[] arguments = invocation.getArguments();
                                    if (arguments != null && arguments.length == 4) {
                                        int id = (int) arguments[0];
                                        int connId = (int) arguments[1];
                                        int transport = (int) arguments[2];
                                        BluetoothDevice device = (BluetoothDevice) arguments[3];
                                        mServerConnections.add(
                                                new ContextMap.Connection(
                                                        connId, device, transport, id));
                                    }
                                    return null;
                                })
                .when(mServerMap)
                .addConnection(anyInt(), anyInt(), anyInt(), any(BluetoothDevice.class));
        doAnswer(
                        (Answer<Void>)
                                invocation -> {
                                    Object[] arguments = invocation.getArguments();
                                    if (arguments != null && arguments.length == 2) {
                                        int id = (int) arguments[0];
                                        int connId = (int) arguments[1];
                                        mServerConnections.removeIf(
                                                conn ->
                                                        conn.appId() == id
                                                                && conn.connId() == connId);
                                    }
                                    return null;
                                })
                .when(mServerMap)
                .removeConnection(anyInt(), anyInt());
        doAnswer(
                        (Answer<List<ContextMap.Connection>>)
                                invocation -> {
                                    List<ContextMap.Connection> currentConnections =
                                            new ArrayList<ContextMap.Connection>();
                                    Object[] arguments = invocation.getArguments();
                                    if (arguments != null && arguments.length == 2) {
                                        int id = (int) arguments[0];
                                        BluetoothDevice device = (BluetoothDevice) arguments[1];
                                        for (ContextMap.Connection connection :
                                                mServerConnections) {
                                            if (connection.device().equals(device)
                                                    && connection.appId() == id) {
                                                currentConnections.add(connection);
                                            }
                                        }
                                    }
                                    return currentConnections;
                                })
                .when(mServerMap)
                .getConnectionsByDevice(anyInt(), any(BluetoothDevice.class));

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

        mService =
                new GattService(
                        mAdapterService,
                        mNativeInterface,
                        mAdvertiseManagerNativeInterface,
                        mDistanceMeasurementNativeInterface,
                        mScanController);

        mService.mClientMap = mClientMap;
        mService.mReliableQueue = mReliableQueue;
        mService.mServerMap = mServerMap;

        mockGetRemoteDevice(mAdapterService, mDevice);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
    }

    // ---------------------------------------------------------------------------------------------
    // Profile Service Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testServiceUpAndDown() throws Exception {
        for (int i = 0; i < 3; i++) {
            mService.cleanup();
            mService =
                    new GattService(
                            mAdapterService,
                            mNativeInterface,
                            mAdvertiseManagerNativeInterface,
                            mDistanceMeasurementNativeInterface,
                            mScanController);
        }
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mService.cleanup();
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }

    // ---------------------------------------------------------------------------------------------
    // GATT Client Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    public void registerClient() {
        UUID uuid = UUID.randomUUID();
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        boolean eattSupport = true;
        int transport = BluetoothDevice.TRANSPORT_LE;

        mService.registerClient(uuid, callback, eattSupport, transport, mAttributionSource);
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
        boolean eattSupport = true;
        int transport = BluetoothDevice.TRANSPORT_LE;

        mService.registerClient(uuid, callback, eattSupport, transport, mAttributionSource);
        verify(mClientMap, never()).add(any(), any(), anyInt(), any(), any());
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
    public void clientUnregAll() throws Exception {
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
    public void clientGetDevicesMatchingConnectionStates() {
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
    public void clientDisconnectAll() {
        Map<Integer, BluetoothDevice> connMap = new HashMap<>();
        connMap.put(CLIENT_IF, mDevice);
        doReturn(connMap).when(mClientMap).getConnectedMap();

        mService.disconnectAll(mAttributionSource);
        verify(mNativeInterface).gattClientDisconnect(CLIENT_IF, mDevice, CLIENT_CONN_ID);
    }

    @Test
    public void clientConnectionParameterUpdate() {
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
    public void clientReadRemoteRssi_entryIsEmpty() {
        mService.readRemoteRssi(mGattCallback, mDevice);

        verify(mNativeInterface).gattClientReadRemoteRssi(CLIENT_IF, mDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_READ_RSSI_THROTTLING)
    public void clientReadRemoteRssi_entryIsNotEmpty() throws Exception {
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
    public void clientOnReadRemoteRssiFromNative() throws Exception {
        mService.onReadRemoteRssiFromNative(
                CLIENT_IF, mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);

        assertThat(mService.mRssiCache.get(mDevice.getAddress()).rssi()).isEqualTo(TEST_RSSI);
        verify(mGattCallback).onReadRemoteRssi(mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);
    }

    @Test
    public void clientLeConnectionUpdate() throws Exception {
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
    public void clientReadCharacteristic() {
        int handle = 2;
        int authReq = 3;

        mService.readCharacteristic(mGattCallback, mDevice, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadCharacteristic(CLIENT_CONN_ID, handle, authReq);
    }

    @Test
    public void clientReadUsingCharacteristicUuid() {
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
    public void clientWriteCharacteristic() {
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
    public void clientReadDescriptor() throws Exception {
        int handle = 2;
        int authReq = 3;

        mService.readDescriptor(mGattCallback, mDevice, handle, authReq, mAttributionSource);
        verify(mNativeInterface).gattClientReadDescriptor(CLIENT_CONN_ID, handle, authReq);
    }

    @Test
    public void clientBeginReliableWrite() {
        mService.beginReliableWrite(mDevice);
        verify(mReliableQueue).add(mDevice);
    }

    @Test
    public void clientEndReliableWrite() {
        boolean execute = true;

        mService.endReliableWrite(mGattCallback, mDevice, execute);
        verify(mReliableQueue).remove(mDevice);
        verify(mNativeInterface).gattClientExecuteWrite(CLIENT_CONN_ID, execute);
    }

    @Test
    public void clientRegisterForNotification() throws Exception {
        int handle = 2;
        boolean enable = true;

        mService.registerForNotification(
                mGattCallback, mDevice, handle, enable, mAttributionSource);

        verify(mNativeInterface)
                .gattClientRegisterForNotifications(CLIENT_IF, mDevice, handle, enable);
    }

    @Test
    public void clientReadRemoteRssi() {
        mService.readRemoteRssi(mGattCallback, mDevice);
        verify(mNativeInterface).gattClientReadRemoteRssi(CLIENT_IF, mDevice);
    }

    @Test
    public void clientConfigureMTU() {
        int mtu = 2;

        mService.configureMTU(mGattCallback, mDevice, mtu);
        verify(mNativeInterface).gattClientConfigureMTU(CLIENT_CONN_ID, mtu);
    }

	@Test
    public void clientRestrictedHandles() throws Exception {
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

    // ---------------------------------------------------------------------------------------------
    // GATT Server Tests
    // ---------------------------------------------------------------------------------------------

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
    public void serverDisconnect_oneBearerConnected_bearerDisconnectRequested() {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.serverDisconnect(mGattServerCallback, mDevice);

        verify(mNativeInterface).gattServerDisconnect(SERVER_IF, mDevice, SERVER_CONN_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverDisconnect_multipleBearersConnected_allBearersDisconnected() {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.serverDisconnect(mGattServerCallback, mDevice);

        verify(mNativeInterface).gattServerDisconnect(SERVER_IF, mDevice, SERVER_CONN_ID);
        verify(mNativeInterface).gattServerDisconnect(SERVER_IF, mDevice, SERVER_CONN_ID_2);
    }

    @Test
    public void serverDisconnect_noBearersConnected_zeroUsedToDisconnectInFlightConnections() {
        mService.serverDisconnect(mGattServerCallback, mDevice);
        verify(mNativeInterface, never()).gattServerDisconnect(SERVER_IF, mDevice, SERVER_CONN_ID);
        verify(mNativeInterface).gattServerDisconnect(SERVER_IF, mDevice, 0);
    }

    @Test
    public void serverClientConnects_noExistingBearers_stateChangedToConnected() throws Exception {
        mService.onClientConnectedFromNative(
                mDevice, BluetoothDevice.TRANSPORT_BREDR, true, SERVER_CONN_ID_2, SERVER_IF);

        verify(mServerMap)
                .addConnection(
                        eq(SERVER_IF),
                        eq(SERVER_CONN_ID_2),
                        eq(BluetoothDevice.TRANSPORT_BREDR),
                        eq(mDevice));
        verify(mGattServerCallback).onServerConnectionState(eq(0), eq(true), eq(mDevice));
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverClientConnects_bearerExistsForSameDevice_stateDoesNotChange()
            throws Exception {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.onClientConnectedFromNative(
                mDevice, BluetoothDevice.TRANSPORT_LE, true, SERVER_CONN_ID_2, SERVER_IF);

        verify(mServerMap)
                .addConnection(
                        eq(SERVER_IF),
                        eq(SERVER_CONN_ID_2),
                        eq(BluetoothDevice.TRANSPORT_LE),
                        eq(mDevice));
        verify(mGattServerCallback, never()).onServerConnectionState(anyInt(), anyBoolean(), any());
    }

    @Test
    public void serverClientDisconnects_noMoreBearersExistsForDevice_stateChangedToDisconnected()
            throws Exception {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.onClientConnectedFromNative(
                mDevice, BluetoothDevice.TRANSPORT_LE, false, SERVER_CONN_ID, SERVER_IF);

        verify(mServerMap).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID));
        assertThat(mServerConnections).isEmpty();
        verify(mGattServerCallback).onServerConnectionState(eq(0), eq(false), eq(mDevice));
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverClientDisconnects_bearerStillExistsForDevice_stateDoesNotChange()
            throws Exception {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.onClientConnectedFromNative(
                mDevice, BluetoothDevice.TRANSPORT_LE, false, SERVER_CONN_ID, SERVER_IF);

        verify(mServerMap).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID));
        verify(mServerMap, never()).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID_2));
        verify(mGattServerCallback, never()).onServerConnectionState(anyInt(), anyBoolean(), any());
    }

    @Test
    public void serverClearServices_withEmptyServiceSetForApp_noServicesDeleted() {
        mService.clearServices(mGattServerCallback);
        verify(mNativeInterface, never()).gattServerDeleteService(eq(SERVER_IF), anyInt());
    }

    @Test
    public void serverSetPreferredPhy() throws Exception {
        int txPhy = 2;
        int rxPhy = 1;
        int phyOptions = 3;

        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);
        mService.serverSetPreferredPhy(mGattServerCallback, mDevice, txPhy, rxPhy, phyOptions);
        verify(mNativeInterface)
                .gattServerSetPreferredPhy(SERVER_IF, mDevice, txPhy, rxPhy, phyOptions);
    }

    @Test
    public void serverReadPhy() {
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);
        mService.serverReadPhy(mGattServerCallback, mDevice);
        verify(mNativeInterface).gattServerReadPhy(SERVER_IF, mDevice);
    }

    @Test
    public void serverSendNotification_oneBearerConnected_bearerNotified() throws Exception {
        int handle = 2;
        boolean confirm = true;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_LE, mGattServerCallback);
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);
        verify(mNativeInterface).gattServerSendIndication(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    public void serverSendIndication_oneBearerConnected_bearerIndicated() throws Exception {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_LE, mGattServerCallback);
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);
        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverSendNotification_multipleBearersConnectedPrefLe_leTransportUsed() {
        int handle = 2;
        byte[] value = new byte[] {5, 6};

        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_BREDR, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, false, value);

        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverSendNotification_multipleBearersConnectedPrefBredr_BredrTransportUsed() {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_BREDR, mGattServerCallback);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_BREDR, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);

        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverSendNotification_twoBearersConnectedPrefAutoBredrOldest_bredrTransportUsed() {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_AUTO, mGattServerCallback);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_BREDR, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_LE, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);

        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverSendNotification_twoBearersConnectedPrefAutoLeOldest_leTransportUsed() {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_AUTO, mGattServerCallback);
        addClientConnectionRecord(SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_LE, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_BREDR, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);

        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    @Test
    public void serverSendNotification_noBearersConnected_noNotificationSent() {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);

        verify(mNativeInterface, never())
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    public void serverSendNotification_noBearersThatMatchPref_notificationSentOnOldest() {
        int handle = 2;
        boolean confirm = false;
        byte[] value = new byte[] {5, 6};

        addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_LE, mGattServerCallback);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID, BluetoothDevice.TRANSPORT_BREDR, mDevice);
        addClientConnectionRecord(
                SERVER_IF, SERVER_CONN_ID_2, BluetoothDevice.TRANSPORT_BREDR, mDevice);

        mService.sendNotification(mGattServerCallback, mDevice, handle, confirm, value);

        verify(mNativeInterface)
                .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value);
    }

    // ---------------------------------------------------------------------------------------------
    // GATT Server Utilities
    // ---------------------------------------------------------------------------------------------

    private void addServerAppRecord(int serverIf, int transport, IBluetoothGattServerCallback cb) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp = mock(ContextMap.App.class);
        serverApp.id = serverIf;
        serverApp.transport = transport;
        serverApp.callback = cb;
        doReturn(serverApp).when(mServerMap).getByCallbackId(mGattServerCallback);
        doReturn(serverApp).when(mServerMap).getById(serverIf);
    }

    private void addClientConnectionRecord(
            int id, int connId, int transport, BluetoothDevice device) {
        ContextMap.Connection conn = new ContextMap.Connection(connId, device, transport, id);
        mServerConnections.add(conn);
    }
}
