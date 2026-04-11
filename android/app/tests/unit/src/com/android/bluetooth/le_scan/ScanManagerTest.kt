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

import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.app.ActivityManager
import android.app.AlarmManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProtoEnums
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryStatsManager
import android.os.Binder
import android.os.Bundle
import android.os.ParcelUuid
import android.os.SystemProperties
import android.os.UserHandle
import android.os.WorkSource
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.test.mock.MockContentProvider
import android.test.mock.MockContentResolver
import android.util.Log
import android.util.SparseIntArray
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils.mockSystemPropertyGet
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.DisplayListener
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.ScanMetricsReporter.Companion.convertScanMode
import com.android.bluetooth.le_scan.ScanThrottler.Companion.ALLOWANCE_REFILL_WINDOW
import com.android.bluetooth.le_scan.ScanUtil.DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING
import com.android.bluetooth.le_scan.ScanUtil.DEFAULT_SCAN_TIMEOUT
import com.android.bluetooth.le_scan.ScanUtil.DEFAULT_SCAN_UPGRADE_DURATION
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW
import com.android.bluetooth.le_scan.ScanUtil.convertAllowanceToRemainingTime
import com.android.bluetooth.le_scan.ScanUtil.getScanAllowance
import com.android.bluetooth.metrics.MetricsLogger
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.bluetooth.mockResources
import com.android.bluetooth.util.WorkSourceUtil
import com.android.tests.bluetooth.FakeTimeProvider
import com.android.tests.bluetooth.staticMockitoRule
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import java.util.UUID
import kotlin.time.ExperimentalTime
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val TAG = "ScanManagerTest"

/** Test cases for [ScanManager]. */
@OptIn(ExperimentalTime::class)
@SmallTest
@RunWith(TestParameterInjector::class)
class ScanManagerTest() {
    @get:Rule val mockitoRule = staticMockitoRule<SystemProperties>()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var displayListener: DisplayListener
    @Mock private lateinit var locationManager: LocationManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var batteryStatsManager: BatteryStatsManager
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var nativeCallback: ScanNativeCallback
    @Mock private lateinit var nativeInterface: ScanNativeInterface
    @Mock private lateinit var scanController: ScanController

    private val timeProvider = FakeTimeProvider()
    private val scanRadioStats = ScanRadioStats(timeProvider)

    private lateinit var appScanStats: AppScanStats
    private lateinit var scanManager: ScanManager
    private lateinit var looper: TestLooper
    private lateinit var inOrder: InOrder

    private var scanReportDelay = 0L
    private var scannerId = 0

