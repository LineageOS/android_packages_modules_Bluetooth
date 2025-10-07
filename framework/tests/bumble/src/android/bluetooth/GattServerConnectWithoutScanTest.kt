/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

import java.util.UUID;

/** Test cases for {@link BluetoothGattServer}. */
@RunWith(AndroidJUnit4.class)
public class GattServerConnectWithoutScanTest {
    private static final String TAG = GattServerConnectWithoutScanTest.class.getSimpleName();

    private static final int TIMEOUT_GATT_CONNECTION_MS = 2_000;
    private static final long TEST_HUB_ID = 1;
    private static final long TEST_ENDPOINT_ID = 2;

    private static final UUID TEST_SERVICE_UUID =
            UUID.fromString("00000000-0000-0000-0000-00000000000");
    private static final UUID TEST_CHARACTERISTIC_UUID =
            UUID.fromString("00010001-0000-0000-0000-000000000000");

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mBluetoothManager =
            mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportAuto() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);

        assertThat(gattServer).isNotNull();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportLE() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE);

        assertThat(gattServer).isNotNull();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/333018293")
    public void serverConnectToPublicAddress_withTransportAuto() throws Exception {
        advertiseWithBumble(OwnAddressType.PUBLIC);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToPublicAddress_withTransportLE() throws Exception {
        advertiseWithBumble(OwnAddressType.PUBLIC);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_OFFLOAD_API)
    public void serverOffloadCharacteristics() throws Exception {
        assumeTrue(
                mBluetoothAdapter.getSupportedGattOffloadCapabilities() != null
                        && mBluetoothAdapter
                                .getSupportedGattOffloadCapabilities()
                                .isServerOffloadSupported());

        advertiseWithBumble(OwnAddressType.RANDOM);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);
        assertThat(gattServer).isNotNull();

        ArgumentCaptor<BluetoothGattService> serviceCaptor =
                ArgumentCaptor.forClass(BluetoothGattService.class);
        gattServer.addService(createGattService());
        verify(mockGattServerCallback, timeout(1000))
                .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture());

        BluetoothGattService service = serviceCaptor.getValue();
        assertThat(service).isNotNull();
        assertThat(service.getCharacteristics()).isNotNull();
        assertThat(service.getCharacteristics()).isNotEmpty();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

            int status =
                    gattServer.offloadCharacteristics(
                            device,
                            service,
                            service.getCharacteristics(),
                            TEST_ENDPOINT_ID,
                            TEST_HUB_ID);
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS);

            ArgumentCaptor<GattOffloadSession> sessionCaptor =
                    ArgumentCaptor.forClass(GattOffloadSession.class);
            verify(mockGattServerCallback, timeout(10000))
                    .onCharacteristicsOffloaded(
                            any(), sessionCaptor.capture(), eq(GattOffloadSession.STATUS_SUCCESS));
            GattOffloadSession session = sessionCaptor.getValue();
            assertThat(session).isNotNull();
            Log.i(TAG, "Offload session: " + session);
            assertThat(session.getSessionId())
                    .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN);
            assertThat(session.getGattService()).isEqualTo(service);
            assertThat(session.getGattCharacteristics()).isEqualTo(service.getCharacteristics());
            assertThat(session.getEndpointId()).isEqualTo(TEST_ENDPOINT_ID);
            assertThat(session.getHubId()).isEqualTo(TEST_HUB_ID);
        } finally {
            gattServer.close();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_OFFLOAD_API)
    public void serverUnoffloadCharacteristics() throws Exception {
        assumeTrue(
                mBluetoothAdapter.getSupportedGattOffloadCapabilities() != null
                        && mBluetoothAdapter
                                .getSupportedGattOffloadCapabilities()
                                .isServerOffloadSupported());

        advertiseWithBumble(OwnAddressType.RANDOM);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);
        assertThat(gattServer).isNotNull();

        ArgumentCaptor<BluetoothGattService> serviceCaptor =
                ArgumentCaptor.forClass(BluetoothGattService.class);
        gattServer.addService(createGattService());
        verify(mockGattServerCallback, timeout(1000))
                .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture());

        BluetoothGattService service = serviceCaptor.getValue();
        assertThat(service).isNotNull();
        assertThat(service.getCharacteristics()).isNotNull();
        assertThat(service.getCharacteristics()).isNotEmpty();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

            int status =
                    gattServer.offloadCharacteristics(
                            device,
                            service,
                            service.getCharacteristics(),
                            TEST_ENDPOINT_ID,
                            TEST_HUB_ID);
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS);

            ArgumentCaptor<GattOffloadSession> sessionCaptor =
                    ArgumentCaptor.forClass(GattOffloadSession.class);
            verify(mockGattServerCallback, timeout(10000))
                    .onCharacteristicsOffloaded(
                            any(), sessionCaptor.capture(), eq(GattOffloadSession.STATUS_SUCCESS));
            GattOffloadSession session = sessionCaptor.getValue();
            assertThat(session).isNotNull();
            Log.i(TAG, "Offload session: " + session);
            assertThat(session.getSessionId())
                    .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN);

            session.close();
            verify(mockGattServerCallback, timeout(10000))
                    .onCharacteristicsUnoffloaded(
                            any(),
                            eq(session.getSessionId()),
                            eq(GattOffloadSession.STATUS_SUCCESS));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_OFFLOAD_API)
    public void serverUnoffloadCharacteristics_autoClose() throws Exception {
        assumeTrue(
                mBluetoothAdapter.getSupportedGattOffloadCapabilities() != null
                        && mBluetoothAdapter
                                .getSupportedGattOffloadCapabilities()
                                .isServerOffloadSupported());

        advertiseWithBumble(OwnAddressType.RANDOM);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);
        assertThat(gattServer).isNotNull();

        ArgumentCaptor<BluetoothGattService> serviceCaptor =
                ArgumentCaptor.forClass(BluetoothGattService.class);
        gattServer.addService(createGattService());
        verify(mockGattServerCallback, timeout(1000))
                .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture());

        BluetoothGattService service = serviceCaptor.getValue();
        assertThat(service).isNotNull();
        assertThat(service.getCharacteristics()).isNotNull();
        assertThat(service.getCharacteristics()).isNotEmpty();

        int sessionId = GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN;
        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));

            int status =
                    gattServer.offloadCharacteristics(
                            device,
                            service,
                            service.getCharacteristics(),
                            TEST_ENDPOINT_ID,
                            TEST_HUB_ID);
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS);
            ArgumentCaptor<GattOffloadSession> sessionCaptor =
                    ArgumentCaptor.forClass(GattOffloadSession.class);
            verify(mockGattServerCallback, timeout(10000))
                    .onCharacteristicsOffloaded(
                            any(), sessionCaptor.capture(), eq(GattOffloadSession.STATUS_SUCCESS));
            try (GattOffloadSession session = sessionCaptor.getValue()) {
                assertThat(session).isNotNull();
                sessionId = session.getSessionId();
                Log.i(TAG, "Offload session: " + session);
                assertThat(session.getSessionId())
                        .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN);
            } // session.close() is automatically called here
            verify(mockGattServerCallback, timeout(10000))
                    .onCharacteristicsUnoffloaded(
                            any(), eq(sessionId), eq(GattOffloadSession.STATUS_SUCCESS));
        } finally {
            gattServer.close();
        }
    }

    private static BluetoothGattService createGattService() {
        BluetoothGattService service =
                new BluetoothGattService(
                        TEST_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic characteristic =
                new BluetoothGattCharacteristic(
                        TEST_CHARACTERISTIC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(characteristic);
        return service;
    }

    private void advertiseWithBumble(OwnAddressType ownAddressType) {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(ownAddressType)
                        .build();
        mBumble.hostBlocking().advertise(request);
    }
}
