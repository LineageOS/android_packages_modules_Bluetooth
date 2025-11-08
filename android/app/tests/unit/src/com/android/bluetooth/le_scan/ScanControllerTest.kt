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
import android.content.res.Resources
import android.location.LocationManager
import android.os.Binder
import android.os.RemoteException
import android.os.WorkSource
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetBluetoothManager
import com.android.bluetooth.TestUtils.mockGetRemoteDevice
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.BatchScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS
import com.android.bluetooth.le_scan.BatchScanUtil.enforceReportDelayFloor
import com.android.bluetooth.le_scan.BatchScanUtil.parseTimestampNanos
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [ScanController]. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ScanControllerTest(flags: FlagsWrapper) {
    @get:Rule val mMockitoRule = MockitoRule()
    @get:Rule val mSetFlagsRule = SetFlagsRule(flags.flags)

    @Mock private lateinit var mSource: AttributionSource
    @Mock private lateinit var mAdapterService: AdapterService
    @Mock private lateinit var mScanManager: ScanManager
    @Mock private lateinit var mScanNativeInterface: ScanNativeInterface
    @Mock private lateinit var mPeriodicScanManager: PeriodicScanManager
    @Mock private lateinit var mPeriodicScanNativeInterface: PeriodicScanNativeInterface
    @Mock private lateinit var mCompanionDeviceManager: CompanionDeviceManager
    @Mock private lateinit var mResources: Resources
    @Mock private lateinit var mScannerMap: ScannerMap
    @Mock private lateinit var mApp: ScannerApp
    @Mock private lateinit var mTimeProvider: TimeProvider

    private val mDevice: BluetoothDevice = getTestDevice(89)

    private lateinit var mScanController: ScanController

    @Before
    fun setUp() {
        doReturn(mResources).whenever(mAdapterService).resources

        val context = InstrumentationRegistry.getInstrumentation().context
        doReturn(context.packageManager).whenever(mAdapterService).packageManager
        doReturn(context.packageName).whenever(mSource).packageName
        doReturn(context.getSharedPreferences("ScanControllerTest", Context.MODE_PRIVATE))
            .whenever(mAdapterService)
            .getSharedPreferences(any<String>(), any<Int>())
        doReturn(TEST_ADDRESS).whenever(mDevice).address

        mockGetRemoteDevice(mAdapterService, mDevice)
        mockGetBluetoothManager(mAdapterService)
        mockGetSystemService(mAdapterService, LocationManager::class.java)
        mockGetSystemService(mAdapterService, AppOpsManager::class.java)

        mScanController =
            ScanController(
                mAdapterService,
                mScanManager,
                mScanNativeInterface,
                mPeriodicScanManager,
                mPeriodicScanNativeInterface,
                mScannerMap,
                mCompanionDeviceManager,
                TestLooper().looper,
                mTimeProvider,
            )
    }

    @After
    fun tearDown() {
        mScanController.cleanup()
    }

    @Test
    fun notifyProfileConnectionStateChange_notify_scanManager() {
        mScanController.notifyProfileConnectionStateChange(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_CONNECTING,
            BluetoothProfile.STATE_CONNECTED,
        )
        verify(mScanManager)
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
        val scanClient = ScanClient(appUid, TEST_SCANNER_ID, scanSettings)
        scanClient.hasNetworkSettingsPermission = true
        val appScanStats = mock(AppScanStats::class.java)
        doReturn(appScanStats).whenever(mApp).appScanStats
        scanClient.appScanStats = appScanStats
        val callback = mock(IScannerCallback::class.java)
        doReturn(callback).whenever(mApp).callback
        val scanClientSet = mutableSetOf(scanClient)
        doReturn(TEST_ADDRESS).whenever(mAdapterService).getIdentityAddress(any<String>())
        doReturn(scanClientSet).whenever(mScanManager).regularScanQueue
        doReturn(mApp).whenever(mScannerMap).getById(scanClient.scannerId)
        doReturn(appScanStats).whenever(mScannerMap).getAppScanStatsById(scanClient.scannerId)

        // Simulate remote client crash
        doThrow(RemoteException()).whenever(callback).onScanResult(any())

        mScanController.onScanResult(
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
        val device = getTestDevice(0xAA)
        val deviceAddress = device.address
        mockGetRemoteDevice(mAdapterService, device)

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
        val matchingClient = ScanClient(1000, matchingScannerId, matchingSettings, matchingFilters)
        matchingClient.hasNetworkSettingsPermission = true // Bypass permission checks
        val matchingApp = mock(ScannerApp::class.java)
        val matchingCallback = mock(IScannerCallback::class.java)
        val matchingAppScanStats = mock(AppScanStats::class.java)
        doReturn(matchingCallback).whenever(matchingApp).callback
        doReturn(matchingAppScanStats).whenever(matchingApp).appScanStats
        matchingClient.appScanStats = matchingAppScanStats

        // Setup non-matching client
        val nonMatchingScannerId = 2
        val nonMatchingSettings =
            ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        val nonMatchingFilters = listOf(ScanFilter.Builder().setDeviceName("OtherDevice").build())
        val nonMatchingClient =
            ScanClient(1001, nonMatchingScannerId, nonMatchingSettings, nonMatchingFilters)
        nonMatchingClient.hasNetworkSettingsPermission = true // Bypass permission checks
        val nonMatchingApp = mock(ScannerApp::class.java)
        val nonMatchingCallback = mock(IScannerCallback::class.java)
        val nonMatchingAppScanStats = mock(AppScanStats::class.java)
        doReturn(nonMatchingCallback).whenever(nonMatchingApp).callback
        doReturn(nonMatchingAppScanStats).whenever(nonMatchingApp).appScanStats
        nonMatchingClient.appScanStats = nonMatchingAppScanStats

        // Mock dependencies
        doReturn(setOf(matchingClient, nonMatchingClient)).whenever(mScanManager).regularScanQueue
        doReturn(matchingApp).whenever(mScannerMap).getById(matchingScannerId)
        doReturn(nonMatchingApp).whenever(mScannerMap).getById(nonMatchingScannerId)
        doReturn(deviceAddress).whenever(mAdapterService).getIdentityAddress(any<String>())

        // Execute the method under test
        mScanController.onScanResult(
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
        val callback = mock(IScannerCallback::class.java)
        doReturn(callback).whenever(mApp).callback
        doReturn(ScanSettings.Builder().build()).whenever(mApp).settings
        doReturn(listOf<ScanFilter>()).whenever(mApp).filters
        doReturn(mSource).whenever(mApp).source
        doReturn(mApp).whenever(mScannerMap).getByUuid(uuid)

        mScanController.onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID, uuid)

        verify(mApp).linkToDeath(any())
        verify(callback).onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID)
        verify(mApp).id = TEST_SCANNER_ID
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
        doReturn(setOf<ScanClient>()).whenever(mScanManager).batchScanQueue
        doReturn(mApp).whenever(mScannerMap).getById(TEST_SCANNER_ID)

        mScanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        // Verify that callbackDone is not called because the method returns early when client is
        // not found.
        verify(mScanManager, never()).callbackDone(any<Int>(), any<Int>())
    }

    @Test
    fun onBatchScanReportsInternal_fullBatchScanNoClients() {
        val reportType = ScanUtil.SCAN_RESULT_TYPE_FULL
        val numRecords = 1
        val recordData =
            byteArrayOf(
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x00,
                0x00,
                0x00,
                0x00,
            )

        val device = getTestDevice("02:00:00:00:00:00")
        mockGetRemoteDevice(mAdapterService, device)

        doReturn(setOf<ScanClient>()).whenever(mScanManager).fullBatchScanQueue

        mScanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        if (!Flags.scanControllerThread()) {
            verify(mScanManager).callbackDone(TEST_SCANNER_ID, TEST_STATUS)
        }
        verify(mScannerMap, never()).getById(any<Int>())
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
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08,
                    0x09,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                )
        }

        val device = getTestDevice("02:00:00:00:00:00")
        mockGetRemoteDevice(mAdapterService, device)

        val scanClientSet = mutableSetOf<ScanClient>()
        val appUid = 1234
        val scanClient = ScanClient(appUid, TEST_SCANNER_ID)
        if (expectResults) {
            if (isTruncated) {
                scanClient.associatedDevices = listOf("02:00:00:00:00:00")
            } else {
                scanClient.hasScanWithoutLocationPermission = true
            }
        }
        scanClientSet.add(scanClient)
        if (isTruncated) {
            doReturn(scanClientSet).whenever(mScanManager).batchScanQueue
        } else {
            doReturn(scanClientSet).whenever(mScanManager).fullBatchScanQueue
        }
        doReturn(mApp).whenever(mScannerMap).getById(scanClient.scannerId)
        doReturn(mock(AppScanStats::class.java)).whenever(mApp).appScanStats
        val callback = mock(IScannerCallback::class.java)
        doReturn(callback).whenever(mApp).callback

        mScanController.onBatchScanReportsInternal(
            TEST_STATUS,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )
        if (!Flags.scanControllerThread()) {
            verify(mScanManager).callbackDone(TEST_SCANNER_ID, TEST_STATUS)
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
        val scanClient = ScanClient(appUid, TEST_SCANNER_ID)
        scanClient.hasNetworkSettingsPermission = true
        scanClient.settings =
            ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setLegacy(false)
                .build()
        val scanClientSet = mutableSetOf(scanClient)

        val app = mock(ScannerApp::class.java)
        val callback = mock(IScannerCallback::class.java)
        doReturn(callback).whenever(app).callback
        doReturn(app).whenever(mScannerMap).getById(TEST_SCANNER_ID)
        doReturn(scanClientSet).whenever(mScanManager).regularScanQueue

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

        mScanController.onTrackAdvFoundLost(advtFilterOnFoundOnLostInfo)
        val resultCaptor = argumentCaptor<ScanResult>()
        verify(callback).onFoundOrLost(eq(true), resultCaptor.capture())
        assertThat(resultCaptor.firstValue.device).isNotNull()
        assertThat(resultCaptor.firstValue.device.address).isEqualTo(TEST_ADDRESS)
        assertThat(resultCaptor.firstValue.device.addressType).isEqualTo(addrType)
    }

    @Test
    fun registerScanner() {
        val callback = mock(IScannerCallback::class.java)
        val workSource = mock(WorkSource::class.java)
        val appScanStats = mock(AppScanStats::class.java)
        doReturn(appScanStats).whenever(mScannerMap).getAppScanStatsByUid(Binder.getCallingUid())

        mScanController.registerScanner(callback, workSource, mSource)
        verify(mScannerMap)
            .addWithCallback(
                any<Int>(),
                any<Int>(),
                any<String>(),
                any<UUID>(),
                eq(mSource),
                eq(workSource),
                eq(callback),
                eq(mAdapterService),
                eq(false),
            )
        verify(mScanManager).registerScanner(any())
    }

    @Test
    fun unregisterScanner() {
        mScanController.unregisterScanner(TEST_SCANNER_ID)

        verify(mScannerMap).remove(TEST_SCANNER_ID)
        verify(mScanManager).unregisterScanner(TEST_SCANNER_ID)
    }

    @Test
    fun continuePiStartScan() {
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
        doReturn(pii).whenever(mApp).info
        val appScanStats = mock(AppScanStats::class.java)
        doReturn(appScanStats).whenever(mScannerMap).getAppScanStatsById(TEST_SCANNER_ID)

        mScanController.continuePiStartScan(TEST_SCANNER_ID, mApp)
        verify(appScanStats)
            .recordScanStart(pii.settings, pii.filters, false, false, TEST_SCANNER_ID, null)
        verify(mScanManager).startScan(any())
    }

    @Test
    fun continuePiStartScanCheckUid() {
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
        doReturn(pii).whenever(mApp).info
        val appScanStats = mock(AppScanStats::class.java)
        doReturn(appScanStats).whenever(mScannerMap).getAppScanStatsById(TEST_SCANNER_ID)

        mScanController.continuePiStartScan(TEST_SCANNER_ID, mApp)
        verify(appScanStats)
            .recordScanStart(pii.settings, pii.filters, false, false, TEST_SCANNER_ID, null)
        verify(mScanManager).startScan(argThat { client -> pii.callingUid == client.appUid })
    }

    @Test
    fun flushPendingBatchResults() {
        val scanClientSet = mutableSetOf<ScanClient>()
        val appUid = 1234
        val scanClient = ScanClient(appUid, TEST_SCANNER_ID)
        scanClientSet.add(scanClient)
        doReturn(scanClientSet).whenever(mScanManager).batchScanQueue

        mScanController.flushPendingBatchResults(TEST_SCANNER_ID)
        verify(mScanManager).flushBatchScanResults(scanClient)
    }

    @Test
    fun flushPendingBatchResults_clientNotFound() {
        // Setup so that no client is found
        doReturn(setOf<ScanClient>()).whenever(mScanManager).batchScanQueue

        mScanController.flushPendingBatchResults(TEST_SCANNER_ID)

        // Verify that flush is not called.
        verify(mScanManager, never()).flushBatchScanResults(any())
    }

    @Test
    fun registerSync() {
        val sid = 123
        val skip = 1
        val timeout = 2
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        mScanController.registerSync(mDevice, sid, skip, timeout, callback)
        verify(mPeriodicScanManager).startSync(mDevice, sid, skip, timeout, callback)
    }

    @Test
    fun registerSyncScanResult() {
        val scanResult = ScanResult(mDevice, 1, 2, 3, 4, 5, 6, 7, null, 8)
        val skip = 1
        val timeout = 2
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        mScanController.registerSync(scanResult, skip, timeout, callback)
        verify(mPeriodicScanManager).startSync(scanResult, skip, timeout, callback)
    }

    @Test
    fun unregisterSync() {
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        mScanController.unregisterSync(callback)
        verify(mPeriodicScanManager).stopSync(callback)
    }

    @Test
    fun transferSync() {
        val serviceData = 1
        val syncHandle = 2

        mScanController.transferSync(mDevice, serviceData, syncHandle)
        verify(mPeriodicScanManager).transferSync(mDevice, serviceData, syncHandle)
    }

    @Test
    fun transferSetInfo() {
        val serviceData = 1
        val advHandle = 2
        val callback = mock(IPeriodicAdvertisingCallback::class.java)

        mScanController.transferSetInfo(mDevice, serviceData, advHandle, callback)
        verify(mPeriodicScanManager).transferSetInfo(mDevice, serviceData, advHandle, callback)
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

        val mockScanRecord = mock(ScanRecord::class.java)
        val resultAboveThreshold =
            ScanResult(mDevice, 0, 0, 0, 0, 0, rssiAboveThreshold, 0, mockScanRecord, 0)
        assertThat(ScanController.matchesFilters(client, resultAboveThreshold)).isTrue()

        val resultBelowThreshold =
            ScanResult(mDevice, 0, 0, 0, 0, 0, rssiBelowThreshold, 0, mockScanRecord, 0)
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
        val mockScanRecord = mock(ScanRecord::class.java)

        val appUid = 1234
        val client = ScanClient(appUid, TEST_SCANNER_ID, settings, filters)
        val scanResult = ScanResult(mDevice, 0, 0, 0, 0, 0, 0, 0, mockScanRecord, 0)

        assertThat(ScanController.matchesFilters(client, scanResult, originalAddress)).isTrue()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        mScanController.dump(sb)
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
