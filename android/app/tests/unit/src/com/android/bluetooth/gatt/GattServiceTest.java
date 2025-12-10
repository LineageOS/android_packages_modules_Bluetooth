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

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
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
import android.os.IBinder;
import android.os.Process;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FakeTimeProvider;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.time.Duration;
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
@RunWith(ParameterizedAndroidJunit4.class)
public class GattServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AttributionSource mSource;
    @Mock private IBluetoothGattCallback mGattCallback;
    @Mock private ContextMap<IBluetoothGattCallback> mClientMap;
    @Mock private ContextMap<IBluetoothGattServerCallback> mServerMap;
    @Mock private Set<BluetoothDevice> mReliableQueue;
    @Mock private GattNativeInterface mNativeInterface;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseManagerNativeInterface;
    @Mock private DistanceMeasurementNativeInterface mDistanceMeasurementNativeInterface;
    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;

    private TestLooper mLooper;
    private GattService mService;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final CompanionDeviceManager mCompanionDeviceManager =
            mContext.getSystemService(CompanionDeviceManager.class);

    private final BluetoothDevice mDevice = getTestDevice(109);

    private static final int TEST_RSSI = 43;

    private static final int CLIENT_IF = 12;
    private static final int CLIENT_CONN_ID = 42;

    private final ContextMap.Connection CLIENT_CONN =
            new ContextMap.Connection(CLIENT_CONN_ID, mDevice, TRANSPORT_LE, CLIENT_IF);

    private final List<ContextMap.Connection> CLIENT_CONN_LIST = Arrays.asList(CLIENT_CONN);
    private final FakeTimeProvider mTimeProvider = new FakeTimeProvider();

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_GATT_THREAD);
    }

    public GattServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        MockContentResolver mMockContentResolver = new MockContentResolver(mContext);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });

        doReturn(mContext.getPackageName()).when(mSource).getPackageName();
        doReturn(mContext.getPackageName()).when(mSource).getAttributionTag();
        doReturn(Binder.getCallingUid()).when(mSource).getUid();

        doReturn(CLIENT_CONN_LIST).when(mClientMap).getConnectionsByDevice(CLIENT_IF, mDevice);
        var clientApp = mock(ContextApp.class);
        doReturn(mGattCallback).when(clientApp).getCallback();
        doReturn(CLIENT_IF).when(clientApp).getId();
        doReturn(clientApp).when(mClientMap).getByCallbackId(mGattCallback);
        doReturn(clientApp).when(mClientMap).getById(CLIENT_IF);
        doReturn(clientApp, (Object[]) null)
                .when(mClientMap)
                .remove(anyInt(), any(ContextMap.RemoveReason.class));

        doReturn(mContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mContext.getSharedPreferences("GattServiceTestPrefs", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        doReturn(mResources).when(mAdapterService).getResources();
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();

        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, LocationManager.class);
        mockGetSystemService(mAdapterService, ActivityManager.class);
        doReturn(mSource).when(mAdapterService).getAttributionSource();

        CompanionManager mBtCompanionManager = new CompanionManager(mAdapterService);
        doReturn(mBtCompanionManager).when(mAdapterService).getCompanionManager();

        mLooper = new TestLooper();
        mService =
                new GattService(
                        mAdapterService,
                        mNativeInterface,
                        mAdvertiseManagerNativeInterface,
                        mDistanceMeasurementNativeInterface,
                        mClientMap,
                        mServerMap,
                        mReliableQueue,
                        mCompanionDeviceManager,
                        mLooper.getLooper(),
                        mTimeProvider);

        mockGetRemoteDevice(mAdapterService, mDevice);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
    }

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
                            mClientMap,
                            mServerMap,
                            mReliableQueue,
                            mCompanionDeviceManager,
                            mLooper.getLooper(),
                            mTimeProvider);
        }
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mService.cleanup();
    }

    @Test
    @DisableFlags(Flags.FLAG_LE_SUBRATE_MANAGER)
    public void subrateModeRequest_withLeSubrateManagerDisabled() {
        InOrder inOrder = inOrder(mNativeInterface);

        for (int subrateMode = BluetoothGatt.SUBRATE_MODE_OFF;
                subrateMode <= BluetoothGatt.SUBRATE_MODE_HIGH;
                subrateMode++) {
            mService.subrateModeRequest(mGattCallback, mDevice, subrateMode);

            // With no cached latency, latency for SUBRATE_MODE_OFF is 0.
            // For other modes, latency is hardcoded to 0.
            final int expectedLatency = 0;
            inOrder.verify(mNativeInterface)
                    .gattSubrateRequest(
                            eq(CLIENT_IF),
                            eq(mDevice),
                            anyInt(),
                            anyInt(),
                            eq(expectedLatency),
                            anyInt(),
                            anyInt());
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SUBRATE_MANAGER)
    public void subrateModeRequest_withLeSubrateManagerEnabled() {
        InOrder inOrder = inOrder(mNativeInterface);

        for (int subrateMode = BluetoothGatt.SUBRATE_MODE_OFF;
                subrateMode <= BluetoothGatt.SUBRATE_MODE_HIGH;
                subrateMode++) {
            mService.subrateModeRequest(mGattCallback, mDevice, subrateMode);

            inOrder.verify(mNativeInterface)
                    .gattSubrateModeRequest(eq(CLIENT_IF), eq(mDevice), eq(subrateMode));
        }
    }

    @Test
    public void subrateModeRequestDisablementLatencyParamRestore() {
        InOrder inOrder = inOrder(mNativeInterface);
        int implementInterval = 3;
        int peripheralLatency = 5;
        int supervisionTimeout = 6;
        int status = 0;

        var app = mock(ContextApp.class);
        doReturn(app).when(mClientMap).getByConnId(CLIENT_CONN_ID);
        doReturn(mGattCallback).when(app).getCallback();
        doReturn(mDevice).when(mClientMap).deviceByConnId(CLIENT_CONN_ID);

        mService.onClientConnUpdateFromNative(
                CLIENT_CONN_ID, implementInterval, peripheralLatency, supervisionTimeout, status);

        mService.subrateModeRequest(mGattCallback, mDevice, BluetoothGatt.SUBRATE_MODE_HIGH);
        if (Flags.leSubrateManager()) {
            inOrder.verify(mNativeInterface)
                    .gattSubrateModeRequest(
                            eq(CLIENT_IF), eq(mDevice), eq(BluetoothGatt.SUBRATE_MODE_HIGH));
        } else {
            inOrder.verify(mNativeInterface)
                    .gattSubrateRequest(
                            eq(CLIENT_IF),
                            eq(mDevice),
                            anyInt(),
                            anyInt(),
                            eq(0),
                            anyInt(),
                            anyInt());
        }

        mService.subrateModeRequest(mGattCallback, mDevice, BluetoothGatt.SUBRATE_MODE_OFF);
        if (Flags.leSubrateManager()) {
            inOrder.verify(mNativeInterface)
                    .gattSubrateModeRequest(
                            eq(CLIENT_IF), eq(mDevice), eq(BluetoothGatt.SUBRATE_MODE_OFF));
        } else {
            inOrder.verify(mNativeInterface)
                    .gattSubrateRequest(
                            eq(CLIENT_IF),
                            eq(mDevice),
                            anyInt(),
                            anyInt(),
                            eq(peripheralLatency),
                            anyInt(),
                            anyInt());
        }
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
        int transport = TRANSPORT_LE;

        mService.registerClient(uuid, callback, eattSupport, transport, mSource);
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
        int transport = TRANSPORT_LE;

        mService.registerClient(uuid, callback, eattSupport, transport, mSource);
        verify(mClientMap, never()).add(anyInt(), any(), any(), any(), anyInt(), any());
        verify(mNativeInterface, never())
                .gattClientRegisterApp(anyLong(), anyLong(), any(), anyBoolean());
    }

    @Test
    public void unregisterClient() {
        mService.unregisterClient(
                mGattCallback, mSource, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mClientMap).remove(CLIENT_IF, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mNativeInterface).gattClientUnregisterApp(CLIENT_IF);
    }

    @Test
    public void unregisterClientTwice() {
        // Simulate simultaneous unregistering from different threads by mocking mClientMap.
        mService.unregisterClient(
                mGattCallback, mSource, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        mService.unregisterClient(
                mGattCallback, mSource, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        verify(mClientMap, atLeastOnce())
                .remove(CLIENT_IF, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);

        // The second call is not propagated to the native stack.
        verify(mNativeInterface, times(1)).gattClientUnregisterApp(CLIENT_IF);
    }

    @Test
    public void onClientRegisteredFromNative_success_unregistersOnBinderDied() throws Exception {
        final UUID uuid = UUID.randomUUID();
        final int clientIf = 1;
        final int status = BluetoothGatt.GATT_SUCCESS;
        final IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);
        final ContextApp<IBluetoothGattCallback> app = mock(ContextApp.class);

        doReturn(callback).when(app).getCallback();
        doReturn(app).when(mClientMap).getByUuid(uuid);
        doReturn(app).when(mClientMap).getByCallbackId(callback);
        doReturn(clientIf).when(app).getId();
        // This mock is needed for unregisterClient to proceed
        doReturn(app)
                .when(mClientMap)
                .remove(eq(clientIf), eq(ContextMap.RemoveReason.REASON_BINDER_DIED));

        // Call the method under test
        mService.setAvailable(true);
        mService.onClientRegisteredFromNative(status, clientIf, uuid);

        // Verify that the app ID is set
        verify(app).setId(clientIf);

        // Verify that linkToDeath is called and capture the DeathRecipient
        ArgumentCaptor<IBinder.DeathRecipient> captor =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(app).linkToDeath(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ActionOnDeathRecipient.class);

        // Verify that the callback is invoked
        verify(callback).onClientRegistered(status);

        // Trigger binderDied on the captured recipient
        captor.getValue().binderDied();
        mLooper.dispatchAll();

        // Verify that unregisterClient logic is executed
        verify(mNativeInterface).gattClientUnregisterApp(clientIf);
    }

    @Test
    public void clientConnect() throws Exception {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = false;
        int transport = 2;
        boolean opportunistic = true;
        boolean isAutomaticMtuEnabled = false;

        mService.clientConnect(
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                isAutomaticMtuEnabled,
                mSource);

        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        0,
                        false,
                        isAutomaticMtuEnabled);
    }

    @Test
    public void clientConnect_withCrossDeviceAccessServiceTag_setsPreferRelaxMode() {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = false;
        int transport = 2;
        boolean opportunistic = true;
        boolean isAutomaticMtuEnabled = false;

        AttributionSource source =
                new AttributionSource.Builder(Process.myUid())
                        .setPackageName("com.test.package")
                        .setAttributionTag("crossdeviceaccessservice")
                        .build();

        mService.clientConnect(
                mGattCallback,
                mDevice,
                addressType,
                isDirect,
                transport,
                opportunistic,
                isAutomaticMtuEnabled,
                source);

        verify(mNativeInterface)
                .gattClientConnect(
                        CLIENT_IF,
                        mDevice,
                        addressType,
                        isDirect,
                        transport,
                        opportunistic,
                        0,
                        true /* preferRelaxMode */,
                        isAutomaticMtuEnabled);
    }

    @Test
    public void clientConnectOverLeFailed() throws Exception {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = TRANSPORT_LE;
        boolean opportunistic = false;
        boolean isAutomaticMtuEnabled = false;

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
                isAutomaticMtuEnabled,
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
                        0,
                        false,
                        isAutomaticMtuEnabled);

        mService.onConnectedFromNative(
                CLIENT_IF, 0, transport, BluetoothGatt.GATT_CONNECTION_TIMEOUT, mDevice);
        verify(mAdapterService).notifyGattClientConnectFailed(anyInt(), any());
    }

    @Test
    public void clientConnectDisconnectOverLe() throws Exception {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = TRANSPORT_LE;
        boolean opportunistic = false;
        boolean isAutomaticMtuEnabled = false;

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
                isAutomaticMtuEnabled,
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
                        0,
                        false,
                        isAutomaticMtuEnabled);

        mService.onConnectedFromNative(
                CLIENT_IF, 15, transport, BluetoothGatt.GATT_SUCCESS, mDevice);
        mService.clientDisconnect(mGattCallback, mDevice, mSource);

        verify(mAdapterService).notifyGattClientDisconnect(anyInt(), any());
    }

    @Test
    public void clientConnectOverLeDisconnectedByRemote() throws Exception {
        int addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        boolean isDirect = true;
        int transport = TRANSPORT_LE;
        boolean opportunistic = false;
        boolean isAutomaticMtuEnabled = false;

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
                isAutomaticMtuEnabled,
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
                        0,
                        false,
                        isAutomaticMtuEnabled);

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

        mService.disconnectAll(mSource);
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
    public void clientReadRemoteRssi_entryIsNotEmpty_elapsedTimeIsLessThanThrottleMs()
            throws Exception {
        mService.mRssiCache.put(
                mDevice.getAddress(),
                new com.android.bluetooth.gatt.GattService.RssiCacheEntry(
                        mTimeProvider.elapsedRealtime(), TEST_RSSI));

        // 25ms is less than the default throttle ms of 75ms
        mTimeProvider.advanceTime(Duration.ofMillis(25));
        mService.readRemoteRssi(mGattCallback, mDevice);

        verify(mGattCallback).onReadRemoteRssi(mDevice, TEST_RSSI, BluetoothGatt.GATT_SUCCESS);
        verify(mNativeInterface, never()).gattClientReadRemoteRssi(CLIENT_IF, mDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_READ_RSSI_THROTTLING)
    public void clientReadRemoteRssi_entryIsNotEmpty_elapsedTimeIsMoreThanThrottleMs() {
        mService.mRssiCache.put(
                mDevice.getAddress(),
                new com.android.bluetooth.gatt.GattService.RssiCacheEntry(
                        mTimeProvider.elapsedRealtime(), TEST_RSSI));

        // 100ms is more than the default throttle ms of 75ms
        mTimeProvider.advanceTime(Duration.ofMillis(100));
        mService.readRemoteRssi(mGattCallback, mDevice);

        verify(mNativeInterface).gattClientReadRemoteRssi(CLIENT_IF, mDevice);
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

        mService.readCharacteristic(mGattCallback, mDevice, handle, authReq);
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

        mService.readDescriptor(mGattCallback, mDevice, handle, authReq);
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

        mService.registerForNotification(mGattCallback, mDevice, handle, enable);

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

        var app = mock(ContextApp.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        doReturn(app).when(mClientMap).getByConnId(CLIENT_CONN_ID);
        doReturn(callback).when(app).getCallback();

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
        assertThat(mService.getRestrictedHandles().get(CLIENT_CONN_ID)).contains(hidInfoChar.id);
        assertThat(mService.getRestrictedHandles().get(CLIENT_CONN_ID))
                .doesNotContain(randomChar.id);

        mService.onDisconnectedFromNative(
                CLIENT_IF, CLIENT_CONN_ID, TRANSPORT_LE, BluetoothGatt.GATT_SUCCESS, mDevice);
        assertThat(mService.getRestrictedHandles()).doesNotContainKey(CLIENT_CONN_ID);
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MESSAGING_PERMISSIONS)
    public void clientAncsAccessPermissionRejected() throws Exception {
        ArrayList<GattDbElement> db = new ArrayList<>();

        var app = mock(ContextApp.class);
        IBluetoothGattCallback callback = mock(IBluetoothGattCallback.class);

        doReturn(app).when(mClientMap).getByConnId(CLIENT_CONN_ID);
        doReturn(callback).when(app).getCallback();

        GattDbElement ancsService =
                GattDbElement.createPrimaryService(
                        UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0"));
        ancsService.id = 1;

        db.add(ancsService);

        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mAdapterService)
                .getMessageAccessPermission(any(BluetoothDevice.class));

        mService.onGetGattDbFromNative(CLIENT_CONN_ID, db);
        // ANCS should be restricted
        assertThat(mService.getRestrictedHandles().get(CLIENT_CONN_ID)).contains(ancsService.id);

        mService.onDisconnectedFromNative(
                CLIENT_IF, CLIENT_CONN_ID, TRANSPORT_LE, BluetoothGatt.GATT_SUCCESS, mDevice);
        assertThat(mService.getRestrictedHandles()).doesNotContainKey(CLIENT_CONN_ID);
    }
}
