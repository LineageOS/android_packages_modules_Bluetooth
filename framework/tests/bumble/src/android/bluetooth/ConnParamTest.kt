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

import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import android.os.SystemProperties
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers.greaterThan
import org.junit.After
import org.junit.Assume.assumeThat
import org.junit.Before
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
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.OwnAddressType

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class ConnParamTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val bumble = PandoraDevice()

    @get:Rule(order = 2) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var host: Host
    private lateinit var remoteLeDevice: BluetoothDevice

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity()

        host = Host(context)
        remoteLeDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        remoteLeDevice.removeBond()
    }

    @After
    fun tearDown() {
        val bondedDevices = adapter.bondedDevices
        if (bondedDevices.contains(remoteLeDevice)) {
            host.removeBondAndVerify(remoteLeDevice)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        host.close()
    }

    @Test
    fun connParamsAreRelaxedAfterServiceDiscovery() {
        checkAggressiveConnectionWillBeUsed()

        val gattCallback = mock<BluetoothGattCallback>()
        val connectionIntervalCaptor = ArgumentCaptor.forClass(Int::class.java)

        val gatt = connectGattAndWaitConnection(gattCallback, false)

        // Wait until service discovery is done and parameters are relaxed.
        verify(gattCallback, timeout(10_000).times(1))
            .onConnectionUpdated(
                any(),
                connectionIntervalCaptor.capture(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
            )

        val capturedConnectionIntervals = connectionIntervalCaptor.allValues
        assertThat(capturedConnectionIntervals).hasSize(1)

        // Since aggressive parameters are used in the initial connection,
        // there should be only one connection parameters update event for relaxing them.
        val relaxedConnIntervalAfterServiceDiscovery = capturedConnectionIntervals[0]
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtLeast(MIN_CONN_INTERVAL_RELAXED)
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtMost(MAX_CONN_INTERVAL_RELAXED)

        disconnectAndWaitDisconnection(gatt, gattCallback)
    }

    @Test
    fun connParamsAreRelaxedForBondedDevice_withBluetoothRestart() {
        checkAggressiveConnectionWillBeUsed()
        createLeBondAndWaitBonding(remoteLeDevice)

        // Turn BT off, and then turn it on
        assertThat(BlockingBluetoothAdapter.disable(false)).isTrue()
        assertThat(BlockingBluetoothAdapter.enable()).isTrue()

        // Connect GATT
        val gattCallback = mock<BluetoothGattCallback>()
        val connectionIntervalCaptor = ArgumentCaptor.forClass(Int::class.java)
        val gatt = connectGattAndWaitConnection(gattCallback, false)

        // Wait for the connection parameter update event
        verify(gattCallback, timeout(3_000))
            .onConnectionUpdated(
                any(),
                connectionIntervalCaptor.capture(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
            )

        val capturedConnectionIntervals = connectionIntervalCaptor.allValues
        assertThat(capturedConnectionIntervals).hasSize(1)

        // Since aggressive parameters are used in the initial connection,
        // there should be only one connection parameters update event for relaxing them.
        val relaxedConnIntervalAfterServiceDiscovery = capturedConnectionIntervals[0]
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtLeast(MIN_CONN_INTERVAL_RELAXED)
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtMost(MAX_CONN_INTERVAL_RELAXED)

        disconnectAndWaitDisconnection(gatt, gattCallback)
    }

    private fun connectGattAndWaitConnection(
        callback: BluetoothGattCallback,
        autoConnect: Boolean,
    ): BluetoothGatt {
        val status = GATT_SUCCESS
        val state = STATE_CONNECTED

        val observer = advertiseWithBumble()

        val gatt = remoteLeDevice.connectGatt(context, autoConnect, callback)
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), eq(status), eq(state))
        observer.cancel("Canceling advertisement")

        return gatt
    }

    private fun advertiseWithBumble():
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> {
        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.RANDOM)
                .build()

        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()

        bumble.host().advertise(request, responseObserver)

        return responseObserver
    }

    private fun createLeBondAndWaitBonding(device: BluetoothDevice) {
        val observer = advertiseWithBumble()
        host.createBondAndVerify(device)
        observer.cancel("Canceling advertisement")
    }

    companion object {
        private val MIN_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.min_connection_interval_relaxed", 0x0018)
        private val MAX_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.max_connection_interval_relaxed", 0x0028)

        private fun disconnectAndWaitDisconnection(
            gatt: BluetoothGatt,
            callback: BluetoothGattCallback,
        ) {
            val state = STATE_DISCONNECTED
            gatt.disconnect()
            verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), any<Int>(), eq(state))

            gatt.close()
        }

        private fun checkAggressiveConnectionWillBeUsed() {
            val aggressiveConnectionThreshold =
                SystemProperties.getInt("bluetooth.core.le.aggressive_connection_threshold", 2)
            assumeThat(aggressiveConnectionThreshold, greaterThan(0))
        }
    }
}
