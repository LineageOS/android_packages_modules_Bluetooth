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
import android.bluetooth.PandoraDevice
import android.bluetooth.setupIntentLogger
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
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
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import pandora.GattProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType

private const val TAG = "ServiceDiscoveryTest"

@RunWith(AndroidJUnit4::class)
class ServiceDiscoveryTest {
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
    @Throws(Exception::class)
    fun setUp() {
        inOrder = inOrder(receiver)
        bumbleDevice = bumble.remoteDevice

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_UUID)
            }

        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        Log.d(TAG, "start tearDown")
        context.unregisterReceiver(receiver)
    }

    /**
     * Ensure that successful service discovery results in a single ACTION_UUID intent
     *
     * <p>Prerequisites:
     * <ol>
     * <li>Bumble and Android are not bonded
     * <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     * <ol>
     * <li>Bumble is discoverable and connectable over LE
     * <li>Android connects to Bumble over LE
     * <li>Android starts GATT service discovery
     * </ol>
     *
     * Expectation: A single ACTION_UUID intent is received The ACTION_UUID intent is not empty
     */
    @Test
    fun testServiceDiscoveryBredr_SingleIntent() {
        // Start GATT service discovery, this will establish BR/EDR
        assertThat(bumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        // Wait for connection on Android
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        // Wait for GATT service discovery to complete on Android
        verifyIntentReceived(hasAction(BluetoothDevice.ACTION_UUID))

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(receiver)
    }

    /**
     * Ensure that successful service discovery results in a single ACTION_UUID intent
     *
     * <p>Prerequisites:
     * <ol>
     * <li>Bumble and Android are not bonded
     * <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     * <ol>
     * <li>Bumble is discoverable and connectable over LE
     * <li>Android connects to Bumble over LE
     * <li>Android starts GATT service discovery
     * </ol>
     *
     * Expectation: A single ACTION_UUID intent is received The ACTION_UUID intent is not empty
     */
    @Test
    fun testServiceDiscoveryLe_SingleIntent() {
        // Register some services on Bumble
        repeat(6) {
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
        }

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

        // Start GATT service discovery, this will establish LE ACL
        assertThat(bumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_LE)).isTrue()

        // Wait for connection on Android
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )

        // Wait for GATT service discovery to complete on Android
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_UUID),
            hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(BATTERY_UUID)),
        )

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(receiver)
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any<Context>(), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    companion object {
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val BATTERY_UUID = ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    }
}
