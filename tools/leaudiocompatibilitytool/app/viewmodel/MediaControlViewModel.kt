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
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.MediaStateListener
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
 * A view model for media control test. The media control test ask the tester to trigger music play/
 * pause the music from the headset. One test run includes two parts:
 * 1. Pause test: The tester needs to trigger the music pause from the headset. The test will time
 *    out after 10 seconds.
 * 2. Play test: The tester needs to trigger the music play from the headset. The test will time out
 *    after 10 seconds.
 */
@HiltViewModel
class MediaControlViewModel
@Inject
constructor(
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val mediaController: IMediaController,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    var isPlayMusicBtnEnabled by mutableStateOf(false)
    var currentTest by mutableStateOf<CurrentTest?>(null)
    private var testStartTime: Instant? = null
    private var isActionTriggeredByApp = false
    private val handler = Handler(Looper.getMainLooper())
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
     * After the timeout period has elapsed, this action will check the current test state and move
     * on to the next test if the test is not finished. Otherwise, it will pause the music.
     */
    private val timeoutAction = Runnable {
        when (currentTest) {
            CurrentTest.PAUSE -> {
                logList.add(
                    LogRecordUiModel(
                        message = LogRecordUiModel.getTimeOutString(failedAction = "pause music")
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    isActionTriggeredByApp = true
                    mediaController.pauseMusic()
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    // Restart the timer for the pause test
                    startTimer(CurrentTest.PAUSE)
                }
            }
            CurrentTest.PLAY -> {
                logList.add(
                    LogRecordUiModel(
                        message = LogRecordUiModel.getTimeOutString(failedAction = "play music")
                    )
                )
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    // Play the music if play test times out so the test can continue.
                    isActionTriggeredByApp = true
                    mediaController.resumeMusic()
                    startTimer(CurrentTest.PAUSE)
                }
            }
            null -> {
                // Handle the case where currentTest is null (shouldn't happen ideally)
                Log.e(TAG, "currentTest is null in timer callback")
            }
        }
    }

    /** End the current run and save the record. */
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

    /** Enables the play music button when the target devices are all connected. */
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
                // Do not update the play music button state if timer is running.
                if (currentTest == null) {
                    isPlayMusicBtnEnabled = enabled
                }
            }
        }

    /** Handle the media play/pause state changes and trigger the corresponding test logic. */
    private val mediaStateListener =
        object : MediaStateListener {
            override fun onMusicPlayingStateChanged(isPlaying: Boolean) {
                if (isActionTriggeredByApp) {
                    // `Play` triggered by the app.
                    Log.d(
                        TAG,
                        "Action triggered by app. isActionTriggeredByApp: $isActionTriggeredByApp",
                    )
                    isActionTriggeredByApp = false
                    return
                }

                if (currentTest == null) {
                    // Test not started yet.
                    Log.d(TAG, "currentTest is null. Test not started yet.")
                    return
                }

                if (!isPlaying) {
                    // `Pause` triggered by the headset.
                    // Stop the current timer and restart the timer for play test.
                    stopTimer()
                    startTimer(CurrentTest.PLAY)
                } else {
                    // `Play` triggered by the headset.
                    // Stop the current timer and restart the timer for pause test.
                    endCurrentRunAndSaveRecord(passed = true)

                    if (isTestFinished()) {
                        isActionTriggeredByApp = true
                        mediaController.pauseMusic()
                        logList.add(
                            LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                        )
                    } else {
                        // Continue the test. Restart the timer for the pause test
                        startTimer(CurrentTest.PAUSE)
                    }
                }
            }
        }

    /**
     * Initialize the media control test.
     *
     * This method should be called when the test page is opened. It will reset the test state and
     * start listening to the bluetooth and media play/pause state changes.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initMediaControlTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        mediaController.initMediaSession(TEST_ITEM_ID.toString())
        mediaController.addMediaStateListener(mediaStateListener)
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
                LogRecordUiModel(message = "Please put on the headset.", actionRequired = true)
            )
        }
    }

    /**
     * Reset the media control test.
     *
     * This method should be called when the test page is closed. It will stop listening to the
     * bluetooth and media play/pause state changes.
     */
    fun resetAndSaveMediaControlTest() {
        stopTimer()
        currentTest = null
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        mediaController.resetMediaSession(TEST_ITEM_ID.toString())
        mediaController.removeMediaStateListener(mediaStateListener)
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

    /**
     * Play the music.
     *
     * This method is called when the play music button is clicked (and should only be called once
     * at the beginning of the media control test). It will start the timer for the play test.
     */
    fun playMusic() {
        isPlayMusicBtnEnabled = false
        if (!mediaController.isPlaying()) {
            // Play the music if it's not playing yet. This avoids enabling
            // `isActionTriggeredByApp` when the music is already playing (triggered by the
            // headset).
            isActionTriggeredByApp = true
            mediaController.playMusic()
        }
        startTimer(CurrentTest.PAUSE)
    }

    /** Start the timer for the given test. */
    private fun startTimer(test: CurrentTest) {
        currentTest = test
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
        if (test == CurrentTest.PAUSE) {
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
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Check if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.MEDIA_CONTROL.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.MEDIA_CONTROL.name
        val RUNS_REQUIRED = TestItemUiModel.MEDIA_CONTROL.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)

        enum class CurrentTest {
            PAUSE,
            PLAY,
        }
    }
}
