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

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.bluetooth.le_scan.BatchScanThrottler.Companion.SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT
import com.android.bluetooth.le_scan.BatchScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS
import com.android.tests.bluetooth.FakeTimeProvider
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import kotlin.math.max
import kotlin.time.ExperimentalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [BatchScanThrottler]. */
@OptIn(ExperimentalTime::class)
@SmallTest
@RunWith(TestParameterInjector::class)
class BatchScanThrottlerTest {
    @get:Rule val mockitoRule = MockitoRule()

    private val timeProvider = FakeTimeProvider()

    @Test
    fun basicThrottling(@TestParameter isFiltered: Boolean, @TestParameter isScreenOn: Boolean) {
        val throttler = BatchScanThrottler(timeProvider, isScreenOn)
        if (!isScreenOn) {
            advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_DEFAULT)
        }
        val clients = setOf(createBatchScanClient(isFiltered))
        val backoffIntervals =
            getBackoffIntervals(
                if (isScreenOn) DEFAULT_REPORT_DELAY_FLOOR_MS
                else SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT.toLong()
            )
        for (x in backoffIntervals) {
            val expected = adjustExpectedInterval(x, isFiltered, isScreenOn)
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected)
        }
        val expected =
            adjustExpectedInterval(
                backoffIntervals[backoffIntervals.size - 1],
                isFiltered,
                isScreenOn,
            )
        // Ensure that subsequent calls continue to return the final throttled interval
        assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected)
        assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected)
    }

    @Test
    fun screenOffDelayAndReset(@TestParameter screenOnAtStart: Boolean) {
        val throttler = BatchScanThrottler(timeProvider, screenOnAtStart)
        if (screenOnAtStart) {
            throttler.onScreenOn(false)
        }
        val clients = setOf(createBatchScanClient(true))
        var backoffIntervals = getBackoffIntervals(DEFAULT_REPORT_DELAY_FLOOR_MS)
        advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_DEFAULT - 1)
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }

        backoffIntervals = getBackoffIntervals(SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT.toLong())
        advanceTime(1)
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
            .isEqualTo(backoffIntervals[backoffIntervals.size - 1])
    }

    @Test
    fun testScreenOnReset() {
        val throttler = BatchScanThrottler(timeProvider, false)
        advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_DEFAULT)
        val clients = setOf(createBatchScanClient(true))
        var backoffIntervals = getBackoffIntervals(SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT.toLong())
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }

        throttler.onScreenOn(true)
        backoffIntervals = getBackoffIntervals(DEFAULT_REPORT_DELAY_FLOOR_MS)
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
            .isEqualTo(backoffIntervals[backoffIntervals.size - 1])
    }

    @Test
    fun resetBackoff_restartsToFirstStage(@TestParameter isScreenOn: Boolean) {
        val throttler = BatchScanThrottler(timeProvider, isScreenOn)
        if (!isScreenOn) {
            // Advance the time before we start the test to when the screen-off intervals should be
            // used
            advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_DEFAULT)
        }
        val clients = setOf(createBatchScanClient(true))
        val backoffIntervals =
            getBackoffIntervals(
                if (isScreenOn) DEFAULT_REPORT_DELAY_FLOOR_MS
                else SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT.toLong()
            )
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
            .isEqualTo(backoffIntervals[backoffIntervals.size - 1])

        throttler.resetBackoff()
        for (x in backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x)
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
            .isEqualTo(backoffIntervals[backoffIntervals.size - 1])
    }

    private fun adjustExpectedInterval(
        interval: Long,
        isFiltered: Boolean,
        isScreenOn: Boolean,
    ): Long {
        if (isFiltered) {
            return interval
        }
        val threshold =
            if (isScreenOn) BatchScanThrottler.UNFILTERED_DELAY_FLOOR_DEFAULT
            else BatchScanThrottler.UNFILTERED_SCREEN_OFF_DELAY_FLOOR_DEFAULT
        return max(interval, threshold.toLong())
    }

    private fun getBackoffIntervals(baseInterval: Long) =
        BatchScanThrottler.BACKOFF_MULTIPLIERS.map { it * baseInterval }

    private fun createBatchScanClient(isFiltered: Boolean) =
        ScanClient(
            mock<ScannerApp> {
                doReturn(
                        if (isFiltered)
                            listOf(ScanFilter.Builder().setDeviceName("TestName").build())
                        else emptyList()
                    )
                    .whenever(it)
                    .filters
            },
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(DEFAULT_REPORT_DELAY_FLOOR_MS)
                .build(),
            mock<UserHandle>(),
        )

    private fun advanceTime(amountToAdvanceMillis: Int) =
        timeProvider.advanceTime(Duration.ofMillis(amountToAdvanceMillis.toLong()))
}
