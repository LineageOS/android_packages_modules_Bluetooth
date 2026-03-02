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
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.CallController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.CallController.Companion.CallDirection
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController.MediaSource
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.VolumeChangedListener
import android.media.AudioManager
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

/** The view model for the call volume control test. */
@HiltViewModel
class CallVolumeControlViewModel
@Inject
constructor(
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val callController: CallController,
    private val mediaController: IMediaController,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    var isStartCallBtnEnabled by mutableStateOf<Boolean>(true)
    private val handler = Handler(Looper.getMainLooper())
    private var currentVolume = INVALID_VOLUME
    private var currentTest: CurrentTest? = null
    private var testStartTime: Instant? = null
    val logList = mutableStateListOf<LogRecordUiModel>()
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

    /** After the timeout period has elapsed, this action will end the current call. */
    private val answerCallTimeoutAction = Runnable {
        viewModelScope.launch { callController.endCurrentCall() }
        logList.add(LogRecordUiModel(message = "Call ended by app due to timeout."))
        isStartCallBtnEnabled = true
        logList.add(
            LogRecordUiModel(
                message = LogRecordUiModel.getTimeOutString(failedAction = "answer call")
            )
        )
        logList.add(LogRecordUiModel(message = PRESS_START_CALL_BTN_TEXT, actionRequired = true))
    }

    /**
     * The action to be executed after the timeout period.
     *
     * After the timeout period has elapsed, this action will update the test state and start the
     * timer again if the test is not finished. Otherwise, it will end the current call.
     */
    private val timeoutAction = Runnable {
        when (currentTest) {
            CurrentTest.VOLUME_UP -> {
                logList.add(
                    LogRecordUiModel(
                        message =
                            LogRecordUiModel.getTimeOutString(failedAction = "increase volume")
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    viewModelScope.launch { callController.endAllCalls() }
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    startTimer(CurrentTest.VOLUME_UP)
                }
            }
            CurrentTest.VOLUME_DOWN -> {
                logList.add(
                    LogRecordUiModel(
                        message =
                            LogRecordUiModel.getTimeOutString(failedAction = "decrease volume")
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    viewModelScope.launch { callController.endAllCalls() }
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    // restart timer if not finished
                    startTimer(CurrentTest.VOLUME_UP)
                }
            }
            null -> {
                // Handle the case where currentTest is null (shouldn't happen ideally)
                Log.e(TAG, "currentTest is null in timer callback")
            }
        }
    }

    /** Ends the current run and saves the record. */
    private fun endCurrentRunAndSaveRecord(passed: Boolean) {
        testStartTime?.let { startTime ->
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
            stopTimer(timeoutAction)
            currentTest = null
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
     * Starts the call and plays the ringtone.
     *
     * This method will be called when the start call button is clicked.
     */
    fun startCall() {
        viewModelScope.launch {
            val cookie =
                callController.addCall(
                    callDirection = CallDirection.INCOMING,
                    onCallAddedAction = { callId ->
                        mediaController.initMediaSession(callId)
                        mediaController.playRingtone()
                        isStartCallBtnEnabled = false
                        startAnswerCallTimer()
                        logList.add(
                            LogRecordUiModel(
                                message = "Please answer the call.",
                                actionRequired = true,
                            )
                        )
                    },
                    onAnswerAction = {
                        stopTimer(answerCallTimeoutAction)
                        mediaController.stopRingtone()
                        mediaController.playMusic(MediaSource.SPEECH)
                        mediaController.resetAudioVolume(audioSrc = AudioManager.STREAM_VOICE_CALL)
                        currentVolume = mediaController.getVolume(AudioManager.STREAM_VOICE_CALL)
                        startTimer(CurrentTest.VOLUME_UP)
                    },
                    onDisconnectAction = { callId ->
                        stopTimer(timeoutAction)
                        stopTimer(answerCallTimeoutAction)
                        currentVolume = INVALID_VOLUME
                        currentTest = null
                        testStartTime = null
                        mediaController.pauseMusic()
                        mediaController.resetMediaSession(callId)
                        isStartCallBtnEnabled = true

                        logList.add(LogRecordUiModel(message = "Call ended by tester."))
                        if (!isTestFinished()) {
                            logList.add(
                                LogRecordUiModel(
                                    message = PRESS_START_CALL_BTN_TEXT,
                                    actionRequired = true,
                                )
                            )
                        }
                    },
                )
            Log.d(TAG, "startCall with cookie: $cookie")
        }
    }

    /**
     * Enables the start call button when the test has not started and the connection state of the
     * target devices are all connected.
     */
    private val connectionStateListener =
        object : ConnectionStateListener {
            @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
            override fun onConnectionStateChange(device: BluetoothDevice, isConnected: Boolean) {
                logList.add(
                    LogRecordUiModel(message = "${device.address} is connected: $isConnected")
                )
            }

            override fun onAllDevicesConnected() {
                updateStartTestBtnState(enabled = true)
            }

            override fun onAllDevicesDisconnected() {
                updateStartTestBtnState(enabled = false)
            }

            override fun onPartialDevicesConnected() {
                updateStartTestBtnState(enabled = false)
            }

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }

            private fun updateStartTestBtnState(enabled: Boolean) {
                if (currentVolume == INVALID_VOLUME) {
                    isStartCallBtnEnabled = enabled
                }
            }
        }

    /**
     * When the volume is changed, this method will be called and it will update the test state and
     * start the timer. When all runs are finished, it will end all calls and pause the music.
     */
    private val volumeChangedListener =
        object : VolumeChangedListener {
            override fun onVolumeChanged() {
                // Do not update the test state before the test starts.
                if (currentVolume == INVALID_VOLUME) {
                    return
                }

                val newVolume = mediaController.getVolume(AudioManager.STREAM_VOICE_CALL)
                if (currentTest == CurrentTest.VOLUME_UP) {
                    if (newVolume > currentVolume) {
                        logList.add(
                            LogRecordUiModel(
                                message = "Volume successfully increased to: $newVolume"
                            )
                        )
                        stopTimer(timeoutAction)
                        startTimer(CurrentTest.VOLUME_DOWN)
                    } else if (newVolume < currentVolume) {
                        logList.add(LogRecordUiModel(message = "Volume changed to: $newVolume."))
                        logList.add(
                            LogRecordUiModel(
                                message = "Please increase the volume instead.",
                                actionRequired = true,
                            )
                        )
                    }
                } else if (currentTest == CurrentTest.VOLUME_DOWN) {
                    if (newVolume < currentVolume) {
                        logList.add(
                            LogRecordUiModel(
                                message = "Volume successfully decreased to: $newVolume"
                            )
                        )
                        endCurrentRunAndSaveRecord(passed = true)

                        if (isTestFinished()) {
                            viewModelScope.launch { callController.endAllCalls() }
                            logList.add(
                                LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                            )
                        } else {
                            // restart timer if not finished
                            startTimer(CurrentTest.VOLUME_UP)
                        }
                    } else if (newVolume > currentVolume) {
                        logList.add(LogRecordUiModel(message = "Volume changed to: $newVolume."))
                        logList.add(
                            LogRecordUiModel(
                                message = "Please decrease the volume instead.",
                                actionRequired = true,
                            )
                        )
                    }
                }
                currentVolume = newVolume
            }
        }

    /**
     * Initializes the call volume control test by setting up the listeners for connection state
     * changes and volume changes and resetting the audio volume.
     *
     * This method will be called when the call volume control test page is opened.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initCallVolumeControlTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        mediaController.addVolumeChangedListener(volumeChangedListener)

        try {
            isStartCallBtnEnabled =
                bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                    BluetoothProfile.STATE_CONNECTED
                )
        } catch (e: Exception) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }

        if (isStartCallBtnEnabled) {
            logList.add(
                LogRecordUiModel(message = PRESS_START_CALL_BTN_TEXT, actionRequired = true)
            )
        } else {
            logList.add(
                LogRecordUiModel(message = "Please put on the headset.", actionRequired = true)
            )
        }
    }

    /**
     * Resets the call volume control test by removing the listeners for connection state changes
     * and volume changes.
     *
     * This method will be called when the call volume control test page is closed.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun resetAndSaveCallVolumeControlTest() {
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        currentTest = null
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        mediaController.removeVolumeChangedListener(volumeChangedListener)
        viewModelScope.launch { callController.endAllCalls() }

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

    /** Starts the countdown timer for the answer call timeout action. */
    fun startAnswerCallTimer() {
        Log.d(TAG, "startAnswerCallTimer")
        handler.postDelayed(answerCallTimeoutAction, TIMEOUT_PERIOD.toMillis())
    }

    /** Starts the countdown timer for the call volume control test. */
    private fun startTimer(test: CurrentTest) {
        currentTest = test
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
        if (test == CurrentTest.VOLUME_UP) {
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

    /** Stops the countdown timer for the call volume control test. */
    private fun stopTimer(action: Runnable) {
        handler.removeCallbacks(action)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Checks if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        const val INVALID_VOLUME = -1
        val TEST_ITEM_ID = TestItemUiModel.CALL_VOLUME_CONTROL.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.CALL_VOLUME_CONTROL.name
        val RUNS_REQUIRED = TestItemUiModel.CALL_VOLUME_CONTROL.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)
        const val PRESS_START_CALL_BTN_TEXT = "Please press the `Start Call` button."

        enum class CurrentTest {
            VOLUME_UP,
            VOLUME_DOWN,
        }
    }
}
