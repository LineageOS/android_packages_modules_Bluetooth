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
package android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel

import android.bluetooth.BluetoothDevice
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Assessment
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run.Companion.toCsvFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestResult
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestResult.Companion.toCsvFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.BluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.FileExporter
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** ViewModel for managing test settings, including selected test items and target devices. */
@HiltViewModel
class TestSettingViewModel
@Inject
constructor(
    private val bluetoothManager: BluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    var isLoading by mutableStateOf(false)
    var loadingProgress by mutableFloatStateOf(0f)
    var isExportBtnEnabled by mutableStateOf(true)
    var isEditMode by mutableStateOf(false)
        private set

    var selectedDeviceGroupIndex by mutableIntStateOf(DEVICE_GROUP_NOT_SET)
        private set

    private val _deviceGroups = mutableStateListOf<List<BluetoothDevice>>()
    val deviceGroups: List<List<BluetoothDevice>> = _deviceGroups

    private val _testSelectedStates = mutableStateListOf<Boolean>()
    val testSelectedStates: List<Boolean> = _testSelectedStates

    var testItemList = mutableStateListOf<TestItemUiModel>()

    /** Update the test item list with the latest test result from the database. */
    fun updateTestListViewWithResults() {
        viewModelScope.launch {
            testItemList.clear()
            if (isLoading) {
                updateLoadingProgress()
            }
            testItemList.addAll(getResultsFromDatabase())

            // Reset the test assessment id to prevent bluetoothManager from invoking
            // broadcastReceiver
            // when the test is finished or not started.
            testStateManager.resetCurrentAssessmentId()
        }
    }

    /** Get the test item list with the latest test result from the database. */
    suspend fun getResultsFromDatabase(): List<TestItemUiModel> {
        return TestItemUiModel.LIST.map { testItem ->
            val latestResult = assessmentDao.getLatestResultForTestItem(testItem.testItemId)
            testItem.copy(
                passed = latestResult?.passed,
                lastTestTime = latestResult?.startTime,
                lastAssessmentId = latestResult?.assessmentId,
            )
        }
    }

    /** Update the loading progress. */
    suspend fun updateLoadingProgress() {
        for (i in 1..100) {
            loadingProgress = i.toFloat() / 100
            delay(DELAY_TO_LOAD_TEST_ITEM_LIST.toMillis() / 100)
        }
        isLoading = false
    }

    /**
     * Update the isEditMode state. If the value is true, initialize the testSelectedStates.
     *
     * @param value The new value of the isEditMode state.
     */
    fun updateIsEditMode(value: Boolean) {
        viewModelScope.launch {
            isEditMode = value

            if (value == true) {
                initTestSelectedStates()
            }
        }
    }

    /** Initialize the testSelectedStates to false. */
    private fun initTestSelectedStates() {
        _testSelectedStates.clear()
        _testSelectedStates.addAll(TestItemUiModel.LIST.map { false })
    }

    /**
     * Update the checked state of the test item.
     *
     * @param index The index of the test item to update.
     */
    fun onTestItemChecked(index: Int) {
        _testSelectedStates[index] = !_testSelectedStates[index]
    }

    /**
     * Update the selected device group index. DEVICE_GROUP_NOT_SET means no device is selected.
     *
     * @param value The new value of the selected device group index.
     */
    fun updateSelectedDeviceGroupIndex(value: Int) {
        selectedDeviceGroupIndex = value
        if (value != DEVICE_GROUP_NOT_SET) {
            bluetoothManager.setTargetDevice(deviceGroups[value])
        }
    }

    /** Update the device groups. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun updateDeviceGroups() {
        viewModelScope.launch {
            _deviceGroups.clear()
            _deviceGroups.addAll(bluetoothManager.getBondedDeviceGroupedByCsipId().values)
            // Set the first device group as default if there is bonded device.
            if (!_deviceGroups.isEmpty()) {
                updateSelectedDeviceGroupIndex(0)
            } else {
                updateSelectedDeviceGroupIndex(DEVICE_GROUP_NOT_SET)
            }
        }
    }

    /** Returns the list of test item ids that are selected (value is true) */
    fun getSelectedTestItemIds(): List<Int> {
        return _testSelectedStates
            .withIndex()
            .filter { it.value }
            .map { TestItemUiModel.LIST[it.index].testItemId }
    }

    /**
     * Create an assessment with the selected test items. The following data will be saved into the
     * database:
     * 1. The new Assessment
     * 2. Selected test items
     * 3. The selected devices
     *
     * @return true if the assessment is created successfully, false otherwise.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createAssessment(): Boolean {
        if (bluetoothManager.targetDevice.isEmpty()) {
            Log.d(BluetoothManager.TAG, "No target device.")
            return false
        }

        viewModelScope.launch {
            val assessment = Assessment()
            val assessmentId =
                assessmentDao.createAssessmentWithTestItems(
                    assessment,
                    getSelectedTestItemIds(),
                    bluetoothManager.targetDevice,
                )

            Log.d(BluetoothManager.TAG, "Created assessment with id: $assessmentId")
            testStateManager.currentAssessmentId = assessmentId.toInt()
        }
        return true
    }

    /** Returns a Flow indicating if the current test is finished. */
    fun isCurrentTestFinished(testId: Int): Flow<Boolean> {
        val testItem = TestItemUiModel.LIST.firstOrNull { it.testItemId == testId }
        if (testItem == null) {
            return flowOf(false)
        }
        return testStateManager.finishedRuns.map { runsMap ->
            (runsMap[testId] ?: 0) >= testItem.qualification.runsRequired
        }
    }

    /** Check if all target devices are bonded. */
    fun isTargetDeviceBonded(): Boolean {
        return bluetoothManager.isAllTargetDeviceBondStateEqualsTo(BluetoothDevice.BOND_BONDED)
    }

    /** Export the test result to a file. */
    fun exportResult(onExportFinished: (String) -> Unit) {
        var testResultList = mutableListOf<TestResult>()
        var runList = mutableListOf<List<Run>>()
        val summaryFilename = FileExporter.getFilePathWithCurrentTime("summary.csv")
        val detailsFilename = FileExporter.getFilePathWithCurrentTime("details.csv")
        viewModelScope.launch {
            isExportBtnEnabled = false
            for (testItem in TestItemUiModel.LIST) {
                val latestResult = assessmentDao.getLatestResultForTestItem(testItem.testItemId)
                if (latestResult == null) {
                    testResultList.add(
                        TestResult(testItemId = testItem.testItemId, testItemName = testItem.name)
                    )
                } else {
                    val assessmentId = latestResult.assessmentId
                    testResultList.add(
                        TestResult(
                            testItemId = testItem.testItemId,
                            testItemName = testItem.name,
                            lastAssessmentId = assessmentId,
                            passed = latestResult.passed,
                            startTime = latestResult.startTime,
                            endTime = latestResult.endTime,
                            p95ValueInMillis = latestResult.p95ValueInMillis,
                            outlierValueInMillis = latestResult.outlierValueInMillis,
                            devices =
                                assessmentDao
                                    .getSelectedDevicesByAssessmentId(assessmentId)
                                    .map { device -> assessmentDao.getDeviceById(device.deviceId) }
                                    .filterNotNull(),
                        )
                    )
                    runList.add(
                        assessmentDao.getRunsByAssessmentIdAndTestItemId(
                            assessmentId,
                            testItem.testItemId,
                        )
                    )
                }
            }

            val summaryCsvString = testResultList.toCsvFormat()
            val runCsvString = runList.toCsvFormat()
            FileExporter.writeFile(summaryFilename, summaryCsvString)
            FileExporter.writeFile(detailsFilename, runCsvString)
            isExportBtnEnabled = true
            onExportFinished("Result saved to ${FileExporter.FOLDER_PATH_TEXT}")
        }
    }

    companion object {
        const val DEVICE_GROUP_NOT_SET = -1
        val DELAY_TO_LOAD_TEST_ITEM_LIST = Duration.ofSeconds(1)
    }
}
