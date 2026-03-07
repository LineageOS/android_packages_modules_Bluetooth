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
package android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestResult.Companion.toCsvString
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestResult.Companion.toLocalTimeString
import androidx.room.Entity
import java.time.Duration
import java.time.Instant

/**
 * Represents a run for a test item.
 *
 * @property runId The run id of the run.
 * @property assessmentId The assessment id of the run.
 * @property testItemId The test item id of the run.
 * @property passed The passed status of the run.
 * @property startTime The start time of the run.
 * @property endTime The end time of the run.
 */
@Entity(primaryKeys = ["runId", "assessmentId", "testItemId"])
data class Run(
    val runId: Int,
    val assessmentId: Int,
    val testItemId: Int,
    val passed: Boolean,
    val startTime: Instant,
    val endTime: Instant,
) {

    /**
     * Returns the duration for the given run.
     *
     * @return The duration between the start time and the end time.
     */
    fun getDuration(): Duration = Duration.between(this.startTime, this.endTime)

    companion object {
        /**
         * Converts a [Run] to a CSV row.
         *
         * @return The CSV row.
         */
        private fun Run.toCsvRow(isPerformanceTest: Boolean): String {
            val startTimeStr = this.startTime.toLocalTimeString()
            val endTimeStr = this.endTime.toLocalTimeString()
            val durationStr =
                if (isPerformanceTest) this.getDuration().toMillis().div(1000f).toString() else ""
            val testTypeStr = if (isPerformanceTest) "Performance" else "Functional"

            val fields =
                listOf(
                    testItemId.toString(),
                    runId.toString(),
                    assessmentId.toString(),
                    passed.toString(),
                    testTypeStr,
                    startTimeStr,
                    endTimeStr,
                    durationStr,
                )

            return fields.toCsvString()
        }

        /**
         * Converts a list of [Run List] to a CSV format.
         *
         * @return The CSV format.
         */
        fun List<List<Run>>.toCsvFormat(): String {
            val csvBuilder = StringBuilder()
            val headerFields =
                listOf(
                    "Test Item ID",
                    "Run ID",
                    "Assessment ID",
                    "Passed",
                    "Test Type",
                    "Start Time",
                    "End Time",
                    "Duration (s)",
                )

            csvBuilder.append(headerFields.toCsvString())
            for (runList in this) {
                val testItem =
                    TestItemUiModel.LIST.firstOrNull {
                        it.testItemId == runList.firstOrNull()?.testItemId
                    }
                val isPerformanceTest = testItem?.qualification?.isPerformanceTest ?: false

                for (run in runList) {
                    csvBuilder.append(run.toCsvRow(isPerformanceTest))
                }
            }
            return csvBuilder.toString()
        }
    }
}

/**
 * The UI model for a run.
 *
 * @property runId The run id of the run.
 * @property passed The passed status of the run.
 * @property startTime The start time of the run.
 * @property endTime The end time of the run.
 * @property isPerformanceTest Whether the run is a performance test.
 * @property duration The duration of the run.
 */
data class RunUiModel(
    val runId: Int,
    val passed: Boolean,
    val startTime: Instant,
    val endTime: Instant,
    val isPerformanceTest: Boolean,
    val duration: Duration? = null,
) {
    companion object {
        /**
         * Converts a list of [Run] from the database to a list of [RunData] to present in the view.
         *
         * @param testItemId The test item id of the runs.
         * @param runs The list of [Run] from the database.
         * @return The list of [RunData].
         */
        fun List<Run>.toViewModelFormat(testItemId: Int): List<RunUiModel> {
            val testItem = TestItemUiModel.LIST.firstOrNull { it.testItemId == testItemId }
            val isPerformanceTest = testItem?.qualification?.isPerformanceTest ?: false
            return this.map { run ->
                RunUiModel(
                    runId = run.runId,
                    passed = run.passed,
                    startTime = run.startTime,
                    endTime = run.endTime,
                    isPerformanceTest = isPerformanceTest,
                    duration = if (isPerformanceTest) run.getDuration() else null,
                )
            }
        }
    }
}

/**
 * Represent the calculated result for a run list.
 *
 * @property runs The list of runs for the test item.
 * @property testItemResult The calculated result for the test item.
 */
data class RunResult(val runs: List<Run>, val testItemResult: SelectedTestItem)
