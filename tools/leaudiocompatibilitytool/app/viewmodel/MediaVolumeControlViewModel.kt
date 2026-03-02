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

/**
 * A ViewModel to handle logic in the media volume control test page. The media volume control test
 * ask the tester to trigger volume up/down from the headset. One test run includes two parts:
 * 1. Volume Up test: The tester needs to turn up the volume from the headset. The test will time
 *    out after 10 seconds.
 * 2. Volume Down test: The tester needs to turn down the volume from the headset. The test will
 *    time out after 10 seconds.
 */
@HiltViewModel
class MediaVolumeControlViewModel
@Inject
constructor(
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val mediaController: IMediaController,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    var isPlayMusicBtnEnabled by mutableStateOf(false)
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

    /**
     * The action to be executed after the timeout period.
     *
     * After the timeout period has elapsed, this action will update the test state and start the
     * timer again if the test is not finished. Otherwise, it will pause the music.
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
                    mediaController.pauseMusic()
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
                    mediaController.pauseMusic()
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
            stopTimer()
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
     * Enables the play music button state when the test has not started and the connection state of
     * the target devices are all connected.
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
                // Do not update the play music button state after the test starts.
                if (currentVolume == INVALID_VOLUME) {
                    isPlayMusicBtnEnabled = enabled
                }
            }
        }

    /**
     * When the volume is changed, this method will be called and it will update the test state and
     * start the timer again if the test is not finished. Otherwise, it will pause the music.
     */
    private val volumeChangedListener =
        object : VolumeChangedListener {
            override fun onVolumeChanged() {
                // Do not update the test state before the test starts.
                if (currentVolume == INVALID_VOLUME) {
                    return
                }

                val newVolume = mediaController.getVolume(AudioManager.STREAM_MUSIC)
                if (currentTest == CurrentTest.VOLUME_UP) {
                    if (newVolume > currentVolume) {
                        logList.add(
                            LogRecordUiModel(
                                message = "Volume successfully increased to: $newVolume"
                            )
                        )
                        stopTimer()
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
                            mediaController.pauseMusic()
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
     * Initializes the media volume control test by setting up the listeners for connection state
     * changes and volume changes and resetting the audio volume.
     *
     * This method will be called when the media volume control test page is opened.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initMediaVolumeControlTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        mediaController.initMediaSession(TEST_ITEM_ID.toString())
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        mediaController.addVolumeChangedListener(volumeChangedListener)
        mediaController.resetAudioVolume()

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
                LogRecordUiModel(message = "Please put on the headset.", actionRequired = true)
            )
        }
    }

    /**
     * Resets the media volume control test by removing the listeners for connection state changes
     * and volume changes.
     *
     * This method will be called when the media volume control test page is closed.
     */
    fun resetAndSaveMediaVolumeControlTest() {
        stopTimer()
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        currentTest = null
        mediaController.resetMediaSession(TEST_ITEM_ID.toString())
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        mediaController.removeVolumeChangedListener(volumeChangedListener)

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
     * Plays music and starts the timer for the media volume control test.
     *
     * This method will be called when the play music button is clicked.
     */
    fun playMusic() {
        isPlayMusicBtnEnabled = false
        mediaController.playMusic()
        currentVolume = mediaController.getVolume(AudioManager.STREAM_MUSIC)
        logList.add(LogRecordUiModel(message = "Current volume: $currentVolume"))
        startTimer(CurrentTest.VOLUME_UP)
    }

    /** Starts the countdown timer for the media volume control test. */
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

    /** Stops the countdown timer for the media volume control test. */
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Checks if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        const val INVALID_VOLUME = -1
        val TEST_ITEM_ID = TestItemUiModel.MEDIA_VOLUME_CONTROL.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.MEDIA_VOLUME_CONTROL.name
        val RUNS_REQUIRED = TestItemUiModel.MEDIA_VOLUME_CONTROL.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)

        enum class CurrentTest {
            VOLUME_UP,
            VOLUME_DOWN,
        }
    }
}
