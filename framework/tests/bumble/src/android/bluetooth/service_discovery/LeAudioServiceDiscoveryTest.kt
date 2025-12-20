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

package android.bluetooth.service_discovery.pairing

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothUuid
import android.bluetooth.PandoraDevice
import android.bluetooth.Utils
import android.bluetooth.VirtualOnly
import android.bluetooth.adapter
import android.bluetooth.setupIntentLogger
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.hamcrest.Matcher
import org.hamcrest.Matchers
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
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import pandora.BumbleConfigProto.IoCapability
import pandora.BumbleConfigProto.KeyDistribution
import pandora.BumbleConfigProto.OverrideRequest
import pandora.BumbleConfigProto.PairingConfig
import pandora.GattProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.DiscoverabilityMode
import pandora.HostProto.OwnAddressType
import pandora.HostProto.SetDiscoverabilityModeRequest

private const val TAG = "LeAudioServiceDiscoveryTest"

@RunWith(AndroidJUnit4::class)
class LeAudioServiceDiscoveryTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var inOrder: InOrder
    private lateinit var bumbleDevice: BluetoothDevice

    @Before
    fun setUp() {
        inOrder = inOrder(receiver)
        bumbleDevice = bumble.remoteDevice
        bumble
            .bumbleConfigBlocking()
            .override(
                OverrideRequest.newBuilder()
                    .setIoCapability(IoCapability.NO_OUTPUT_NO_INPUT)
                    .setPairingConfig(
                        PairingConfig.newBuilder()
                            .setSc(true)
                            .setMitm(true)
                            .setBonding(true)
                            .setIdentityAddressType(OwnAddressType.PUBLIC)
                            .build()
                    )
                    .addInitiatorKeyDistribution(KeyDistribution.ENCRYPTION_KEY)
                    .addInitiatorKeyDistribution(KeyDistribution.IDENTITY_KEY)
                    .addInitiatorKeyDistribution(KeyDistribution.SIGNING_KEY)
                    .addInitiatorKeyDistribution(KeyDistribution.LINK_KEY)
                    .addResponderKeyDistribution(KeyDistribution.ENCRYPTION_KEY)
                    .addResponderKeyDistribution(KeyDistribution.IDENTITY_KEY)
                    .addResponderKeyDistribution(KeyDistribution.SIGNING_KEY)
                    .addResponderKeyDistribution(KeyDistribution.LINK_KEY)
                    .build()
            )

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }

        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
    }

    @After
    fun tearDown() {
        if (bumbleDevice.bondState == BluetoothDevice.BOND_BONDED) {
            removeBond(bumbleDevice)
        }
        context.unregisterReceiver(receiver)
    }

    /**
     * Ensure that successful service discovery results on both Transport for LE Audio capable
     * device
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble has LE Audio service in addition to GAP and GATT services
     *
     * Steps:
     * 1. Bumble is discoverable and connectable on both Transport
     * 2. Android creates the Bond
     * 3. Android starts service discovery on both Transport
     *
     * Expectation: ACTION_UUID intent is received and The ACTION_UUID intent has both LE and
     * Classic services
     */
    @Test
    fun testServiceDiscoveryWithPublicAddr() {
        // Register Battery and Le Audio services on Bumble
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(BATTERY_UUID.toString())
                            .build()
                    )
                    .build()
            )
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(LEAUDIO_UUID.toString())
                            .build()
                    )
                    .build()
            )

        // Make Bumble connectable
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )
        // Make Bumble discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                    .build()
            )
        // Start Discovery
        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_FOUND),
            hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        assertThat(adapter.cancelDiscovery()).isTrue()

        // Start pairing from Android with Auto transport
        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        // Verify  ACL connection on LE transport first and then Classic transport
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        verifyIntentReceivedUnordered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        // Verify both LE and Classic Services
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_UUID),
            hasExtra(
                BluetoothDevice.EXTRA_UUID,
                Matchers.allOf(
                    Matchers.hasItemInArray(BluetoothUuid.HFP),
                    Matchers.hasItemInArray(BluetoothUuid.A2DP_SOURCE),
                    Matchers.hasItemInArray(BluetoothUuid.A2DP_SINK),
                    Matchers.hasItemInArray(BluetoothUuid.AVRCP),
                    Matchers.hasItemInArray(BluetoothUuid.LE_AUDIO),
                    Matchers.hasItemInArray(BluetoothUuid.BATTERY),
                ),
            ),
        )
    }

    /**
     * Ensure that successful service discovery results on both Transport for LE Audio capable
     * device
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble has GATT services in addition to GAP and GATT services
     *
     * Steps:
     * 1. Bumble is discoverable and connectable on both Transport
     * 2. Android creates the Bond
     * 3. Android starts service discovery on both Transport
     *
     * Expectation: ACTION_UUID intent is received and The ACTION_UUID intent has both LE and
     * Classic services
     */
    @Test
    @VirtualOnly
    @Throws(Exception::class)
    fun testServiceDiscoveryWithRandomAddr() {
        // Register Battery and Le Audio services on Bumble
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(BATTERY_UUID.toString())
                            .build()
                    )
                    .build()
            )
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(LEAUDIO_UUID.toString())
                            .build()
                    )
                    .build()
            )

        // Make Bumble discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                    .build()
            )
        // Start Discovery
        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_FOUND),
            hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        assertThat(adapter.cancelDiscovery()).isTrue()
        // Start pairing from Android with Auto transport
        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Make Bumble connectable with some delay
        Thread.sleep(300)
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.RANDOM)
                    .build()
            )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        // Verify both LE and Classic Services
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_UUID),
            hasExtra(
                BluetoothDevice.EXTRA_UUID,
                Matchers.allOf(
                    Matchers.hasItemInArray(BluetoothUuid.HFP),
                    Matchers.hasItemInArray(BluetoothUuid.A2DP_SOURCE),
                    Matchers.hasItemInArray(BluetoothUuid.A2DP_SINK),
                    Matchers.hasItemInArray(BluetoothUuid.AVRCP),
                    Matchers.hasItemInArray(BluetoothUuid.LE_AUDIO),
                    Matchers.hasItemInArray(BluetoothUuid.BATTERY),
                ),
            ),
        )
    }

    private fun removeBond(device: BluetoothDevice) {
        assertThat(device.removeBond()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )
    }

    @SafeVarargs
    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    @SafeVarargs
    private fun verifyIntentReceivedUnordered(num: Int, vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).times(num))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    @SafeVarargs
    private fun verifyIntentReceivedUnorderedAtLeast(
        atLeast: Int,
        vararg matchers: Matcher<Intent>,
    ) {
        verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    @SafeVarargs
    private fun verifyIntentReceivedUnordered(vararg matchers: Matcher<Intent>) {
        verifyIntentReceivedUnordered(1, *matchers)
    }

    companion object {
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val BATTERY_UUID = ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        private val LEAUDIO_UUID = ParcelUuid.fromString("0000184E-0000-1000-8000-00805F9B34FB")
    }
}
