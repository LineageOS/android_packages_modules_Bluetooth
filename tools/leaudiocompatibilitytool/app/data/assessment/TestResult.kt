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

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.device.Device
import java.time.Instant
import java.time.ZoneId

/**
 * Represent the test result for a test item.
 *
 * @property testItemId The ID of the test item.
 * @property testItemName The name of the test item.
 * @property lastAssessmentId The ID of the last assessment that the test item was tested in.
 * @property passed Whether the test item passed or not. Default is null for not tested items.
 * @property startTime The start time of the test item. Default is null for not tested items.
 * @property endTime The end time of the test item. Default is null for not tested items.
 * @property p95ValueInMillis The P95 value of the test item in milliseconds. Default is null for
 *   not tested items.
 * @property outlierValueInMillis The outlier value of the test item in milliseconds. Default is
 *   null for not tested items.
 * @property devices The list of devices that were used for the test item.
 */
data class TestResult(
    val testItemId: Int,
    val testItemName: String,
    val lastAssessmentId: Int? = null,
    val passed: Boolean? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val p95ValueInMillis: Long? = null,
    val outlierValueInMillis: Long? = null,
    val devices: List<Device> = listOf(),
) {
    companion object {
        /**
         * Converts an [Instant] to a local time string.
         *
         * @return The local time string.
         */
        fun Instant?.toLocalTimeString(): String {
            if (this == null) return ""
            return this.atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
        }

        /**
         * Converts an [Iterable] to a CSV string.
         *
         * @return The CSV string.
         */
        fun <T> Iterable<T>.toCsvString(): String {
            return this.joinToString(prefix = "\"", separator = "\",\"", postfix = "\"\n")
        }

        /**
         * Converts a [TestResult] to a CSV row.
         *
         * @return The CSV row.
         */
        private fun TestResult.toCsvRow(): String {
            val startTimeStr = startTime.toLocalTimeString()
            val endTimeStr = endTime.toLocalTimeString()
            val p95ValueStr = p95ValueInMillis?.div(1000f).toString()
            val outlierValueStr = outlierValueInMillis?.div(1000f).toString()
            val deviceAddresses = devices.joinToString(",") { it.address }

            val fields =
                listOf(
                    testItemId.toString(),
                    testItemName,
                    lastAssessmentId.toString(),
                    deviceAddresses,
                    passed.toString(),
                    startTimeStr,
                    endTimeStr,
                    p95ValueStr,
                    outlierValueStr,
                )

            return fields.toCsvString()
        }

        /**
         * Converts a list of [TestResult] to a CSV format.
         *
         * @return The CSV format.
         */
        fun List<TestResult>.toCsvFormat(): String {
            val csvBuilder = StringBuilder()
            val headerFields =
                listOf(
                    "Test Item ID",
                    "Test Item Name",
                    "Last Assessment ID",
                    "Devices",
                    "Passed",
                    "Start Time",
                    "End Time",
                    "p95 Value (s)",
                    "Outlier Value (s)",
                )

            // Add the header row
            csvBuilder.append(headerFields.toCsvString())
            for (testResult in this) {
                csvBuilder.append(testResult.toCsvRow())
            }

            return csvBuilder.toString()
        }
    }
}
