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

import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType

/** Test cases for [BluetoothGattServer]. */
@RunWith(AndroidJUnit4::class)
class GattServerConnectWithScanTest {
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 0) val bumble = PandoraDevice()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun serverConnectToRandomAddress_withTransportAuto() {
        advertiseWithBumble(OwnAddressType.RANDOM)
        assertThat(scanBumbleDevice(Utils.BUMBLE_RANDOM_ADDRESS)).isNotNull()

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
        assertThat(scanBumbleDevice(Utils.BUMBLE_RANDOM_ADDRESS)).isNotNull()

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
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    fun serverConnectToPublicAddress_withTransportAuto() {
        val publicAddress = bumble.remoteDevice.address
        advertiseWithBumble(OwnAddressType.PUBLIC)
        assertThat(scanBumbleDevice(publicAddress)).isNotNull()

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
        val publicAddress = bumble.remoteDevice.address
        advertiseWithBumble(OwnAddressType.PUBLIC)
        assertThat(scanBumbleDevice(publicAddress)).isNotNull()

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

    private fun advertiseWithBumble(ownAddressType: OwnAddressType) {
        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(ownAddressType)
                .build()
        bumble.hostBlocking().advertise(request)
    }

    private fun scanBumbleDevice(address: String): List<ScanResult>? {
        val future = CompletableFuture<List<ScanResult>?>()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()

        val scanFilter = ScanFilter.Builder().setDeviceAddress(address).build()

        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.d(TAG, "onScanResult: result=$result")
                    future.complete(listOf(result))
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.d(TAG, "onScanFailed: errorCode=$errorCode")
                    future.complete(null)
                }
            }

        leScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        val result =
            future.completeOnTimeout(null, TIMEOUT_SCANNING_MS, TimeUnit.MILLISECONDS).join()

        leScanner.stopScan(scanCallback)
        return result
    }

    companion object {
        private const val TAG = "GattServerConnectWithScanTest"
        private const val TIMEOUT_SCANNING_MS = 2_000L
        private const val TIMEOUT_GATT_CONNECTION_MS = 2_000L
    }
}
