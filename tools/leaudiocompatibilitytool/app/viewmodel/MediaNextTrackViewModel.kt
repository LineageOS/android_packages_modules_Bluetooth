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
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TrackChangeListener
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
 * A ViewModel to handle logic in the media control (next, previous) test page. The test ask the
 * tester to trigger next/previous track from the headset. One test run includes two parts:
 * 1. Skip to Next Track test: The tester needs to trigger next track from the headset. The test
 *    will time out after 10 seconds.
 * 2. Previous Track test: The tester needs to trigger previous track from the headset. The test
 *    will time out after 10 seconds.
 */
@HiltViewModel
class MediaNextTrackViewModel
@Inject
constructor(
    private val mediaController: IMediaController,
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    private var currentTest by mutableStateOf<CurrentTest?>(null)
    private var testStartTime: Instant? = null
    private val handler = Handler(Looper.getMainLooper())
    private val runRecordList = mutableListOf<Run>()
    var isPlayMusicBtnEnabled by mutableStateOf(true)
        private set

    var logList = mutableStateListOf<LogRecordUiModel>()
        private set

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

    /**
     * The action to be executed after the timeout period.
     *
     * After the timeout period has elapsed, this action will fail the current run and restart from
     * playMusic, which calls playMusicFromList to reset the song list.
     */
    private val timeoutAction = Runnable {
        when (currentTest) {
            CurrentTest.SKIP_TO_NEXT_TRACK -> {
                logList.add(
                    LogRecordUiModel(
                        message =
                            LogRecordUiModel.getTimeOutString(failedAction = "skip to next track")
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    mediaController.pauseMusic()
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    startMusic()
                }
            }
            CurrentTest.PREVIOUS_TRACK -> {
                logList.add(
                    LogRecordUiModel(
                        message =
                            LogRecordUiModel.getTimeOutString(
                                failedAction = "skip to previous track"
                            )
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    mediaController.pauseMusic()
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    startMusic()
                }
            }
            null -> {
                // Handle the case where currentTest is null (shouldn't happen ideally)
                Log.e(TAG, "currentTest is null in timer callback")
            }
        }
    }

    /** The listener for next/ previous track commands. */
    private val trackChangeListener =
        object : TrackChangeListener {
            override fun onTrackChanged(oldIndex: Int, newIndex: Int) {
                logList.add(
                    LogRecordUiModel(message = "TrackIndex Changed: $oldIndex -> $newIndex")
                )

                if (currentTest == null) {
                    // Test not started yet.
                    Log.d(TAG, "currentTest is null. Test not started yet.")
                    return
                }

                if (currentTest == CurrentTest.SKIP_TO_NEXT_TRACK) {
                    if (newIndex > oldIndex) {
                        logList.add(
                            LogRecordUiModel(message = "Successfully skipped to next track.")
                        )
                        stopTimer()
                        startTimer(CurrentTest.PREVIOUS_TRACK)
                    } else {
                        logList.add(
                            LogRecordUiModel(
                                message = "Please trigger $currentTest from the headset.",
                                actionRequired = true,
                            )
                        )
                    }
                } else if (currentTest == CurrentTest.PREVIOUS_TRACK) {
                    if (newIndex < oldIndex) {
                        logList.add(
                            LogRecordUiModel(message = "Successfully skipped to previous track.")
                        )
                        endCurrentRunAndSaveRecord(passed = true)

                        if (isTestFinished()) {
                            logList.add(
                                LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                            )
                        } else {
                            startTimer(CurrentTest.SKIP_TO_NEXT_TRACK)
                        }
                    } else {
                        logList.add(
                            LogRecordUiModel(
                                message = "Please trigger $currentTest from the headset.",
                                actionRequired = true,
                            )
                        )
                    }
                }
            }
        }

    /**
     * The listener for bluetooth connection state changes. The PlayMusic button will be enabled
     * when the headset is connected and the test is not running.
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
                updatePlayMusicBtnState(enabled = true)
            }

            override fun onAllDevicesDisconnected() {
                updatePlayMusicBtnState(enabled = false)
            }

            override fun onPartialDevicesConnected() {
                updatePlayMusicBtnState(enabled = false)
            }

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }

            private fun updatePlayMusicBtnState(enabled: Boolean) {
                // Do not update the play music button state if test is running.
                if (currentTest != null) return
                isPlayMusicBtnEnabled = enabled

                if (isPlayMusicBtnEnabled) {
                    logList.add(
                        LogRecordUiModel(
                            message = "Please press the `Play Music` button.",
                            actionRequired = true,
                        )
                    )
                }
            }
        }

    /** Ends the current run and saves the record. */
    private fun endCurrentRunAndSaveRecord(passed: Boolean) {
        val startTime = testStartTime ?: return
        runRecordList.add(
            Run(
                runId = testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1,
                assessmentId = testStateManager.currentAssessmentId,
                testItemId = TEST_ITEM_ID,
                startTime = startTime,
                endTime = Instant.now(),
                passed = passed,
            )
        )
        stopTimer()
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

    /**
     * Initializes the media next track test.
     *
     * This method should be called when the test page is opened. It will reset the test state and
     * start listening to the bluetooth and media track change state changes.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initMediaNextTrackTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        mediaController.initMediaSession(TEST_ITEM_ID.toString())
        mediaController.addTrackChangeListener(trackChangeListener)
        bluetoothManager.addConnectionStateListener(connectionStateListener)

        try {
            isPlayMusicBtnEnabled =
                bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                    BluetoothProfile.STATE_CONNECTED
                )
        } catch (e: Exception) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }

        if (isPlayMusicBtnEnabled) {
            logList.add(
                LogRecordUiModel(
                    message = "Please press the `Play Music` button.",
                    actionRequired = true,
                )
            )
        } else {
            logList.add(
                LogRecordUiModel(message = "Please connect the headset.", actionRequired = true)
            )
        }
    }

    /** Resets the media next track test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun resetAndSaveMediaNextTrackTest() {
        stopTimer()
        currentTest = null
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        mediaController.pauseMusic()
        mediaController.resetMediaSession(TEST_ITEM_ID.toString())
        mediaController.removeTrackChangeListener(trackChangeListener)
        bluetoothManager.removeConnectionStateListener(connectionStateListener)

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

    /** Starts the music. */
    fun startMusic() {
        isPlayMusicBtnEnabled = false
        mediaController.playMusicFromList()
        startTimer(CurrentTest.SKIP_TO_NEXT_TRACK)
    }

    /** Starts the timer for the given test. */
    private fun startTimer(test: CurrentTest) {
        currentTest = test
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
        if (test == CurrentTest.SKIP_TO_NEXT_TRACK) {
            logList.add(
                LogRecordUiModel(
                    message =
                        LogRecordUiModel.getRunStartString(
                            testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                        )
                )
            )
            testStartTime = Instant.now()
        }
        logList.add(
            LogRecordUiModel(
                message = "Please trigger $currentTest from the headset.",
                actionRequired = true,
            )
        )
    }

    /** Stop the timer for the current test. */
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
    }

    /** Checks if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.MEDIA_NEXT_TRACK.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.MEDIA_NEXT_TRACK.name
        val RUNS_REQUIRED = TestItemUiModel.MEDIA_NEXT_TRACK.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)

        enum class CurrentTest {
            SKIP_TO_NEXT_TRACK,
            PREVIOUS_TRACK,
        }
    }
}
