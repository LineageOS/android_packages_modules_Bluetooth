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
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.AudioFocusChangeListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.CallController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.CallController.Companion.CallDirection
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.CallController.Companion.CallState
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController.MediaSource
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.media.AudioDeviceInfo
import android.media.AudioManager.OnCommunicationDeviceChangedListener
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The view model for the media call switch test. This is a functionality test that checks if the
 * call pickup/ end works from the headset AND if the audio path changes correctly. This test will
 * start playing music and ask the tester to answer/ end the call. One test run includes two parts:
 * 1. Answer call test: The tester needs to answer the call from the headset. The app will check if
 *    the Audio Focus is lost and the communication device is a BLE headset. The test will time out
 *    after 10 seconds.
 * 2. End call test: The tester needs to end the call from the headset. The app will check if the
 *    Audio Focus is gained and the communication device is a Buildin earpiece (i.e. phone). The
 *    test will time out after 10 seconds.
 */
@HiltViewModel
class MediaCallSwitchViewModel
@Inject
constructor(
    private val callController: CallController,
    private val mediaController: IMediaController,
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    private var job: Job? = null
    val logList = mutableStateListOf<LogRecordUiModel>()
    private var currentCommunicationDevice: AudioDeviceInfo? = null
    private var currentAudioFocusStatus: String = EMPTY_STRING
    private var currentCallState: CallState = CallState.CALL_DISCONNECTED
    private var currentCallCookie: String = EMPTY_STRING
    private val handler = Handler(Looper.getMainLooper())
    var isStartMusicBtnEnabled by mutableStateOf(true)
    private var testStartTime: Instant? = null
    private var currentTest: CurrentTest? = null
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
     * After the timeout period has elapsed, this action will end the current test.
     */
    private val timeoutAction = Runnable {
        val currentTestType = currentTest ?: return@Runnable
        val timeoutMessage = getTimeoutMessage(currentTestType)
        logList.add(LogRecordUiModel(message = timeoutMessage))
        endCurrentRunAndSaveRecord(passed = false)

        // If the test is not finished, end the current call, play music again, and make another
        // call in
        // 10 seconds.
        if (!isTestFinished()) {
            viewModelScope.launch {
                callController.endCurrentCall()
                logList.add(LogRecordUiModel(message = WAIT_FOR_CALL_TEXT, actionRequired = true))
                handler.postDelayed(startCallAction, CALL_START_TIMEOUT_PERIOD.toMillis())
                mediaController.playMusic(MediaSource.SONG)
            }
        } else {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        }
    }

    // Returns the timeout message for the current test.
    private fun getTimeoutMessage(currentTest: CurrentTest): String {
        return when (currentTest) {
            CurrentTest.RING_CALL -> {
                """
              Call State is ${currentCallState}. passed: ${currentCallState == CallState.CALL_RINGING}
              Audio focus is ${currentAudioFocusStatus}. passed: ${currentAudioFocusStatus.contains(AUDIO_FOCUS_LOSS)}
              Communication Device type is ${currentCommunicationDevice?.type}. passed: ${currentCommunicationDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET}
              ${LogRecordUiModel.getTimeOutString(failedAction = "set up call")}
            """
                    .trimIndent()
            }
            CurrentTest.ANSWER_CALL -> {
                """
              Call State is ${currentCallState}. passed: ${currentCallState == CallState.CALL_ANSWERED}
              ${LogRecordUiModel.getTimeOutString(failedAction = "answer call")}
            """
                    .trimIndent()
            }
            CurrentTest.END_CALL -> {
                """
              Call State is ${currentCallState}. passed: ${currentCallState == CallState.CALL_DISCONNECTED}
              Audio focus is ${currentAudioFocusStatus}. passed: ${currentAudioFocusStatus.contains(AUDIO_FOCUS_GAIN)}
              Communication Device type is ${currentCommunicationDevice?.type}. passed: ${currentCommunicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE}
              ${LogRecordUiModel.getTimeOutString(failedAction = "end call")}
            """
                    .trimIndent()
            }
        }
    }

    /**
     * Updates the test status based on the current test state, call state, audio focus status, and
     * communication device.
     *
     * This method will be called when status is updated.
     */
    fun updateTestStatus() {
        when (currentTest) {
            CurrentTest.RING_CALL -> {
                if (
                    currentCallState == CallState.CALL_RINGING &&
                        currentAudioFocusStatus.contains(AUDIO_FOCUS_LOSS) &&
                        currentCommunicationDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                ) {
                    logList.add(LogRecordUiModel(message = "Call audio path set up successfully."))
                    stopTimer(timeoutAction)
                    startTimer(CurrentTest.ANSWER_CALL)
                    logList.add(
                        LogRecordUiModel(
                            message =
                                "Please pick up the call from the headset in ${TIMEOUT_PERIOD.toSeconds()} seconds.",
                            actionRequired = true,
                        )
                    )
                }
            }
            CurrentTest.ANSWER_CALL -> {
                if (currentCallState == CallState.CALL_ANSWERED) {
                    logList.add(LogRecordUiModel(message = "Call answered successfully."))
                    stopTimer(timeoutAction)
                    startTimer(CurrentTest.END_CALL)
                    logList.add(
                        LogRecordUiModel(
                            message =
                                "Please end the call from the headset in ${TIMEOUT_PERIOD.toSeconds()} seconds.",
                            actionRequired = true,
                        )
                    )
                }
            }
            CurrentTest.END_CALL ->
                if (
                    currentCallState == CallState.CALL_DISCONNECTED &&
                        currentAudioFocusStatus.contains(AUDIO_FOCUS_GAIN) &&
                        currentCommunicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                ) {
                    logList.add(
                        LogRecordUiModel(
                            message = "Call ended and audio path changed successfully."
                        )
                    )
                    stopTimer(timeoutAction)
                    endCurrentRunAndSaveRecord(passed = true)

                    // If the test is not finished, make another call in 10 seconds.
                    if (isTestFinished()) {
                        logList.add(
                            LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                        )
                    } else {
                        logList.add(
                            LogRecordUiModel(message = WAIT_FOR_CALL_TEXT, actionRequired = true)
                        )
                        handler.postDelayed(startCallAction, CALL_START_TIMEOUT_PERIOD.toMillis())
                    }
                }
            null -> {
                // Handle the case where currentTest is null (i.e. The test has not started yet.)
                Log.e(TAG, "currentTest is null in updateTestStatus")
            }
        }
    }

    /** The listener for communication device changed events. */
    val onCommunicationDeviceChangedListener =
        object : OnCommunicationDeviceChangedListener {
            override fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
                currentCommunicationDevice = device
                logList.add(
                    LogRecordUiModel(
                        message =
                            "Communication device changed to: ${device?.productName} type: ${device?.type}"
                    )
                )
                updateTestStatus()
            }
        }

    /** The listener for audio focus changes. */
    private val audioFocusChangeListener =
        object : AudioFocusChangeListener {
            override fun onAudioFocusChange(newStatus: String) {
                currentAudioFocusStatus = newStatus
                logList.add(LogRecordUiModel(message = "Audio focus change to: $newStatus"))
                updateTestStatus()
            }
        }

    /** Starts a call. */
    private val startCallAction = Runnable {
        job?.cancel()
        job = viewModelScope.launch {
            val callCookie =
                callController.addCall(
                    callDirection = CallDirection.INCOMING,
                    onCallAddedAction = { cookie ->
                        mediaController.playRingtone()
                        logList.add(
                            LogRecordUiModel(
                                message =
                                    LogRecordUiModel.getRunStartString(
                                        testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                                    )
                            )
                        )
                        startTimer(CurrentTest.RING_CALL)
                        currentCallCookie = cookie
                    },
                    onAnswerAction = {
                        mediaController.stopRingtone()
                        mediaController.playMusic(MediaSource.SPEECH)
                        logList.add(LogRecordUiModel(message = "Communication started."))
                    },
                    onDisconnectAction = {
                        // Play music again after the call is disconnected.
                        logList.add(LogRecordUiModel(message = "Call disconnected."))
                        mediaController.playMusic(MediaSource.SONG)
                        job?.cancel()
                    },
                )
            Log.d(TAG, "startCall with cookie: $callCookie")
        }
    }

    /** The listener for connection state changes. */
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
                // Do not enable the button if the test is running.
                if (currentTest == null) {
                    isStartMusicBtnEnabled = enabled
                }
            }
        }

    /** The listener for call state changes. */
    private val callStateChangeListener =
        object : CallController.CallStateChangeListener {
            override fun onCallStateChanged(cookie: String, newState: CallState) {
                Log.d(TAG, "onCallStateChanged: $newState cookie: $cookie")
                if (cookie != currentCallCookie) return
                currentCallState = newState
                logList.add(LogRecordUiModel(message = "Call state changed to: $newState"))
                updateTestStatus()

                // Reset the call cookie when the call is disconnected.
                if (newState == CallState.CALL_DISCONNECTED) {
                    currentCallCookie = EMPTY_STRING
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

    /** Starts the music and requests audio focus for media. */
    fun startMusic() {
        isStartMusicBtnEnabled = false
        mediaController.playMusic(MediaSource.SONG)
        logList.add(LogRecordUiModel(message = "Music started."))

        // start a call in 10 seconds
        logList.add(LogRecordUiModel(message = WAIT_FOR_CALL_TEXT, actionRequired = true))
        handler.postDelayed(startCallAction, CALL_START_TIMEOUT_PERIOD.toMillis())
    }

    /** Starts the countdown timer for the media call switch test. */
    private fun startTimer(test: CurrentTest) {
        currentTest = test
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
        if (test == CurrentTest.RING_CALL) {
            testStartTime = Instant.now()
        }
    }

    /** Stops the timer for the action. */
    private fun stopTimer(action: Runnable) {
        handler.removeCallbacks(action)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    /** Initializes the media session for the media to call test. */
    fun initMediaCallSwitchTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        mediaController.initMediaSession(TEST_ITEM_ID.toString())
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        mediaController.addAudioFocusChangeListener(audioFocusChangeListener)
        callController.addCallStateChangeListener(callStateChangeListener)
        mediaController.addCommunicationDeviceChangedListener(onCommunicationDeviceChangedListener)
        mediaController.requestAudioFocus()

        try {
            isStartMusicBtnEnabled =
                bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                    BluetoothProfile.STATE_CONNECTED
                )
        } catch (e: Exception) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    /** Resets the media session and saves the test result. */
    fun resetAndSaveMediaCallSwitchTest() {
        stopTimer(timeoutAction)
        stopTimer(startCallAction)
        currentTest = null
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        mediaController.resetMediaSession(TEST_ITEM_ID.toString())
        mediaController.removeAudioFocusChangeListener(audioFocusChangeListener)
        callController.removeCallStateChangeListener(callStateChangeListener)
        mediaController.removeCommunicationDeviceChangedListener(
            onCommunicationDeviceChangedListener
        )
        mediaController.releaseAudioFocus()
        viewModelScope.launch { callController.endAllCalls() }

        // Save the test result to the database.
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

    /** Checks if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.MEDIA_CALL_SWITCH.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.MEDIA_CALL_SWITCH.name
        val RUNS_REQUIRED = TestItemUiModel.MEDIA_CALL_SWITCH.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val CALL_START_TIMEOUT_PERIOD = Duration.ofSeconds(10)
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)
        const val AUDIO_FOCUS_GAIN = "AUDIOFOCUS_GAIN"
        const val AUDIO_FOCUS_LOSS = "AUDIOFOCUS_LOSS"
        const val EMPTY_STRING = ""
        const val WAIT_FOR_CALL_TEXT = "Waiting for a call in 10 seconds."

        enum class CurrentTest {
            RING_CALL,
            ANSWER_CALL,
            END_CALL,
        }
    }
}
