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

import static com.android.bluetooth.le_scan.ScanController.DEFAULT_REPORT_DELAY_FLOOR;

import android.content.Context;
import android.provider.DeviceConfig;

import com.android.bluetooth.R;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Throttler to reduce the number of times the Bluetooth process wakes up to check for pending batch
 * scan results. The wake-up intervals are increased when no matching results are found and are
 * longer when the screen is off.
 */
class BatchScanThrottler {
    // Backoff stages used as multipliers for the minimum delay floor (standard or screen-off)
    @VisibleForTesting static final int[] BACKOFF_MULTIPLIERS = {1, 1, 2, 2, 4};

    private final TimeProvider mTimeProvider;
    private final long mDelayFloor;
    private final long mScreenOffDelayFloor;
    private int mBackoffStage = 0;
    private long mScreenOffTriggerTime = 0L;
    private boolean mScreenOffThrottling = false;

    private int mScreenOffMinimumDelayFloorMs;
    private int mUnfilteredDelayFloorMs;
    private int mUnfilteredScreenOffDelayFloorMs;
    private int mScreenOffDelayMs;

    BatchScanThrottler(Context context, TimeProvider timeProvider, boolean screenOn) {
        mTimeProvider = timeProvider;

        mScreenOffMinimumDelayFloorMs =
                context.getResources()
                        .getInteger(R.integer.batch_scan_screen_off_minimum_delay_floor_ms);
        mUnfilteredDelayFloorMs =
                context.getResources().getInteger(R.integer.batch_scan_unfiltered_delay_floor_ms);
        mUnfilteredScreenOffDelayFloorMs =
                context.getResources()
                        .getInteger(R.integer.batch_scan_unfiltered_screen_off_delay_floor_ms);
        mScreenOffDelayMs =
                context.getResources().getInteger(R.integer.batch_scan_screen_off_delay_ms);

        mDelayFloor =
                DeviceConfig.getLong(
                        DeviceConfig.NAMESPACE_BLUETOOTH,
                        "report_delay",
                        DEFAULT_REPORT_DELAY_FLOOR);
        mScreenOffDelayFloor = Math.max(mDelayFloor, mScreenOffMinimumDelayFloorMs);
        onScreenOn(screenOn);
    }

    void resetBackoff() {
        mBackoffStage = 0;
    }

    void onScreenOn(boolean screenOn) {
        if (screenOn) {
            mScreenOffTriggerTime = 0L;
            mScreenOffThrottling = false;
            resetBackoff();
        } else {
            // Screen-off intervals to be used after the trigger time
            mScreenOffTriggerTime = mTimeProvider.elapsedRealtime() + mScreenOffDelayMs;
        }
    }

    long getBatchTriggerIntervalMillis(Set<ScanClient> batchClients) {
        // Check if we're past the screen-off time and should be using screen-off backoff values
        if (!mScreenOffThrottling
                && mScreenOffTriggerTime != 0
                && mTimeProvider.elapsedRealtime() >= mScreenOffTriggerTime) {
            mScreenOffThrottling = true;
            resetBackoff();
        }
        long unfilteredFloor =
                mScreenOffThrottling ? mUnfilteredScreenOffDelayFloorMs : mUnfilteredDelayFloorMs;
        long intervalMillis = Long.MAX_VALUE;
        for (ScanClient client : batchClients) {
            if (client.mSettings.getReportDelayMillis() > 0) {
                long clientIntervalMillis = client.mSettings.getReportDelayMillis();
                if ((client.mFilters == null || client.mFilters.isEmpty())
                        && clientIntervalMillis < unfilteredFloor) {
                    clientIntervalMillis = unfilteredFloor;
                }
                intervalMillis = Math.min(intervalMillis, clientIntervalMillis);
            }
        }
        int backoffIndex =
                mBackoffStage >= BACKOFF_MULTIPLIERS.length
                        ? BACKOFF_MULTIPLIERS.length - 1
                        : mBackoffStage++;
        return Math.max(
                intervalMillis,
                (mScreenOffThrottling ? mScreenOffDelayFloor : mDelayFloor)
                        * BACKOFF_MULTIPLIERS[backoffIndex]);
    }
}
