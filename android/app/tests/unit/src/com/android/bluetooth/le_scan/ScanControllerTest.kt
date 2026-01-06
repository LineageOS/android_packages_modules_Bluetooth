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
import android.os.WorkSource
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
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
    @Mock private lateinit var app: ScannerApp
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

        val appUid = 1234
        val scanSettings =
            ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(false)
                .build()
        val scanClient =
            ScanClient(
                appUid,
                TEST_SCANNER_ID,
                scanSettings,
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(app).appScanStats
        scanClient.appScanStats = appScanStats
        val callback = mock<IScannerCallback>()
        doReturn(callback).whenever(app).callback
        val scanClientSet = mutableSetOf(scanClient)
        doReturn(TEST_ADDRESS).whenever(adapterService).getIdentityAddress(any<String>())
        doReturn(scanClientSet).whenever(scanManager).regularScanQueue
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
            ScanClient(
                1000,
                matchingScannerId,
                matchingSettings,
                matchingFilters,
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
            ScanClient(
                1001,
                nonMatchingScannerId,
                nonMatchingSettings,
                nonMatchingFilters,
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
        verify(matchingAppScanStats).addResults(matchingScannerId, 1)

        // Verify that the non-matching client did not receive the scan result
        verify(nonMatchingCallback, never()).onScanResult(any<ScanResult>())
        verify(nonMatchingAppScanStats, never()).addResults(any<Int>(), any<Int>())
    }

    @Test
    @Throws(RemoteException::class)
    fun onScannerRegistered_success_callback() {
        val uuidLsb = 12345L
        val uuidMsb = 67890L
        val uuid = UUID(uuidMsb, uuidLsb)
        val callback = mock<IScannerCallback>()
        doReturn(callback).whenever(app).callback
        doReturn(ScanSettings.Builder().build()).whenever(app).settings
        doReturn(listOf<ScanFilter>()).whenever(app).filters
        doReturn(source).whenever(app).source
        doReturn(app).whenever(scannerMap).getByUuid(uuid)

        scanController.onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID, uuid)

        verify(app).linkToDeath(any())
        verify(callback).onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID)
        verify(app).id = TEST_SCANNER_ID
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
        doReturn(app).whenever(scannerMap).getById(TEST_SCANNER_ID)

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        // Verify that callbackDone is not called because the method returns early when client is
        // not found.
        verify(scanManager, never()).callbackDone(any<Int>(), any<Int>())
    }

    @Test
    fun onBatchScanReportsInternal_fullBatchScanNoClients() {
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
                0x00, // Note: Address type is not checked in mockGetRemoteDevice
                0x08,
                0x09,
                0x00,
                0x00,
                0x00,
                0x00,
            )

        adapterService.mockGetRemoteDevice(getTestDevice("02:00:00:00:00:00"))
        doReturn(setOf<ScanClient>()).whenever(scanManager).fullBatchScanQueue

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        if (!Flags.scanControllerThread()) {
            verify(scanManager).callbackDone(TEST_SCANNER_ID, TEST_STATUS)
        }
        verify(scannerMap, never()).getById(any<Int>())
    }

    @Throws(RemoteException::class)
    private fun verifyOnBatchScanReportsInternal(expectResults: Boolean, isTruncated: Boolean) {
        val reportType =
            if (isTruncated) ScanUtil.SCAN_RESULT_TYPE_TRUNCATED else ScanUtil.SCAN_RESULT_TYPE_FULL
        val numRecords = 1
        val recordData: ByteArray
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
                    0x00, // Note: Address type is not checked in mockGetRemoteDevice
                    0x08,
                    0x09,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                )
        }

        adapterService.mockGetRemoteDevice(getTestDevice("02:00:00:00:00:00"))
        val scanClientSet = mutableSetOf<ScanClient>()
        val appUid = 1234
        val associatedDevices =
            if (expectResults && isTruncated) listOf("02:00:00:00:00:00") else emptyList()
        val hasScanWithoutLocationPermission = expectResults && isTruncated.not()
        val scanClient =
            ScanClient(
                appUid,
                TEST_SCANNER_ID,
                hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
                associatedDevices = associatedDevices,
            )
        scanClientSet.add(scanClient)
        if (isTruncated) {
            doReturn(scanClientSet).whenever(scanManager).batchScanQueue
        } else {
            doReturn(scanClientSet).whenever(scanManager).fullBatchScanQueue
        }
        doReturn(app).whenever(scannerMap).getById(scanClient.scannerId)
        doReturn(mock<AppScanStats>()).whenever(app).appScanStats
        val callback = mock<IScannerCallback>()
        doReturn(callback).whenever(app).callback

        scanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )
        if (!Flags.scanControllerThread()) {
            verify(scanManager).callbackDone(TEST_SCANNER_ID, TEST_STATUS)
        }
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

        val appUid = 1234
        val scanClient =
            ScanClient(
                appUid,
                TEST_SCANNER_ID,
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        scanClient.settings =
            ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setLegacy(false)
                .build()
        val scanClientSet = mutableSetOf(scanClient)

        val mockApp = mock<ScannerApp>()
        val callback = mock<IScannerCallback>()
        doReturn(callback).whenever(mockApp).callback
        doReturn(mockApp).whenever(scannerMap).getById(TEST_SCANNER_ID)
        doReturn(scanClientSet).whenever(scanManager).regularScanQueue
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
    fun registerScanner() {
        val callback = mock<IScannerCallback>()
        val workSource = mock<WorkSource>()
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsByUid(Binder.getCallingUid())

        scanController.registerScanner(callback, workSource, source, false)
        verify(scannerMap)
            .addWithCallback(
                any<Int>(),
                any<Int>(),
                any<String>(),
                any<UUID>(),
                eq(source),
                eq(workSource),
                eq(callback),
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
        val pii =
            ScanController.PendingIntentInfo(
                null,
                ScanSettings.Builder().build(),
                filters,
                null,
                0,
                0,
            )
        doReturn(pii).whenever(app).info
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsById(TEST_SCANNER_ID)

        scanController.dispatchPendingIntentStartScan(TEST_SCANNER_ID, app)
        verify(appScanStats)
            .recordScanStart(pii.settings, pii.filters, false, false, TEST_SCANNER_ID, null)
        verify(scanManager).startScan(any())
    }

    @Test
    fun dispatchPendingIntentStartScanCheckUid() {
        val filters = emptyList<ScanFilter>()
        val pii =
            ScanController.PendingIntentInfo(
                null,
                ScanSettings.Builder().build(),
                filters,
                null,
                123,
                456,
            )
        doReturn(pii).whenever(app).info
        val appScanStats = mock<AppScanStats>()
        doReturn(appScanStats).whenever(scannerMap).getAppScanStatsById(TEST_SCANNER_ID)

        scanController.dispatchPendingIntentStartScan(TEST_SCANNER_ID, app)
        verify(appScanStats)
            .recordScanStart(pii.settings, pii.filters, false, false, TEST_SCANNER_ID, null)
        verify(scanManager).startScan(argThat { client -> pii.callingUid == client.appUid })
    }

    @Test
    fun flushPendingBatchResults() {
        val scanClientSet = mutableSetOf<ScanClient>()
        val appUid = 1234
        val scanClient = ScanClient(appUid, TEST_SCANNER_ID)
        scanClientSet.add(scanClient)
        doReturn(scanClientSet).whenever(scanManager).batchScanQueue

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
    @EnableFlags(Flags.FLAG_RSSI_SCAN_FILTER)
    fun matchesFilters_rssiThreshold() {
        val rssiThreshold = -50
        val rssiAboveThreshold = -40
        val rssiBelowThreshold = -60

        val settings = ScanSettings.Builder().setRssiThreshold(rssiThreshold).build()
        val appUid = 1234
        val client = ScanClient(appUid, TEST_SCANNER_ID, settings)

        val mockScanRecord = mock<ScanRecord>()
        val resultAboveThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiAboveThreshold, 0, mockScanRecord, 0)
        assertThat(ScanController.matchesFilters(client, resultAboveThreshold)).isTrue()

        val resultBelowThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiBelowThreshold, 0, mockScanRecord, 0)
        assertThat(ScanController.matchesFilters(client, resultBelowThreshold)).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ORIGINAL_ADDRESS_FILTER_MATCH)
    fun matchesFilters_originalAddress() {
        // This address is different from mDevice.getAddress()
        val originalAddress = "00:11:22:33:CC:DD"
        val filter = ScanFilter.Builder().setDeviceAddress(originalAddress).build()
        val filters = listOf(filter)
        val settings = ScanSettings.Builder().build()
        val mockScanRecord = mock<ScanRecord>()

        val appUid = 1234
        val client = ScanClient(appUid, TEST_SCANNER_ID, settings, filters)
        val scanResult = ScanResult(device, 0, 0, 0, 0, 0, 0, 0, mockScanRecord, 0)

        assertThat(ScanController.matchesFilters(client, scanResult, originalAddress)).isTrue()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        scanController.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }

    companion object {
        private const val TEST_SCANNER_ID = 1
        private const val TEST_STATUS = 0
        private const val TEST_ADDRESS = "00:11:22:33:FF:EE"

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() = FlagsWrapper.progressionOf(Flags.FLAG_SCAN_CONTROLLER_THREAD)
    }
}
