/*
 * Copyright (C) 2026 The Android Open Source Project
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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.tests.bluetooth.FakeTimeProvider
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTime::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AppCurrentConsumptionStatsTest {

    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var stats: AppCurrentConsumptionStats

    @Before
    fun setUp() {
        timeProvider = FakeTimeProvider()
        stats = AppCurrentConsumptionStats(timeProvider)
    }

    @Test
    fun addScanResults_screenOn_isIgnoredForSeverity() {
        // Add 1000 screen-on results (which is > 800 HIGH threshold for 1H)
        stats.addScanResults(1000, isScreenOn = true)

        val severities = stats.getCurrentSeverities()

        assertThat(severities[1]?.severity).isEqualTo(AppCurrentConsumptionStats.Severity.NORMAL)
        assertThat(severities[1]?.total).isEqualTo(0)
    }

    @Test
    fun oneHourWindow_reachesMediumSeverity() {
        // 1H MEDIUM threshold is 640
        stats.addScanResults(650, isScreenOn = false)

        val severities = stats.getCurrentSeverities()

        assertThat(severities[1]?.severity).isEqualTo(AppCurrentConsumptionStats.Severity.MEDIUM)
        assertThat(severities[1]?.total).isEqualTo(650)
    }

    @Test
    fun oneHourWindow_reachesHighSeverity_doesNotCrashOnCheck() {
        // 1H HIGH threshold is 800
        stats.addScanResults(850, isScreenOn = false)
        stats.checkThresholdViolation("com.test.app")

        val severities = stats.getCurrentSeverities()

        assertThat(severities[1]?.severity).isEqualTo(AppCurrentConsumptionStats.Severity.HIGH)
        assertThat(severities[1]?.average).isEqualTo(850)
    }

    @Test
    fun fourHourWindow_accumulatesCorrectly_reachesHighSeverity() {
        // Hour 1: 500 scans
        stats.addScanResults(500, isScreenOn = false)
        timeProvider.advanceTime(1.hours)

        // Hour 2: 500 scans
        stats.addScanResults(500, isScreenOn = false)
        timeProvider.advanceTime(1.hours)

        // Hour 3: 500 scans
        stats.addScanResults(500, isScreenOn = false)
        timeProvider.advanceTime(1.hours)

        // Hour 4: 1100 scans (Total = 2600. Avg = 650/hr)
        stats.addScanResults(1100, isScreenOn = false)

        val severities = stats.getCurrentSeverities()

        val fourHourReport = severities[4]
        assertThat(fourHourReport).isNotNull()
        assertThat(fourHourReport?.severity).isEqualTo(AppCurrentConsumptionStats.Severity.HIGH)
        assertThat(fourHourReport?.total).isEqualTo(2600)
        assertThat(fourHourReport?.average).isEqualTo(650)
    }

    @Test
    fun pruning_oldDataIsRemovedAfterEightHours() {
        stats.addScanResults(1000, isScreenOn = false)
        assertThat(stats.getCurrentSeverities()[1]?.severity)
            .isEqualTo(AppCurrentConsumptionStats.Severity.HIGH)

        // Advance time by 9 hours (past the 8-hour keep limit)
        timeProvider.advanceTime(9.hours)

        // Add a small number of new scans to trigger the prune logic
        stats.addScanResults(10, isScreenOn = false)

        val severities = stats.getCurrentSeverities()

        // The 1000 scans should be pruned, leaving only the 10 recent ones (NORMAL)
        assertThat(severities[1]?.severity).isEqualTo(AppCurrentConsumptionStats.Severity.NORMAL)
        assertThat(severities[1]?.total).isEqualTo(10)

        // Also verify the 8-hour window doesn't contain the old 1000 scans
        assertThat(severities[8]?.total).isEqualTo(10)
    }
}
