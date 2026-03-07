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

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.RunUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.RunUiModel.Companion.toViewModelFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel.Companion.toViewModelFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ResultDetailsViewModel @Inject constructor(private val assessmentDao: AssessmentDao) :
    ViewModel() {
    var testName by mutableStateOf<String>("")
    var testItem by mutableStateOf<TestItemUiModel?>(null)
    var deviceName by mutableStateOf<String>("")
    var deviceAddressList = mutableStateListOf<String>()
    var runList = mutableStateListOf<RunUiModel>()
    var logList = mutableStateListOf<LogRecordUiModel>()

    /** Get the test result for the given test item id. */
    fun getTestResult(testItemId: Int) {
        viewModelScope.launch {
            testItem = TestItemUiModel.LIST.firstOrNull { it.testItemId == testItemId }
            testName = testItem?.name ?: ""
            val latestResult = assessmentDao.getLatestResultForTestItem(testItemId) ?: return@launch
            testItem =
                testItem?.copy(
                    passed = latestResult.passed,
                    lastTestTime = latestResult.startTime,
                    lastAssessmentId = latestResult.assessmentId,
                    lastTestP95ValueInMillis = latestResult.p95ValueInMillis,
                    lastTestOutlierValueInMillis = latestResult.outlierValueInMillis,
                )
            val assessmentId = latestResult.assessmentId
            getTestDevices(assessmentId)
            getRunList(assessmentId, testItemId)
            getLogDataList(assessmentId, testItemId)
        }
    }

    /** Get the test devices for the given assessment id. */
    fun getTestDevices(assessmentId: Int) {
        viewModelScope.launch() {
            deviceAddressList.clear()
            val selectedDevices = assessmentDao.getSelectedDevicesByAssessmentId(assessmentId)
            for (selectedDevice in selectedDevices) {
                val device = assessmentDao.getDeviceById(selectedDevice.deviceId) ?: continue
                deviceName = device.name
                deviceAddressList.add(device.address)
            }
        }
    }

    /** Get the run list for the given assessment id and test item id. */
    fun getRunList(assessmentId: Int, testItemId: Int) {
        viewModelScope.launch {
            runList.clear()
            runList.addAll(
                assessmentDao
                    .getRunsByAssessmentIdAndTestItemId(assessmentId, testItemId)
                    .toViewModelFormat(testItemId)
            )
        }
    }

    /** Get the log data list for the given assessment id and test item id. */
    fun getLogDataList(assessmentId: Int, testItemId: Int) {
        viewModelScope.launch {
            logList.clear()
            logList.addAll(
                assessmentDao
                    .getLogsByAssessmentIdAndTestItemId(assessmentId, testItemId)
                    .toViewModelFormat()
            )
        }
    }
}
