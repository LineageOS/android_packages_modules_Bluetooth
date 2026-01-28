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

package com.android.bluetooth.gatt

import android.app.ActivityManager
import android.bluetooth.BluetoothProtoEnums
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.metrics.MetricsLogger
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.clearInvocations

private const val TAG = "AppAdvertiseStatsTest"

/** Test cases for [AppAdvertiseStats]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AppAdvertiseStatsTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var metricsLogger: MetricsLogger

    @Captor private lateinit var advDurationCaptor: ArgumentCaptor<Long>

    private lateinit var latch: CountDownLatch

    @Before
    fun setUp() {
        MetricsLogger.setInstanceForTesting(metricsLogger)

        latch = CountDownLatch(1)
        assertThat(latch).isNotNull()
    }

    @After
    fun tearDown() {
        MetricsLogger.setInstanceForTesting(null)
    }

    @Test
    fun recordAdvertiseStart() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty()

        val duration = 1
        val maxExtAdvEvents = 2
        val instanceCount = 3
        appAdvertiseStats.recordAdvertiseStart(duration, maxExtAdvEvents, instanceCount)

        val parameters = AdvertisingSetParameters.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        appAdvertiseStats.recordAdvertiseStart(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            instanceCount,
        )

        val numOfExpectedRecords = 2
        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords)
    }

    @Test
    fun recordAdvertiseStop() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty()

        val duration = 1
        val maxExtAdvEvents = 2
        val instanceCount = 3
        appAdvertiseStats.recordAdvertiseStart(duration, maxExtAdvEvents, instanceCount)

        val parameters = AdvertisingSetParameters.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        appAdvertiseStats.recordAdvertiseStart(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            instanceCount,
        )

        appAdvertiseStats.recordAdvertiseStop(instanceCount)
        val numOfExpectedRecords = 2
        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords)
    }

    @Test
    fun enableAdvertisingSet() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty()

        val duration = 1
        val maxExtAdvEvents = 2
        val instanceCount = 3
        appAdvertiseStats.enableAdvertisingSet(true, duration, maxExtAdvEvents, instanceCount)
        appAdvertiseStats.enableAdvertisingSet(false, duration, maxExtAdvEvents, instanceCount)

        val numOfExpectedRecords = 1
        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords)
    }

    @Test
    fun setAdvertisingData() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        val advertiseData = AdvertiseData.Builder().build()
        appAdvertiseStats.setAdvertisingData(advertiseData)
    }

    @Test
    fun setScanResponseData() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        val scanResponse = AdvertiseData.Builder().build()
        appAdvertiseStats.setScanResponseData(scanResponse)
    }

    @Test
    fun setAdvertisingParameters() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        val parameters = AdvertisingSetParameters.Builder().build()
        appAdvertiseStats.setAdvertisingParameters(parameters)
    }

    @Test
    fun setPeriodicAdvertisingParameters() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        appAdvertiseStats.setPeriodicAdvertisingParameters(periodicParameters)
    }

    @Test
    fun setPeriodicAdvertisingData() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        val periodicData = AdvertiseData.Builder().build()
        appAdvertiseStats.setPeriodicAdvertisingData(periodicData)
    }

    @Test
    fun testDump_doesNotCrash() {
        val sb = StringBuilder()
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)

        val parameters = AdvertisingSetParameters.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        val duration = 1
        val maxExtAdvEvents = 2
        val instanceCount = 3
        appAdvertiseStats.recordAdvertiseStart(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            instanceCount,
        )
        AppAdvertiseStats.dumpToString(sb, appAdvertiseStats)
    }

    @Test
    fun testAdvertiseCounterMetrics() {
        val appUid = 0
        val id = 1
        val name = "name"
        val appAdvertiseStats = AppAdvertiseStats(appUid, id, name, source)
        // Set app importance as Foreground Service for the stats
        appAdvertiseStats.setAppImportance(
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        )

        val parameters = AdvertisingSetParameters.Builder().setConnectable(true).build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        val duration = 1
        val maxExtAdvEvents = 2
        val instanceCount = 3
        val advTestDuration = 100L
        appAdvertiseStats.recordAdvertiseStart(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            instanceCount,
        )
        verify(metricsLogger).cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_ENABLE), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_CONNECTABLE_ENABLE), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_PERIODIC_ENABLE), eq(1L))
        verify(metricsLogger)
            .logAdvStateChanged(
                intArrayOf(appUid),
                arrayOf(name),
                true,
                BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_INTERVAL__INTERVAL_LOW,
                BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_TX_POWER__TX_POWER_MEDIUM,
                true,
                true,
                false,
                true,
                instanceCount,
                0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                "",
            )
        clearInvocations(metricsLogger)

        // Wait for adv test duration
        testSleep(advTestDuration)

        appAdvertiseStats.recordAdvertiseStop(instanceCount)
        verify(metricsLogger).cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_DISABLE), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_CONNECTABLE_DISABLE), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_PERIODIC_DISABLE), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_TOTAL_1M), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_CONNECTABLE_1M), eq(1L))
        verify(metricsLogger)
            .cacheCount(eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_PERIODIC_1M), eq(1L))
        verify(metricsLogger)
            .logAdvStateChanged(
                eq(intArrayOf(appUid)),
                eq(arrayOf(name)),
                eq(false),
                eq(BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_INTERVAL__INTERVAL_LOW),
                eq(BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_TX_POWER__TX_POWER_MEDIUM),
                eq(true),
                eq(true),
                eq(false),
                eq(true),
                eq(instanceCount),
                advDurationCaptor.capture(),
                eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
                eq(""),
            )
        val capturedAppScanDuration = advDurationCaptor.getValue()
        Log.d(TAG, "capturedDuration: $capturedAppScanDuration")
        assertThat(capturedAppScanDuration).isAtLeast(advTestDuration)
    }

    private fun testSleep(millis: Long) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Latch await", e)
        }
    }
}
