/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.ByteString;

import io.grpc.Deadline;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import pandora.GattProto;
import pandora.GattProto.DiscoverServiceByUuidRequest;
import pandora.GattProto.DiscoverServicesResponse;
import pandora.GattProto.GattService;
import pandora.HostProto;
import pandora.HostProto.Connection;
import pandora.HostProto.ScanRequest;
import pandora.HostProto.ScanningResponse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for Private GATT advertising */
@RunWith(AndroidJUnit4.class)
public class PrivateGattAdvertisingTest {
    private static final String TAG = PrivateGattAdvertisingTest.class.getSimpleName();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private static final int ADVERTISING_TIMEOUT_MS = 2_000;

    private static final int GATT_CONN_TIMEOUT_MS = 2_000;

    private static final UUID TEST_GATT_SERVICE_UUID_1 =
            UUID.fromString("00000000-0000-0000-0000-000000011111");

    private static final UUID TEST_GATT_SERVICE_UUID_2 =
            UUID.fromString("00000000-0000-0000-0000-000000022222");

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mBluetoothManager =
            mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();
    private final BluetoothLeAdvertiser mLeAdvertiser =
            mBluetoothAdapter.getBluetoothLeAdvertiser();
    private final List<AdvertisingSetCallback> mAdvertisingSetCallbacksToClear = new ArrayList<>();

    @After
    public void tearDown() {
        for (AdvertisingSetCallback callback : mAdvertisingSetCallbacksToClear) {
            mLeAdvertiser.stopAdvertisingSet(callback);
        }
    }

    @RequiresFlagsEnabled(Flags.FLAG_FIX_PRIVATE_GATT_ADVERTISEMENT)
    @Test
    public void privateGattAdvertisingWithNormalAdvertising() throws Exception {
        // Starts private GATT advertisement, and get address of it.
        BluetoothGattServerCallback privateGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer privateGattServer =
                mBluetoothManager.openGattServer(mContext, privateGattServerCallback);
        privateGattServer.addService(
                new BluetoothGattService(
                        TEST_GATT_SERVICE_UUID_1, BluetoothGattService.SERVICE_TYPE_PRIMARY));
        assertThat(startAdvertisingWithServiceUuid(privateGattServer, TEST_GATT_SERVICE_UUID_1))
                .isNotNull();
        ScanningResponse scanResultForPrivateGattAdv = scanWithBumble(TEST_GATT_SERVICE_UUID_1);
        ByteString privateAdvAddress = scanResultForPrivateGattAdv.getRandom();
        verify(privateGattServerCallback, never())
                .onConnectionStateChange(any(), anyInt(), anyInt());

        // Starts a normal advertisement, and get address of it.
        BluetoothGattServerCallback normalGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer normalGattServer =
                mBluetoothManager.openGattServer(mContext, normalGattServerCallback);
        normalGattServer.addService(
                new BluetoothGattService(
                        TEST_GATT_SERVICE_UUID_2, BluetoothGattService.SERVICE_TYPE_PRIMARY));
        assertThat(
                        startAdvertisingWithServiceUuid(
                                null /* normal GATT */, TEST_GATT_SERVICE_UUID_2))
                .isNotNull();
        ScanningResponse scanResultForNormalAdv = scanWithBumble(TEST_GATT_SERVICE_UUID_2);
        ByteString normalAdvAddress = scanResultForNormalAdv.getRandom();
        verify(normalGattServerCallback, never())
                .onConnectionStateChange(any(), anyInt(), anyInt());

        // Make the Bumble connect to the private GATT advertisement.
        // Bumble should be able to see the services in private GATT server,
        // but not the services in normal GATT server.
        HostProto.ConnectLEResponse connectLEResponse =
                mBumble.hostBlocking()
                        .connectLE(
                                HostProto.ConnectLERequest.newBuilder()
                                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                        .setRandom(privateAdvAddress)
                                        .build());
        verify(privateGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED));

        // TODO(b/411294650): The normal GATT server's onConnectionStateChange() shouldn't be
        // called,
        //       However this is being called. Make connection callbacks isolated.
        // verify(normalGattServerCallback, never()).onConnectionStateChange(any(), anyInt(),
        // anyInt());

