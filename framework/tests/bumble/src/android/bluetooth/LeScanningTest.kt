/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.after
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.OwnAddressType
import pandora.HostProto.PrimaryPhy

private const val TAG = "LeScanningTest"

@RunWith(TestParameterInjector::class)
class LeScanningTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 2) val bumble = PandoraDevice()

    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun startBleScan_withCallbackTypeAllMatches() {
        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
        assertThat(results[1].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    @Test
    fun scanForIrkIdentityAddress_withCallbackTypeAllMatches() {
        advertiseWithBumble(null, OwnAddressType.RANDOM)

        val scanFilter =
            ScanFilter.Builder()
                .setDeviceAddress(
                    TEST_ADDRESS_RANDOM_STATIC,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                    Utils.BUMBLE_IRK,
                )
                .build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotEmpty()
        assertThat(results!![0].device.address).isEqualTo(TEST_ADDRESS_RANDOM_STATIC)
    }

    @Test
    fun startBleScan_withCallbackTypeFirstMatchSilentlyFails() {
        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .build()
        val mockScanCallback = mock<ScanCallback>()

        leScanner.startScan(listOf(scanFilter), scanSettings, mockScanCallback)
        verify(mockScanCallback, after(TIMEOUT_SCANNING_MS).never()).onScanFailed(anyInt())
        leScanner.stopScan(mockScanCallback)
    }

    @Test
    fun startBleScan_withCallbackTypeMatchLostSilentlyFails() {
        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .build()
        val mockScanCallback = mock<ScanCallback>()

        leScanner.startScan(listOf(scanFilter), scanSettings, mockScanCallback)
        verify(mockScanCallback, after(TIMEOUT_SCANNING_MS).never()).onScanFailed(anyInt())
        leScanner.stopScan(mockScanCallback)
    }

    @Test
    fun startBleScan_withPendingIntentAndDynamicReceiverAndCallbackTypeAllMatches() {
        val mockReceiver = mock<BroadcastReceiver>()
        val intentFilter = IntentFilter(ACTION_DYNAMIC_RECEIVER_SCAN_RESULT)
        context.registerReceiver(mockReceiver, intentFilter, Context.RECEIVER_EXPORTED)

        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(CALLBACK_TYPE_ALL_MATCHES)
                .build()
        // NOTE: Intent.setClass() must not be called, or else scan results won't be received.
        val scanIntent = Intent(ACTION_DYNAMIC_RECEIVER_SCAN_RESULT)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                scanIntent,
                PendingIntent.FLAG_MUTABLE or
                    PendingIntent.FLAG_CANCEL_CURRENT or
                    PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,
            )

        leScanner.startScan(listOf(scanFilter), scanSettings, pendingIntent)

        val intent = ArgumentCaptor.forClass(Intent::class.java)
        verify(mockReceiver, timeout(TIMEOUT_SCANNING_MS)).onReceive(any(), intent.capture())

        leScanner.stopScan(pendingIntent)
        context.unregisterReceiver(mockReceiver)

        assertThat(intent.value.action).isEqualTo(ACTION_DYNAMIC_RECEIVER_SCAN_RESULT)
        assertThat(intent.value.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1))
            .isEqualTo(CALLBACK_TYPE_ALL_MATCHES)

        val results =
            intent.value.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            )
        assertThat(results).isNotEmpty()
        assertThat(results!![0].scanRecord!!.serviceUuids).isNotEmpty()
        assertThat(results[0].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
        assertThat(results[0].scanRecord!!.serviceUuids)
            .containsExactly(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    @Test
    fun startBleScan_withPendingIntentAndStaticReceiverAndCallbackTypeAllMatches() {
        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(CALLBACK_TYPE_ALL_MATCHES)
                .build()
        val pendingIntent = PendingIntentScanReceiver.newBroadcastPendingIntent(context, 0)

        leScanner.startScan(listOf(scanFilter), scanSettings, pendingIntent)
        val results =
            PendingIntentScanReceiver.nextScanResult()
                .completeOnTimeout(null, TIMEOUT_SCANNING_MS, TimeUnit.MILLISECONDS)
                .join()
        leScanner.stopScan(pendingIntent)
        PendingIntentScanReceiver.resetNextScanResultFuture()

        assertThat(results).isNotEmpty()
        assertThat(results!![0].scanRecord!!.serviceUuids).isNotEmpty()
        assertThat(results[0].scanRecord!!.serviceUuids)
            .containsExactly(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    @Test
    fun startBleScan_oneTooManyScansFails() {
        val maxNumScans = 32
        advertiseWithBumble(TEST_UUID_STRING, OwnAddressType.PUBLIC)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()
        val scanFilters = listOf(scanFilter)
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(CALLBACK_TYPE_ALL_MATCHES)
                .build()
        val scanCallbacks =
            (1..maxNumScans).map {
                val mockScanCallback = mock<ScanCallback>()
                leScanner.startScan(scanFilters, scanSettings, mockScanCallback)
                mockScanCallback
            }

        // This last scan should fail
        val lastMockScanCallback = mock<ScanCallback>()
        leScanner.startScan(scanFilters, scanSettings, lastMockScanCallback)

        // We expect an error only for the last scan, which was over the maximum active scans limit.
        for (mockScanCallback in scanCallbacks) {
            verify(mockScanCallback, timeout(TIMEOUT_SCANNING_MS).atLeast(1))
                .onScanResult(eq(CALLBACK_TYPE_ALL_MATCHES), any())
            verify(mockScanCallback, never()).onScanFailed(anyInt())
            leScanner.stopScan(mockScanCallback)
        }
        verify(lastMockScanCallback, timeout(TIMEOUT_SCANNING_MS))
            .onScanFailed(eq(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED))
        leScanner.stopScan(lastMockScanCallback)
    }

    @Test
    fun startBleScan_withNonConnectablePublicAdvertisement() {
        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setConnectable(false)
                .setOwnAddressType(OwnAddressType.PUBLIC)
        advertiseWithBumble(requestBuilder, true)

        val scanFilter = ScanFilter.Builder().setDeviceAddress(bumble.remoteDevice.address).build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].isConnectable).isFalse()
        assertThat(results[1].isConnectable).isFalse()
    }

    @Test
    fun startBleScan_withNonConnectableScannablePublicAdvertisement() {
        val payload = byteArrayOf(0x02, 0x03)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerData = byteArrayOf(0xE0.toByte(), 0x00, payload[0], payload[1])
        val scanResponse =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerData))

        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setConnectable(false)
                .setOwnAddressType(OwnAddressType.PUBLIC)
                .setScanResponseData(scanResponse)
        advertiseWithBumble(requestBuilder, true)

        val scanFilter = ScanFilter.Builder().setDeviceAddress(bumble.remoteDevice.address).build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].isConnectable).isFalse()
        assertThat(results[0].scanRecord!!.getManufacturerSpecificData(0x00E0)).isEqualTo(payload)
    }

    @Test
    fun startBleScan_scanFilterOnManufacturerDataInScanResponse() {
        val payloadInAdvData = byteArrayOf(0x01, 0x02)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInAdvData =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInAdvData[0], payloadInAdvData[1])
        val advData =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInAdvData))

        val payloadInScanRsp = byteArrayOf(0x03, 0x04)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInScanRsp =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInScanRsp[0], payloadInScanRsp[1])
        val scanResponse =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInScanRsp))

        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.PUBLIC)
                .setData(advData)
                .setScanResponseData(scanResponse)
        advertiseWithBumble(requestBuilder, true)

        // Set the filter on manufacturer data in scan response
        val scanFilter = ScanFilter.Builder().setManufacturerData(0xE0, payloadInScanRsp).build()
        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.getManufacturerSpecificData(0x00E0))
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }

    @Test
    fun startBleScan_scanFilterOnManufacturerDataInAdvertisingData() {
        val payloadInAdvData = byteArrayOf(0x01, 0x02)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInAdvData =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInAdvData[0], payloadInAdvData[1])
        val advData =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInAdvData))

        val payloadInScanRsp = byteArrayOf(0x03, 0x04)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInScanRsp =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInScanRsp[0], payloadInScanRsp[1])
        val scanResponse =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInScanRsp))

        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.PUBLIC)
                .setData(advData)
                .setScanResponseData(scanResponse)
        advertiseWithBumble(requestBuilder, true)

        // Set the filter on manufacturer data in advertising data
        val scanFilter = ScanFilter.Builder().setManufacturerData(0xE0, payloadInAdvData).build()
        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.getManufacturerSpecificData(0x00E0))
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }

    @Test
    fun startBleScan_scanFilterOnConcatenatedManufacturerData() {
        val payloadInAdvData = byteArrayOf(0x01, 0x02)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInAdvData =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInAdvData[0], payloadInAdvData[1])
        val advData =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInAdvData))

        val payloadInScanRsp = byteArrayOf(0x03, 0x04)
        // first 2 bytes are the manufacturer ID 0x00E0 (Google) in little endian
        val manufacturerDataInScanRsp =
            byteArrayOf(0xE0.toByte(), 0x00, payloadInScanRsp[0], payloadInScanRsp[1])
        val scanResponse =
            HostProto.DataTypes.newBuilder()
                .setManufacturerSpecificData(ByteString.copyFrom(manufacturerDataInScanRsp))

        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.PUBLIC)
                .setData(advData)
                .setScanResponseData(scanResponse)
        advertiseWithBumble(requestBuilder, true)

        // Set the filter on concatenated manufacturer data (Advertising data + Scan response)
        val concatenatedPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val scanFilter = ScanFilter.Builder().setManufacturerData(0xE0, concatenatedPayload).build()
        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, true)

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.getManufacturerSpecificData(0x00E0))
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }

    @Test
    @VirtualOnly
    fun startBleScan_withServiceData() {
        advertiseWithBumbleWithServiceData()

        val scanFilter =
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(TEST_UUID_STRING), TEST_SERVICE_DATA)
                .build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, false)

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    // Test against UUIDs that are close to TEST_UUID_STRING, one that has a few bits unset and one
    // that has an extra bit set.
    @Test
    @VirtualOnly
    fun startBleScan_withServiceData_uuidDoesntMatch(
        @TestParameter("00001800", "00001815") uuid: String
    ) {
        advertiseWithBumbleWithServiceData()

        val scanFilter =
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(uuid + TEST_UUID_SUFFIX), TEST_SERVICE_DATA)
                .build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, false)

        assertThat(results).isNull()
    }

    // PHY_LE_1M: 1, PHY_LE_CODED: 3, PHY_LE_ALL_SUPPORTED: 255
    @Test
    @VirtualOnly
    fun startBleScan_codedPhy(
        @TestParameter("1", "3", "255") phy: Int,
        @TestParameter advertiseCoded: Boolean,
    ) {
        advertiseWithBumbleWithServiceDataAndPhy(advertiseCoded)

        val scanFilter =
            ScanFilter.Builder()
                .setServiceData(ParcelUuid.fromString(TEST_UUID_STRING), TEST_SERVICE_DATA)
                .build()

        val results = startScanning(scanFilter, CALLBACK_TYPE_ALL_MATCHES, false, phy)

        if (advertiseCoded && phy == BluetoothDevice.PHY_LE_1M) {
            assertThat(results).isNull()
            return
        }

        if (!advertiseCoded && phy == BluetoothDevice.PHY_LE_CODED) {
            assertThat(results).isNull()
            return
        }

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
    }

    @Test
    fun startScan_scanType(@TestParameter isActive: Boolean) {
        val requestBuilder = AdvertiseRequest.newBuilder()

        // advertise data
        val dataTypeBuilder = HostProto.DataTypes.newBuilder()
        dataTypeBuilder.addCompleteServiceClassUuids128(TEST_UUID_STRING)
        requestBuilder.data = dataTypeBuilder.build()

        // scan response
        val responseDataTypeBuilder = HostProto.DataTypes.newBuilder()
        responseDataTypeBuilder.addCompleteServiceClassUuids128(TEST_UUID_STRING2)
        requestBuilder.scanResponseData = responseDataTypeBuilder.build()

        advertiseWithBumble(requestBuilder, true)

        val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TEST_UUID_STRING)).build()

        val results =
            startScanning(
                scanFilter,
                CALLBACK_TYPE_ALL_MATCHES,
                true,
                BluetoothDevice.PHY_LE_1M,
                if (isActive) ScanSettings.SCAN_TYPE_ACTIVE else ScanSettings.SCAN_TYPE_PASSIVE,
            )

        assertThat(results).isNotNull()
        assertThat(results!![0].scanRecord!!.serviceUuids[0])
            .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING))
        if (isActive) {
            assertThat(results[0].scanRecord!!.serviceUuids.size).isEqualTo(2)

            // scan response is added
            assertThat(results[0].scanRecord!!.serviceUuids[1])
                .isEqualTo(ParcelUuid.fromString(TEST_UUID_STRING2))
        } else {
            assertThat(results[0].scanRecord!!.serviceUuids.size).isEqualTo(1)
        }
    }

    private fun startScanning(
        scanFilter: ScanFilter,
        callbackType: Int,
        isLegacy: Boolean,
        phy: Int = BluetoothDevice.PHY_LE_1M,
        scanType: Int = ScanSettings.SCAN_TYPE_ACTIVE,
    ): List<ScanResult>? {
        val future = CompletableFuture<List<ScanResult>?>()
        val scanResults = mutableListOf<ScanResult>()

        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(callbackType)
                .setLegacy(isLegacy)
                .setPhy(phy)
                .setScanType(scanType)
                .build()

        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.i(
                        TAG,
                        "onScanResult address: ${result.device.address}" +
                            ", connectable: ${result.isConnectable}, callbackType: $callbackType" +
                            ", service uuids: ${result.scanRecord?.serviceUuids}",
                    )
                    scanResults.add(result)
                    if (callbackType != CALLBACK_TYPE_ALL_MATCHES || scanResults.size > 1) {
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

    private fun advertiseWithBumbleWithServiceData() {
        advertiseWithBumbleWithServiceDataAndPhy(false)
    }

    private fun advertiseWithBumbleWithServiceDataAndPhy(useCoded: Boolean) {
        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setOwnAddressType(OwnAddressType.PUBLIC)
                .setPrimaryPhy(if (useCoded) PrimaryPhy.PRIMARY_CODED else PrimaryPhy.PRIMARY_1M)

        val dataTypeBuilder = HostProto.DataTypes.newBuilder()
        dataTypeBuilder.addCompleteServiceClassUuids128(TEST_UUID_STRING)
        dataTypeBuilder.putServiceDataUuid128(
            TEST_UUID_STRING,
            ByteString.copyFrom(TEST_SERVICE_DATA),
        )
        requestBuilder.data = dataTypeBuilder.build()

        advertiseWithBumble(requestBuilder, false)
    }

    private fun advertiseWithBumble(serviceUuid: String?, addressType: OwnAddressType) {
        val requestBuilder = AdvertiseRequest.newBuilder().setOwnAddressType(addressType)

        if (serviceUuid != null) {
            val dataTypeBuilder = HostProto.DataTypes.newBuilder()
            dataTypeBuilder.addCompleteServiceClassUuids128(serviceUuid)
            requestBuilder.data = dataTypeBuilder.build()
        }

        advertiseWithBumble(requestBuilder, true)
    }

    private fun advertiseWithBumble(requestBuilder: AdvertiseRequest.Builder, isLegacy: Boolean) {
        requestBuilder.legacy = isLegacy
        // Collect and ignore responses.
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        bumble.host().advertise(requestBuilder.build(), responseObserver)
    }

    companion object {
        private const val TIMEOUT_SCANNING_MS = 5000L
        private const val TEST_UUID_STRING = "00001805-0000-1000-8000-00805f9b34fb"
        private const val TEST_UUID_STRING2 = "00001806-0000-1000-8000-00805f9b34fb"
        private const val TEST_ADDRESS_RANDOM_STATIC = "F0:43:A8:23:10:11"
        private const val ACTION_DYNAMIC_RECEIVER_SCAN_RESULT =
            "android.bluetooth.test.ACTION_DYNAMIC_RECEIVER_SCAN_RESULT"
        private val TEST_SERVICE_DATA = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        private const val TEST_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    }
}
