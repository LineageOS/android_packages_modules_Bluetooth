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

import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.hamcrest.CustomTypeSafeMatcher
import org.hamcrest.Matcher
import org.hamcrest.Matchers.arrayContainingInAnyOrder
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.DataTypes
import pandora.HostProto.DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE
import pandora.HostProto.OwnAddressType
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer

private const val TAG = "GetUuidsFromLeAdvertisingDataTest"

/** Test cases for getting BLE UUIDs from [BluetoothDevice.ACTION_FOUND]. */
@RunWith(TestParameterInjector::class)
class GetUuidsFromLeAdvertisingDataTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()

    @Mock private lateinit var receiver: BroadcastReceiver

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var inOrder: InOrder
    private lateinit var randomAddressBumbleDevice: BluetoothDevice

    @Before
    fun setUp() {
        inOrder = inOrder(receiver)

        randomAddressBumbleDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val filter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        val bondedDevices = adapter.bondedDevices
        if (bondedDevices.contains(randomAddressBumbleDevice)) {
            randomAddressBumbleDevice.removeBond()
        }
        if (bondedDevices.contains(bumble.remoteDevice)) {
            bumble.remoteDevice.removeBond()
        }
    }

    @Test
    fun getUuidsFromServiceUuid(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        val dataType =
            DataTypes.newBuilder()
                .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                .addCompleteServiceClassUuids32(TEST_32_BIT_SERVICE_UUID)
                .addCompleteServiceClassUuids128(TEST_128_BIT_SERVICE_UUID)
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()
        val expectedUuids =
            arrayOf(
                ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
                ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)),
                ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID)),
            )

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    @Test
    fun getUuidsFromServiceData(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        val dataType =
            DataTypes.newBuilder()
                .putServiceDataUuid16(TEST_16_BIT_SERVICE_UUID, ByteString.copyFromUtf8("a"))
                .putServiceDataUuid32(TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"))
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()
        val expectedUuids =
            arrayOf(
                ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
                ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)),
            )

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    // Due to packet size limit in legacy advertising, separate test for 128 bit UUID.
    @Test
    fun getUuidsFromServiceData_128BitUuid(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        val dataType =
            DataTypes.newBuilder()
                .putServiceDataUuid128(TEST_128_BIT_SERVICE_UUID, ByteString.copyFromUtf8("c"))
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()
        val expectedUuids = arrayOf(ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID)))

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    @Test
    fun getUuidsFromBothServiceUuidAndData(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        val dataType =
            DataTypes.newBuilder()
                .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                .putServiceDataUuid32(TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"))
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()
        val expectedUuids =
            arrayOf(
                ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
                ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)),
            )

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    @Test
    fun doesNotContainAnyUuidDataType_shouldReturnNullUuid(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        val dataType =
            DataTypes.newBuilder()
                // No UUID data types are used.
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()

        // EXTRA_UUID_LE should give null as the advertisement does not contain any Service UUID or
        // Service DATA data type.
        val expectedUuids: Array<ParcelUuid>? = null
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    @Test
    fun uuidTypesAreRemovedFromAdvertisement_shouldReturnNullUuid(
        @TestParameter usePublicAddress: Boolean,
        @TestParameter createLeBond: Boolean,
    ) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress)
            restartBluetooth()
        }

        var dataType =
            DataTypes.newBuilder()
                .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                .build()
        var expectedUuids: Array<ParcelUuid>? =
            arrayOf(ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)))
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)

        // Now, start a new advertisement with no UUIDs. ACTION_FOUND should have null UUIDs.
        bumble.hostBlocking().factoryReset(Empty.getDefaultInstance())

        try {
            // Need to wait for the canceled discovery truly ends before starting a new discovery.
            // We cannot rely on ACTION_DISCOVERY_FINISHED, because it comes multiple times.
            // If we don't, then sometimes ACTION_FOUND intent is not sent because AdapterService
            // clears the 'discovering package' list with sending ACTION_DISCOVERY_FINISHED.
            Thread.sleep(CANCEL_DISCOVERY_WAIT_TIME.toMillis())
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }

        dataType =
            DataTypes.newBuilder().setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE).build()
        expectedUuids = null
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids)
    }

    fun createLeBondAndVerify(usePublicAddress: Boolean) {
        val device = if (usePublicAddress) bumble.remoteDevice else randomAddressBumbleDevice

        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(
                    if (usePublicAddress) OwnAddressType.PUBLIC else OwnAddressType.RANDOM
                )
                .build()
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        bumble.host().advertise(request, responseObserver)

        // Create bond over LE transport
        val pairingEventStreamObserver = StreamObserverSpliterator<Void, PairingEvent>()
        val pairingEventAnswerObserver =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(pairingEventStreamObserver)
        assertThat(device.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        device.setPairingConfirmation(true)

        val pairingEvent = pairingEventStreamObserver.iterator().next()
        assertThat(pairingEvent.hasJustWorks()).isTrue()
        pairingEventAnswerObserver.onNext(
            PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build()
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        responseObserver.cancel("Canceling Advertising.")
    }

    fun verifyDiscoveryBroadcastUuids(
        dataTypes: DataTypes,
        usePublicAddress: Boolean,
        expectedUuids: Array<ParcelUuid>?,
    ) {
        assertThat(adapter.startDiscovery()).isTrue()

        val request =
            AdvertiseRequest.newBuilder()
                .setOwnAddressType(
                    if (usePublicAddress) OwnAddressType.PUBLIC else OwnAddressType.RANDOM
                )
                .setData(dataTypes)
                .setLegacy(true) // Bumble only supports legacy advertising
                .build()

        // Collect and ignore responses.
        bumble
            .host()
            .advertise(request, StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>())

        try {
            verifyIntentReceived(hasAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
            val expectedLeUuidsIntentMatcher =
                object : CustomTypeSafeMatcher<Intent>("has LE UUIDs") {
                    override fun matchesSafely(intent: Intent): Boolean {
                        if (intent.action != BluetoothDevice.ACTION_FOUND) return false
                        val resultType =
                            intent.getIntExtra(BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE, -1)
                        if ((resultType and BluetoothDevice.DEVICE_TYPE_LE) == 0) return false
                        val uuids = intent.getParcelUuidArray(BluetoothDevice.EXTRA_UUID_LE)
                        return when {
                            expectedUuids == null -> uuids.isEmpty()
                            else -> arrayContainingInAnyOrder(*expectedUuids).matches(uuids)
                        }
                    }
                }
            verifyIntentReceived(expectedLeUuidsIntentMatcher)
        } finally {
            assertThat(adapter.cancelDiscovery()).isTrue()
        }
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun restartBluetooth() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue()
        assertThat(BlockingBluetoothAdapter.enable()).isTrue()
    }

    companion object {
        private const val TEST_16_BIT_SERVICE_UUID = "1809"
        private const val TEST_32_BIT_SERVICE_UUID = "12345678"
        private const val TEST_128_BIT_SERVICE_UUID = "88400001-e95a-844e-c53f-fbec32ed5e54"
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val CANCEL_DISCOVERY_WAIT_TIME = Duration.ofMillis(500)
    }
}
