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

import static com.android.bluetooth.le_scan.BatchScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS;

import static java.util.Objects.requireNonNull;

import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.bluetooth.util.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Throttler to reduce the number of times the Bluetooth process wakes up to check for pending batch
 * scan results. The wake-up intervals are increased when no matching results are found and are
 * longer when the screen is off.
 */
class BatchScanThrottler {
    private static final String TAG =
            ScanUtil.TAG_PREFIX + BatchScanThrottler.class.getSimpleName();

    // Minimum batch trigger interval to check for batched results when the screen is off
    private static final String SCREEN_OFF_MINIMUM_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.screen_off_minimum_delay_floor_ms.config";
    // Adjusted minimum report delay for unfiltered batch scan clients
    private static final String UNFILTERED_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.unfiltered_delay_floor_ms.config";
    // Adjusted minimum report delay for unfiltered batch scan clients when the screen is off
    private static final String UNFILTERED_SCREEN_OFF_DELAY_FLOOR_PROP =
            "bluetooth.ble.batch_scan.unfiltered_screen_off_delay_floor_ms.config";
    // Start screen-off trigger interval throttling after the screen has been off for this period
    // of time. This allows the screen-on intervals to be used for a short period of time after the
    // screen has gone off, and avoids too much flipping between screen-off and screen-on backoffs
    // when the screen is off for a short period of time
    private static final String SCREEN_OFF_DELAY_PROP =
            "bluetooth.ble.batch_scan.screen_off_delay_ms.config";

    @VisibleForTesting static final int SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT = 20000;
    @VisibleForTesting static final int UNFILTERED_DELAY_FLOOR_DEFAULT = 20000;
    @VisibleForTesting static final int UNFILTERED_SCREEN_OFF_DELAY_FLOOR_DEFAULT = 60000;
    @VisibleForTesting static final int SCREEN_OFF_DELAY_DEFAULT = 60000;

    // Backoff stages used as multipliers for the minimum delay floor (standard or screen-off)
    @VisibleForTesting static final int[] BACKOFF_MULTIPLIERS = {1, 1, 2, 2, 4};

    private final TimeProvider mTimeProvider;
    private final int mScreenOffMinimumDelayFloorMs;
    private final int mUnfilteredDelayFloorMs;
    private final int mUnfilteredScreenOffDelayFloorMs;
    private final int mScreenOffDelayMs;
    private final long mDelayFloorMs;
    private final long mScreenOffDelayFloorMs;

    private int mBackoffStage = 0;
    private long mScreenOffTriggerTime = 0L;
    private boolean mScreenOffThrottling = false;

    BatchScanThrottler(TimeProvider timeProvider, boolean screenOn) {
        mTimeProvider = requireNonNull(timeProvider);
        mScreenOffMinimumDelayFloorMs =
                SystemProperties.getInt(
                        SCREEN_OFF_MINIMUM_DELAY_FLOOR_PROP,
                        SCREEN_OFF_MINIMUM_DELAY_FLOOR_DEFAULT);
        mUnfilteredDelayFloorMs =
                SystemProperties.getInt(
                        UNFILTERED_DELAY_FLOOR_PROP, UNFILTERED_DELAY_FLOOR_DEFAULT);
        mUnfilteredScreenOffDelayFloorMs =
                SystemProperties.getInt(
                        UNFILTERED_SCREEN_OFF_DELAY_FLOOR_PROP,
                        UNFILTERED_SCREEN_OFF_DELAY_FLOOR_DEFAULT);
        mScreenOffDelayMs =
                SystemProperties.getInt(SCREEN_OFF_DELAY_PROP, SCREEN_OFF_DELAY_DEFAULT);
        mDelayFloorMs =
                DeviceConfig.getLong(
                        DeviceConfig.NAMESPACE_BLUETOOTH,
                        "report_delay",
                        DEFAULT_REPORT_DELAY_FLOOR_MS);
        mScreenOffDelayFloorMs = Math.max(mDelayFloorMs, mScreenOffMinimumDelayFloorMs);
        Log.d(
                TAG,
                "Initialized with:"
                        + (" screenOffMinimumDelayFloorMs=" + mScreenOffMinimumDelayFloorMs)
                        + (", unfilteredDelayFloorMs=" + mUnfilteredDelayFloorMs)
                        + (", unfilteredScreenOffDelayFloorMs=" + mUnfilteredScreenOffDelayFloorMs)
                        + (", screenOffDelayMs=" + mScreenOffDelayMs)
                        + (", delayFloorMs=" + mDelayFloorMs)
                        + (", screenOffDelayFloorMs=" + mScreenOffDelayFloorMs));
        onScreenOn(screenOn);
    }

    void resetBackoff() {
        Log.d(TAG, "resetBackoff() called");
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

        long minimumReportDelayMs = getMinimumReportDelayMillis(batchClients);

        final int backoffIndex =
                mBackoffStage >= BACKOFF_MULTIPLIERS.length
                        ? BACKOFF_MULTIPLIERS.length - 1
                        : mBackoffStage++;
        final long finalInterval =
                Math.max(
                        minimumReportDelayMs,
                        (mScreenOffThrottling ? mScreenOffDelayFloorMs : mDelayFloorMs)
                                * BACKOFF_MULTIPLIERS[backoffIndex]);
        Log.d(TAG, "Batch trigger interval: " + finalInterval + "ms");
        return finalInterval;
    }

    private long getMinimumReportDelayMillis(Set<ScanClient> batchClients) {
        long unfilteredFloor =
                mScreenOffThrottling ? mUnfilteredScreenOffDelayFloorMs : mUnfilteredDelayFloorMs;
        long minimumReportDelayMs = Long.MAX_VALUE;
        for (ScanClient client : batchClients) {
            if (client.getSettings().getReportDelayMillis() > 0) {
                long clientReportDelayMs = client.getSettings().getReportDelayMillis();
                if (!client.isFiltered() && clientReportDelayMs < unfilteredFloor) {
                    clientReportDelayMs = unfilteredFloor;
                }
                minimumReportDelayMs = Math.min(minimumReportDelayMs, clientReportDelayMs);
            }
        }
        return minimumReportDelayMs;
    }
}
