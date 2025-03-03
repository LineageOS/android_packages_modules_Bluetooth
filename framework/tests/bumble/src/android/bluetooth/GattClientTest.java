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

package android.bluetooth;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.Context;
import android.os.SystemProperties;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;

import com.google.protobuf.ByteString;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;

import pandora.GattProto.AttStatusCode;
import pandora.GattProto.GattCharacteristicParams;
import pandora.GattProto.GattServiceParams;
import pandora.GattProto.IndicateOnCharacteristicRequest;
import pandora.GattProto.IndicateOnCharacteristicResponse;
import pandora.GattProto.NotifyOnCharacteristicRequest;
import pandora.GattProto.NotifyOnCharacteristicResponse;
import pandora.GattProto.RegisterServiceRequest;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.OwnAddressType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RunWith(TestParameterInjector.class)
public class GattClientTest {
    private static final String TAG = GattClientTest.class.getSimpleName();

    private static final int ANDROID_MTU = 517;
    private static final int MTU_REQUESTED = 23;
    private static final int ANOTHER_MTU_REQUESTED = 42;
    private static final String NOTIFICATION_VALUE = "hello world";

    private static final UUID GAP_UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final UUID TEST_SERVICE_UUID =
            UUID.fromString("00000000-0000-0000-0000-00000000000");
    private static final UUID TEST_CHARACTERISTIC_UUID =
            UUID.fromString("00010001-0000-0000-0000-000000000000");

