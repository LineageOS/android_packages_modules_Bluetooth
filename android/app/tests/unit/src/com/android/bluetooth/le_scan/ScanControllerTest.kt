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
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanCallback.NO_ERROR
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH
import android.companion.CompanionDeviceManager
import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.BatteryStatsManager
import android.os.IBinder
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

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var batteryStatsManager: BatteryStatsManager
    @Mock private lateinit var scanManager: ScanManager
    @Mock private lateinit var scanNativeInterface: ScanNativeInterface
    @Mock private lateinit var periodicScanManager: PeriodicScanManager
    @Mock private lateinit var periodicScanNativeInterface: PeriodicScanNativeInterface
    @Mock private lateinit var companionDeviceManager: CompanionDeviceManager
    @Mock private lateinit var timeProvider: TimeProvider

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val device = getTestDevice(TEST_ADDRESS)
    private val source = AttributionSource.myAttributionSource()

    private lateinit var scanController: ScanController

    @Before
    fun setUp() {
        adapterService.mockResources()
        adapterService.mockPackageManager(context.packageManager)
        adapterService.mockGetRemoteDevice(device)
        adapterService.mockGetSystemService<LocationManager>()
        adapterService.mockGetSystemService<AppOpsManager>()
        doReturn(adapterService)
            .whenever(adapterService)
            .createPackageContextAsUser(any(), any(), any())
        doReturn(context.getSharedPreferences("ScanControllerTest", Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(any<String>(), any<Int>())

        scanController =
            ScanController(
                adapterService,
                scanManager,
                scanNativeInterface,
                periodicScanManager,
                periodicScanNativeInterface,
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
    fun onScanResult_remoteException_clientDied() {
        val callback = mock<IScannerCallback>()
        val app = addScannerApp(callback = callback)
        val client =
            createScanClient(
                app,
                settings = createSettings(legacy = false),
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        doReturn(TEST_ADDRESS).whenever(adapterService).getIdentityAddress(any<String>())
        doReturn(setOf(client)).whenever(scanManager).regularScanQueue

        // Simulate remote client crash
        doThrow(RemoteException()).whenever(callback).onScanResult(any())

        scanController.onScanResult(
            /* eventType = */ 0x0A, // scannable and scan response
            /* addressType = */ 0,
            /* address = */ TEST_ADDRESS,
            /* primaryPhy = */ 0,
            /* secondaryPhy = */ 0,
            /* advertisingSid = */ 0,
            /* txPower = */ 0,
            /* rssi = */ 0,
            /* periodicAdvInt = */ 0,
            /* advData = */ ByteArray(0),
            /* originalAddress = */ TEST_ADDRESS,
        )

        assertThat(client.appDied).isTrue()
        verify(scanManager).stopScan(client.scannerId)
    }

    @Test
    fun onScanResult_multipleClients_oneMatchesFilter() {
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
        val matchingCallback = mock<IScannerCallback>()
        val matchingClient =
            createScanClient(
                addScannerApp(
                    callback = matchingCallback,
                    filters = listOf(ScanFilter.Builder().setDeviceName("TestDevice").build()),
                    scannerId = 1,
                ),
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )

        // Setup non-matching client
        val nonMatchingCallback = mock<IScannerCallback>()
        val nonMatchingClient =
            createScanClient(
                addScannerApp(
                    source = mock<AttributionSource>(),
                    callback = nonMatchingCallback,
                    filters = listOf(ScanFilter.Builder().setDeviceName("OtherDevice").build()),
                    scannerId = 2, // Different from above
                ),
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )

        doReturn(setOf(matchingClient, nonMatchingClient)).whenever(scanManager).regularScanQueue
        doReturn(deviceAddress).whenever(adapterService).getIdentityAddress(any<String>())

        scanController.onScanResult(
            /* eventType = */ 0x1B, // Connectable and scannable legacy advertising PDU
            /* addressType = */ 0,
            /* address = */ deviceAddress,
            /* primaryPhy = */ 1,
            /* secondaryPhy = */ 0,
            /* advertisingSid = */ 0xFF,
            /* txPower = */ 127,
            /* rssi = */ -50,
            /* periodicAdvInt = */ 0,
            /* advData = */ scanRecordBytes,
            /* originalAddress = */ deviceAddress,
        )

        // Verify that only the matching client received the scan result
        verify(matchingCallback).onScanResult(any<ScanResult>())
        assertThat(matchingClient.appScanStats.results).isEqualTo(1)

        // Verify that the non-matching client did not receive the scan result
        verify(nonMatchingCallback, never()).onScanResult(any<ScanResult>())
        assertThat(nonMatchingClient.appScanStats.results).isEqualTo(0)
    }

    @Test
    fun onScannerRegistered_success_callback() {
        val callback = mock<IScannerCallback> { doReturn(mock<IBinder>()).whenever(it).asBinder() }
        val app = addScannerApp(callback = callback)
        scanController.onScannerRegistered(NO_ERROR, TEST_SCANNER_ID, app.uuid)

        assertThat(app.scannerId).isEqualTo(TEST_SCANNER_ID)
        assertThat(app.deathRecipient).isNotNull()
        verify(callback).onScannerRegistered(NO_ERROR, TEST_SCANNER_ID)
    }

    @Test
    fun onBatchScanReportsInternal_deliverTruncatedBatchScan_expectResults() {
        verifyOnBatchScanReportsInternal(expectResults = true, isTruncated = true)
    }

    @Test
    fun onBatchScanReportsInternal_deliverTruncatedBatchScan_noResults() {
        verifyOnBatchScanReportsInternal(expectResults = false, isTruncated = true)
    }

    @Test
    fun onBatchScanReportsInternal_deliverFullBatchScan_expectResults() {
        verifyOnBatchScanReportsInternal(expectResults = true, isTruncated = false)
    }

    @Test
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
            NO_ERROR,
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
            NO_ERROR,
            TEST_SCANNER_ID,
            reportType,
            numRecords,
            recordData,
        )

        assertThat(scanController.scannerMap.getById(TEST_SCANNER_ID)).isNull()
    }

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
        val client =
            createScanClient(
                app = addScannerApp(callback = callback),
                hasScanWithoutLocationPermission = hasScanWithoutLocationPermission,
                associatedDevices = associatedDevices,
            )
        scanClientSet.add(client)
        if (isTruncated) {
            doReturn(scanClientSet).whenever(scanManager).batchScanQueue
        } else {
            doReturn(scanClientSet).whenever(scanManager).fullBatchScanQueue
        }

        scanController.onBatchScanReportsInternal(
            NO_ERROR,
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
    fun onTrackAdvFoundLost() {
        val addrType = ADDRESS_TYPE_RANDOM
        val callback = mock<IScannerCallback>()
        val client =
            createScanClient(
                app = addScannerApp(callback = callback),
                settings = createSettings(callbackType = CALLBACK_TYPE_FIRST_MATCH, legacy = false),
                hasNetworkSettingsPermission = true, // Bypass permission checks
            )
        doReturn(setOf(client)).whenever(scanManager).regularScanQueue
        doReturn(addrType).whenever(device).addressType
        doReturn(device).whenever(adapterService).getRemoteDevice(TEST_ADDRESS, addrType)

        val advtFilterOnFoundOnLostInfo =
            AdvtFilterOnFoundOnLostInfo(
                scannerId = TEST_SCANNER_ID,
                advPacketLen = 1,
                advPacket = ByteString.copyFrom(byteArrayOf(0x02)),
                scanResponseLen = 3,
                scanResponse = ByteString.copyFrom(byteArrayOf(0x04)),
                filtIndex = 5,
                advState = ScanController.ADVT_STATE_ONFOUND,
                advInfoPresent = 7,
                address = TEST_ADDRESS,
                addressType = addrType,
                txPower = 9,
                rssiValue = 10,
                timeStamp = 11,
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
        scanController.registerAndStartScan(
            mock<IScannerCallback>(),
            mock<WorkSource>(),
            source,
            false,
            createSettings(),
            listOf(ScanFilter.Builder().build()),
        )
        assertThat(scanController.scannerMap.getAppScanStatsByUid(source.uid)).isNotNull()
        verify(scanManager).registerScanner(any())
    }

    @Test
    fun unregisterScanner() {
        scanController.unregisterScanner(TEST_SCANNER_ID)
        assertThat(scanController.scannerMap.getById(TEST_SCANNER_ID)).isNull()
        verify(scanManager).unregisterScanner(TEST_SCANNER_ID)
    }

    @Test
    fun dispatchPendingIntentStartScanCheckUid() {
        val intent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val settings = createSettings()
        val filters = listOf(ScanFilter.Builder().build())
        val app = scanController.scannerMap.addWithPendingIntent(source, intent, settings, filters)
        // The following will call dispatchPendingIntentStartScan as the app has a pendingIntent
        scanController.onScannerRegistered(NO_ERROR, TEST_SCANNER_ID, app.uuid)
        verify(scanManager).startScan(argThat { client -> app.uid == client.appUid })
    }

    @Test
    fun flushPendingBatchResults() {
        val client =
            createScanClient(mock<ScannerApp> { doReturn(TEST_SCANNER_ID).whenever(it).scannerId })
        doReturn(setOf(client)).whenever(scanManager).batchScanQueue

        scanController.flushPendingBatchResults(TEST_SCANNER_ID)
        verify(scanManager).flushBatchScanResults(client)
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
        val rssiAboveThreshold = -40
        val rssiBelowThreshold = -60
        val client =
            createScanClient(settings = ScanSettings.Builder().setRssiThreshold(-50).build())

        val resultAboveThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiAboveThreshold, 0, mock<ScanRecord>(), 0)
        assertThat(ScanController.matchesFilters(client, resultAboveThreshold)).isTrue()

        val resultBelowThreshold =
            ScanResult(device, 0, 0, 0, 0, 0, rssiBelowThreshold, 0, mock<ScanRecord>(), 0)
        assertThat(ScanController.matchesFilters(client, resultBelowThreshold)).isFalse()
    }

    @Test
    fun matchesFilters_originalAddress() {
        // This address is different from device.getAddress()
        val originalAddress = "00:11:22:33:CC:DD"
        val mockScanRecord = mock<ScanRecord>()
        val client =
            createScanClient(
                addScannerApp(
                    filters = listOf(ScanFilter.Builder().setDeviceAddress(originalAddress).build())
                )
            )
        val scanResult = ScanResult(device, 0, 0, 0, 0, 0, 0, 0, mockScanRecord, 0)

        assertThat(ScanController.matchesFilters(client, scanResult, originalAddress)).isTrue()
    }

    @Test
    fun dump_doesNotCrash() {
        val sb = StringBuilder()
        scanController.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }

    private fun createSettings(
        callbackType: Int = ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
        legacy: Boolean = true,
    ) = ScanSettings.Builder().setCallbackType(callbackType).setLegacy(legacy).build()

    private fun addScannerApp(
        source: AttributionSource = this@ScanControllerTest.source,
        callback: IScannerCallback = mock<IScannerCallback>(),
        settings: ScanSettings = createSettings(),
        filters: List<ScanFilter> = listOf(ScanFilter.Builder().build()),
        scannerId: Int = TEST_SCANNER_ID,
    ) =
        scanController.scannerMap.addWithCallback(source, null, callback, settings, filters).apply {
            this.scannerId = scannerId
        }

    private fun createScanClient(
        app: ScannerApp = addScannerApp(),
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
        private const val TEST_ADDRESS = "00:11:22:33:FF:EE"

        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