    @Before
    fun setUp() {
        doReturn(displayListener).whenever(adapterService).displayListener
        doReturn(DEFAULT_SCAN_TIMEOUT).whenever(adapterService).scanTimeout
        doReturn(DEFAULT_NUM_OFFLOAD_SCAN_FILTER)
            .whenever(adapterService)
            .numOfOffloadedScanFilterSupported
        doReturn(DEFAULT_BYTES_OFFLOAD_SCAN_RESULT_STORAGE)
            .whenever(adapterService)
            .offloadedScanResultStorage
        doReturn(TEST_SCAN_QUOTA_COUNT).whenever(adapterService).scanQuotaCount
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW)
            .whenever(adapterService)
            .screenOffLowPowerWindow
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW)
            .whenever(adapterService)
            .screenOffBalancedWindow
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL)
            .whenever(adapterService)
            .screenOffLowPowerInterval
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL)
            .whenever(adapterService)
            .screenOffBalancedInterval
        doReturn(DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS)
            .whenever(adapterService)
            .totalNumOfTrackableAdvertisements

        adapterService.mockGetSystemService<LocationManager>(locationManager)
        doReturn(true).whenever(locationManager).isLocationEnabled
        adapterService.mockGetSystemService<BatteryStatsManager>(batteryStatsManager)
        adapterService.mockGetSystemService<AlarmManager>()
        adapterService.mockPackageManager(packageManager)

        val context = InstrumentationRegistry.getInstrumentation().context
        adapterService.mockResources(context.resources)
        val mockContentResolver = MockContentResolver(context)
        mockContentResolver.addProvider(
            Settings.AUTHORITY,
            object : MockContentProvider() {
                override fun call(method: String, request: String?, args: Bundle?): Bundle? {
                    return Bundle.EMPTY
                }
            },
        )
        doReturn(mockContentResolver).whenever(adapterService).contentResolver
        // Needed to mock Native call/callback when hw offload scan filter is enabled
        simulateIsOffloadFilteringSupported(true)

        MetricsLogger.setInstanceForTesting(metricsLogger)
        inOrder = Mockito.inOrder(metricsLogger)

        doReturn(context.user).whenever(adapterService).user
        doReturn(context.packageName).whenever(adapterService).packageName

        scannerId = 0
        looper = TestLooper()
        scanManager =
            ScanManager(
                adapterService,
                scanController,
                nativeCallback,
                nativeInterface,
                scanRadioStats,
                looper.looper,
                timeProvider,
            )

        scanReportDelay = DEFAULT_BATCH_SCAN_REPORT_DELAY_MS.toLong()
        val appUid = 1234
        val appPid = 5678
        val workSource = WorkSource(appUid, TEST_PACKAGE_NAME)
        val workSourceUtil = WorkSourceUtil(workSource)
        val metricsReporter = ScanMetricsReporter(workSource, workSourceUtil, batteryStatsManager)
        appScanStats =
            spy(
                AppScanStats(
                    appUid,
                    appPid,
                    TEST_APP_NAME,
                    workSourceUtil,
                    adapterService,
                    metricsReporter,
                    timeProvider,
                )
            )
    }

    @After
    fun tearDown() {
        MetricsLogger.setInstanceForTesting(null)
        MetricsLogger.getInstance()
    }

    @Test
    fun testScreenOffStartUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOffStartFilteredScan() {
        // Set filtered scan flag
        val isFiltered = true
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOffStartEmptyFilterScan() {
        // Set filtered scan flag
        val isFiltered = true
        val isEmptyFilter = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode, isEmptyFilter = isEmptyFilter)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOnStartUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOnStartFilteredScan() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testResumeUnfilteredScanAfterScreenOn() {
        // Set filtered scan flag
        val isFiltered = false
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testResumeFilteredScanAfterScreenOn() {
        // Set filtered scan flag
        val isFiltered = true
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testUnfilteredScanTimeout() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_OPPORTUNISTIC
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Wait for scan timeout
            advanceTime(DEFAULT_SCAN_TIMEOUT)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
            // Turn off screen
            setScreenOn(false)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testFilteredScanTimeout() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan, this sends scan timeout message with delay
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so scan timeout message can be dispatched
            advanceTime(DEFAULT_SCAN_TIMEOUT)
            // Since we are using a TestLooper, need to mock AppScanStats.isScanningTooLong
            // to return true because no real time is elapsed
            doReturn(true).whenever(appScanStats).isScanningTooLong()
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
            // Turn off screen
            setScreenOn(false)
            assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
            // Turn on screen
            setScreenOn(true)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanTimeoutResetForNewScan() {
        // Set filtered scan flag
        val isFiltered = false
        // Turn on screen
        setScreenOn(true)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)

        // Put a timeout runnable in the map to emulate the scan being started already
        val fakeTimeoutRunnable = Runnable {}
        scanManager.mScanTimeoutRunnables!![client] = fakeTimeoutRunnable
        scanManager.mHandler!!.postDelayed(
            fakeTimeoutRunnable,
            DEFAULT_SCAN_TIMEOUT.dividedBy(2).toMillis(),
        )
        // Start the scan. This should remove the fake runnable and post a new one.
        startScan(client)

        // Verify that only the new, real runnable is in the map.
        assertThat(scanManager.mScanTimeoutRunnables).hasSize(1)

        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2))
        // After restarting the scan, we can check that the initial timeout message is not triggered
        assertThat(looper.dispatchAll()).isEqualTo(0)

        // After timeout, the next message that is run should be a timeout message
        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2))

        // Dispatching should now execute the real timeout.
        looper.dispatchAll()
        // Verify the client was moved to opportunistic mode, proving the timeout logic ran.
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
        assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testFilteredScanOutOfAllowance(@TestParameter isFiltered: Boolean) {
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )
        scanModeMap.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Move time forward to clear any pending refill job
            advanceTime(
                ALLOWANCE_REFILL_WINDOW -
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance
            )
            this@ScanManagerTest.looper.dispatchAll()
            // Start scan, this sends scan allowance check message with delay
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so scan allowance check message can be dispatched
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance >=
                        getScanAllowance()
                )
                .isTrue()
            // Turn off screen
            setScreenOn(false)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowanceForNewScan() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        // Create scan client
        val scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        val client = createScanClient(isFiltered, scanMode)

        // Put a record usage runnable in the map to emulate the scan being started already
        val fakeStepDownRunnable = Runnable {}
        scanManager.mScanThrottler.recordUsageRunnables!![client] = fakeStepDownRunnable
        scanManager.mHandler!!.postDelayed(
            fakeStepDownRunnable,
            (convertAllowanceToRemainingTime(getScanAllowance(), scanMode) / 2).inWholeMilliseconds,
        )
        // Start the scan. This should remove the fake runnable and post a new one.
        startScan(client)

        // Verify that the new, real record usage runnable is in the map.
        assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(1)

        advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode) / 2)
        // After restarting the scan, we can check that the initial record allowace usage message is
        // not triggered
        assertThat(looper.dispatchAll()).isEqualTo(0)

        // The next job should record allowance usage
        advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode) / 2)

        // Dispatching should now record allowance usage.
        looper.dispatchAll()
        // Verify the client was moved to SCREEN_OFF mode
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
        assertThat(
                client.appScanStats!!.scanAllowanceLedger.spentScanAllowance >= getScanAllowance()
            )
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowanceSkipped_scanModeScreenOff() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        // Create scan client
        val scanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
        val client = createScanClient(isFiltered, scanMode)

        // Start the scan.
        startScan(client)

        // Verify that therer is no record usage runnable in the map.
        assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(0)

        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
        assertThat(
                client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                    kotlin.time.Duration.ZERO
            )
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowanceRefill_recordUsageNotScheduled_noRefillJobScheduled() {
        // Set filtered scan flag
        val isFiltered = true
        val scanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
        // Turn on screen
        setScreenOn(true)
        // Create scan client
        val client = createScanClient(isFiltered, scanMode)
        startScan(client)
        assertThat(client.settings.scanMode).isEqualTo(scanMode)
        // Verify that therer is no record usage runnable in the map.
        assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(0)
        // Verify that therer is no refill runnable in the map.
        assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowanceRefill_recordUsageNotRun_noRefillJobScheduled() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan, this sends scan allowance check message with delay
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Verify that the record usage runnable is in the map.
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(scannerCount)
            // Verify that therer is no refill runnable in the map.
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                        kotlin.time.Duration.ZERO
                )
                .isTrue()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowanceRefill_refillJobRun() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )
        scanModeMap.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan, this sends scan allowance check message with delay
            startScan(client)
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(scannerCount)
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so record usage message can be dispatched
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(0)
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(1)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance >=
                        getScanAllowance()
                )
                .isTrue()
            // Move time forward so refill message can be dispatched
            advanceTime(
                ALLOWANCE_REFILL_WINDOW -
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance
            )
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(scannerCount)
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                        kotlin.time.Duration.ZERO
                )
                .isTrue()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanWithLargeAllowance(@TestParameter isFiltered: Boolean) {
        mockSystemPropertyGet(ScanUtil.SCAN_ALLOWANCE_SECONDS_PROPERTY, 24 * 60 * 60) // 24h
        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Move time forward to clear any pending refill job
            advanceTime(
                ALLOWANCE_REFILL_WINDOW -
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance
            )
            this@ScanManagerTest.looper.dispatchAll()
            // Start scan, this sends scan allowance check message with delay
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so scan allowance check message can be dispatched
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Allowance is refilled right after record allowance usage
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                        kotlin.time.Duration.ZERO
                )
                .isTrue()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanWithPrivilegedPermission_skipAllowanceCheck(@TestParameter isFiltered: Boolean) {
        mockPrivilegedPermission()
        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(0)
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
            // Move time forward to assert there is no scheduled jobs on the handler thread
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                        kotlin.time.Duration.ZERO
                )
                .isTrue()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanWithForegroundUi_skipAllowanceCheck(@TestParameter isFiltered: Boolean) {
        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Move uid to foreground UI
            setAppImportance(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                Binder.getCallingUid(),
            )
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            assertThat(scanManager.mScanThrottler.recordUsageRunnables).hasSize(0)
            assertThat(scanManager.mScanThrottler.refillRunnables).hasSize(0)
            // Move time forward to assert there is no scheduled jobs on the handler thread
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance ==
                        kotlin.time.Duration.ZERO
                )
                .isTrue()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testDelayedScreenOffThrottle_throttleJobRun() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_SCREEN_OFF,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to
                    ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
            )
        scanModeMap.forEach { (scanMode, throttledScanMode) ->
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode throttledScanMode: $throttledScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            startScan(client)
            // Turn off screen, this schedules screen off throttle job with delay
            setScreenOn(false)
            assertThat(scanManager.mScanThrottler.pendingScreenOffThrottleTask).isNotNull()
            // Scan mode is not throttled yet
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so delayed screen off throttle job can run
            advanceTime(ScanUtil.DEFAULT_SCAN_THROTTLE_DELAY)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.mScanThrottler.pendingScreenOffThrottleTask).isNull()
            assertThat(client.settings.scanMode).isEqualTo(throttledScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testDelayedScreenOffThrottle_screenOn_cancelPendingJob() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_SCREEN_OFF,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to
                    ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
            )
        scanModeMap.forEach { (scanMode, throttledScanMode) ->
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode throttledScanMode: $throttledScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            startScan(client)
            // Turn off screen, this schedules screen off throttle job with delay
            setScreenOn(false)
            assertThat(scanManager.mScanThrottler.pendingScreenOffThrottleTask).isNotNull()
            // Scan mode is not throttled yet
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Turn on screen, this cancel any pending delayed screen off throttle job
            setScreenOn(true)
            assertThat(scanManager.mScanThrottler.pendingScreenOffThrottleTask).isNull()
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testDelayedBackgroundUidThrottle_throttleJobRun() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_LOW_POWER,
            )
        scanModeMap.forEach { (scanMode, throttledScanMode) ->
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode throttledScanMode: $throttledScanMode")
            // Turn on screen and set as foreground app
            setScreenOn(true)
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            startScan(client)
            // Set as background app, this schedules background uid throttle job with delay
            setAppImportance(false, Binder.getCallingUid())
            assertThat(scanManager.mScanThrottler.backgroundUidThrottleRunnables).hasSize(1)
            // Scan mode is not throttled yet
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Move time forward so delayed background uid throttle job can run
            advanceTime(ScanUtil.DEFAULT_SCAN_THROTTLE_DELAY)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.mScanThrottler.backgroundUidThrottleRunnables).hasSize(0)
            assertThat(client.settings.scanMode).isEqualTo(throttledScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testDelayedBackgroundUidThrottle_changeToForeground_cancelPendingJob() {
        // Set filtered scan flag
        val isFiltered = true
        var scannerCount = 0
        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_LOW_POWER,
            )
        scanModeMap.forEach { (scanMode, throttledScanMode) ->
            scannerId += 1
            scannerCount += 1
            Log.d(TAG, "ScanMode: $scanMode throttledScanMode: $throttledScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            startScan(client)
            // Set as background app, this schedules background uid throttle job with delay
            setAppImportance(false, Binder.getCallingUid())
            assertThat(scanManager.mScanThrottler.backgroundUidThrottleRunnables).hasSize(1)
            // Scan mode is not throttled yet
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set as foreground app, this cancel any pending delayed background uid throttle job
            setAppImportance(true, Binder.getCallingUid())
            assertThat(scanManager.mScanThrottler.backgroundUidThrottleRunnables).hasSize(0)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testSwitchForeBackgroundUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testSwitchForeBackgroundFilteredScan() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testUpgradeStartScan() {
        // Set filtered scan flag
        val isFiltered = true
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
        )
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).whenever(adapterService).scanUpgradeDuration

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Wait for upgrade duration
            advanceTime(DEFAULT_SCAN_UPGRADE_DURATION)
            looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testUpDowngradeStartScanForConcurrency() {
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).whenever(adapterService).scanUpgradeDuration
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_BALANCED
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Set connecting state
            setConnectingState(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Wait for upgrade and downgrade duration
            val maxDuration: Duration =
                if (
                    DEFAULT_SCAN_UPGRADE_DURATION.compareTo(
                        DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING
                    ) > 0
                )
                    DEFAULT_SCAN_UPGRADE_DURATION
                else DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING
            advanceTime(maxDuration)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testDowngradeDuringScanForConcurrency() {
        // Set filtered scan flag
        val isFiltered = true
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
        )

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            looper.dispatchAll()
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
        }
    }

    @Test
    fun testDowngradeDuringScanForConcurrencyScreenOff() {
        // Set filtered scan flag
        val isFiltered = true
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED,
        )

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            // Turn off screen
            setScreenOn(false)
            // Move time forward so that stop connecting action can be dispatched
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            looper.dispatchAll()
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testDowngradeDuringScanForConcurrencyBackground() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testDowngradeDuringScanForConcurrencyOutOfAllowance() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        // Set filtered scan flag
        val isFiltered = true

        val scanModeMap =
            mapOf(
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )
        scanModeMap.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            // Move time forward so record allowance usage message can be dispatched
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(
                    client.appScanStats!!.scanAllowanceLedger.spentScanAllowance >=
                        getScanAllowance()
                )
                .isTrue()
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
            // Set connecting state
            setConnectingState(true)
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(client.settings.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testStartUnfilteredBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = false
        val isBatch = true
        val isAutoBatch = false
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(scanManager.batchScanQueue).doesNotContain(client)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).contains(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testStartFilteredBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = true
        val isBatch = true
        val isAutoBatch = false
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).contains(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowance_BatchScan() {
        val isFiltered = true
        val isBatch = true
        val isAutoBatch = false
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
            // Move time forward so scan allowance check message can be dispatched
            advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), scanMode))
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).contains(client)
            // Allowance is refilled immediately for SCAN_MODE_LOW_POWER, so scan mode does not
            // change. For other scan mode,
            // allowance refill job is scheduled and scan mode is lowered to SCAN_MODE_SCREEN_OFF
            // due to out of allowance
            assertThat(scanManager.batchScanParams.scanMode)
                .isEqualTo(
                    if (scanMode == ScanSettings.SCAN_MODE_LOW_POWER)
                        ScanSettings.SCAN_MODE_LOW_POWER
                    else ScanSettings.SCAN_MODE_SCREEN_OFF
                )
            // Move time forward so refill message can be dispatched
            advanceTime(ALLOWANCE_REFILL_WINDOW)
            this@ScanManagerTest.looper.dispatchAll()
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SCAN_ALLOWANCE_THROTTLING_ENABLED)
    fun testScanAllowance_BatchScanOutOfAllowance() {
        val isFiltered = true
        val isBatch = true
        val isAutoBatch = false
        val previousScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        val client =
            createScanClient(
                isFiltered,
                previousScanMode,
                isBatch = isBatch,
                isAutoBatch = isAutoBatch,
            )
        startScan(client)
        // Move time forward so scan allowance check message can be dispatched
        advanceTime(convertAllowanceToRemainingTime(getScanAllowance(), previousScanMode))
        this@ScanManagerTest.looper.dispatchAll()
        assertThat(
                client.appScanStats!!.scanAllowanceLedger.spentScanAllowance >= getScanAllowance()
            )
            .isTrue()

        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_SCREEN_OFF)
        scanModeMap.put(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY, ScanSettings.SCAN_MODE_SCREEN_OFF)

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val expectedScanMode = scanModeMap.get(scanMode)
            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testUnfilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = false
        val isBatch = true
        val isAutoBatch = true
        // Set report delay for auto batch scan callback type
        scanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(scanManager.batchScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams).isNull()
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams).isNull()
            // Turn off screen
            setScreenOn(false)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            assertThat(scanManager.batchScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams).isNull()
        }
    }

    @Test
    fun testFilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = true
        val isBatch = true
        val isAutoBatch = true
        // Set report delay for auto batch scan callback type
        scanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            scannerId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client =
                createScanClient(isFiltered, scanMode, isBatch = isBatch, isAutoBatch = isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).contains(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(client.settings.scanMode).isEqualTo(scanMode)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanParams).isNull()
            // Turn off screen
            setScreenOn(false)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            assertThat(scanManager.batchScanQueue).contains(client)
            assertThat(scanManager.batchScanParams.scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testLocationAndScreenOnOffResumeUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false
        // Set scan mode array
        val scanModeArr =
            intArrayOf(
                ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )

        for (i in scanModeArr.indices) {
            val scanMode = scanModeArr[i]
            Log.d(TAG, "ScanMode: $scanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
            // Turn off location
            doReturn(false).whenever(locationManager).isLocationEnabled
            setLocationOn(false)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            // Turn off screen
            setScreenOn(false)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            // Turn on screen
            setScreenOn(true)
            assertThat(scanManager.regularScanQueue).doesNotContain(client)
            assertThat(scanManager.suspendedScanQueue).contains(client)
            // Turn on location
            doReturn(true).whenever(locationManager).isLocationEnabled
            setLocationOn(true)
            assertThat(scanManager.regularScanQueue).contains(client)
            assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
        }
    }

    @Test
    fun testMetricsAppScanScreenOn() {
        // Set filtered scan flag
        val isFiltered = true
        val scanTestDuration: Long = 100
        // Turn on screen
        setScreenOn(true)

        // Set scan mode map {original scan mode (ScanMode) : logged scan mode (loggedScanMode)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(
            ScanSettings.SCAN_MODE_LOW_POWER,
            BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER,
        )
        scanModeMap.put(
            ScanSettings.SCAN_MODE_BALANCED,
            BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED,
        )
        scanModeMap.put(
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY,
        )
        scanModeMap.put(
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_AMBIENT_DISCOVERY,
        )

        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val loggedScanMode = scanModeMap.get(scanMode)

            // Create workSource for the app
            val APP_NAME = TEST_APP_NAME + i
            val UID = 10000 + i
            val appPid = 5678
            val PACKAGE_NAME = TEST_PACKAGE_NAME + i
            val workSource = WorkSource(UID, PACKAGE_NAME)
            val workSourceUtil = WorkSourceUtil(workSource)
            val metricsReporter =
                ScanMetricsReporter(workSource, workSourceUtil, batteryStatsManager)
            val appScanStatsSpy =
                spy(
                    AppScanStats(
                        UID,
                        appPid,
                        APP_NAME,
                        workSourceUtil,
                        adapterService,
                        metricsReporter,
                        timeProvider,
                    )
                )
            // Set app importance as Foreground Service for the stats
            appScanStatsSpy.appImportance =
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            // Create scan client for the app, which also records scan start
            val client =
                createScanClient(isFiltered, scanMode, uid = UID, appScanStatsPar = appScanStatsSpy)
            // Verify that the app scan start is logged
            inOrder
                .verify(metricsLogger)
                .logAppScanStateChanged(
                    intArrayOf(UID),
                    arrayOf(PACKAGE_NAME),
                    true,
                    true,
                    false,
                    BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES,
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR,
                    loggedScanMode,
                    DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS.toLong(),
                    0,
                    0,
                    true,
                    false,
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                    "",
                )

            advanceTime(scanTestDuration)
            // Record scan stop
            client.appScanStats?.recordScanStop(scannerId)
            // Verify that the app scan stop is logged
            inOrder
                .verify(metricsLogger)
                .logAppScanStateChanged(
                    eq(intArrayOf(UID)),
                    eq(arrayOf(PACKAGE_NAME)),
                    eq(false),
                    eq(true),
                    eq(false),
                    eq(
                        BluetoothStatsLog
                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES
                    ),
                    eq(
                        BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR
                    ),
                    eq(loggedScanMode),
                    eq(DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS.toLong()),
                    eq(scanTestDuration),
                    eq(0),
                    eq(true),
                    eq(false),
                    eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                    eq(""),
                )
        }
    }

    @Test
    fun testMetricsRadioScanScreenOnOffMultiScan() {
        // Set filtered scan flag
        val isFiltered = true
        val scanTestDuration: Long = 100
        // Turn on screen
        setScreenOn(true)

        // Create workSource for the first app
        val uid1 = 10001
        val appName1 = TEST_APP_NAME + uid1
        val packageName1 = TEST_PACKAGE_NAME + uid1
        val appPid1 = 5678
        val workSource1 = WorkSource(uid1, packageName1)
        val workSourceUtil1 = WorkSourceUtil(workSource1)
        val metricsReporter1 =
            ScanMetricsReporter(workSource1, workSourceUtil1, batteryStatsManager)
        val appScanStats1 =
            spy(
                AppScanStats(
                    uid1,
                    appPid1,
                    appName1,
                    workSourceUtil1,
                    adapterService,
                    metricsReporter1,
                    timeProvider,
                )
            )
        // Set app importance as Foreground Service for the stats
        appScanStats1.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the first app
        val client1 =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_POWER,
                uid = uid1,
                appScanStatsPar = appScanStats1,
            )
        // Start scan with lower duty cycle for the first app
        startScan(client1)
        advanceTime(scanTestDuration)

        // Create workSource for the second app
        val uid2 = 10002
        val appName2 = TEST_APP_NAME + uid2
        val packageName2 = TEST_PACKAGE_NAME + uid2
        val appPid2 = 56782
        val workSource2 = WorkSource(uid2, packageName2)
        val workSourceUtil2 = WorkSourceUtil(workSource2)
        val metricsReporter2 =
            ScanMetricsReporter(workSource2, workSourceUtil2, batteryStatsManager)
        val appScanStats2 =
            spy(
                AppScanStats(
                    uid2,
                    appPid2,
                    appName2,
                    workSourceUtil2,
                    adapterService,
                    metricsReporter2,
                    timeProvider,
                )
            )
        // Set app importance as Foreground Service for the stats
        appScanStats2.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the second app
        val client2 =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_BALANCED,
                uid = uid2,
                appScanStatsPar = appScanStats2,
            )
        // Start scan with higher duty cycle for the second app
        startScan(client2)
        // Verify radio scan stop is logged with the first app
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid1)),
                eq(arrayOf(packageName1)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER),
                eq(SCAN_MODE_LOW_POWER_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_LOW_POWER_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Create workSource for the third app
        val uid3 = 10003
        val appName3 = TEST_APP_NAME + uid3
        val packageName3 = TEST_PACKAGE_NAME + uid3
        val appPid3 = 56783
        val workSource3 = WorkSource(uid3, packageName3)
        val workSourceUtil3 = WorkSourceUtil(workSource3)
        val metricsReporter3 =
            ScanMetricsReporter(workSource3, workSourceUtil3, batteryStatsManager)
        val appScanStats3 =
            spy(
                AppScanStats(
                    uid3,
                    appPid3,
                    appName3,
                    workSourceUtil3,
                    adapterService,
                    metricsReporter3,
                    timeProvider,
                )
            )
        // Set app importance as Foreground Service for the stats
        appScanStats3.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the third app
        val client3 =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                uid = uid3,
                appScanStatsPar = appScanStats3,
            )
        // Start scan with highest duty cycle for the third app
        startScan(client3)
        // Verify radio scan stop is logged with the second app
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid2)),
                eq(arrayOf(packageName2)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED),
                eq(SCAN_MODE_BALANCED_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_BALANCED_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Create workSource for the fourth app
        val uid4 = 10004
        val appName4 = TEST_APP_NAME + uid4
        val packageName4 = TEST_PACKAGE_NAME + uid4
        val appPid4 = 56784
        val workSource4 = WorkSource(uid4, packageName4)
        val workSourceUtil4 = WorkSourceUtil(workSource4)
        val metricsReporter4 =
            ScanMetricsReporter(workSource4, workSourceUtil4, batteryStatsManager)
        val appScanStats4 =
            spy(
                AppScanStats(
                    uid4,
                    appPid4,
                    appName4,
                    workSourceUtil4,
                    adapterService,
                    metricsReporter4,
                    timeProvider,
                )
            )
        // Set app importance as Foreground Service for the stats
        appScanStats4.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the fourth app
        val client4 =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
                uid = uid4,
                appScanStatsPar = appScanStats4,
            )
        // Start scan with lower duty cycle for the fourth app
        startScan(client4)
        // Verify radio scan stop is not logged with the third app since there is no change in radio
        // scan
        inOrder
            .verify(metricsLogger, never())
            .logRadioScanStopped(
                eq(intArrayOf(uid3)),
                eq(arrayOf(packageName3)),
                any<Int>(),
                any<Int>(),
                any<Long>(),
                any<Long>(),
                any<Boolean>(),
                any<Long>(),
                any<Int>(),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Set as background app
        setAppImportance(false, uid1)
        setAppImportance(false, uid2)
        setAppImportance(false, uid3)
        setAppImportance(false, uid4)
        // Turn off screen
        setScreenOn(false)
        // Verify radio scan stop is logged with the third app when screen turns off
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid3)),
                eq(arrayOf(packageName3)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY
                ),
                eq(SCAN_MODE_LOW_LATENCY_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_LOW_LATENCY_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration * 2),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Get the most aggressive scan client when screen is off
        // Since all the clients are updated to SCAN_MODE_SCREEN_OFF when screen is off and
        // app is in background mode, get the first client in the iterator
        val scanClients = scanManager.regularScanQueue
        val mostAggressiveClient = scanClients.iterator().next()

        // Turn on screen
        setScreenOn(true)
        // Set as foreground app
        setAppImportance(true, uid1)
        setAppImportance(true, uid2)
        setAppImportance(true, uid3)
        setAppImportance(true, uid4)
        // Verify radio scan stop is logged with the third app when screen turns on
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(mostAggressiveClient.appUid)),
                eq(arrayOf(TEST_PACKAGE_NAME + mostAggressiveClient.appUid)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(convertScanMode(mostAggressiveClient.scanModeApp)),
                eq(SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL.toMillis()),
                eq(SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW.toMillis()),
                eq(false),
                eq(scanTestDuration),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE + 1),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Stop scan for the fourth app
        stopScan(client4)
        // Verify radio scan stop is not logged with the third app since there is no change in radio
        // scan
        inOrder
            .verify(metricsLogger, never())
            .logRadioScanStopped(
                eq(intArrayOf(uid3)),
                eq(arrayOf(packageName3)),
                any<Int>(),
                any<Int>(),
                any<Long>(),
                any<Long>(),
                any<Boolean>(),
                any<Long>(),
                any<Int>(),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Stop scan for the third app
        stopScan(client3)
        // Verify radio scan stop is logged with the third app
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid3)),
                eq(arrayOf(packageName3)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(
                    BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY
                ),
                eq(SCAN_MODE_LOW_LATENCY_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_LOW_LATENCY_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration * 2),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Stop scan for the second app
        stopScan(client2)
        // Verify radio scan stop is logged with the second app
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid2)),
                eq(arrayOf(packageName2)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED),
                eq(SCAN_MODE_BALANCED_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_BALANCED_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Stop scan for the first app
        stopScan(client1)
        // Verify radio scan stop is logged with the first app
        inOrder
            .verify(metricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(uid1)),
                eq(arrayOf(packageName1)),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                eq(BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER),
                eq(SCAN_MODE_LOW_POWER_INTERVAL_MS.toLong()),
                eq(SCAN_MODE_LOW_POWER_WINDOW_MS.toLong()),
                eq(true),
                eq(scanTestDuration),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
    }

    @Test
    fun testMetricsScanRadioDurationScreenOn() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        clearInvocations(metricsLogger)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        // Start scan
        startScan(client)
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Stop scan
        stopScan(client)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
    }

    @Test
    fun testMetricsScanRadioDurationScreenOnOff() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        clearInvocations(metricsLogger)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        // Start scan
        startScan(client)
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Turn off screen
        setScreenOn(false)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Turn on screen
        setScreenOn(true)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Stop scan
        stopScan(client)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
    }

    @Test
    fun testMetricsScanRadioDurationMultiScan() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        clearInvocations(metricsLogger)
        // Create scan clients with different duty cycles
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        val client2 = createScanClient(isFiltered, ScanSettings.SCAN_MODE_BALANCED)
        // Start scan with lower duty cycle
        startScan(client)
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Start scan with higher duty cycle
        startScan(client2)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
        advanceTime(50)
        // Stop scan with lower duty cycle
        stopScan(client)
        inOrder.verify(metricsLogger, never()).cacheCount(any<Int>(), any<Long>())
        // Stop scan with higher duty cycle
        stopScan(client2)
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                any<Long>(),
            )
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                any<Long>(),
            )
    }

    @Test
    fun testMetricsScanRadioWeightedDuration() {
        // Set filtered scan flag
        val isFiltered = true
        val scanTestDuration: Long = 100
        // Set scan mode map {scan mode (ScanMode) : scan weight (ScanWeight)}
        val scanModeMap = SparseIntArray()
        scanModeMap.put(ScanSettings.SCAN_MODE_SCREEN_OFF, ScanUtil.WEIGHT_SCREEN_OFF_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_POWER, ScanUtil.WEIGHT_LOW_POWER)
        scanModeMap.put(ScanSettings.SCAN_MODE_BALANCED, ScanUtil.WEIGHT_BALANCED)
        scanModeMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanUtil.WEIGHT_LOW_LATENCY)
        scanModeMap.put(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY, ScanUtil.WEIGHT_AMBIENT_DISCOVERY)

        // Turn on screen
        setScreenOn(true)
        for (i in 0..<scanModeMap.size()) {
            val scanMode = scanModeMap.keyAt(i)
            val weightedScanDuration =
                (scanTestDuration * scanModeMap.get(scanMode) * 0.01).toLong()
            Log.d(TAG, "ScanMode: $scanMode weightedScanDuration: $weightedScanDuration")

            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            // Wait for scan test duration
            advanceTime(Duration.ofMillis(scanTestDuration))
            // Stop scan
            stopScan(client)
            inOrder
                .verify(metricsLogger)
                .cacheCount(
                    eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR),
                    eq(weightedScanDuration),
                )
        }
    }

    @Test
    fun testMetricsScreenOnOff() {
        // Turn off screen initially
        setScreenOn(false)
        clearInvocations(metricsLogger)
        // Turn on screen
        setScreenOn(true)
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), any<Long>())
        // Turn off screen
        setScreenOn(false)
        inOrder
            .verify(metricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), any<Long>())
        inOrder
            .verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), any<Long>())
    }

    @Test
    fun testDowngradeWithNonNullClientAppScanStats() {
        // Set filtered scan flag
        val isFiltered = true

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration

        // Turn off screen
        setScreenOn(false)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Start Scan
        startScan(client)
        assertThat(scanManager.regularScanQueue).contains(client)
        assertThat(scanManager.suspendedScanQueue).doesNotContain(client)
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Set connecting state
        setConnectingState(true)
        // SCAN_MODE_LOW_LATENCY is now downgraded to SCAN_MODE_BALANCED
        assertThat(client.settings.scanMode).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
    }

    @Test
    fun profileConnectionStateChanged_sendStartConnectionMessage() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration
        assertThat(scanManager.mIsConnecting).isFalse()

        scanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )

        looper.dispatchAll()
        assertThat(scanManager.mIsConnecting).isTrue()
    }

    @Test
    fun multipleProfileConnectionStateChanged_updateCountersCorrectly() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(adapterService)
            .scanDowngradeDuration
        assertThat(scanManager.mIsConnecting).isFalse()

        scanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.HEADSET,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        scanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        scanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.HID_HOST,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        looper.dispatchAll()
        assertThat(scanManager.mProfilesConnecting).isEqualTo(3)
    }

    @Test
    fun getNumOfTrackingAdvertisements_withMaxTrackableAdvertisement() {
        val scanSettings: ScanSettings?
        scanSettings =
            ScanSettings.Builder().setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).build()

        assertThat(scanManager.getNumOfTrackingAdvertisements(scanSettings))
            .isEqualTo(DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS / 4)
    }

    @Test
    fun startScan_withPhy1M() {
        verifyPhyScanForAllScanModes(
            BluetoothDevice.PHY_LE_1M,
            /* expectedPhyMask= */ BluetoothDevice.PHY_LE_1M_MASK,
            /* expect1m= */ true,
            /* expectCoded= */ false,
        )
    }

    @Test
    fun startScan_withPhyCoded() {
        verifyPhyScanForAllScanModes(
            BluetoothDevice.PHY_LE_CODED,
            /* expectedPhyMask= */ BluetoothDevice.PHY_LE_CODED_MASK,
            /* expect1m= */ false,
            /* expectCoded= */ true,
        )
    }

    @Test
    fun startScan_withAllSupportedPhys() {
        verifyPhyScanForAllScanModes(
            ScanSettings.PHY_LE_ALL_SUPPORTED,
            /* expectedPhyMask= */ BluetoothDevice.PHY_LE_1M_MASK or
                BluetoothDevice.PHY_LE_CODED_MASK,
            /* expect1m= */ true,
            /* expectCoded= */ true,
        )
    }

    // PHY_LE_1M: 1, PHY_LE_CODED: 3, PHY_LE_ALL_SUPPORTED: 255
    private fun verifyPhyScanForAllScanModes(
        phy: Int,
        expectedPhyMask: Int,
        expect1m: Boolean,
        expectCoded: Boolean,
    ) {
        val isFiltered = false
        val isEmptyFilter = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            scannerId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client =
                createScanClient(
                    isFiltered,
                    scanMode,
                    isEmptyFilter,
                    scannerIdPar = scannerId,
                    phy = phy,
                )
            startScan(client)

            assertThat(client.settings.phy).isEqualTo(phy)
            verify(nativeInterface)
                .setScanParameters(
                    eq(if (expect1m) scannerId else 0),
                    any<Int>(),
                    any<Int>(),
                    eq(if (expectCoded) scannerId else 0),
                    any<Int>(),
                    any<Int>(),
                    eq(expectedPhyMask),
                )

            // Stop scan
            stopScan(client)
        }
    }

    @Test
    fun startScan_phyTestMultiplexing() {
        val scannerId1m = ++scannerId
        val scannerIdCoded = ++scannerId

        // Turn on screen
        setScreenOn(true)

        // Create 1m scan client
        val client1m =
            createScanClient(
                true,
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                false,
                scannerIdPar = scannerId1m,
                phy = BluetoothDevice.PHY_LE_1M,
            )

        // Start scan on 1m
        startScan(client1m)

        assertThat(client1m.settings.phy).isEqualTo(BluetoothDevice.PHY_LE_1M)
        verify(nativeInterface)
            .setScanParameters(
                eq(scannerId1m),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                eq(0),
                any<Int>(),
                any<Int>(),
                eq(BluetoothDevice.PHY_LE_1M_MASK),
            )

        // Create coded scan client
        val clientCoded =
            createScanClient(
                true,
                ScanSettings.SCAN_MODE_BALANCED,
                false,
                scannerIdPar = scannerIdCoded,
                phy = BluetoothDevice.PHY_LE_CODED,
            )

        // Start scan on coded
        startScan(clientCoded)

        assertThat(clientCoded.settings.phy).isEqualTo(BluetoothDevice.PHY_LE_CODED)
        verify(nativeInterface)
            .setScanParameters(
                eq(scannerId1m),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                eq(scannerIdCoded),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                eq(BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK),
            )

        // Stop scan on 1m
        stopScan(client1m)

        verify(nativeInterface)
            .setScanParameters(
                eq(0),
                any<Int>(),
                any<Int>(),
                eq(scannerIdCoded),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                eq(BluetoothDevice.PHY_LE_CODED_MASK),
            )

        // Stop scan on coded
        stopScan(clientCoded)

        verify(nativeInterface, atLeastOnce()).scan(eq(false), any<String>())
        verify(nativeInterface, never())
            .setScanParameters(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                eq(0),
            )
    }

    @Test
    fun testMsftScan() {
        doReturn(true).whenever(nativeInterface).isMsftSupported()
        simulateIsOffloadFilteringSupported(false)

        val isFiltered = true
        val serviceUuid = ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"))
        val serviceData = byteArrayOf(0x01, 0x02, 0x03)

        // Create new ScanManager since sysprop and MSFT support are only checked when
        // ScanManager is created
        scanManager =
            ScanManager(
                adapterService,
                scanController,
                nativeCallback,
                nativeInterface,
                scanRadioStats,
                looper.looper,
                timeProvider,
            )

        // Turn on screen
        setScreenOn(true)
        // Create scan client with service data
        val scanFilterList =
            listOf(ScanFilter.Builder().setServiceData(serviceUuid, serviceData).build())
        val client =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_POWER,
                isBatch = false,
                isAutoBatch = false,
                uid = Binder.getCallingUid(),
                appScanStatsPar = appScanStats,
                filterList = scanFilterList,
            )
        // Start scan
        startScan(client)

        // Create another scan client with the same service data
        val anotherClient =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_POWER,
                isBatch = false,
                isAutoBatch = false,
                uid = Binder.getCallingUid(),
                appScanStatsPar = appScanStats,
                filterList = scanFilterList,
            )
        // Start scan
        startScan(anotherClient)

        // Verify MSFT APIs are only called once
        verify(nativeInterface)
            .msftAdvMonitorAdd(
                any<MsftAdvMonitor.Monitor>(),
                any<Array<MsftAdvMonitor.Pattern>>(),
                any<MsftAdvMonitor.Uuid>(),
                any<MsftAdvMonitor.Address>(),
                any<Int>(),
            )
        verify(nativeInterface).msftAdvMonitorEnable(eq(true))
    }

    @Test
    fun testPreferApcfOverMsftScan() {
        doReturn(true).whenever(nativeInterface).isMsftSupported()
        simulateIsOffloadFilteringSupported(true)

        val isFiltered = true
        val serviceUuid = ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"))
        val serviceData = byteArrayOf(0x01, 0x02, 0x03)

        // Create new ScanManager since sysprop and MSFT support are only on ScanManager creation
        scanManager =
            ScanManager(
                adapterService,
                scanController,
                nativeCallback,
                nativeInterface,
                scanRadioStats,
                looper.looper,
                timeProvider,
            )

        // Turn on screen
        setScreenOn(true)
        // Create scan client with service data
        val scanFilterList =
            listOf(ScanFilter.Builder().setServiceData(serviceUuid, serviceData).build())
        val client =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_POWER,
                isBatch = false,
                isAutoBatch = false,
                uid = Binder.getCallingUid(),
                appScanStatsPar = appScanStats,
                filterList = scanFilterList,
            )
        // Start scan
        startScan(client)

        // Verify APCF APIs are called
        verify(nativeInterface).scanFilterParamAdd(any<FilterParams>())

        // Verify MSFT APIs are never called
        verify(nativeInterface, never())
            .msftAdvMonitorAdd(
                any<MsftAdvMonitor.Monitor>(),
                any<Array<MsftAdvMonitor.Pattern>>(),
                any<MsftAdvMonitor.Uuid>(),
                any<MsftAdvMonitor.Address>(),
                any<Int>(),
            )
        verify(nativeInterface, never()).msftAdvMonitorEnable(any<Boolean>())

        // Stop scan
        stopScan(client)

        // Verify APCF APIs are called
        verify(nativeInterface).scanFilterParamDelete(any<Int>(), any<Int>())

        // Verify MSFT APIs are never called
        verify(nativeInterface, never()).msftAdvMonitorRemove(any<Int>(), any<Int>())
        verify(nativeInterface, never()).msftAdvMonitorEnable(any<Boolean>())
    }

    @Test
    fun testRestartScan_noRegularClients() {
        // Verifies that when there are no regular scan clients, the scan is only stopped
        // and not started again.
        scanManager.restartScan("testCaller")

        verify(nativeInterface).scan(false, "testCaller")
        verify(nativeInterface, never()).scan(eq(true), any())
    }

    @Test
    fun testRestartScan_withRegularClients() {
        // Turn on screen to allow regular scan to start
        setScreenOn(true)

        // Create and start a regular scan client
        val client =
            createScanClient(isFiltered = false, scanMode = ScanSettings.SCAN_MODE_LOW_POWER)
        startScan(client)

        // Clear invocations from the initial startScan call
        clearInvocations(nativeInterface)

        // Verifies that when there are regular scan clients, the scan is stopped and then started
        // again.
        scanManager.restartScan("testCaller")

        verify(nativeInterface).scan(false, "testCaller")
        verify(nativeInterface).scan(true, "testCaller")
    }

    private fun createScanClient(
        isFiltered: Boolean,
        scanMode: Int,
        isEmptyFilter: Boolean = false,
        isBatch: Boolean = false,
        isAutoBatch: Boolean = false,
        uid: Int = Binder.getCallingUid(),
        appScanStatsPar: AppScanStats = this@ScanManagerTest.appScanStats,
        scannerIdPar: Int? = null,
        phy: Int? = null,
        filterList: List<ScanFilter>? = null,
    ): ScanClient {
        val settings =
            ScanSettings.Builder()
                .apply {
                    setScanMode(scanMode)
                    if (isBatch) {
                        setReportDelay(scanReportDelay)
                        if (isAutoBatch) {
                            setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)
                        }
                    }
                    phy?.let { setPhy(it) }
                }
                .build()
        val filters =
            filterList
                ?: if (isFiltered) {
                    if (isEmptyFilter) {
                        listOf(ScanFilter.Builder().build())
                    } else {
                        listOf(ScanFilter.Builder().setDeviceName("TestName").build())
                    }
                } else {
                    emptyList()
                }
        val scannerIdForClient = scannerIdPar ?: ++scannerId
        return ScanClient(
                mock<ScannerApp> {
                    doReturn(scannerIdForClient).whenever(it).scannerId
                    doReturn(uid).whenever(it).uid
                    doReturn(filters).whenever(it).filters
                    doReturn(appScanStatsPar).whenever(it).appScanStats
                },
                settings,
                mock<UserHandle>(),
            )
            .apply {
                appScanStats.recordScanStart(
                    settings,
                    filters,
                    isFiltered,
                    false,
                    scannerIdForClient,
                    null,
                )
            }
    }

    private fun advanceTime(amountToAdvance: Duration) {
        looper.moveTimeForward(amountToAdvance.toMillis())
        timeProvider.advanceTime(amountToAdvance)
    }

    private fun advanceTime(amountToAdvanceMillis: Long) {
        looper.moveTimeForward(amountToAdvanceMillis)
        timeProvider.advanceTime(Duration.ofMillis(amountToAdvanceMillis))
    }

    private fun advanceTime(amountToAdvance: kotlin.time.Duration) {
        looper.moveTimeForward(amountToAdvance.inWholeMilliseconds)
        timeProvider.advanceTime(amountToAdvance)
    }

    private fun startScan(client: ScanClient?) {
        scanManager.startScan(client)
    }

    private fun stopScan(client: ScanClient) = executeOnScanThread {
        scanManager.stopScan(client.scannerId)
    }

    private fun setScreenOn(isScreenOn: Boolean) = executeOnScanThread {
        if (isScreenOn) scanManager.handleScreenOn() else scanManager.handleScreenOff()
    }

    private fun setLocationOn(isLocationOn: Boolean) = executeOnScanThread {
        if (isLocationOn) scanManager.handleResumeScans() else scanManager.handleSuspendScans()
    }

    private fun setAppImportance(isForeground: Boolean, uid: Int) {
        val importance =
            if (isForeground) ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            else ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE + 1
        val uidImportance = ScanManager.UidImportance(uid, importance)
        executeOnScanThread { scanManager.handleImportanceChange(uidImportance) }
    }

    private fun setAppImportance(importance: Int, uid: Int) {
        val uidImportance = ScanManager.UidImportance(uid, importance)
        executeOnScanThread { scanManager.handleImportanceChange(uidImportance) }
    }

    private fun setConnectingState(isConnecting: Boolean) = executeOnScanThread {
        if (isConnecting) scanManager.handleConnectingState()
        else scanManager.handleClearConnectingState()
    }

    private fun executeOnScanThread(r: Runnable) {
        scanManager.mHandler!!.post(r)
        assertThat(looper.dispatchAll()).isEqualTo(1)
    }

    private fun simulateIsOffloadFilteringSupported(supported: Boolean) {
        val filterCount = if (supported) ScanUtil.MIN_OFFLOADED_FILTERS else 0
        doReturn(filterCount).whenever(adapterService).numOfOffloadedScanFilterSupported
    }

    private fun mockPrivilegedPermission() {
        doReturn(arrayOf(TEST_PRIVILEGED_PACKAGE_NAME))
            .whenever(packageManager)
            .getPackagesForUid(any())
        val mockUserContext = mock<Context>()
        doReturn(mockUserContext).whenever(adapterService).createContextAsUser(any(), any())
        doReturn(packageManager).whenever(mockUserContext).packageManager
        val privilegedPackageInfo =
            PackageInfo().apply {
                requestedPermissions = arrayOf(BLUETOOTH_PRIVILEGED)
                requestedPermissionsFlags = intArrayOf(REQUESTED_PERMISSION_GRANTED)
            }
        doReturn(privilegedPackageInfo)
            .whenever(packageManager)
            .getPackageInfo(any<String>(), any<Int>())
    }

    companion object {
        private const val DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS = 0
        private const val DEFAULT_BATCH_SCAN_REPORT_DELAY_MS = 100
        private const val DEFAULT_NUM_OFFLOAD_SCAN_FILTER = 16
        private const val DEFAULT_BYTES_OFFLOAD_SCAN_RESULT_STORAGE = 4096
        private const val DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS = 32
        private const val TEST_SCAN_QUOTA_COUNT = 5
        private const val TEST_APP_NAME = "Test"
        private const val TEST_PACKAGE_NAME = "com.test.package"
        private const val TEST_PRIVILEGED_PACKAGE_NAME = "com.test.privileged.package"

        private val defaultScanMode =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )
    }
}
