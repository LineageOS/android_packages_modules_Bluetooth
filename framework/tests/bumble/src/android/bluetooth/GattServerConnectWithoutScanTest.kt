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

package android.bluetooth

import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.Context
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType

private const val TAG = "GattServerConnectWithoutScanTest"

/** Test cases for [BluetoothGattServer]. */
@RunWith(AndroidJUnit4::class)
class GattServerConnectWithoutScanTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 2) val bumble = PandoraDevice()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun serverConnectToRandomAddress_withTransportAuto() {
        advertiseWithBumble(OwnAddressType.RANDOM)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO)

        assertThat(gattServer).isNotNull()

        try {
            val device =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )

            gattServer.connect(device, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        } finally {
            gattServer.close()
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun serverConnectToRandomAddress_withTransportLE() {
        advertiseWithBumble(OwnAddressType.RANDOM)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE)

        assertThat(gattServer).isNotNull()

        try {
            val device =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )

            gattServer.connect(device, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        } finally {
            gattServer.close()
        }
    }

    @Test
    @Ignore("b/333018293")
    fun serverConnectToPublicAddress_withTransportAuto() {
        advertiseWithBumble(OwnAddressType.PUBLIC)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO)

        assertThat(gattServer).isNotNull()

        try {
            gattServer.connect(bumble.remoteDevice, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        } finally {
            gattServer.close()
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun serverConnectToPublicAddress_withTransportLE() {
        advertiseWithBumble(OwnAddressType.PUBLIC)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE)

        assertThat(gattServer).isNotNull()

        try {
            gattServer.connect(bumble.remoteDevice, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        } finally {
            gattServer.close()
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun serverOffloadCharacteristics() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isServerOffloadSupported ?: false)

        advertiseWithBumble(OwnAddressType.RANDOM)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO)
        assertThat(gattServer).isNotNull()

        val serviceCaptor = ArgumentCaptor.forClass(BluetoothGattService::class.java)
        gattServer.addService(createGattService())
        verify(mockGattServerCallback, timeout(1000))
            .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture())

        val service = serviceCaptor.value
        assertThat(service).isNotNull()
        assertThat(service.characteristics).isNotNull()
        assertThat(service.characteristics).isNotEmpty()

        try {
            val device =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )

            gattServer.connect(device, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

            val status =
                gattServer.offloadCharacteristics(
                    device,
                    service,
                    service.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(mockGattServerCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.value
            assertThat(session).isNotNull()
            Log.i(TAG, "Offload session: $session")
            assertThat(session.sessionId)
                .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)
            assertThat(session.gattService).isEqualTo(service)
            assertThat(session.gattCharacteristics).isEqualTo(service.characteristics)
            assertThat(session.endpointId).isEqualTo(TEST_ENDPOINT_ID)
            assertThat(session.hubId).isEqualTo(TEST_HUB_ID)
        } finally {
            gattServer.close()
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun serverUnoffloadCharacteristics() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isServerOffloadSupported ?: false)

        advertiseWithBumble(OwnAddressType.RANDOM)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO)
        assertThat(gattServer).isNotNull()

        val serviceCaptor = ArgumentCaptor.forClass(BluetoothGattService::class.java)
        gattServer.addService(createGattService())
        verify(mockGattServerCallback, timeout(1000))
            .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture())

        val service = serviceCaptor.value
        assertThat(service).isNotNull()
        assertThat(service.characteristics).isNotNull()
        assertThat(service.characteristics).isNotEmpty()

        try {
            val device =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )

            gattServer.connect(device, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

            val status =
                gattServer.offloadCharacteristics(
                    device,
                    service,
                    service.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(mockGattServerCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.value
            assertThat(session).isNotNull()
            Log.i(TAG, "Offload session: $session")
            assertThat(session.sessionId)
                .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)

            session.close()
            verify(mockGattServerCallback, timeout(10000))
                .onCharacteristicsUnoffloaded(
                    any(),
                    eq(session.sessionId),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
        } finally {
            gattServer.close()
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun serverUnoffloadCharacteristics_autoClose() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isServerOffloadSupported ?: false)

        advertiseWithBumble(OwnAddressType.RANDOM)

        val mockGattServerCallback = mock<BluetoothGattServerCallback>()
        val gattServer =
            manager.openGattServer(context, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO)
        assertThat(gattServer).isNotNull()

        val serviceCaptor = ArgumentCaptor.forClass(BluetoothGattService::class.java)
        gattServer.addService(createGattService())
        verify(mockGattServerCallback, timeout(1000))
            .onServiceAdded(eq(GATT_SUCCESS), serviceCaptor.capture())

        val service = serviceCaptor.value
        assertThat(service).isNotNull()
        assertThat(service.characteristics).isNotNull()
        assertThat(service.characteristics).isNotEmpty()

        var sessionId = GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN
        try {
            val device =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )

            gattServer.connect(device, false)
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

            val status =
                gattServer.offloadCharacteristics(
                    device,
                    service,
                    service.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(mockGattServerCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            sessionCaptor.value.use { session ->
                assertThat(session).isNotNull()
                sessionId = session.sessionId
                Log.i(TAG, "Offload session: $session")
                assertThat(session.sessionId)
                    .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)
            } // session.close() is automatically called here
            verify(mockGattServerCallback, timeout(10000))
                .onCharacteristicsUnoffloaded(
                    any(),
                    eq(sessionId),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
        } finally {
            gattServer.close()
        }
    }

    private fun createGattService(): BluetoothGattService {
        val service =
            BluetoothGattService(TEST_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic =
            BluetoothGattCharacteristic(
                TEST_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        service.addCharacteristic(characteristic)
        return service
    }

    private fun advertiseWithBumble(ownAddressType: OwnAddressType) {
        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(ownAddressType)
                .build()
        bumble.hostBlocking().advertise(request)
    }

    companion object {
        private const val TIMEOUT_GATT_CONNECTION_MS = 2_000L
        private const val TEST_HUB_ID = 1L
        private const val TEST_ENDPOINT_ID = 2L

        private val TEST_SERVICE_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000")
        private val TEST_CHARACTERISTIC_UUID =
            UUID.fromString("00010001-0000-0000-0000-000000000000")
    }
}
