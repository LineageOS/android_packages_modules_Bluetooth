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

package com.android.bluetooth.le_scan

import android.app.AppOpsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.CompanionDeviceManager
import android.content.AttributionSource
import android.content.Context
import android.location.LocationManager
import android.os.BatteryStatsManager
import android.os.Binder
import android.os.RemoteException
import android.os.UserHandle
import android.os.WorkSource
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.le_scan.BatchScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS
import com.android.bluetooth.le_scan.BatchScanUtil.enforceReportDelayFloor
import com.android.bluetooth.le_scan.BatchScanUtil.parseTimestampNanos
import com.android.bluetooth.mockGetRemoteDevice
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.bluetooth.mockResources
import com.android.bluetooth.util.TimeProvider
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [ScanController]. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ScanControllerTest(flags: FlagsWrapper) {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var scanManager: ScanManager
    @Mock private lateinit var scanNativeInterface: ScanNativeInterface
    @Mock private lateinit var periodicScanManager: PeriodicScanManager
    @Mock private lateinit var periodicScanNativeInterface: PeriodicScanNativeInterface
    @Mock private lateinit var batteryStatsManager: BatteryStatsManager
    @Mock private lateinit var companionDeviceManager: CompanionDeviceManager
    @Mock private lateinit var scannerMap: ScannerMap
    @Mock private lateinit var timeProvider: TimeProvider

    private val device = getTestDevice(89)

    private lateinit var scanController: ScanController

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        adapterService.mockResources()
        adapterService.mockPackageManager(context.packageManager)
        adapterService.mockGetRemoteDevice(device)
        adapterService.mockGetSystemService<LocationManager>()
        adapterService.mockGetSystemService<AppOpsManager>()
        doReturn(adapterService).whenever(adapterService).createContextAsUser(any(), any())

        doReturn(context.packageName).whenever(source).packageName
        doReturn(context.getSharedPreferences("ScanControllerTest", Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(any<String>(), any<Int>())
        doReturn(TEST_ADDRESS).whenever(device).address

        scanController =
            ScanController(
                adapterService,
                scanManager,
                scanNativeInterface,
                periodicScanManager,
                periodicScanNativeInterface,
                scannerMap,
                batteryStatsManager,
                companionDeviceManager,
                TestLooper().looper,
                timeProvider,
            )
    }

    @After
    fun tearDown() {
        scanController.cleanup()
    }

    @Test
    fun notifyProfileConnectionStateChange_notify_scanManager() {
        scanController.notifyProfileConnectionStateChange(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_CONNECTING,
            BluetoothProfile.STATE_CONNECTED,
        )
        verify(scanManager)
            .handleBluetoothProfileConnectionStateChanged(
                BluetoothProfile.A2DP,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_CONNECTED,
            )
    }

    @Test
    @Throws(Exception::class)
    fun onScanResult_remoteException_clientDied() {
        // scannable and scan response
        val eventType = 0x0A
        val addressType = 0
        val primaryPhy = 0
        val secondPhy = 0
        val advertisingSid = 0
        val txPower = 0
        val rssi = 0
        val periodicAdvInt = 0
        val advData = ByteArray(0)
        val scanSettings =
            ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(false)
                .build()
        val appScanStats = mock<AppScanStats>()
        val callback = mock<IScannerCallback>()
        val app =
            mock<ScannerApp> {
                doReturn(TEST_SCANNER_ID).whenever(it).scannerId
                doReturn(appScanStats).whenever(it).appScanStats
                doReturn(callback).whenever(it).callback
            }
        val scanClient =
            createScanClient(
                app,
                scanSettings,
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        scanClient.appScanStats = appScanStats
        doReturn(TEST_ADDRESS).whenever(adapterService).getIdentityAddress(any<String>())
        doReturn(setOf(scanClient)).whenever(scanManager).regularScanQueue
        doReturn(app).whenever(scannerMap).getById(scanClient.scannerId)
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsById(scanClient.scannerId)

        // Simulate remote client crash
        doThrow(RemoteException()).whenever(callback).onScanResult(any())

        scanController.onScanResult(
            eventType,
            addressType,
            TEST_ADDRESS,
            primaryPhy,
            secondPhy,
            advertisingSid,
            txPower,
            rssi,
            periodicAdvInt,
            advData,
            TEST_ADDRESS,
        )

        assertThat(scanClient.appDied).isTrue()
        verify(appScanStats).recordScanStop(TEST_SCANNER_ID)
    }

    @Test
    @Throws(Exception::class)
    fun onScanResult_multipleClients_oneMatchesFilter() {
        // Setup common parameters for onScanResult
        val eventType = 0x1B // Connectable and scannable legacy advertising PDU
        val addressType = 0
        val primaryPhy = 1
        val secondPhy = 0
        val advertisingSid = 0xFF
        val txPower = 127
        val rssi = -50
        val periodicAdvInt = 0
        val bluetoothDevice = getTestDevice(0xAA)
        val deviceAddress = bluetoothDevice.address
        adapterService.mockGetRemoteDevice(bluetoothDevice)

        // Create a scan record for a device named "TestDevice"
        val scanRecordBytes =
            byteArrayOf(
                0x02,
                0x01,
                0x06, // AD Flags
                0x0B,
                0x09,
                'T'.code.toByte(),
                'e'.code.toByte(),
                's'.code.toByte(),
                't'.code.toByte(),
                'D'.code.toByte(),
                'e'.code.toByte(),
                'v'.code.toByte(),
                'i'.code.toByte(),
                'c'.code.toByte(),
                'e'.code.toByte(), // Complete Local Name
            )

        // Setup matching client
        val matchingScannerId = 1
        val matchingSettings =
            ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        val matchingFilters = listOf(ScanFilter.Builder().setDeviceName("TestDevice").build())
        val matchingClient =
            createScanClient(
                mock<ScannerApp> {
                    doReturn(1000).whenever(it).uid
                    doReturn(matchingScannerId).whenever(it).scannerId
                    doReturn(matchingFilters).whenever(it).filters
                },
                matchingSettings,
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )

        val matchingApp = mock<ScannerApp>()
        val matchingCallback = mock<IScannerCallback>()
        val matchingAppScanStats = mock<AppScanStats>()
        doReturn(matchingCallback).whenever(matchingApp).callback
        doReturn(matchingAppScanStats).whenever(matchingApp).appScanStats
        matchingClient.appScanStats = matchingAppScanStats

        // Setup non-matching client
        val nonMatchingScannerId = 2
        val nonMatchingSettings =
            ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        val nonMatchingFilters = listOf(ScanFilter.Builder().setDeviceName("OtherDevice").build())
        val nonMatchingClient =
            createScanClient(
                mock<ScannerApp> {
                    doReturn(1001).whenever(it).uid
                    doReturn(nonMatchingScannerId).whenever(it).scannerId
                    doReturn(nonMatchingFilters).whenever(it).filters
                },
                nonMatchingSettings,
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        val nonMatchingApp = mock<ScannerApp>()
        val nonMatchingCallback = mock<IScannerCallback>()
        val nonMatchingAppScanStats = mock<AppScanStats>()
        doReturn(nonMatchingCallback).whenever(nonMatchingApp).callback
        doReturn(nonMatchingAppScanStats).whenever(nonMatchingApp).appScanStats
        nonMatchingClient.appScanStats = nonMatchingAppScanStats

        // Mock dependencies
        doReturn(setOf(matchingClient, nonMatchingClient)).whenever(scanManager).regularScanQueue
        doReturn(matchingApp).whenever(scannerMap).getById(matchingScannerId)
        doReturn(nonMatchingApp).whenever(scannerMap).getById(nonMatchingScannerId)
        doReturn(deviceAddress).whenever(adapterService).getIdentityAddress(any<String>())

        // Execute the method under test
        scanController.onScanResult(
            eventType,
            addressType,
            deviceAddress,
            primaryPhy,
            secondPhy,
            advertisingSid,
            txPower,
            rssi,
            periodicAdvInt,
            scanRecordBytes,
            deviceAddress,
        )

        // Verify that only the matching client received the scan result
        verify(matchingCallback).onScanResult(any<ScanResult>())
        verify(matchingAppScanStats).addResults(matchingScannerId, 1, false)

        // Verify that the non-matching client did not receive the scan result
        verify(nonMatchingCallback, never()).onScanResult(any<ScanResult>())
        verify(nonMatchingAppScanStats, never()).addResults(any<Int>(), any<Int>(), any<Boolean>())
    }

    @Test
    @Throws(RemoteException::class)
    fun onScannerRegistered_success_callback() {
        val uuidLsb = 12345L
        val uuidMsb = 67890L
        val uuid = UUID(uuidMsb, uuidLsb)
        val callback = mock<IScannerCallback>()
        val app =
            mock<ScannerApp> {
                doReturn(callback).whenever(it).callback
                doReturn(ScanSettings.Builder().build()).whenever(it).settings
                doReturn(listOf<ScanFilter>()).whenever(it).filters
                doReturn(source).whenever(it).source
            }
        doReturn(app).whenever(scannerMap).getByUuid(uuid)

        scanController.onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID, uuid)

        verify(app).linkToDeath(any())
        verify(callback).onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID)
        verify(app).scannerId = TEST_SCANNER_ID
    }

    @Test
    @Throws(RemoteException::class)
    fun onBatchScanReportsInternal_deliverTruncatedBatchScan_expectResults() {
        verifyOnBatchScanReportsInternal(expectResults = true, isTruncated = true)
    }

    @Test
    @Throws(RemoteException::class)
    fun onBatchScanReportsInternal_deliverTruncatedBatchScan_noResults() {
        verifyOnBatchScanReportsInternal(expectResults = false, isTruncated = true)
    }

    @Test
    @Throws(RemoteException::class)
    fun onBatchScanReportsInternal_deliverFullBatchScan_expectResults() {
        verifyOnBatchScanReportsInternal(expectResults = true, isTruncated = false)
    }

    @Test
    @Throws(RemoteException::class)
    fun onBatchScanReportsInternal_deliverFullBatchScan_noResults() {
        verifyOnBatchScanReportsInternal(expectResults = false, isTruncated = false)
    }

    @Test
    fun onBatchScanReportsInternal_truncatedScanClientNotFound() {
        val reportType = ScanUtil.SCAN_RESULT_TYPE_TRUNCATED
        val numRecords = 1
        val recordData =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x02,
                0x06,
                0x04,
                0x02,
                0x02,
                0x00,
                0x00,
                0x02,
            )

        // Setup so that no client is found
        doReturn(setOf<ScanClient>()).whenever(scanManager).batchScanQueue

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )
    }

    @Test
    fun onBatchScanReportsInternal_fullBatchScanNoClients() {
        val addressType = ADDRESS_TYPE_PUBLIC
        val reportType = ScanUtil.SCAN_RESULT_TYPE_FULL
        val numRecords = 1
        val recordData =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x02,
                addressType.toByte(),
                0x08,
                0x09,
                0x00,
                0x00,
                0x00,
                0x00,
            )

        adapterService.mockGetRemoteDevice(getTestDevice("02:00:00:00:00:00", addressType))
        doReturn(setOf<ScanClient>()).whenever(scanManager).fullBatchScanQueue

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        verify(scannerMap, never()).getById(any<Int>())
    }

    @Throws(RemoteException::class)
    private fun verifyOnBatchScanReportsInternal(expectResults: Boolean, isTruncated: Boolean) {
        val reportType =
            if (isTruncated) ScanUtil.SCAN_RESULT_TYPE_TRUNCATED else ScanUtil.SCAN_RESULT_TYPE_FULL
        val numRecords = 1
        val recordData: ByteArray

        val addressTypeFromScanRecord: Byte = 0x03 // AddressType::RANDOM_IDENTITY_ADDRESS
        val expectedConvertedAddressType = ADDRESS_TYPE_RANDOM

        if (isTruncated) {
            recordData =
                byteArrayOf(
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x02,
                    0x06,
                    0x04,
                    0x02,
                    0x02,
                    0x00,
                    0x00,
                    0x02,
                )
        } else {
            recordData =
                byteArrayOf(
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x02,
                    addressTypeFromScanRecord,
                    0x08,
                    0x09,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                )
        }

        // TODO(b/469914545): Remove this comment when cleaning up the flag.
        // For the flag Flags.useAddressTypeFromBatchScanResult(),
        // When it is false, the address type is ignored, and the address type is not checked.
        // When it is true, the address type is converted, and the converted type should match.
        // In both cases, the test should pass.
        adapterService.mockGetRemoteDevice(
            getTestDevice("02:00:00:00:00:00", expectedConvertedAddressType)
        )
        val scanClientSet = mutableSetOf<ScanClient>()
        val associatedDevices =
            if (expectResults && isTruncated) listOf("02:00:00:00:00:00") else emptyList()
        val hasScanWithoutLocationPermission = expectResults && isTruncated.not()
        val callback = mock<IScannerCallback>()
        val app =
            mock<ScannerApp> {
                doReturn(TEST_SCANNER_ID).whenever(it).scannerId
                doReturn(callback).whenever(it).callback
                doReturn(mock<AppScanStats>()).whenever(it).appScanStats
            }
        val scanClient =
            createScanClient(
                app,
                hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
                associatedDevices = associatedDevices,
            )
        doReturn(app).whenever(scannerMap).getById(scanClient.scannerId)
        scanClientSet.add(scanClient)
        if (isTruncated) {
            doReturn(scanClientSet).whenever(scanManager).batchScanQueue
        } else {
            doReturn(scanClientSet).whenever(scanManager).fullBatchScanQueue
        }

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )
        if (expectResults) {
            verify(callback).onBatchScanResults(any())
        } else {
            verify(callback, never()).onBatchScanResults(any())
        }
    }

    @Test
    fun parseTimestampNanos() {
        val timestampNanos = parseTimestampNanos(byteArrayOf(-54, 7))
        assertThat(timestampNanos).isEqualTo(99700000000L)
    }

    @Test
    @Throws(RemoteException::class)
    fun onTrackAdvFoundLost() {
        val advPacketLen = 1
        val advPacket = byteArrayOf(0x02)
        val scanResponseLen = 3
        val scanResponse = byteArrayOf(0x04)
        val filtIndex = 5
        val advState = ScanController.ADVT_STATE_ONFOUND
        val advInfoPresent = 7
        val addrType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val txPower = 9
        val rssiValue = 10
        val timeStamp = 11
        val callback = mock<IScannerCallback>()
        val app =
            mock<ScannerApp> {
                doReturn(TEST_SCANNER_ID).whenever(it).scannerId
                doReturn(callback).whenever(it).callback
            }
        val scanClient =
            createScanClient(
                app,
                ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                    .setLegacy(false)
                    .build(),
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        doReturn(app).whenever(scannerMap).getById(TEST_SCANNER_ID)
        doReturn(setOf(scanClient)).whenever(scanManager).regularScanQueue
        doReturn(TEST_ADDRESS).whenever(device).address
        doReturn(addrType).whenever(device).addressType
        doReturn(device).whenever(adapterService).getRemoteDevice(TEST_ADDRESS, addrType)

        val advtFilterOnFoundOnLostInfo =
            AdvtFilterOnFoundOnLostInfo(
                TEST_SCANNER_ID,
                advPacketLen,
                ByteString.copyFrom(advPacket),
                scanResponseLen,
                ByteString.copyFrom(scanResponse),
                filtIndex,
                advState,
                advInfoPresent,
                TEST_ADDRESS,
                addrType,
                txPower,
                rssiValue,
                timeStamp,
            )

        scanController.onTrackAdvFoundLost(advtFilterOnFoundOnLostInfo)
        val resultCaptor = argumentCaptor<ScanResult>()
        verify(callback).onFoundOrLost(eq(true), resultCaptor.capture())
        assertThat(resultCaptor.firstValue.device).isNotNull()
        assertThat(resultCaptor.firstValue.device.address).isEqualTo(TEST_ADDRESS)
        assertThat(resultCaptor.firstValue.device.addressType).isEqualTo(addrType)
    }

    @Test
    fun registerAndStartScan() {
        val callback = mock<IScannerCallback>()
        val workSource = mock<WorkSource>()
        val appScanStats = mock<AppScanStats>()
        val settings = ScanSettings.Builder().build()
        val filters = listOf(ScanFilter.Builder().build())
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsByUid(Binder.getCallingUid())

        scanController.registerAndStartScan(callback, workSource, source, false, settings, filters)
        verify(scannerMap)
            .addWithCallback(
                any<Int>(),
                any<Int>(),
                any<String>(),
                any<UUID>(),
                eq(source),
                eq(workSource),
                eq(callback),
                eq(settings),
                eq(filters),
                eq(adapterService),
                eq(batteryStatsManager),
                eq(false),
            )
        verify(scanManager).registerScanner(any())
    }

    @Test
    fun unregisterScanner() {
        scanController.unregisterScanner(TEST_SCANNER_ID)

        verify(scannerMap).remove(TEST_SCANNER_ID)
        verify(scanManager).unregisterScanner(TEST_SCANNER_ID)
    }

    @Test
    fun dispatchPendingIntentStartScan() {
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder().build()
        val app =
            mock<ScannerApp> {
                doReturn(TEST_SCANNER_ID).whenever(it).scannerId
                doReturn(settings).whenever(it).settings
                doReturn(filters).whenever(it).filters
            }
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsById(TEST_SCANNER_ID)
        scanController.dispatchPendingIntentStartScan(app)
        verify(appScanStats).recordScanStart(settings, filters, false, false, TEST_SCANNER_ID, null)
        verify(scanManager).startScan(any())
    }

    @Test
    fun dispatchPendingIntentStartScanCheckUid() {
        val filters = emptyList<ScanFilter>()
        val uid = 123
        val settings = ScanSettings.Builder().build()
        val app =
            mock<ScannerApp> {
                doReturn(TEST_SCANNER_ID).whenever(it).scannerId
                doReturn(uid).whenever(it).uid
                doReturn(settings).whenever(it).settings
                doReturn(filters).whenever(it).filters
            }
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsById(TEST_SCANNER_ID)
        scanController.dispatchPendingIntentStartScan(app)
        verify(appScanStats).recordScanStart(settings, filters, false, false, TEST_SCANNER_ID, null)
        verify(scanManager).startScan(argThat { client -> uid == client.appUid })
    }

    @Test
    fun flushPendingBatchResults() {
        val scanClient =
            createScanClient(mock<ScannerApp> { doReturn(TEST_SCANNER_ID).whenever(it).scannerId })
        doReturn(setOf(scanClient)).whenever(scanManager).batchScanQueue

        scanController.flushPendingBatchResults(TEST_SCANNER_ID)
        verify(scanManager).flushBatchScanResults(scanClient)
    }

    @Test
    fun flushPendingBatchResults_clientNotFound() {
        // Setup so that no client is found
        doReturn(setOf<ScanClient>()).whenever(scanManager).batchScanQueue

        scanController.flushPendingBatchResults(TEST_SCANNER_ID)

        // Verify that flush is not called.
        verify(scanManager, never()).flushBatchScanResults(any())
    }

    @Test
    fun registerSync() {
        val sid = 123
        val skip = 1
        val timeout = 2
        val callback = mock<IPeriodicAdvertisingCallback>()

        scanController.registerSync(device, sid, skip, timeout, callback)
        verify(periodicScanManager).startSync(device, sid, skip, timeout, callback)
    }

    @Test
    fun registerSyncScanResult() {
        val scanResult = ScanResult(device, 1, 2, 3, 4, 5, 6, 7, null, 8)
        val skip = 1
        val timeout = 2
        val callback = mock<IPeriodicAdvertisingCallback>()

        scanController.registerSync(scanResult, skip, timeout, callback)
        verify(periodicScanManager).startSync(scanResult, skip, timeout, callback)
    }

    @Test
    fun unregisterSync() {
        val callback = mock<IPeriodicAdvertisingCallback>()

        scanController.unregisterSync(callback)
        verify(periodicScanManager).stopSync(callback)
    }

    @Test
    fun transferSync() {
        val serviceData = 1
        val syncHandle = 2

        scanController.transferSync(device, serviceData, syncHandle)
        verify(periodicScanManager).transferSync(device, serviceData, syncHandle)
    }

    @Test
    fun transferSetInfo() {
        val serviceData = 1
        val advHandle = 2
        val callback = mock<IPeriodicAdvertisingCallback>()

        scanController.transferSetInfo(device, serviceData, advHandle, callback)
        verify(periodicScanManager).transferSetInfo(device, serviceData, advHandle, callback)
    }

    @Test
    fun enforceReportDelayFloor() {
        val reportDelayFloorHigher = DEFAULT_REPORT_DELAY_FLOOR_MS + 1
        val scanSettings = ScanSettings.Builder().setReportDelay(reportDelayFloorHigher).build()
        val newScanSettings = enforceReportDelayFloor(scanSettings)

        assertThat(newScanSettings.reportDelayMillis).isEqualTo(scanSettings.reportDelayMillis)

        val scanSettingsFloor = ScanSettings.Builder().setReportDelay(1).build()
        val newScanSettingsFloor = enforceReportDelayFloor(scanSettingsFloor)

        assertThat(newScanSettingsFloor.reportDelayMillis).isEqualTo(DEFAULT_REPORT_DELAY_FLOOR_MS)
    }

    @Test
    fun matchesFilters_rssiThreshold() {
        val rssiThreshold = -50
        val rssiAboveThreshold = -40
        val rssiBelowThreshold = -60
        val client =
            createScanClient(
                mock<ScannerApp>(),
                ScanSettings.Builder().setRssiThreshold(rssiThreshold).build(),
            )

        val mockScanRecord = mock<ScanRecord>()
        val resultAboveThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiAboveThreshold, 0, mockScanRecord, 0)
        assertThat(ScanController.matchesFilters(client, resultAboveThreshold)).isTrue()

        val resultBelowThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiBelowThreshold, 0, mockScanRecord, 0)
        assertThat(ScanController.matchesFilters(client, resultBelowThreshold)).isFalse()
    }

    @Test
    fun matchesFilters_originalAddress() {
        // This address is different from mDevice.getAddress()
        val originalAddress = "00:11:22:33:CC:DD"
        val filter = ScanFilter.Builder().setDeviceAddress(originalAddress).build()
        val filters = listOf(filter)
        val mockScanRecord = mock<ScanRecord>()
        val client = createScanClient(mock<ScannerApp> { doReturn(filters).whenever(it).filters })
        val scanResult = ScanResult(device, 0, 0, 0, 0, 0, 0, 0, mockScanRecord, 0)

        assertThat(ScanController.matchesFilters(client, scanResult, originalAddress)).isTrue()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        scanController.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }

    private fun createScanClient(
        app: ScannerApp,
        settings: ScanSettings = ScanSettings.Builder().build(),
        hasNetworkSettingsPermission: Boolean = false,
        hasScanWithoutLocationPermission: Boolean = false,
        associatedDevices: List<String> = emptyList(),
    ) =
        ScanClient(
            app,
            settings,
            mock<UserHandle>(),
            eligibleForSanitizedExposureNotification = false,
            hasDisavowedLocation = false,
            hasLocationPermission = false,
            hasNetworkSettingsPermission = hasNetworkSettingsPermission,
            hasNetworkSetupWizardPermission = false,
            hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
            associatedDevices = associatedDevices,
        )

    companion object {
        private const val TEST_SCANNER_ID = 1
        private const val TEST_STATUS = 0
        private const val TEST_ADDRESS = "00:11:22:33:FF:EE"

        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
