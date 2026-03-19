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

import android.bluetooth.BluetoothDevice
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.device.Device
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.device.SelectedDevice
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecord
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel.Companion.toDatabaseFormat
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant

@Dao
interface AssessmentDao {

    // Inserts a new assessment and returns the rowId (which is also the assessment id).
    @Insert suspend fun insertAssessment(assessment: Assessment): Long

    // Gets the assessment by id. Returns null if not found.
    @Query("SELECT * FROM Assessment WHERE assessmentId = :assessmentId")
    suspend fun getAssessmentById(assessmentId: Int): Assessment?

    // Inserts a list of selected test items.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSelectedTestItems(selectedTestItems: List<SelectedTestItem>)

    // Gets the selected test items by assessment id.
    @Query("SELECT * FROM SelectedTestItem WHERE assessmentId = :assessmentId")
    suspend fun getSelectedTestItemsByAssessmentId(assessmentId: Int): List<SelectedTestItem>

    // Inserts a list of test items.
    @Insert suspend fun insertTestItems(testItems: List<TestItem>)

    // Gets the number of test items.
    @Query("SELECT COUNT(*) FROM TestItem") suspend fun getTestItemCount(): Int

    // Gets the test item by id. Returns null if not found.
    @Query("SELECT * FROM TestItem WHERE testItemId = :testItemId")
    suspend fun getTestItemById(testItemId: Int): TestItem?

    // Inserts a new device.
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDevice(device: Device): Long

    // Gets the device id by address. Returns null if not found.
    @Query("SELECT deviceId FROM Device WHERE address = :deviceAddress")
    suspend fun getDeviceIdByAddress(deviceAddress: String): Int?

    // Gets the device by id. Returns null if not found.
    @Query("SELECT * FROM Device WHERE deviceId = :deviceId")
    suspend fun getDeviceById(deviceId: Int): Device?

    // Inserts a list of selected devices.
    @Insert suspend fun insertSelectedDevices(selectedDevices: List<SelectedDevice>)

    // Gets the selected devices by assessment id.
    @Query("SELECT * FROM SelectedDevice WHERE assessmentId = :assessmentId")
    suspend fun getSelectedDevicesByAssessmentId(assessmentId: Int): List<SelectedDevice>

    // Creates a new assessment with the given test items and devices.
    @Transaction
    suspend fun createAssessmentWithTestItems(
        assessment: Assessment,
        testItemIds: List<Int>,
        devices: List<BluetoothDevice>,
    ): Long {
        val assessmentId = insertAssessment(assessment)

        // Handle selected test items
        val selectedTestItems = testItemIds.map { id ->
            SelectedTestItem(assessmentId = assessmentId.toInt(), testItemId = id)
        }
        insertSelectedTestItems(selectedTestItems)

        // Handle selected devices. If the device is not in the database, insert it.
        val selectedDevices = devices.map {
            val deviceId =
                getDeviceIdByAddress(it.address)
                    ?: insertDevice(Device(name = it.name, address = it.address)).toInt()
            SelectedDevice(deviceId, assessmentId.toInt())
        }
        insertSelectedDevices(selectedDevices)
        return assessmentId
    }

    // Inserts a list of runs.
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRuns(runs: List<Run>)

    // Gets the runs by assessment id and test item id.
    @Query(
        """
    SELECT * FROM Run
    WHERE assessmentId = :assessmentId AND testItemId = :testItemId
    ORDER BY runId, startTime ASC
    """
    )
    suspend fun getRunsByAssessmentIdAndTestItemId(assessmentId: Int, testItemId: Int): List<Run>

    // Removes the runs by test item id.
    @Query("DELETE FROM Run WHERE testItemId = :testItemId")
    suspend fun removeRunsByTestItemId(testItemId: Int)

    // Updates the last assessment id in TestItem.
    @Query("UPDATE TestItem SET lastAssessmentId = :assessmentId WHERE testItemId = :testItemId")
    suspend fun updateLastAssessmentId(testItemId: Int, assessmentId: Int)

    // Gets the selected test item by test item id and assessment id.
    @Query(
        """
    SELECT * FROM SelectedTestItem
    WHERE testItemId = :testItemId AND assessmentId = :assessmentId
    """
    )
    suspend fun getSelectedTestItemByTestItemIdAndAssessmentId(
        testItemId: Int,
        assessmentId: Int,
    ): SelectedTestItem?

    // Gets the latest result for a test item.
    @Query(
        """
    SELECT * FROM SelectedTestItem
    WHERE testItemId = :testItemId
    AND assessmentId = (SELECT lastAssessmentId FROM TestItem WHERE testItemId = :testItemId)
    """
    )
    suspend fun getLatestResultForTestItem(testItemId: Int): SelectedTestItem?

    // Updates the selected test item.
    @Query(
        """
    UPDATE SelectedTestItem
    SET passed = :passed, startTime = :startTime, endTime = :endTime, p95ValueInMillis = :p95ValueInMillis, outlierValueInMillis = :outlierValueInMillis
    WHERE testItemId = :testItemId AND assessmentId = :assessmentId
    """
    )
    suspend fun updateSelectedTestItem(
        testItemId: Int,
        assessmentId: Int,
        passed: Boolean?,
        startTime: Instant?,
        endTime: Instant?,
        p95ValueInMillis: Long?,
        outlierValueInMillis: Long?,
    )

    // Inserts a list of log data units.
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLogs(logs: List<LogRecord>)

    // Gets the log data units by assessment id and test item id.
    @Query(
        """
    SELECT * FROM LogRecord
    WHERE assessmentId = :assessmentId AND testItemId = :testItemId
    ORDER BY logId ASC
    """
    )
    suspend fun getLogsByAssessmentIdAndTestItemId(
        assessmentId: Int,
        testItemId: Int,
    ): List<LogRecord>

    // Removes the log data units by test item id.
    @Query("DELETE FROM LogRecord WHERE testItemId = :testItemId")
    suspend fun removeLogsByTestItemId(testItemId: Int)

    /**
     * Clears the legacy runs and inserts the new runs with its result.
     *
     * This method should be called when the individual test for a test item is finished or
     * terminated. It will:
     * 1. clear the legacy runs
     * 2. insert the new runs with its result.
     * 3. clear the legacy logs.
     * 4. insert the new logs.
     * 5. update the selected test item with the new result.
     * 6. update the last assessment id in TestItem.
     */
    @Transaction
    suspend fun clearAndInsertResults(
        testItemId: Int,
        newRuns: List<Run>,
        newLogs: List<LogRecordUiModel>,
        testItemResult: SelectedTestItem,
    ) {
        removeRunsByTestItemId(testItemId)
        insertRuns(newRuns)
        removeLogsByTestItemId(testItemId)
        insertLogs(newLogs.toDatabaseFormat(testItemResult.assessmentId, testItemId))
        updateSelectedTestItem(
            testItemId,
            testItemResult.assessmentId,
            testItemResult.passed,
            testItemResult.startTime,
            testItemResult.endTime,
            testItemResult.p95ValueInMillis,
            testItemResult.outlierValueInMillis,
        )
        updateLastAssessmentId(testItemId, testItemResult.assessmentId)
    }
}
