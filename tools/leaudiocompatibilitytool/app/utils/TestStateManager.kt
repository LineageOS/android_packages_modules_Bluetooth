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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.RunResult
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.SelectedTestItem
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * TestStateManager is a singleton class that manages the state of the test. For each test, it
 * tracks the test run time, number of testItems, test devices, etc.
 */
@Singleton
class TestStateManager @Inject constructor() {
    private val _finishedRuns = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val finishedRuns: StateFlow<Map<Int, Int>> = _finishedRuns.asStateFlow()
    var currentAssessmentId = INVALID_ID

    /** Initialize the finished runs to 0 for the given test item id. */
    fun initFinishedRuns(testItemId: Int) {
        _finishedRuns.update { it + (testItemId to 0) }
    }

    /**
     * Reset the finished runs to 0 for the given test item id.
     *
     * @param testItemId The test item id.
     */
    fun resetFinishedRuns(testItemId: Int) {
        _finishedRuns.update { it - testItemId }
    }

    /** Reset the current assessment id to INVALID_ID. */
    fun resetCurrentAssessmentId() {
        currentAssessmentId = INVALID_ID
    }

    /**
     * Returns the finished runs for the given test item id.
     *
     * @param testItemId The test item id.
     * @return The finished runs for the given test item id.
     */
    fun getFinishedRuns(testItemId: Int): Int {
        return _finishedRuns.value[testItemId] ?: 0
    }

    /** Update the finished runs by adding the given number. */
    fun addFinishedRunsByOne(testItemId: Int) {
        _finishedRuns.update { currentMap ->
            val currentRun = currentMap[testItemId]
            if (currentRun != null) {
                currentMap + (testItemId to currentRun + 1)
            } else {
                currentMap
            }
        }
    }

    /**
     * Update the runs with their performance test results.
     *
     * @param testItemId The test item id.
     * @param runs The runs to be updated.
     * @return The runs with passed/failed results.
     */
    fun updateRunsWithResults(testItemId: Int, runs: List<Run>): RunResult {
        val testItem =
            TestItemUiModel.LIST.firstOrNull { it.testItemId == testItemId }
                ?: return RunResult(
                    runs,
                    SelectedTestItem(assessmentId = currentAssessmentId, testItemId = testItemId),
                )
        val isPerformanceTest = testItem.qualification.isPerformanceTest
        val runsRequired = testItem.qualification.runsRequired
        val startTime = runs.firstOrNull()?.startTime
        val endTime = runs.lastOrNull()?.endTime

        // Functionality test - Failed if any run fails.
        if (!isPerformanceTest) {
            return RunResult(
                runs,
                SelectedTestItem(
                    assessmentId = currentAssessmentId,
                    testItemId = testItemId,
                    passed = runs.size >= runsRequired && runs.all { it.passed },
                    startTime,
                    endTime,
                ),
            )
        }

        var runsSortedByDuration = runs.sortedBy { it.getDuration() }
        val outlierValue = testItem.qualification.outlierValue
        val p95Value = testItem.qualification.p95Value

        /**
         * The index of the 95th percentile value.
         * - run.size = 0, p95Index = 0, but p95ValueInMillis will be null.
         * - run.size = 1, p95Index = 0, p95ValueInMillis will be runs[0]'s duration.
         * - run.size >= 2, p95Index will be (run.size * 0.95).toInt() - 1.
         */
        val p95Index = maxOf(0, (runs.size * PERCENTILE_95).toInt() - 1)

        runsSortedByDuration = runsSortedByDuration.mapIndexed { index, run ->
            /**
             * Failed if:
             * - Did not pass. (e.g. timeout)
             * - Duration is greater than outlierValue.
             * - Duration is greater than p95Value for those whose index is less or equal to
             *   p95Index.
             */
            // Failed if does not pass or its outlier/ P95 value is greater than expected.
            val failed =
                !run.passed ||
                    (run.getDuration() >= outlierValue) ||
                    (index <= p95Index && run.getDuration() >= p95Value)
            run.copy(passed = !failed)
        }

        val p95ValueInMillis =
            if (runs.size > 0) {
                runsSortedByDuration[p95Index].getDuration().toMillis()
            } else {
                null
            }
        val outlierValueInMillis = runsSortedByDuration.lastOrNull()?.getDuration()?.toMillis()
        val updatedRuns = runsSortedByDuration.sortedBy { it.runId }

        // Performance test - Failed if outlier or P95 value is greater than expected.
        return RunResult(
            updatedRuns,
            SelectedTestItem(
                assessmentId = currentAssessmentId,
                testItemId = testItemId,
                passed = updatedRuns.size >= runsRequired && updatedRuns.all { it.passed },
                startTime,
                endTime,
                p95ValueInMillis,
                outlierValueInMillis,
            ),
        )
    }

    companion object {
        const val INVALID_ID = -1
        const val PERCENTILE_95 = 0.95
    }
}