        // The service UUIDs from private GATT server should only contain the private services.
        List<UUID> serviceUuids = getAllServiceUUIDs(connectLEResponse.getConnection());
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1);
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_2);

        // Now disconnect from private GATT advertisement, and connect to normal advertisement
        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(connectLEResponse.getConnection())
                                .build());
        verify(privateGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED));

        Mockito.clearInvocations(normalGattServerCallback);
        connectLEResponse =
                mBumble.hostBlocking()
                        .connectLE(
                                HostProto.ConnectLERequest.newBuilder()
                                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                        .setRandom(normalAdvAddress)
                                        .build());
        verify(normalGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED));

        // The service UUIDs from normal GATT server should contain all services.
        serviceUuids = getAllServiceUUIDs(connectLEResponse.getConnection());
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1);
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_2);

        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(connectLEResponse.getConnection())
                                .build());
        verify(normalGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED));
    }

    @RequiresFlagsEnabled(Flags.FLAG_FIX_PRIVATE_GATT_ADVERTISEMENT)
    @Test
    public void twoPrivateGattAdvertising() {
        // Starts private GATT advertisement 1, and get address of it.
        BluetoothGattServerCallback privateGattServer1Callback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer privateGattServer1 =
                mBluetoothManager.openGattServer(mContext, privateGattServer1Callback);
        privateGattServer1.addService(
                new BluetoothGattService(
                        TEST_GATT_SERVICE_UUID_1, BluetoothGattService.SERVICE_TYPE_PRIMARY));
        assertThat(startAdvertisingWithServiceUuid(privateGattServer1, TEST_GATT_SERVICE_UUID_1))
                .isNotNull();
        ScanningResponse scanResultForPrivateGattAdv1 = scanWithBumble(TEST_GATT_SERVICE_UUID_1);
        ByteString privateAdv1Address = scanResultForPrivateGattAdv1.getRandom();
        verify(privateGattServer1Callback, never())
                .onConnectionStateChange(any(), anyInt(), anyInt());

        // Starts private GATT advertisement 2, and get address of it.
        BluetoothGattServerCallback privateGattServer2Callback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer privateGattServer2 =
                mBluetoothManager.openGattServer(mContext, privateGattServer2Callback);
        privateGattServer2.addService(
                new BluetoothGattService(
                        TEST_GATT_SERVICE_UUID_2, BluetoothGattService.SERVICE_TYPE_PRIMARY));
        assertThat(startAdvertisingWithServiceUuid(privateGattServer2, TEST_GATT_SERVICE_UUID_2))
                .isNotNull();
        ScanningResponse scanResultForPrivateGattAdv2 = scanWithBumble(TEST_GATT_SERVICE_UUID_2);
        ByteString privateAdv2Address = scanResultForPrivateGattAdv2.getRandom();
        verify(privateGattServer2Callback, never())
                .onConnectionStateChange(any(), anyInt(), anyInt());

        // Make the Bumble connect to the private GATT advertisement 1.
        HostProto.ConnectLEResponse connectLEResponse =
                mBumble.hostBlocking()
                        .connectLE(
                                HostProto.ConnectLERequest.newBuilder()
                                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                        .setRandom(privateAdv1Address)
                                        .build());
        verify(privateGattServer1Callback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED));

        // TODO(b/411294650): The private GATT server 2's onConnectionStateChange() shouldn't be
        // called,
        //       However this is being called. Make connection callbacks isolated.
        // verify(privateGattServer2Callback, never()).onConnectionStateChange(any(), anyInt(),
        // anyInt());

        // Bumble should be able to see the services in private GATT server 1,
        // but not the services in private GATT server 2.
        List<UUID> serviceUuids = getAllServiceUUIDs(connectLEResponse.getConnection());
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1);
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_2);

        // Now disconnect from private GATT advertisement 1,
        // and connect to private GATT advertisement 2.
        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(connectLEResponse.getConnection())
                                .build());
        verify(privateGattServer1Callback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED));

        Mockito.clearInvocations(privateGattServer2Callback);
        connectLEResponse =
                mBumble.hostBlocking()
                        .connectLE(
                                HostProto.ConnectLERequest.newBuilder()
                                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                        .setRandom(privateAdv2Address)
                                        .build());
        verify(privateGattServer2Callback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED));

        // Bumble should be able to see the services in private GATT server 2,
        // but not the services in private GATT server 1.
        serviceUuids = getAllServiceUUIDs(connectLEResponse.getConnection());
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_2);
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_1);

        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(connectLEResponse.getConnection())
                                .build());
        verify(privateGattServer2Callback, timeout(GATT_CONN_TIMEOUT_MS))
                .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED));
    }

    /** Return a {@link ScanningResponse} whose advertising data includes given UUID. */
    private ScanningResponse scanWithBumble(UUID uuid) {
        StreamObserverSpliterator<ScanRequest, ScanningResponse> responseObserver =
                new StreamObserverSpliterator<>();
        Deadline deadline = Deadline.after(ADVERTISING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        mBumble.host()
                .withDeadline(deadline)
                .scan(ScanRequest.newBuilder().build(), responseObserver);
        Iterator<ScanningResponse> responseObserverIterator = responseObserver.iterator();
        while (true) {
            ScanningResponse scanningResponse = responseObserverIterator.next();
            if (scanningResponse
                    .getData()
                    .getCompleteServiceClassUuids128List()
                    .contains(uuid.toString())) {
                responseObserver.cancel("Canceling scan request");
                return scanningResponse;
            }
        }
    }

    /**
     * Starts an advertising set with a service UUID included in advertising data. If the gattServer
     * is not null, then private GATT advertisement associated with the server will be started.
     */
    private AdvertisingSet startAdvertisingWithServiceUuid(
            BluetoothGattServer gattServer, UUID serviceUuid) {
        CompletableFuture<AdvertisingSet> future = new CompletableFuture<>();

        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder()
                        .setOwnAddressType(
                                AdvertisingSetParameters.ADDRESS_TYPE_RANDOM_NON_RESOLVABLE)
                        .setConnectable(true)
                        .build();
        AdvertiseData advertiseData =
                new AdvertiseData.Builder()
                        .addServiceUuid(ParcelUuid.fromString(serviceUuid.toString()))
                        .build();
        AdvertisingSetCallback advertisingSetCallback =
                new AdvertisingSetCallback() {
                    @Override
                    public void onAdvertisingSetStarted(
                            AdvertisingSet advertisingSet, int txPower, int status) {
                        Log.i(
                                TAG,
                                "onAdvertisingSetStarted "
                                        + " txPower:"
                                        + txPower
                                        + " status:"
                                        + status);
                        future.complete(advertisingSet);
                    }
                };

        mLeAdvertiser.startAdvertisingSet(
                parameters,
                advertiseData,
                null,
                null,
                null,
                0,
                0,
                gattServer,
                advertisingSetCallback,
                new Handler(Looper.getMainLooper()));
        mAdvertisingSetCallbacksToClear.add(advertisingSetCallback);

        try {
            return future.get();
        } catch (Exception e) {
            Log.i(TAG, "startAdvertisingWithServiceUuid failed.", e);
            return null;
        }
    }

    List<UUID> getAllServiceUUIDs(Connection leConnection) {
        StreamObserverSpliterator<DiscoverServiceByUuidRequest, DiscoverServicesResponse>
                responseObserver = new StreamObserverSpliterator<>();

        mBumble.gatt()
                .discoverServices(
                        GattProto.DiscoverServicesRequest.newBuilder()
                                .setConnection(leConnection)
                                .build(),
                        responseObserver);

        Iterator<DiscoverServicesResponse> responseObserverIterator = responseObserver.iterator();
        DiscoverServicesResponse response;
        while (true) {
            response = responseObserverIterator.next();
            if (response.getServicesCount() > 0) {
                break;
            }
        }

        List<UUID> uuidStrings = new ArrayList<>();
        for (GattService s : response.getServicesList()) {
            String uuidString = s.getUuid();
            uuidStrings.add(Utils.uuidFromString(uuidString));
        }

        responseObserver.cancel("Canceling discoverServices request");
        return uuidStrings;
    }
}
