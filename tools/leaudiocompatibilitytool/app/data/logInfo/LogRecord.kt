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
package android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.TimeFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.toTimeString
import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Represent a log data collected during a test and stored in the database.
 *
 * @property logId The unique identifier for the log record.
 * @property assessmentId The id of the assessment for the log record.
 * @property testItemId The id of the test item for the log record.
 * @property timestamp The timestamp of the log record.
 * @property message The message of the log record.
 * @property actionRequired Whether the log record requires action from the user.
 */
@Entity
data class LogRecord
constructor(
    @PrimaryKey(autoGenerate = true) val logId: Int = 0,
    val assessmentId: Int,
    val testItemId: Int,
    val timestamp: Instant,
    val message: String,
    val actionRequired: Boolean,
)

/**
 * Represent a log data collected during a test. This class is used to show the log data in the
 * View.
 *
 * @property timestamp The timestamp of the log data.
 * @property message The message of the log data.
 * @property actionRequired Whether the log data requires action from the user.
 */
data class LogRecordUiModel
constructor(
    val timestamp: Instant = Instant.now(),
    val message: String,
    val actionRequired: Boolean = false,
) {
    init {
        // add log into logcat
        Log.d(TAG, message)
    }

    /** Returns the timestamp of the log record in a human-readable format. */
    fun getTimestampText(): String = timestamp.toTimeString(TimeFormat.TIME_ONLY)

    companion object {
        const val TAG = "LEA Compatibility Tool"
        const val ALL_RUNS_FINISHED_TEXT = "=== All runs finished ==="

        /**
         * Converts a list of [LogRecordUiModel] to a list of [LogRecord].
         *
         * @param assessmentId The id of the assessment for the logs.
         * @param testItemId The id of the test item for the logs.
         * @return The list of [LogRecord] converted from the input [LogRecordUiModel].
         */
        fun List<LogRecordUiModel>.toDatabaseFormat(
            assessmentId: Int,
            testItemId: Int,
        ): List<LogRecord> {
            return this.map { log ->
                LogRecord(
                    assessmentId = assessmentId,
                    testItemId = testItemId,
                    timestamp = log.timestamp,
                    message = log.message,
                    actionRequired = log.actionRequired,
                )
            }
        }

        /**
         * Converts a list of [LogRecord] to a list of [LogRecordUiModel].
         *
         * @return The list of [LogRecordUiModel] converted from the input [LogRecord].
         */
        fun List<LogRecord>.toViewModelFormat(): List<LogRecordUiModel> {
            return this.map { log ->
                LogRecordUiModel(
                    timestamp = log.timestamp,
                    message = log.message,
                    actionRequired = log.actionRequired,
                )
            }
        }

        /**
         * Returns the fail message for a timeouted test.
         *
         * @param failedAction The action that failed to be triggered.
         */
        fun getTimeOutString(failedAction: String): String {
            return "Time out: Failed to $failedAction."
        }

        /**
         * Returns the string for the start of a test run.
         *
         * @param runs The number of the current run.
         */
        fun getRunStartString(runs: Int): String {
            return "--- Run $runs ---"
        }

        /**
         * Returns the string for the end of a test run.
         *
         * @param runs The number of the current run.
         * @param success Whether the run was successful or not.
         */
        fun getRunEndString(runs: Int, success: Boolean): String {
            if (success) {
                return "--- Run $runs succeed. ---"
            }
            return "--- Run $runs failed. ---"
        }

        /**
         * Returns the string for an exception.
         *
         * @param e The exception.
         */
        fun getExceptionString(e: Exception): String {
            return "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
