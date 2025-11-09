/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.ActivityManager
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProtoEnums
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.os.BatteryStatsManager
import android.os.Binder
import android.os.Bundle
import android.os.Message
import android.os.ParcelUuid
import android.os.SystemProperties
import android.os.WorkSource
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
import com.android.bluetooth.TestUtils.mockGetSystemService
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.MetricsLogger
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.ScanMetricsReporter.Companion.convertScanMode
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
import com.android.tests.bluetooth.FakeTimeProvider
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.StaticMockitoRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.UUID
import kotlin.time.ExperimentalTime
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

private const val TAG = "ScanManagerTest"

/** Test cases for [ScanManager]. */
@OptIn(ExperimentalTime::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ScanManagerTest(flags: FlagsWrapper) {
    @get:Rule val mMockitoRule = StaticMockitoRule(SystemProperties::class.java)
    @get:Rule val mSetFlagsRule = SetFlagsRule(flags.flags)

    @Mock private lateinit var mAdapterService: AdapterService
    @Mock private lateinit var mBluetoothManager: BluetoothManager
    @Mock private lateinit var mAdapter: BluetoothAdapter
    @Mock private lateinit var mLocationManager: LocationManager
    @Mock private lateinit var mMetricsLogger: MetricsLogger
    @Mock private lateinit var mScanNativeCallback: ScanNativeCallback
    @Mock private lateinit var mScanNativeInterface: ScanNativeInterface
    @Mock private lateinit var mScanController: ScanController

    private val mTimeProvider = FakeTimeProvider()

    private lateinit var mAppScanStats: AppScanStats
    private lateinit var mScanManager: ScanManager
    private lateinit var mLooper: TestLooper
    private lateinit var mInOrder: InOrder

    private var mScanReportDelay = 0L
    private var mClientId = 0

    @Before
    fun setUp() {
        doReturn(DEFAULT_SCAN_TIMEOUT).whenever(mAdapterService).getScanTimeout()
        doReturn(DEFAULT_NUM_OFFLOAD_SCAN_FILTER)
            .whenever(mAdapterService)
            .getNumOfOffloadedScanFilterSupported()
        doReturn(DEFAULT_BYTES_OFFLOAD_SCAN_RESULT_STORAGE)
            .whenever(mAdapterService)
            .getOffloadedScanResultStorage()
        doReturn(TEST_SCAN_QUOTA_COUNT).whenever(mAdapterService).getScanQuotaCount()
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW)
            .whenever(mAdapterService)
            .getScreenOffLowPowerWindow()
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW)
            .whenever(mAdapterService)
            .getScreenOffBalancedWindow()
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL)
            .whenever(mAdapterService)
            .getScreenOffLowPowerInterval()
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL)
            .whenever(mAdapterService)
            .getScreenOffBalancedInterval()
        doReturn(DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS)
            .whenever(mAdapterService)
            .getTotalNumOfTrackableAdvertisements()

        mockGetSystemService(mAdapterService, LocationManager::class.java, mLocationManager)
        doReturn(true).whenever(mLocationManager).isLocationEnabled()
        mockGetSystemService(mAdapterService, DisplayManager::class.java)
        mockGetSystemService(mAdapterService, BatteryStatsManager::class.java)
        mockGetSystemService(mAdapterService, AlarmManager::class.java)
        mockGetSystemService(mAdapterService, BluetoothManager::class.java, mBluetoothManager)
        doReturn(mAdapter).whenever(mBluetoothManager).getAdapter()

        val context = InstrumentationRegistry.getInstrumentation().getContext()
        doReturn(context.getResources()).whenever(mAdapterService).getResources()
        val mockContentResolver = MockContentResolver(context)
        mockContentResolver.addProvider(
            Settings.AUTHORITY,
            object : MockContentProvider() {
                override fun call(method: String, request: String?, args: Bundle?): Bundle? {
                    return Bundle.EMPTY
                }
            },
        )
        doReturn(mockContentResolver).whenever(mAdapterService).getContentResolver()
        // Needed to mock Native call/callback when hw offload scan filter is enabled
        doReturn(true).whenever(mAdapter).isOffloadedFilteringSupported()

        // TODO(b/397863857) Delete on `Flags.scanControllerThread()` cleanup
        // Mock JNI callback in ScanNativeCallback
        doReturn(true).whenever(mScanNativeCallback).waitForCallback(anyInt().toLong())

        val scanRadioStats = ScanRadioStats(mTimeProvider)
        doReturn(scanRadioStats).whenever(mScanController).getScanRadioStats()
        MetricsLogger.setInstanceForTesting(mMetricsLogger)
        mInOrder = Mockito.inOrder(mMetricsLogger)

        doReturn(context.getUser()).whenever(mAdapterService).getUser()
        doReturn(context.getPackageName()).whenever(mAdapterService).getPackageName()

        mClientId = 0
        mLooper = TestLooper()
        mScanManager =
            ScanManager(
                mAdapterService,
                mScanController,
                mScanNativeCallback,
                mScanNativeInterface,
                mLooper.getLooper(),
                mTimeProvider,
            )

        mScanReportDelay = DEFAULT_BATCH_SCAN_REPORT_DELAY_MS.toLong()
        val appUid = 1234
        val appPid = 5678
        mAppScanStats =
            spy(AppScanStats(appUid, appPid, TEST_APP_NAME, null, mAdapterService, mTimeProvider))
    }

    @After
    fun tearDown() {
        MetricsLogger.setInstanceForTesting(null)
        MetricsLogger.getInstance()
    }

    private fun advanceTime(amountToAdvance: Duration) {
        mLooper.moveTimeForward(amountToAdvance.toMillis())
        mTimeProvider.advanceTime(amountToAdvance)
    }

    private fun advanceTime(amountToAdvanceMillis: Long) {
        mLooper.moveTimeForward(amountToAdvanceMillis)
        mTimeProvider.advanceTime(Duration.ofMillis(amountToAdvanceMillis))
    }

    private fun startScan(client: ScanClient?) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread { mScanManager.startScan(client) }
        } else {
            sendMessageWaitForProcessed(createStartStopScanMessage(true, client))
        }
    }

    private fun stopScan(client: ScanClient) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread { mScanManager.stopScan(client.scannerId) }
        } else {
            sendMessageWaitForProcessed(createStartStopScanMessage(false, client))
        }
    }

    private fun setScreenOn(isScreenOn: Boolean) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                if (isScreenOn) Runnable { mScanManager.handleScreenOn() }
                else Runnable { mScanManager.handleScreenOff() }
            )
        } else {
            sendMessageWaitForProcessed(createScreenOnOffMessage(isScreenOn))
        }
    }

    private fun setLocationOn(isLocationOn: Boolean) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                if (isLocationOn) Runnable { mScanManager.handleResumeScans() }
                else Runnable { mScanManager.handleSuspendScans() }
            )
        } else {
            sendMessageWaitForProcessed(createLocationOnOffMessage(isLocationOn))
        }
    }

    private fun setAppImportance(isForeground: Boolean, uid: Int) {
        val importance =
            if (isForeground) ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            else ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE + 1
        val uidImportance = ScanManager.UidImportance(uid, importance)
        if (Flags.scanControllerThread()) {
            executeOnScanThread { mScanManager.handleImportanceChange(uidImportance) }
        } else {
            val message = Message()
            message.what = ScanManager.MSG_IMPORTANCE_CHANGE
            message.obj = uidImportance
            sendMessageWaitForProcessed(message)
        }
    }

    private fun setConnectingState(isConnecting: Boolean) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                if (isConnecting) Runnable { mScanManager.handleConnectingState() }
                else Runnable { mScanManager.handleClearConnectingState() }
            )
        } else {
            sendMessageWaitForProcessed(createConnectingMessage(isConnecting))
        }
    }

    private fun executeOnScanThread(r: Runnable) {
        mScanManager.mHandler!!.post(r)
        assertThat(mLooper.dispatchAll()).isEqualTo(1)
    }

    private fun sendMessageWaitForProcessed(msg: Message) {
        mScanManager.mClientHandler!!.sendMessage(msg)
        mLooper.dispatchAll()
    }

    private fun createScanClient(
        isFiltered: Boolean,
        scanMode: Int,
        isBatch: Boolean,
        isAutoBatch: Boolean,
        appUid: Int,
        appScanStats: AppScanStats?,
        scanFilterList: List<ScanFilter>,
    ): ScanClient {
        val scanSettings = createScanSettings(scanMode, isBatch, isAutoBatch)
        mClientId += 1
        val client = ScanClient(appUid, mClientId, scanSettings, scanFilterList)
        client.appScanStats = appScanStats
        client.appScanStats!!.recordScanStart(
            scanSettings,
            scanFilterList,
            isFiltered,
            false,
            mClientId,
            null,
        )
        return client
    }

    private fun createScanClient(
        isFiltered: Boolean,
        isEmptyFilter: Boolean,
        scanMode: Int,
        isBatch: Boolean,
        isAutoBatch: Boolean,
        appUid: Int,
        appScanStats: AppScanStats?,
    ): ScanClient {
        val scanFilterList = createScanFilterList(isFiltered, isEmptyFilter)
        return createScanClient(
            isFiltered,
            scanMode,
            isBatch,
            isAutoBatch,
            appUid,
            appScanStats,
            scanFilterList,
        )
    }

    private fun createScanClient(isFiltered: Boolean, scanMode: Int): ScanClient {
        return createScanClient(
            isFiltered,
            false,
            scanMode,
            false,
            false,
            Binder.getCallingUid(),
            mAppScanStats,
        )
    }

    private fun createScanClient(
        isFiltered: Boolean,
        scanMode: Int,
        appUid: Int,
        appScanStats: AppScanStats?,
    ): ScanClient {
        return createScanClient(isFiltered, false, scanMode, false, false, appUid, appScanStats)
    }

    private fun createScanClient(
        isFiltered: Boolean,
        scanMode: Int,
        isBatch: Boolean,
        isAutoBatch: Boolean,
    ): ScanClient {
        return createScanClient(
            isFiltered,
            false,
            scanMode,
            isBatch,
            isAutoBatch,
            Binder.getCallingUid(),
            mAppScanStats,
        )
    }

    private fun createScanClient(
        isFiltered: Boolean,
        isEmptyFilter: Boolean,
        scanMode: Int,
    ): ScanClient {
        return createScanClient(
            isFiltered,
            isEmptyFilter,
            scanMode,
            false,
            false,
            Binder.getCallingUid(),
            mAppScanStats,
        )
    }

    private fun createScanFilterList(
        isFiltered: Boolean,
        isEmptyFilter: Boolean,
    ): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        if (isFiltered) {
            if (isEmptyFilter) {
                filters.add(ScanFilter.Builder().build())
            } else {
                filters.add(ScanFilter.Builder().setDeviceName("TestName").build())
            }
        }
        return filters
    }

    private fun createScanSettingsWithPhy(scanMode: Int, phy: Int): ScanSettings {
        val scanSettings: ScanSettings
        scanSettings = ScanSettings.Builder().setScanMode(scanMode).setPhy(phy).build()

        return scanSettings
    }

    private fun createScanSettings(
        scanMode: Int,
        isBatch: Boolean,
        isAutoBatch: Boolean,
    ): ScanSettings {
        val scanSettings: ScanSettings
        if (isBatch && isAutoBatch) {
            val autoCallbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH
            scanSettings =
                ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .setReportDelay(mScanReportDelay)
                    .setCallbackType(autoCallbackType)
                    .build()
        } else if (isBatch) {
            scanSettings =
                ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .setReportDelay(mScanReportDelay)
                    .build()
        } else {
            scanSettings = ScanSettings.Builder().setScanMode(scanMode).build()
        }
        return scanSettings
    }

    private fun createScanClientWithPhy(
        id: Int,
        isFiltered: Boolean,
        isEmptyFilter: Boolean,
        scanMode: Int,
        phy: Int,
    ): ScanClient {
        val scanFilterList = createScanFilterList(isFiltered, isEmptyFilter)
        val scanSettings: ScanSettings = createScanSettingsWithPhy(scanMode, phy)

        val appUid = 1234
        val client = ScanClient(appUid, id, scanSettings, scanFilterList)
        client.appScanStats = mAppScanStats
        client.appScanStats!!.recordScanStart(
            scanSettings,
            scanFilterList,
            isFiltered,
            false,
            id,
            null,
        )
        return client
    }

    private fun createStartStopScanMessage(isStartScan: Boolean, obj: Any?): Message {
        val message = Message()
        message.what =
            if (isStartScan) ScanManager.MSG_START_BLE_SCAN else ScanManager.MSG_STOP_BLE_SCAN
        message.obj = obj
        return message
    }

    private fun createScreenOnOffMessage(isScreenOn: Boolean): Message {
        val message = Message()
        message.what = if (isScreenOn) ScanManager.MSG_SCREEN_ON else ScanManager.MSG_SCREEN_OFF
        message.obj = null
        return message
    }

    private fun createLocationOnOffMessage(isLocationOn: Boolean): Message {
        val message = Message()
        message.what =
            if (isLocationOn) ScanManager.MSG_RESUME_SCANS else ScanManager.MSG_SUSPEND_SCANS
        message.obj = null
        return message
    }

    private fun createConnectingMessage(isConnectingOn: Boolean): Message {
        val message = Message()
        message.what =
            if (isConnectingOn) ScanManager.MSG_START_CONNECTING
            else ScanManager.MSG_STOP_CONNECTING
        message.obj = null
        return message
    }

    @Test
    fun testScreenOffStartUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            mClientId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOffStartEmptyFilterScan() {
        // Set filtered scan flag
        val isFiltered = true
        val isEmptyFilter = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            mClientId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, isEmptyFilter, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOnStartUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            mClientId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScreenOnStartFilteredScan() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            mClientId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
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
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
        }
    }

    @Test
    fun testUnfilteredScanTimeout() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_OPPORTUNISTIC
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Wait for scan timeout
            advanceTime(DEFAULT_SCAN_TIMEOUT)
            mLooper.dispatchAll()
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
            // Turn off screen
            setScreenOn(false)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testFilteredScanTimeout() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan, this sends scan timeout message with delay
            startScan(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Move time forward so scan timeout message can be dispatched
            advanceTime(DEFAULT_SCAN_TIMEOUT)
            // Since we are using a TestLooper, need to mock AppScanStats.isScanningTooLong
            // to return true because no real time is elapsed
            doReturn(true).whenever(mAppScanStats).isScanningTooLong()
            mLooper.dispatchAll()
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
            // Turn off screen
            setScreenOn(false)
            assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_SCREEN_OFF)
            // Turn on screen
            setScreenOn(true)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testScanTimeoutResetForNewScan() {
        // Set filtered scan flag
        val isFiltered = false
        // Turn on screen
        setScreenOn(true)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)

        if (Flags.scanControllerThread()) {
            // Put a timeout runnable in the map to emulate the scan being started already
            val fakeTimeoutRunnable = Runnable {}
            mScanManager.mScanTimeoutRunnables!!.put(client, fakeTimeoutRunnable)
            mScanManager.mHandler!!.postDelayed(
                fakeTimeoutRunnable,
                DEFAULT_SCAN_TIMEOUT.dividedBy(2).toMillis(),
            )
            // Start the scan. This should remove the fake runnable and post a new one.
            startScan(client)
        } else {
            // Put a timeout message in the queue to emulate the scan being started already
            val timeoutMessage =
                mScanManager.mClientHandler!!.obtainMessage(ScanManager.MSG_SCAN_TIMEOUT, client)
            mScanManager.mClientHandler!!.sendMessageDelayed(
                timeoutMessage,
                DEFAULT_SCAN_TIMEOUT.dividedBy(2).toMillis(),
            )
            mScanManager.mClientHandler!!.sendMessage(createStartStopScanMessage(true, client))
        }

        if (Flags.scanControllerThread()) {
            // Verify that only the new, real runnable is in the map.
            assertThat(mScanManager.mScanTimeoutRunnables).hasSize(1)
        } else {
            // Dispatching all messages only runs start scan
            assertThat(mLooper.dispatchAll()).isEqualTo(1)
        }

        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2))
        // After restarting the scan, we can check that the initial timeout message is not triggered
        assertThat(mLooper.dispatchAll()).isEqualTo(0)

        // After timeout, the next message that is run should be a timeout message
        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2))

        if (Flags.scanControllerThread()) {
            // Dispatching should now execute the real timeout.
            mLooper.dispatchAll()
            // Verify the client was moved to opportunistic mode, proving the timeout logic ran.
            assertThat(client.settings.getScanMode())
                .isEqualTo(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
            assertThat(client.appScanStats?.isScanTimeout(client.scannerId)).isTrue()
        } else {
            val nextMessage = mLooper.nextMessage()
            assertThat(nextMessage.what).isEqualTo(ScanManager.MSG_SCAN_TIMEOUT)
            assertThat(nextMessage.obj).isEqualTo(client)
        }
    }

    @Test
    fun testSwitchForeBackgroundUnfilteredScan() {
        // Set filtered scan flag
        val isFiltered = false

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
        }
    }

    @Test
    fun testSwitchForeBackgroundFilteredScan() {
        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_LOW_POWER
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")
            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid())
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
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
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).whenever(mAdapterService).getScanUpgradeDuration()

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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Wait for upgrade duration
            advanceTime(DEFAULT_SCAN_UPGRADE_DURATION)
            mLooper.dispatchAll()
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
        }
    }

    @Test
    fun testUpDowngradeStartScanForConcurrency() {
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).whenever(mAdapterService).getScanUpgradeDuration()
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
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
            mLooper.dispatchAll()
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
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
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            mLooper.dispatchAll()
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
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
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            // Turn off screen
            setScreenOn(false)
            // Move time forward so that stop connecting action can be dispatched
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            mLooper.dispatchAll()
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testDowngradeDuringScanForConcurrencyBackground() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

        // Set filtered scan flag
        val isFiltered = true

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            // Set connecting state
            setConnectingState(true)
            // Set as background app
            setAppImportance(false, Binder.getCallingUid())
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            mLooper.dispatchAll()
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(client.settings.getScanMode()).isEqualTo(expectedScanMode)
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
            val client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanParams().scanMode).isEqualTo(expectedScanMode)
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
            val client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanParams().scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanParams().scanMode).isEqualTo(expectedScanMode)
        }
    }

    @Test
    fun testUnfilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = false
        val isBatch = true
        val isAutoBatch = true
        // Set report delay for auto batch scan callback type
        mScanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanParams()).isNull()
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanParams()).isNull()
            // Turn off screen
            setScreenOn(false)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanParams()).isNull()
        }
    }

    @Test
    fun testFilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        val isFiltered = true
        val isBatch = true
        val isAutoBatch = true
        // Set report delay for auto batch scan callback type
        mScanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS

        defaultScanMode.forEach { (scanMode, expectedScanMode) ->
            var expectedScanMode = expectedScanMode
            mClientId += 1
            expectedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn off screen
            setScreenOn(false)
            // Create scan client
            val client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch)
            // Start scan
            startScan(client)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanParams().scanMode).isEqualTo(expectedScanMode)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(client.settings.getScanMode()).isEqualTo(scanMode)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanParams()).isNull()
            // Turn off screen
            setScreenOn(false)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getBatchScanQueue()).contains(client)
            assertThat(mScanManager.getBatchScanParams().scanMode).isEqualTo(expectedScanMode)
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
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
            // Turn off location
            doReturn(false).whenever(mLocationManager).isLocationEnabled()
            setLocationOn(false)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            // Turn off screen
            setScreenOn(false)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            // Turn on screen
            setScreenOn(true)
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client)
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client)
            // Turn on location
            doReturn(true).whenever(mLocationManager).isLocationEnabled()
            setLocationOn(true)
            assertThat(mScanManager.getRegularScanQueue()).contains(client)
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
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
            val PACKAGE_NAME = TEST_PACKAGE_NAME + i
            val source = WorkSource(UID, PACKAGE_NAME)
            // Create app scan stats for the app
            val appUid = 1234
            val appPid = 5678
            val appScanStats =
                spy(AppScanStats(appUid, appPid, APP_NAME, source, mAdapterService, mTimeProvider))
            // Set app importance as Foreground Service for the stats
            appScanStats.appImportance =
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            // Create scan client for the app, which also records scan start
            val client = createScanClient(isFiltered, scanMode, UID, appScanStats)
            // Verify that the app scan start is logged
            mInOrder
                .verify(mMetricsLogger)
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
            client.appScanStats?.recordScanStop(mClientId)
            // Verify that the app scan stop is logged
            mInOrder
                .verify(mMetricsLogger)
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
        val UID_1 = 10001
        val APP_NAME_1 = TEST_APP_NAME + UID_1
        val PACKAGE_NAME_1 = TEST_PACKAGE_NAME + UID_1
        val source1 = WorkSource(UID_1, PACKAGE_NAME_1)
        // Create app scan stats for the first app
        val appUid1 = 12341
        val appPid1 = 5678
        val appScanStats1 =
            spy(AppScanStats(appUid1, appPid1, APP_NAME_1, source1, mAdapterService, mTimeProvider))
        // Set app importance as Foreground Service for the stats
        appScanStats1.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the first app
        val client1 =
            createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER, UID_1, appScanStats1)
        // Start scan with lower duty cycle for the first app
        startScan(client1)
        advanceTime(scanTestDuration)

        // Create workSource for the second app
        val UID_2 = 10002
        val APP_NAME_2 = TEST_APP_NAME + UID_2
        val PACKAGE_NAME_2 = TEST_PACKAGE_NAME + UID_2
        val source2 = WorkSource(UID_2, PACKAGE_NAME_2)
        // Create app scan stats for the second app
        val appUid2 = 12342
        val appPid2 = 56782
        val appScanStats2 =
            spy(AppScanStats(appUid2, appPid2, APP_NAME_2, source2, mAdapterService, mTimeProvider))
        // Set app importance as Foreground Service for the stats
        appScanStats2.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the second app
        val client2 =
            createScanClient(isFiltered, ScanSettings.SCAN_MODE_BALANCED, UID_2, appScanStats2)
        // Start scan with higher duty cycle for the second app
        startScan(client2)
        // Verify radio scan stop is logged with the first app
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_1)),
                eq(arrayOf(PACKAGE_NAME_1)),
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
        val UID_3 = 10003
        val APP_NAME_3 = TEST_APP_NAME + UID_3
        val PACKAGE_NAME_3 = TEST_PACKAGE_NAME + UID_3
        val source3 = WorkSource(UID_3, PACKAGE_NAME_3)
        // Create app scan stats for the third app
        val appUid3 = 12343
        val appPid3 = 56783
        val appScanStats3 =
            spy(AppScanStats(appUid3, appPid3, APP_NAME_3, source3, mAdapterService, mTimeProvider))
        // Set app importance as Foreground Service for the stats
        appScanStats3.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the third app
        val client3 =
            createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_LATENCY, UID_3, appScanStats3)
        // Start scan with highest duty cycle for the third app
        startScan(client3)
        // Verify radio scan stop is logged with the second app
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_2)),
                eq(arrayOf(PACKAGE_NAME_2)),
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
        val UID_4 = 10004
        val APP_NAME_4 = TEST_APP_NAME + UID_4
        val PACKAGE_NAME_4 = TEST_PACKAGE_NAME + UID_4
        val source4 = WorkSource(UID_4, PACKAGE_NAME_4)
        // Create app scan stats for the fourth app
        val appUid4 = 12344
        val appPid4 = 56784
        val appScanStats4 =
            spy(AppScanStats(appUid4, appPid4, APP_NAME_4, source4, mAdapterService, mTimeProvider))
        // Set app importance as Foreground Service for the stats
        appScanStats4.appImportance =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        // Create scan client for the fourth app
        val client4 =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
                UID_4,
                appScanStats4,
            )
        // Start scan with lower duty cycle for the fourth app
        startScan(client4)
        // Verify radio scan stop is not logged with the third app since there is no change in radio
        // scan
        mInOrder
            .verify(mMetricsLogger, never())
            .logRadioScanStopped(
                eq(intArrayOf(UID_3)),
                eq(arrayOf(PACKAGE_NAME_3)),
                anyInt(),
                anyInt(),
                anyLong(),
                anyLong(),
                anyBoolean(),
                anyLong(),
                anyInt(),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Set as background app
        setAppImportance(false, UID_1)
        setAppImportance(false, UID_2)
        setAppImportance(false, UID_3)
        setAppImportance(false, UID_4)
        // Turn off screen
        setScreenOn(false)
        // Verify radio scan stop is logged with the third app when screen turns off
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_3)),
                eq(arrayOf(PACKAGE_NAME_3)),
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
        val scanClients = mScanManager.getRegularScanQueue()
        val mostAggressiveClient = scanClients.iterator().next()

        // Turn on screen
        setScreenOn(true)
        // Set as foreground app
        setAppImportance(true, UID_1)
        setAppImportance(true, UID_2)
        setAppImportance(true, UID_3)
        setAppImportance(true, UID_4)
        // Verify radio scan stop is logged with the third app when screen turns on
        mInOrder
            .verify(mMetricsLogger)
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
        mInOrder
            .verify(mMetricsLogger, never())
            .logRadioScanStopped(
                eq(intArrayOf(UID_3)),
                eq(arrayOf(PACKAGE_NAME_3)),
                anyInt(),
                anyInt(),
                anyLong(),
                anyLong(),
                anyBoolean(),
                anyLong(),
                anyInt(),
                eq(""),
            )
        advanceTime(scanTestDuration)

        // Stop scan for the third app
        stopScan(client3)
        // Verify radio scan stop is logged with the third app
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_3)),
                eq(arrayOf(PACKAGE_NAME_3)),
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
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_2)),
                eq(arrayOf(PACKAGE_NAME_2)),
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
        mInOrder
            .verify(mMetricsLogger)
            .logRadioScanStopped(
                eq(intArrayOf(UID_1)),
                eq(arrayOf(PACKAGE_NAME_1)),
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
        clearInvocations(mMetricsLogger)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        // Start scan
        startScan(client)
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Stop scan
        stopScan(client)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
    }

    @Test
    fun testMetricsScanRadioDurationScreenOnOff() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        clearInvocations(mMetricsLogger)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        // Start scan
        startScan(client)
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Turn off screen
        setScreenOn(false)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Turn on screen
        setScreenOn(true)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Stop scan
        stopScan(client)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
    }

    @Test
    fun testMetricsScanRadioDurationMultiScan() {
        // Set filtered scan flag
        val isFiltered = true
        // Turn on screen
        setScreenOn(true)
        clearInvocations(mMetricsLogger)
        // Create scan clients with different duty cycles
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_POWER)
        val client2 = createScanClient(isFiltered, ScanSettings.SCAN_MODE_BALANCED)
        // Start scan with lower duty cycle
        startScan(client)
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Start scan with higher duty cycle
        startScan(client2)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
            )
        advanceTime(50)
        // Stop scan with lower duty cycle
        stopScan(client)
        mInOrder.verify(mMetricsLogger, never()).cacheCount(anyInt(), anyLong())
        // Stop scan with higher duty cycle
        stopScan(client2)
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON), anyLong())
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(
                eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                anyLong(),
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
            mInOrder
                .verify(mMetricsLogger)
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
        clearInvocations(mMetricsLogger)
        // Turn on screen
        setScreenOn(true)
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), anyLong())
        // Turn off screen
        setScreenOn(false)
        mInOrder
            .verify(mMetricsLogger, never())
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), anyLong())
        mInOrder
            .verify(mMetricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), anyLong())
    }

    @Test
    fun testDowngradeWithNonNullClientAppScanStats() {
        // Set filtered scan flag
        val isFiltered = true

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

        // Turn off screen
        setScreenOn(false)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Start Scan
        startScan(client)
        assertThat(mScanManager.getRegularScanQueue()).contains(client)
        assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
        assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Set connecting state
        setConnectingState(true)
        // SCAN_MODE_LOW_LATENCY is now downgraded to SCAN_MODE_BALANCED
        assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
    }

    @Test
    fun testDowngradeWithNullClientAppScanStats() {
        // Set filtered scan flag
        val isFiltered = true

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()

        // Turn off screen
        setScreenOn(false)
        // Create scan client
        val client = createScanClient(isFiltered, ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Start Scan
        startScan(client)
        assertThat(mScanManager.getRegularScanQueue()).contains(client)
        assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client)
        assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Set AppScanStats to empty
        client.appScanStats = null
        // Set connecting state
        setConnectingState(true)
        // Since AppScanStats is null, no downgrade takes place for scan mode
        assertThat(client.settings.getScanMode()).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    fun profileConnectionStateChanged_sendStartConnectionMessage() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()
        assertThat(mScanManager.mIsConnecting).isFalse()

        mScanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )

        mLooper.dispatchAll()
        assertThat(mScanManager.mIsConnecting).isTrue()
    }

    @Test
    fun multipleProfileConnectionStateChanged_updateCountersCorrectly() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
            .whenever(mAdapterService)
            .getScanDowngradeDuration()
        assertThat(mScanManager.mIsConnecting).isFalse()

        mScanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.HEADSET,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        mScanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.A2DP,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        mScanManager.handleBluetoothProfileConnectionStateChanged(
            BluetoothProfile.HID_HOST,
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTING,
        )
        mLooper.dispatchAll()
        assertThat(mScanManager.mProfilesConnecting).isEqualTo(3)
    }

    @Test
    fun getNumOfTrackingAdvertisements_withMaxTrackableAdvertisement() {
        val scanSettings: ScanSettings?
        scanSettings =
            ScanSettings.Builder().setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).build()

        assertThat(mScanManager.getNumOfTrackingAdvertisements(scanSettings))
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
            mClientId += 1
            Log.d(TAG, "ScanMode: $scanMode expectedScanMode: $expectedScanMode")

            // Turn on screen
            setScreenOn(true)
            // Create scan client
            val client =
                createScanClientWithPhy(mClientId, isFiltered, isEmptyFilter, scanMode, phy)
            // Start scan
            startScan(client)

            assertThat(client.settings.getPhy()).isEqualTo(phy)
            verify(mScanNativeInterface)
                .setScanParameters(
                    eq(if (expect1m) mClientId else 0),
                    anyInt(),
                    anyInt(),
                    eq(if (expectCoded) mClientId else 0),
                    anyInt(),
                    anyInt(),
                    eq(expectedPhyMask),
                )

            // Stop scan
            stopScan(client)
        }
    }

    @Test
    fun startScan_phyTestMultiplexing() {
        val clientId1m = ++mClientId
        val clientIdCoded = ++mClientId

        // Turn on screen
        setScreenOn(true)

        // Create 1m scan client
        val client1m =
            createScanClientWithPhy(
                clientId1m,
                true,
                false,
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                BluetoothDevice.PHY_LE_1M,
            )

        // Start scan on 1m
        startScan(client1m)

        assertThat(client1m.settings.getPhy()).isEqualTo(BluetoothDevice.PHY_LE_1M)
        verify(mScanNativeInterface)
            .setScanParameters(
                eq(clientId1m),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                eq(0),
                anyInt(),
                anyInt(),
                eq(BluetoothDevice.PHY_LE_1M_MASK),
            )

        // Create coded scan client
        val clientCoded =
            createScanClientWithPhy(
                clientIdCoded,
                true,
                false,
                ScanSettings.SCAN_MODE_BALANCED,
                BluetoothDevice.PHY_LE_CODED,
            )

        // Start scan on coded
        startScan(clientCoded)

        assertThat(clientCoded.settings.getPhy()).isEqualTo(BluetoothDevice.PHY_LE_CODED)
        verify(mScanNativeInterface)
            .setScanParameters(
                eq(clientId1m),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                eq(clientIdCoded),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                eq(BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK),
            )

        // Stop scan on 1m
        stopScan(client1m)

        verify(mScanNativeInterface)
            .setScanParameters(
                eq(0),
                anyInt(),
                anyInt(),
                eq(clientIdCoded),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                eq(BluetoothDevice.PHY_LE_CODED_MASK),
            )

        // Stop scan on coded
        stopScan(clientCoded)

        verify(mScanNativeInterface, atLeastOnce()).scan(eq(false), anyString())
        verify(mScanNativeInterface, never())
            .setScanParameters(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), eq(0))
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SCAN_MSFT_SUPPORT)
    fun testMsftScan() {
        doReturn(true).whenever(mScanNativeInterface).isMsftSupported()
        doReturn(false).whenever(mAdapter).isOffloadedFilteringSupported()

        val isFiltered = true
        val serviceUuid = ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"))
        val serviceData = byteArrayOf(0x01, 0x02, 0x03)

        // Create new ScanManager since sysprop and MSFT support are only checked when
        // ScanManager is created
        mScanManager =
            ScanManager(
                mAdapterService,
                mScanController,
                mScanNativeCallback,
                mScanNativeInterface,
                mLooper.getLooper(),
                mTimeProvider,
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
                false,
                false,
                Binder.getCallingUid(),
                mAppScanStats,
                scanFilterList,
            )
        // Start scan
        startScan(client)

        // Create another scan client with the same service data
        val anotherClient =
            createScanClient(
                isFiltered,
                ScanSettings.SCAN_MODE_LOW_POWER,
                false,
                false,
                Binder.getCallingUid(),
                mAppScanStats,
                scanFilterList,
            )
        // Start scan
        startScan(anotherClient)

        // Verify MSFT APIs are only called once
        verify(mScanNativeInterface)
            .msftAdvMonitorAdd(
                any(MsftAdvMonitor.Monitor::class.java),
                any(Array<MsftAdvMonitor.Pattern>::class.java),
                any(MsftAdvMonitor.Uuid::class.java),
                any(MsftAdvMonitor.Address::class.java),
                anyInt(),
            )
        verify(mScanNativeInterface).msftAdvMonitorEnable(eq(true))
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SCAN_MSFT_SUPPORT)
    fun testPreferApcfOverMsftScan() {
        doReturn(true).whenever(mScanNativeInterface).isMsftSupported()
        doReturn(true).whenever(mAdapter).isOffloadedFilteringSupported()

        val isFiltered = true
        val serviceUuid = ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"))
        val serviceData = byteArrayOf(0x01, 0x02, 0x03)

        // Create new ScanManager since sysprop and MSFT support are only on ScanManager creation
        mScanManager =
            ScanManager(
                mAdapterService,
                mScanController,
                mScanNativeCallback,
                mScanNativeInterface,
                mLooper.getLooper(),
                mTimeProvider,
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
                false,
                false,
                Binder.getCallingUid(),
                mAppScanStats,
                scanFilterList,
            )
        // Start scan
        startScan(client)

        // Verify APCF APIs are called
        verify(mScanNativeInterface).scanFilterParamAdd(any())

        // Verify MSFT APIs are never called
        verify(mScanNativeInterface, never())
            .msftAdvMonitorAdd(
                any(MsftAdvMonitor.Monitor::class.java),
                any(Array<MsftAdvMonitor.Pattern>::class.java),
                any(MsftAdvMonitor.Uuid::class.java),
                any(MsftAdvMonitor.Address::class.java),
                anyInt(),
            )
        verify(mScanNativeInterface, never()).msftAdvMonitorEnable(anyBoolean())

        // Stop scan
        stopScan(client)

        // Verify APCF APIs are called
        verify(mScanNativeInterface).scanFilterParamDelete(anyInt(), anyInt())

        // Verify MSFT APIs are never called
        verify(mScanNativeInterface, never()).msftAdvMonitorRemove(anyInt(), anyInt())
        verify(mScanNativeInterface, never()).msftAdvMonitorEnable(anyBoolean())
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

        private val defaultScanMode =
            mapOf(
                ScanSettings.SCAN_MODE_LOW_POWER to ScanSettings.SCAN_MODE_LOW_POWER,
                ScanSettings.SCAN_MODE_BALANCED to ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY to ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY to ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            )

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() = FlagsWrapper.progressionOf(Flags.FLAG_SCAN_CONTROLLER_THREAD)
    }
}
