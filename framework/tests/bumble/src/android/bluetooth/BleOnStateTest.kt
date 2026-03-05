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

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_SUCCESS
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.bluetooth.test_utils.TestUtils
import android.content.Context
import android.os.ParcelUuid
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import pandora.HostProto

@RunWith(AndroidJUnit4::class)
class BleOnStateTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 2) val bumble = PandoraDevice()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private var wasBluetoothAdapterEnabled = true

    @Before
    fun setUp() {
        assumeTrue(TestUtils.hasBluetooth())
        wasBluetoothAdapterEnabled = adapter.isEnabled
        if (wasBluetoothAdapterEnabled) {
            assertThat(BlockingBluetoothAdapter.disable(true)).isTrue()
        }
        assertThat(BlockingBluetoothAdapter.enableBLE(true)).isTrue()
    }

    @After
    fun tearDown() {
        assumeTrue(TestUtils.hasBluetooth())
        assertThat(BlockingBluetoothAdapter.disableBLE()).isTrue()
        if (wasBluetoothAdapterEnabled) {
            assertThat(BlockingBluetoothAdapter.enable()).isTrue()
        }
    }

    @Test
    fun confirm_stateIsBleOn() {
        assertThat(adapter.isEnabled).isFalse()
        assertThat(adapter.isLeEnabled).isTrue()
    }

    @Test
    fun whenOnlyStartScanDuringBleOnOffOrOn_scanWorks() {
        advertiseWithBumble(TEST_UUID_STRING, HostProto.OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()

        val results =
            startScanning(scanFilter, ScanSettings.CALLBACK_TYPE_ALL_MATCHES, isLegacy = true)

        assertThat(results).isNotNull()
        assertThat(results!!.first().scanRecord!!.serviceUuids.first())
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
        assertThat(results[1].scanRecord!!.serviceUuids.first())
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    @Test
    @RequiresFlagsDisabled("com.android.bluetooth.flags.only_start_scan_during_ble_on")
    fun whenOnlyStartScanDuringBleOnOff_canAdvertise() {
        val settings = AdvertiseSettings.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()

        val future = CompletableFuture<Int>()

        val advertiseCallback =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    future.complete(ADVERTISE_SUCCESS)
                }

                override fun onStartFailure(errorCode: Int) {
                    future.complete(errorCode)
                }
            }

        try {
            leAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback)
            future.completeOnTimeout(null, TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS).join()

            val advertisingResult = future.get()
            assertThat(advertisingResult).isNotNull()
            assertThat(advertisingResult).isEqualTo(ADVERTISE_SUCCESS)
        } finally {
            leAdvertiser.stopAdvertising(advertiseCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.only_start_scan_during_ble_on")
    fun whenOnlyStartScanDuringBleOnOn_cantAdvertise() {
        val settings = AdvertiseSettings.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()

        val future = CompletableFuture<Int>()

        val advertiseCallback =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    future.complete(ADVERTISE_SUCCESS)
                }

                override fun onStartFailure(errorCode: Int) {
                    future.complete(errorCode)
                }
            }

        try {
            leAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback)
            future.completeOnTimeout(null, TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS).join()

            val advertisingResult = future.get()
            assertThat(advertisingResult).isNotNull()
            assertThat(advertisingResult).isEqualTo(ADVERTISE_FAILED_INTERNAL_ERROR)
        } finally {
            leAdvertiser.stopAdvertising(advertiseCallback)
        }
    }

    @Test
    @RequiresFlagsDisabled("com.android.bluetooth.flags.only_start_scan_during_ble_on")
    fun whenOnlyStartScanDuringBleOnOff_gattCanConnect() {
        advertiseWithBumble()

        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = device.connectGatt(context, false, gattCallback)
        assertThat(gatt).isNotNull()
        gatt?.close()
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.only_start_scan_during_ble_on")
    fun whenOnlyStartScanDuringBleOnOn_gattCantConnect() {
        advertiseWithBumble()

        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = device.connectGatt(context, false, gattCallback)
        assertThat(gatt).isNull()
    }

    private fun advertiseWithBumble() {
        val request =
            HostProto.AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                .build()

        val responseObserver =
            StreamObserverSpliterator<HostProto.AdvertiseRequest, HostProto.AdvertiseResponse>()

        bumble.host().advertise(request, responseObserver)
    }

    private fun advertiseWithBumble(serviceUuid: String?, addressType: HostProto.OwnAddressType) {
        val requestBuilder = HostProto.AdvertiseRequest.newBuilder().setOwnAddressType(addressType)

        if (serviceUuid != null) {
            val dataTypeBuilder = HostProto.DataTypes.newBuilder()
            dataTypeBuilder.addCompleteServiceClassUuids128(serviceUuid)
            requestBuilder.setData(dataTypeBuilder.build())
        }

        advertiseWithBumble(requestBuilder, true)
    }

    private fun advertiseWithBumble(
        requestBuilder: HostProto.AdvertiseRequest.Builder,
        isLegacy: Boolean,
    ) {
        requestBuilder.setLegacy(isLegacy)
        // Collect and ignore responses.
        val responseObserver =
            StreamObserverSpliterator<HostProto.AdvertiseRequest, HostProto.AdvertiseResponse>()
        bumble.host().advertise(requestBuilder.build(), responseObserver)
    }

    private fun startScanning(
        scanFilter: ScanFilter,
        callbackType: Int,
        isLegacy: Boolean,
    ): List<ScanResult>? {
        val future = CompletableFuture<List<ScanResult>?>()
        val scanResults = mutableListOf<ScanResult>()

        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(callbackType)
                .setLegacy(isLegacy)
                .build()

        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.i(
                        TAG,
                        "onScanResult address: ${result.device.address}, " +
                            "connectable: ${result.isConnectable}, " +
                            "callbackType: $callbackType, " +
                            "service uuids: ${result.scanRecord?.serviceUuids}",
                    )

                    scanResults.add(result)
                    if (
                        callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES ||
                            scanResults.size > 1
                    ) {
                        future.complete(scanResults)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.i(TAG, "onScanFailed errorCode: $errorCode")
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
        private const val TAG = "BleOnStateTest"

        private const val TIMEOUT_ADVERTISING_MS = 1000L
        private const val TIMEOUT_SCANNING_MS = 2000L
        private const val TEST_UUID_STRING = "00001805-0000-1000-8000-00805f9b34fb"
    }
}
