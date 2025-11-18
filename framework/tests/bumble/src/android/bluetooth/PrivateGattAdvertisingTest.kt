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

package android.bluetooth

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import io.grpc.Deadline
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import pandora.GattProto
import pandora.GattProto.DiscoverServiceByUuidRequest
import pandora.GattProto.DiscoverServicesResponse
import pandora.GattProto.GattService
import pandora.HostProto
import pandora.HostProto.Connection
import pandora.HostProto.ScanRequest
import pandora.HostProto.ScanningResponse

private const val TAG = "PrivateGattAdvertisingTest"

/** Test cases for Private GATT advertising */
@RunWith(AndroidJUnit4::class)
class PrivateGattAdvertisingTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 2) val bumble = PandoraDevice()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val advertisingSetCallbacksToClear = mutableListOf<AdvertisingSetCallback>()

    @After
    fun tearDown() {
        for (callback in advertisingSetCallbacksToClear) {
            leAdvertiser.stopAdvertisingSet(callback)
        }
    }

    @RequiresFlagsEnabled("com.android.bluetooth.flags.fix_private_gatt_advertisement")
    @Test
    fun privateGattAdvertisingWithNormalAdvertising() {
        // Starts private GATT advertisement, and get address of it.
        val privateGattServerCallback = mock<BluetoothGattServerCallback>()
        val privateGattServer = manager.openGattServer(context, privateGattServerCallback)
        privateGattServer.addService(
            BluetoothGattService(
                TEST_GATT_SERVICE_UUID_1,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        )
        assertThat(startAdvertisingWithServiceUuid(privateGattServer, TEST_GATT_SERVICE_UUID_1))
            .isNotNull()
        val scanResultForPrivateGattAdv = scanWithBumble(TEST_GATT_SERVICE_UUID_1)
        val privateAdvAddress = scanResultForPrivateGattAdv.random
        verify(privateGattServerCallback, never()).onConnectionStateChange(any(), any(), any())

        // Starts a normal advertisement, and get address of it.
        val normalGattServerCallback = mock<BluetoothGattServerCallback>()
        val normalGattServer = manager.openGattServer(context, normalGattServerCallback)
        normalGattServer.addService(
            BluetoothGattService(
                TEST_GATT_SERVICE_UUID_2,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        )
        assertThat(
                startAdvertisingWithServiceUuid(null /* normal GATT */, TEST_GATT_SERVICE_UUID_2)
            )
            .isNotNull()
        val scanResultForNormalAdv = scanWithBumble(TEST_GATT_SERVICE_UUID_2)
        val normalAdvAddress = scanResultForNormalAdv.random
        verify(normalGattServerCallback, never()).onConnectionStateChange(any(), any(), any())

        // Make the Bumble connect to the private GATT advertisement.
        // Bumble should be able to see the services in private GATT server,
        // but not the services in normal GATT server.
        var connectLEResponse =
            bumble
                .hostBlocking()
                .connectLE(
                    HostProto.ConnectLERequest.newBuilder()
                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                        .setRandom(privateAdvAddress)
                        .build()
                )
        verify(privateGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED))

        // TODO(b/411294650): The normal GATT server's onConnectionStateChange() shouldn't be
        // called,
        //       However this is being called. Make connection callbacks isolated.
        // verify(normalGattServerCallback, never()).onConnectionStateChange(any(), any(),
        // any())

        // The service UUIDs from private GATT server should only contain the private services.
        var serviceUuids = getAllServiceUUIDs(connectLEResponse.connection)
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1)
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_2)

        // Now disconnect from private GATT advertisement, and connect to normal advertisement
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .setConnection(connectLEResponse.connection)
                    .build()
            )
        verify(privateGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED))

        Mockito.clearInvocations(normalGattServerCallback)
        connectLEResponse =
            bumble
                .hostBlocking()
                .connectLE(
                    HostProto.ConnectLERequest.newBuilder()
                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                        .setRandom(normalAdvAddress)
                        .build()
                )
        verify(normalGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED))

        // The service UUIDs from normal GATT server should contain all services.
        serviceUuids = getAllServiceUUIDs(connectLEResponse.connection)
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1)
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_2)

        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .setConnection(connectLEResponse.connection)
                    .build()
            )
        verify(normalGattServerCallback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED))
    }

    @RequiresFlagsEnabled("com.android.bluetooth.flags.fix_private_gatt_advertisement")
    @Test
    fun twoPrivateGattAdvertising() {
        // Starts private GATT advertisement 1, and get address of it.
        val privateGattServer1Callback = mock<BluetoothGattServerCallback>()
        val privateGattServer1 = manager.openGattServer(context, privateGattServer1Callback)
        privateGattServer1.addService(
            BluetoothGattService(
                TEST_GATT_SERVICE_UUID_1,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        )
        assertThat(startAdvertisingWithServiceUuid(privateGattServer1, TEST_GATT_SERVICE_UUID_1))
            .isNotNull()
        val scanResultForPrivateGattAdv1 = scanWithBumble(TEST_GATT_SERVICE_UUID_1)
        val privateAdv1Address = scanResultForPrivateGattAdv1.random
        verify(privateGattServer1Callback, never()).onConnectionStateChange(any(), any(), any())

        // Starts private GATT advertisement 2, and get address of it.
        val privateGattServer2Callback = mock<BluetoothGattServerCallback>()
        val privateGattServer2 = manager.openGattServer(context, privateGattServer2Callback)
        privateGattServer2.addService(
            BluetoothGattService(
                TEST_GATT_SERVICE_UUID_2,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        )
        assertThat(startAdvertisingWithServiceUuid(privateGattServer2, TEST_GATT_SERVICE_UUID_2))
            .isNotNull()
        val scanResultForPrivateGattAdv2 = scanWithBumble(TEST_GATT_SERVICE_UUID_2)
        val privateAdv2Address = scanResultForPrivateGattAdv2.random
        verify(privateGattServer2Callback, never()).onConnectionStateChange(any(), any(), any())

        // Make the Bumble connect to the private GATT advertisement 1.
        var connectLEResponse =
            bumble
                .hostBlocking()
                .connectLE(
                    HostProto.ConnectLERequest.newBuilder()
                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                        .setRandom(privateAdv1Address)
                        .build()
                )
        verify(privateGattServer1Callback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED))

        // TODO(b/411294650): The private GATT server 2's onConnectionStateChange() shouldn't be
        // called,
        //       However this is being called. Make connection callbacks isolated.
        // verify(privateGattServer2Callback, never()).onConnectionStateChange(any(), any(),
        // any())

        // Bumble should be able to see the services in private GATT server 1,
        // but not the services in private GATT server 2.
        var serviceUuids = getAllServiceUUIDs(connectLEResponse.connection)
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_1)
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_2)

        // Now disconnect from private GATT advertisement 1,
        // and connect to private GATT advertisement 2.
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .setConnection(connectLEResponse.connection)
                    .build()
            )
        verify(privateGattServer1Callback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED))

        Mockito.clearInvocations(privateGattServer2Callback)
        connectLEResponse =
            bumble
                .hostBlocking()
                .connectLE(
                    HostProto.ConnectLERequest.newBuilder()
                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                        .setRandom(privateAdv2Address)
                        .build()
                )
        verify(privateGattServer2Callback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_CONNECTED))

        // Bumble should be able to see the services in private GATT server 2,
        // but not the services in private GATT server 1.
        serviceUuids = getAllServiceUUIDs(connectLEResponse.connection)
        assertThat(serviceUuids).contains(TEST_GATT_SERVICE_UUID_2)
        assertThat(serviceUuids).doesNotContain(TEST_GATT_SERVICE_UUID_1)

        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .setConnection(connectLEResponse.connection)
                    .build()
            )
        verify(privateGattServer2Callback, timeout(GATT_CONN_TIMEOUT_MS))
            .onConnectionStateChange(any(), eq(0), eq(BluetoothProfile.STATE_DISCONNECTED))
    }

    /** Return a [ScanningResponse] whose advertising data includes given UUID. */
    private fun scanWithBumble(uuid: UUID): ScanningResponse {
        val responseObserver = StreamObserverSpliterator<ScanRequest, ScanningResponse>()
        val deadline = Deadline.after(ADVERTISING_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        bumble
            .host()
            .withDeadline(deadline)
            .scan(ScanRequest.newBuilder().build(), responseObserver)
        val responseObserverIterator = responseObserver.iterator()
        while (true) {
            val scanningResponse = responseObserverIterator.next()
            if (scanningResponse.data.completeServiceClassUuids128List.contains(uuid.toString())) {
                responseObserver.cancel("Canceling scan request")
                return scanningResponse
            }
        }
    }

    /**
     * Starts an advertising set with a service UUID included in advertising data. If the gattServer
     * is not null, then private GATT advertisement associated with the server will be started.
     */
    private fun startAdvertisingWithServiceUuid(
        gattServer: BluetoothGattServer?,
        serviceUuid: UUID,
    ): AdvertisingSet? {
        val future = CompletableFuture<AdvertisingSet>()

        val parameters =
            AdvertisingSetParameters.Builder()
                .setOwnAddressType(AdvertisingSetParameters.ADDRESS_TYPE_RANDOM_NON_RESOLVABLE)
                .setConnectable(true)
                .build()
        val advertiseData =
            AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(serviceUuid.toString()))
                .build()
        val advertisingSetCallback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet,
                    txPower: Int,
                    status: Int,
                ) {
                    Log.i(TAG, "onAdvertisingSetStarted txPower:$txPower status:$status")
                    future.complete(advertisingSet)
                }
            }

        leAdvertiser.startAdvertisingSet(
            parameters,
            advertiseData,
            null,
            null,
            null,
            0,
            0,
            gattServer,
            advertisingSetCallback,
            Handler(Looper.getMainLooper()),
        )
        advertisingSetCallbacksToClear.add(advertisingSetCallback)

        return try {
            future.get()
        } catch (e: Exception) {
            Log.i(TAG, "startAdvertisingWithServiceUuid failed.", e)
            null
        }
    }

    fun getAllServiceUUIDs(leConnection: Connection): List<UUID> {
        val responseObserver =
            StreamObserverSpliterator<DiscoverServiceByUuidRequest, DiscoverServicesResponse>()

        bumble
            .gatt()
            .discoverServices(
                GattProto.DiscoverServicesRequest.newBuilder().setConnection(leConnection).build(),
                responseObserver,
            )

        val responseObserverIterator = responseObserver.iterator()
        var response: DiscoverServicesResponse
        while (true) {
            response = responseObserverIterator.next()
            if (response.servicesCount > 0) {
                break
            }
        }

        val uuidStrings = mutableListOf<UUID>()
        for (s: GattService in response.servicesList) {
            Utils.uuidFromString(s.uuid)?.let { uuid -> uuidStrings.add(uuid) }
        }

        responseObserver.cancel("Canceling discoverServices request")
        return uuidStrings
    }

    companion object {
        private const val ADVERTISING_TIMEOUT_MS = 2_000L
        private const val GATT_CONN_TIMEOUT_MS = 2_000L
        private val TEST_GATT_SERVICE_UUID_1 =
            UUID.fromString("00000000-0000-0000-0000-000000011111")
        private val TEST_GATT_SERVICE_UUID_2 =
            UUID.fromString("00000000-0000-0000-0000-000000022222")
    }
}