    private static final int MIN_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.min_connection_interval_relaxed", 0x0018);
    private static final int MAX_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.max_connection_interval_relaxed", 0x0028);

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 2)
    public final EnableBluetoothRule mEnableBluetoothRule = new EnableBluetoothRule(false, true);

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();

    private Host mHost;
    private BluetoothDevice mRemoteLeDevice;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();

        mHost = new Host(mContext);
        mRemoteLeDevice =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        mRemoteLeDevice.removeBond();
    }

    @After
    public void tearUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        if (bondedDevices.contains(mRemoteLeDevice)) {
            mRemoteLeDevice.removeBond();
        }
        mHost.close();
    }

    @Test
    public void directConnectGattAfterClose() throws Exception {
        advertiseWithBumble();

        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = device.connectGatt(mContext, false, gattCallback);
        gatt.close();

        // Save the number of call in the callback to be checked later
        Collection<Invocation> invocations = mockingDetails(gattCallback).getInvocations();
        int numberOfCalls = invocations.size();

        BluetoothGattCallback gattCallback2 = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt2 = device.connectGatt(mContext, false, gattCallback2);
        verify(gattCallback2, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        disconnectAndWaitDisconnection(gatt2, gattCallback2);

        // After reconnecting, verify the first callback was not invoked.
        Collection<Invocation> invocationsAfterSomeTimes =
                mockingDetails(gattCallback).getInvocations();
        int numberOfCallsAfterSomeTimes = invocationsAfterSomeTimes.size();
        assertThat(numberOfCallsAfterSomeTimes).isEqualTo(numberOfCalls);
    }

    @Test
    public void fullGattClientLifecycle(@TestParameter boolean autoConnect) {
        if (autoConnect) {
            createLeBondAndWaitBonding(mRemoteLeDevice);
        }
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback, autoConnect);
        disconnectAndWaitDisconnection(gatt, gattCallback);
    }

    @RequiresFlagsEnabled(Flags.FLAG_INITIAL_CONN_PARAMS_P1)
    @Test
    public void onConnectionUpdatedIsCalledOnlyOnceForRelaxingConnectionParameters_noGattCache() {
        int aggressiveConnectionThreshold =
                SystemProperties.getInt("bluetooth.core.le.aggressive_connection_threshold", 2);
        // This test is for the case where aggressive initial parameters are used.
        assumeThat(aggressiveConnectionThreshold, greaterThan(0));

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        ArgumentCaptor<Integer> connectionIntervalCaptor = ArgumentCaptor.forClass(Integer.class);

        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback, false);

        // Wait until service discovery is done and parameters are relaxed.
        verify(gattCallback, timeout(10_000).times(1))
                .onConnectionUpdated(
                        any(), connectionIntervalCaptor.capture(), anyInt(), anyInt(), anyInt());

        List<Integer> capturedConnectionIntervals = connectionIntervalCaptor.getAllValues();
        assertThat(capturedConnectionIntervals).hasSize(1);

        // Since aggressive parameters are used in the initial connection,
        // there should be only one connection parameters update event for relaxing them.
        int relaxedConnIntervalAfterServiceDiscovery = capturedConnectionIntervals.get(0);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtLeast(MIN_CONN_INTERVAL_RELAXED);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtMost(MAX_CONN_INTERVAL_RELAXED);

        disconnectAndWaitDisconnection(gatt, gattCallback);
    }

    @Test
    public void reconnectExistingClient() throws Exception {
        advertiseWithBumble();

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        InOrder inOrder = inOrder(gattCallback);

        BluetoothGatt gatt = mRemoteLeDevice.connectGatt(mContext, false, gattCallback);
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

        gatt.disconnect();
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(STATE_DISCONNECTED));

        gatt.connect();
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

        // TODO(323889717): Fix callback being called after gatt.close(). This disconnect shouldn't
        //  be necessary.
        gatt.disconnect();
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(STATE_DISCONNECTED));
        gatt.close();
    }

    @Test
    public void clientGattDiscoverServices() throws Exception {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            gatt.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            assertThat(gatt.getServices().stream().map(BluetoothGattService::getUuid))
                    .contains(GAP_UUID);

        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void clientGattReadCharacteristics() throws Exception {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            gatt.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            BluetoothGattService firstService = gatt.getServices().get(0);

            BluetoothGattCharacteristic firstCharacteristic =
                    firstService.getCharacteristics().get(0);

            gatt.readCharacteristic(firstCharacteristic);

            verify(gattCallback, timeout(5000)).onCharacteristicRead(any(), any(), any(), anyInt());

        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void clientGattWriteCharacteristic() throws Exception {
        registerGattService();

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            gatt.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            BluetoothGattCharacteristic characteristic =
                    gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID);

            byte[] newValue = new byte[] {13};

            gatt.writeCharacteristic(
                    characteristic, newValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            verify(gattCallback, timeout(5000))
                    .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS));

        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void clientGattNotifyOrIndicateCharacteristic(@TestParameter boolean isIndicate)
            throws Exception {
        registerNotificationIndicationGattService(isIndicate);

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            gatt.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            BluetoothGattCharacteristic characteristic =
                    gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID);

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            descriptor.setValue(
                    isIndicate
                            ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            assertThat(gatt.writeDescriptor(descriptor)).isTrue();

            verify(gattCallback, timeout(5000))
                    .onDescriptorWrite(any(), eq(descriptor), eq(GATT_SUCCESS));

            gatt.setCharacteristicNotification(characteristic, true);

            if (isIndicate) {
                Log.i(TAG, "Triggering characteristic indication");
                triggerCharacteristicIndication(characteristic.getInstanceId());
            } else {
                Log.i(TAG, "Triggering characteristic notification");
                triggerCharacteristicNotification(characteristic.getInstanceId());
            }

            verify(gattCallback, timeout(5000))
                    .onCharacteristicChanged(
                            any(), any(), eq(NOTIFICATION_VALUE.getBytes(StandardCharsets.UTF_8)));

        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void connectTimeout() {
        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);

        // Connecting to a device not advertising results in connection timeout after 30 seconds
        device.connectGatt(mContext, false, gattCallback);

        verify(gattCallback, timeout(35000))
                .onConnectionStateChange(
                        any(), eq(BluetoothGatt.GATT_CONNECTION_TIMEOUT), eq(STATE_DISCONNECTED));
    }

    @Test
    public void consecutiveWriteCharacteristicFails_thenSuccess() throws Exception {
        registerGattService();

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGattCallback gattCallback2 = mock(BluetoothGattCallback.class);

        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);
        BluetoothGatt gatt2 = connectGattAndWaitConnection(gattCallback2);

        try {
            gatt.discoverServices();
            gatt2.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));
            verify(gattCallback2, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            BluetoothGattCharacteristic characteristic =
                    gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID);

            BluetoothGattCharacteristic characteristic2 =
                    gatt2.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID);

            byte[] newValue = new byte[] {13};

            gatt.writeCharacteristic(
                    characteristic, newValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            // TODO: b/324355496 - Make the test consistent when Bumble supports holding a response.
            // Skip the test if the second write succeeded.
            Assume.assumeFalse(
                    gatt2.writeCharacteristic(
                                    characteristic2,
                                    newValue,
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            == BluetoothStatusCodes.SUCCESS);

            verify(gattCallback, timeout(5000))
                    .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS));
            verify(gattCallback2, never())
                    .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS));

            assertThat(
                            gatt2.writeCharacteristic(
                                    characteristic2,
                                    newValue,
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
                    .isEqualTo(BluetoothStatusCodes.SUCCESS);
            verify(gattCallback2, timeout(5000))
                    .onCharacteristicWrite(any(), eq(characteristic2), eq(GATT_SUCCESS));
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
            disconnectAndWaitDisconnection(gatt2, gattCallback2);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_FIX_MULTIPLE_DIRECT_CONNECT)
    public void connectMultiple_closeOne_shouldSuccess() {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGattCallback gattCallback2 = mock(BluetoothGattCallback.class);

        advertiseWithBumble();
        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        BluetoothGatt gatt = device.connectGatt(mContext, false, gattCallback);
        BluetoothGatt gatt2 = device.connectGatt(mContext, false, gattCallback2);

        try {
            gatt.disconnect();
            gatt.close();

            verify(gattCallback2, timeout(1000))
                    .onConnectionStateChange(eq(gatt2), eq(GATT_SUCCESS), eq(STATE_CONNECTED));
        } finally {
            gatt2.disconnect();
            gatt2.close();
        }
    }

    private void registerGattService() {
        GattCharacteristicParams characteristicParams =
                GattCharacteristicParams.newBuilder()
                        .setProperties(
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_WRITE)
                        .setUuid(TEST_CHARACTERISTIC_UUID.toString())
                        .build();

        GattServiceParams serviceParams =
                GattServiceParams.newBuilder()
                        .addCharacteristics(characteristicParams)
                        .setUuid(TEST_SERVICE_UUID.toString())
                        .build();

        RegisterServiceRequest request =
                RegisterServiceRequest.newBuilder().setService(serviceParams).build();

        mBumble.gattBlocking().registerService(request);
    }

    private void registerNotificationIndicationGattService(boolean isIndicate) {
        GattCharacteristicParams characteristicParams =
                GattCharacteristicParams.newBuilder()
                        .setProperties(
                                isIndicate
                                        ? BluetoothGattCharacteristic.PROPERTY_INDICATE
                                        : BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                        .setUuid(TEST_CHARACTERISTIC_UUID.toString())
                        .build();

        GattServiceParams serviceParams =
                GattServiceParams.newBuilder()
                        .addCharacteristics(characteristicParams)
                        .setUuid(TEST_SERVICE_UUID.toString())
                        .build();

        RegisterServiceRequest request =
                RegisterServiceRequest.newBuilder().setService(serviceParams).build();

        mBumble.gattBlocking().registerService(request);
    }

    private void triggerCharacteristicNotification(int instanceId) {
        NotifyOnCharacteristicRequest req =
                NotifyOnCharacteristicRequest.newBuilder()
                        .setHandle(instanceId)
                        .setValue(ByteString.copyFromUtf8(NOTIFICATION_VALUE))
                        .build();
        NotifyOnCharacteristicResponse resp = mBumble.gattBlocking().notifyOnCharacteristic(req);
        assertThat(resp.getStatus()).isEqualTo(AttStatusCode.SUCCESS);
    }

    private void triggerCharacteristicIndication(int instanceId) {
        IndicateOnCharacteristicRequest req =
                IndicateOnCharacteristicRequest.newBuilder()
                        .setHandle(instanceId)
                        .setValue(ByteString.copyFromUtf8(NOTIFICATION_VALUE))
                        .build();
        IndicateOnCharacteristicResponse resp =
                mBumble.gattBlocking().indicateOnCharacteristic(req);
        assertThat(resp.getStatus()).isEqualTo(AttStatusCode.SUCCESS);
    }

    private void advertiseWithBumble() {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();

        StreamObserverSpliterator<AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();

        mBumble.host().advertise(request, responseObserver);
    }

    private BluetoothGatt connectGattAndWaitConnection(BluetoothGattCallback callback) {
        return connectGattAndWaitConnection(callback, /* autoConnect= */ false);
    }

    private BluetoothGatt connectGattAndWaitConnection(
            BluetoothGattCallback callback, boolean autoConnect) {
        final int status = GATT_SUCCESS;
        final int state = STATE_CONNECTED;

        advertiseWithBumble();

        BluetoothGatt gatt = mRemoteLeDevice.connectGatt(mContext, autoConnect, callback);
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), eq(status), eq(state));

        return gatt;
    }

    /** Tries to connect GATT, it could fail and return null. */
    private BluetoothGatt tryConnectGatt(BluetoothGattCallback callback, boolean autoConnect) {
        advertiseWithBumble();

        BluetoothGatt gatt = mRemoteLeDevice.connectGatt(mContext, autoConnect, callback);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> stateCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(callback, timeout(1000))
                .onConnectionStateChange(eq(gatt), statusCaptor.capture(), stateCaptor.capture());

        if (statusCaptor.getValue() == GATT_SUCCESS && stateCaptor.getValue() == STATE_CONNECTED) {
            return gatt;
        }
        gatt.close();
        return null;
    }

    private void disconnectAndWaitDisconnection(
            BluetoothGatt gatt, BluetoothGattCallback callback) {
        final int state = STATE_DISCONNECTED;
        gatt.disconnect();
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), anyInt(), eq(state));

        gatt.close();
        gatt = null;
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_CALLBACK_ON_FAILURE)
    public void requestMtu_invalidParameter_isFalse() {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            assertThat(gatt.requestMtu(1024)).isTrue();
            // status should be 0x87 (GATT_ILLEGAL_PARAMETER) but not defined.
            verify(gattCallback, timeout(5000).atLeast(1))
                    .onMtuChanged(
                            eq(gatt),
                            anyInt(),
                            AdditionalMatchers.not(eq(BluetoothGatt.GATT_SUCCESS)));
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void requestMtu_once_isSuccess() {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue();
            // Check that only the ANDROID_MTU is returned, not the MTU_REQUESTED
            verify(gattCallback, timeout(5000))
                    .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS));
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void requestMtu_multipleTimeFromSameClient_isRejected() {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue();
            // Check that only the ANDROID_MTU is returned, not the MTU_REQUESTED
            verify(gattCallback, timeout(5000))
                    .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS));

            assertThat(gatt.requestMtu(ANOTHER_MTU_REQUESTED)).isTrue();
            verify(gattCallback, timeout(5000).times(2))
                    .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS));
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    @Test
    public void requestMtu_onceFromMultipleClient_secondIsSuccessWithoutUpdate() {
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue();
            verify(gattCallback, timeout(5000))
                    .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS));

            BluetoothGattCallback gattCallback2 = mock(BluetoothGattCallback.class);
            BluetoothGatt gatt2 = connectGattAndWaitConnection(gattCallback2);
            try {
                // first callback because there is already a connected device
                verify(gattCallback2, timeout(9000))
                        .onMtuChanged(eq(gatt2), eq(ANDROID_MTU), eq(GATT_SUCCESS));
                assertThat(gatt2.requestMtu(ANOTHER_MTU_REQUESTED)).isTrue();
                verify(gattCallback2, timeout(9000).times(2))
                        .onMtuChanged(eq(gatt2), eq(ANDROID_MTU), eq(GATT_SUCCESS));
            } finally {
                disconnectAndWaitDisconnection(gatt2, gattCallback2);
            }
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback);
        }
    }

    // Check if we can have 100 simultaneous clients
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_CLIENT_DYNAMIC_ALLOCATION)
    public void connectGatt_multipleClients() {
        registerGattService();

        List<BluetoothGatt> gatts = new ArrayList<>();
        boolean failed = false;
        final int repeatTimes = 100;

        try {
            for (int i = 0; i < repeatTimes; i++) {
                BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
                BluetoothGatt gatt = tryConnectGatt(gattCallback, false);
                // If it fails, close an existing gatt instance and try again.
                if (gatt == null) {
                    failed = true;
                    BluetoothGatt connectedGatt = gatts.remove(0);
                    connectedGatt.disconnect();
                    connectedGatt.close();
                    gattCallback = mock(BluetoothGattCallback.class);
                    gatt = connectGattAndWaitConnection(gattCallback);
                }
                gatts.add(gatt);
                gatt.discoverServices();
                verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

                BluetoothGattCharacteristic characteristic =
                        gatt.getService(TEST_SERVICE_UUID)
                                .getCharacteristic(TEST_CHARACTERISTIC_UUID);
                gatt.readCharacteristic(characteristic);
                verify(gattCallback, timeout(5000))
                        .onCharacteristicRead(any(), any(), any(), anyInt());
            }
        } finally {
            for (BluetoothGatt gatt : gatts) {
                gatt.disconnect();
                gatt.close();
            }
        }
        // We should fail because we reached the limit.
        assertThat(failed).isTrue();
    }

    @Test
    public void writeCharacteristic_disconnected_shouldNotCrash() {
        registerGattService();

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);

        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback);

        try {
            gatt.discoverServices();
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS));

            BluetoothGattCharacteristic characteristic =
                    gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID);

            byte[] newValue = new byte[] {13};

            gatt.writeCharacteristic(
                    characteristic, newValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            // TODO(b/370607862): disconnect from the remote
            gatt.disconnect();
            gatt.close();
        } finally {
            // it's okay to close twice.
            gatt.close();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_UNREGISTER_GATT_CLIENT_DISCONNECTED)
    public void connectAndDisconnectManyClientsWithoutClose() throws Exception {
        advertiseWithBumble();

        List<BluetoothGatt> gatts = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
                InOrder inOrder = inOrder(gattCallback);

                BluetoothGatt gatt = mRemoteLeDevice.connectGatt(mContext, false, gattCallback);
                gatts.add(gatt);

                inOrder.verify(gattCallback, timeout(1000))
                        .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

                gatt.disconnect();
                inOrder.verify(gattCallback, timeout(1000))
                        .onConnectionStateChange(any(), anyInt(), eq(STATE_DISCONNECTED));

                gatt.connect();
                inOrder.verify(gattCallback, timeout(1000))
                        .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

                gatt.disconnect();
                inOrder.verify(gattCallback, timeout(1000))
                        .onConnectionStateChange(any(), anyInt(), eq(STATE_DISCONNECTED));
            }
        } finally {
            for (BluetoothGatt gatt : gatts) {
                gatt.close();
            }
        }
    }

    private void createLeBondAndWaitBonding(BluetoothDevice device) {
        advertiseWithBumble();
        mHost.createBondAndVerify(device);
    }
}
