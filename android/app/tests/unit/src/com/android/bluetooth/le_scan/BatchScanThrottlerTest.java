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

package com.android.bluetooth.le_scan;

import static android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.le_scan.ScanController.DEFAULT_REPORT_DELAY_FLOOR;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.bluetooth.TestUtils.FakeTimeProvider;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

/** Test cases for {@link BatchScanThrottler}. */
@SmallTest
@RunWith(TestParameterInjector.class)
public class BatchScanThrottlerTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private FakeTimeProvider mTimeProvider;

    @Before
    public void setUp() {
        mTimeProvider = new FakeTimeProvider();
    }

    private void advanceTime(long amountToAdvanceMillis) {
        mTimeProvider.advanceTime(Duration.ofMillis(amountToAdvanceMillis));
    }

    @Test
    public void basicThrottling(
            @TestParameter boolean isFiltered, @TestParameter boolean isScreenOn) {
        BatchScanThrottler throttler = new BatchScanThrottler(mTimeProvider, isScreenOn);
        if (!isScreenOn) {
            advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_MS);
        }
        Set<ScanClient> clients =
                Collections.singleton(
                        createBatchScanClient(DEFAULT_REPORT_DELAY_FLOOR, isFiltered));
        long[] backoffIntervals =
                getBackoffIntervals(
                        isScreenOn
                                ? DEFAULT_REPORT_DELAY_FLOOR
                                : BatchScanThrottler.SCREEN_OFF_MINIMUM_DELAY_FLOOR_MS);
        for (long x : backoffIntervals) {
            long expected = adjustExpectedInterval(x, isFiltered, isScreenOn);
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected);
        }
        long expected =
                adjustExpectedInterval(
                        backoffIntervals[backoffIntervals.length - 1], isFiltered, isScreenOn);
        // Ensure that subsequent calls continue to return the final throttled interval
        assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected);
        assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(expected);
    }

    @Test
    public void screenOffDelayAndReset(@TestParameter boolean screenOnAtStart) {
        BatchScanThrottler throttler = new BatchScanThrottler(mTimeProvider, screenOnAtStart);
        if (screenOnAtStart) {
            throttler.onScreenOn(false);
        }
        Set<ScanClient> clients =
                Collections.singleton(createBatchScanClient(DEFAULT_REPORT_DELAY_FLOOR, true));
        long[] backoffIntervals = getBackoffIntervals(DEFAULT_REPORT_DELAY_FLOOR);
        advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_MS - 1);
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }

        backoffIntervals =
                getBackoffIntervals(BatchScanThrottler.SCREEN_OFF_MINIMUM_DELAY_FLOOR_MS);
        advanceTime(1);
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
                .isEqualTo(backoffIntervals[backoffIntervals.length - 1]);
    }

    @Test
    public void testScreenOnReset() {
        BatchScanThrottler throttler = new BatchScanThrottler(mTimeProvider, false);
        advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_MS);
        Set<ScanClient> clients =
                Collections.singleton(createBatchScanClient(DEFAULT_REPORT_DELAY_FLOOR, true));
        long[] backoffIntervals =
                getBackoffIntervals(BatchScanThrottler.SCREEN_OFF_MINIMUM_DELAY_FLOOR_MS);
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }

        throttler.onScreenOn(true);
        backoffIntervals = getBackoffIntervals(DEFAULT_REPORT_DELAY_FLOOR);
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
                .isEqualTo(backoffIntervals[backoffIntervals.length - 1]);
    }

    @Test
    public void resetBackoff_restartsToFirstStage(@TestParameter boolean isScreenOn) {
        BatchScanThrottler throttler = new BatchScanThrottler(mTimeProvider, isScreenOn);
        if (!isScreenOn) {
            // Advance the time before we start the test to when the screen-off intervals should be
            // used
            advanceTime(BatchScanThrottler.SCREEN_OFF_DELAY_MS);
        }
        Set<ScanClient> clients =
                Collections.singleton(createBatchScanClient(DEFAULT_REPORT_DELAY_FLOOR, true));
        long[] backoffIntervals =
                getBackoffIntervals(
                        isScreenOn
                                ? DEFAULT_REPORT_DELAY_FLOOR
                                : BatchScanThrottler.SCREEN_OFF_MINIMUM_DELAY_FLOOR_MS);
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
                .isEqualTo(backoffIntervals[backoffIntervals.length - 1]);

        throttler.resetBackoff();
        for (long x : backoffIntervals) {
            assertThat(throttler.getBatchTriggerIntervalMillis(clients)).isEqualTo(x);
        }
        assertThat(throttler.getBatchTriggerIntervalMillis(clients))
                .isEqualTo(backoffIntervals[backoffIntervals.length - 1]);
    }

    private static long adjustExpectedInterval(
            long interval, boolean isFiltered, boolean isScreenOn) {
        if (isFiltered) {
            return interval;
        }
        long threshold =
                isScreenOn
                        ? BatchScanThrottler.UNFILTERED_DELAY_FLOOR_MS
                        : BatchScanThrottler.UNFILTERED_SCREEN_OFF_DELAY_FLOOR_MS;
        return Math.max(interval, threshold);
    }

    private static long[] getBackoffIntervals(long baseInterval) {
        return LongStream.range(0, BatchScanThrottler.BACKOFF_MULTIPLIERS.length)
                .map(x -> BatchScanThrottler.BACKOFF_MULTIPLIERS[(int) x] * baseInterval)
                .toArray();
    }

    private static ScanClient createBatchScanClient(long reportDelayMillis, boolean isFiltered) {
        ScanSettings scanSettings =
                new ScanSettings.Builder()
                        .setScanMode(SCAN_MODE_BALANCED)
                        .setReportDelay(reportDelayMillis)
                        .build();

        return new ScanClient(1, scanSettings, createScanFilterList(isFiltered), 1);
    }

    private static List<ScanFilter> createScanFilterList(boolean isFiltered) {
        List<ScanFilter> scanFilterList = null;
        if (isFiltered) {
            scanFilterList = List.of(new ScanFilter.Builder().setDeviceName("TestName").build());
        }
        return scanFilterList;
    }
}
