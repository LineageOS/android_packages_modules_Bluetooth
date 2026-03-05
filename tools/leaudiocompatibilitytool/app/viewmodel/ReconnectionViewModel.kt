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
import android.bluetooth.BluetoothProfile
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.AdbIntentManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A view model for reconnection test. The reconnection time is calculated from the time when the
 * tester opens the case of the headset to the time when all target devices are connected.
 */
@HiltViewModel
class ReconnectionViewModel
@Inject
constructor(
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
    private val adbIntentManager: AdbIntentManager,
) : ViewModel() {

    private var testStartTime: Instant? = null
    var isReconnectBtnEnabled by mutableStateOf(false)
    var logList = mutableStateListOf<LogRecordUiModel>()
    private val handler = Handler(Looper.getMainLooper())
    private val runRecordList = mutableListOf<Run>()
    /**
     * A StateFlow that emits the current testing progress as a string. This will automatically
     * update the UI when collected as state.
     */
    val progressString: StateFlow<String> =
        testStateManager.finishedRuns
            .map { runsMap: Map<Int, Int> ->
                val finishedRuns = runsMap[TEST_ITEM_ID] ?: 0
                "Runs: $finishedRuns/${RUNS_REQUIRED}"
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = "Runs: 0/${RUNS_REQUIRED}",
            )

    /** After the timeout period has elapsed, this action will restart the reconnection test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private val timeoutAction = Runnable {
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getTimeOutString(failedAction = "finish reconnection test")
            )
        )
        endCurrentRunAndSaveRecord()

        if (isTestFinished()) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        } else {
            logList.add(LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true))
            try {
                isReconnectBtnEnabled =
                    bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                        BluetoothDevice.BOND_BONDED
                    ) &&
                        bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                            BluetoothProfile.STATE_DISCONNECTED
                        )
            } catch (e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }

            if (isReconnectBtnEnabled) {
                logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
            }
        }
    }

    /** End the current run and save the record. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun endCurrentRunAndSaveRecord() {
        testStartTime?.let { startTime ->
            stopTimer()
            val currentTime = Instant.now()
            val duration = Duration.between(startTime, currentTime)
            val passed = duration < TIMEOUT_PERIOD
            if (passed) {
                logList.add(
                    LogRecordUiModel(message = "Connection duration: ${duration.toMillis()} ms.")
                )
            }

            runRecordList.add(
                Run(
                    runId = testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1,
                    assessmentId = testStateManager.currentAssessmentId,
                    testItemId = TEST_ITEM_ID,
                    startTime = startTime,
                    endTime = currentTime,
                    passed = passed,
                )
            )

            testStartTime = null
            logList.add(
                LogRecordUiModel(
                    message =
                        LogRecordUiModel.getRunEndString(
                            testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1,
                            passed,
                        )
                )
            )
            testStateManager.addFinishedRunsByOne(TEST_ITEM_ID)
        }
    }

    /**
     * The listener for when the target device connection state changes. It will record the
     * connection ending time when all target devices are connected.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private val connectionStateListener =
        object : ConnectionStateListener {
            @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
            override fun onConnectionStateChange(device: BluetoothDevice, isConnected: Boolean) {
                logList.add(
                    LogRecordUiModel(message = "${device.address} is connected: $isConnected")
                )
            }

            override fun onAllDevicesConnected() {
                updateReconnectBtnState(enabled = false)
                if (testStartTime == null) return

                endCurrentRunAndSaveRecord()

                if (isTestFinished()) {
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    logList.add(
                        LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true)
                    )
                }
            }

            override fun onAllDevicesDisconnected() {
                updateReconnectBtnState(enabled = true)
            }

            override fun onPartialDevicesConnected() {
                updateReconnectBtnState(enabled = false)
            }

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }

            private fun updateReconnectBtnState(enabled: Boolean) {
                if (isTestFinished()) return
                isReconnectBtnEnabled =
                    enabled &&
                        bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                            BluetoothDevice.BOND_BONDED
                        )

                if (isReconnectBtnEnabled) {
                    logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
                }
            }
        }

    private val testStartCommandListener =
        object : AdbIntentManager.TestStartCommandListener {
            @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
            override fun onTestStartCommandReceived(testId: String) {
                if (testId != TEST_ITEM_ID.toString()) return
                startReconnectionTest()
            }
        }

    /**
     * Initialize the reconnection test.
     *
     * This method should be called when the test page is opened. It will reset the test state and
     * start listening to the bluetooth connection state changes.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initReconnectionTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        adbIntentManager.addTestStartCommandListener(testStartCommandListener)

        try {
            isReconnectBtnEnabled =
                bluetoothManager.isAllTargetDeviceBondStateEqualsTo(BluetoothDevice.BOND_BONDED) &&
                    bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                        BluetoothProfile.STATE_DISCONNECTED
                    )
        } catch (e: Exception) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }

        if (isReconnectBtnEnabled) {
            logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
        } else {
            logList.add(LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true))
        }
    }

    /**
     * Reset the reconnection test.
     *
     * This method should be called when the test page is closed. It will stop listening to the
     * bluetooth connection state changes.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun resetAndSaveReconnectionTest() {
        stopTimer()
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        adbIntentManager.removeTestStartCommandListener(testStartCommandListener)
        val updatedResult = testStateManager.updateRunsWithResults(TEST_ITEM_ID, runRecordList)
        viewModelScope.launch {
            assessmentDao.clearAndInsertResults(
                TEST_ITEM_ID,
                updatedResult.runs,
                logList,
                updatedResult.testItemResult,
            )
            runRecordList.clear()
        }
    }

    /**
     * Start the reconnection test.
     *
     * This method should be called to record the test start time when the tester opens the case of
     * the headset and clicked the reconnect button.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun startReconnectionTest() {
        testStartTime = Instant.now()
        startTimer()
        isReconnectBtnEnabled = false
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getRunStartString(
                        testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                    )
            )
        )
    }

    /** Start the timer for the reconnection test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun startTimer() {
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
    }

    /** Stop the timer for the reconnection test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Check if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.RECONNECTION.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.RECONNECTION.name
        val RUNS_REQUIRED = TestItemUiModel.RECONNECTION.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD =
            TestItemUiModel.RECONNECTION.qualification.outlierValue + Duration.ofSeconds(5)
        const val CLOSE_CASE_STRING = "Please disconnect the headset."
        const val OPEN_CASE_STRING =
            "Press the `Start Testing` button and trigger bluetooth reconnection from the headset in the same time."
    }
}
