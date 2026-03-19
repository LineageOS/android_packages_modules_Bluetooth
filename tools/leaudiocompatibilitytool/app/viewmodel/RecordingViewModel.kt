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
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IRecorder
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RecordingViewModel
@Inject
constructor(
    private val mediaController: IMediaController,
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val recorder: IRecorder,
    private val assessmentDao: AssessmentDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val logList = mutableStateListOf<LogRecordUiModel>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _maxAmplitude = MutableStateFlow(0)
    private val handler = Handler(Looper.getMainLooper())
    private val runRecordList = mutableListOf<Run>()
    private var recordingJob: Job? = null
    private var bleHeadsetDevice: AudioDeviceInfo? = null
    private var testStartTime: Instant? = null
    var isRecordingBtnEnabled by mutableStateOf(false)
        private set

    // This is the public, read-only state for the UI to observe.
    val maxAmplitude = _maxAmplitude.asStateFlow()

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
                logList.add(LogRecordUiModel(message = "All devices connected."))
            }

            override fun onAllDevicesDisconnected() {
                bleHeadsetDevice = null
                isRecordingBtnEnabled = false
            }

            override fun onPartialDevicesConnected() {
                bleHeadsetDevice = null
                isRecordingBtnEnabled = false
            }

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }
        }

    /** The listener for audio device changes. */
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                logList.add(LogRecordUiModel(message = "onAudioDevicesAdded"))
                findBleInputDevice()
            }
        }

    /** The listener for when the timeout period has elapsed. */
    private val timeoutAction = Runnable {
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getTimeOutString(failedAction = "receive audio from headset")
            )
        )
        stopRecording()
        endCurrentRunAndSaveRecord(passed = false)

        // If the test not finished, the recording test will continue.
        if (isTestFinished()) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        } else {
            startRecordingTestAfterDelay()
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

    /** Finds the BLE headset input device from the AudioManager. */
    private fun findBleInputDevice() {
        val targetDeviceAddresses = bluetoothManager.targetDevice.map { it.address }
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        bleHeadsetDevice = inputDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET && it.address in targetDeviceAddresses
        }

        if (bleHeadsetDevice != null) {
            logList.add(
                LogRecordUiModel(
                    message =
                        "Found BLE Headset input device: ${bleHeadsetDevice?.productName} type: ${bleHeadsetDevice?.type}"
                )
            )
            isRecordingBtnEnabled = true
        } else {
            logList.add(LogRecordUiModel(message = "No target BLE Headset found in input devices."))
            isRecordingBtnEnabled = false
        }
    }

    /** Starts the recording test. */
    val startRecordingTest = Runnable {
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getRunStartString(
                        testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                    )
            )
        )
        startTimer()
        recordingJob?.cancel()
        recorder.startRecording(bleHeadsetDevice)
        isRecordingBtnEnabled = false
        logList.add(LogRecordUiModel(message = "Start recording."))
        logList.add(
            LogRecordUiModel(
                message = "Please say something in ${TIMEOUT_PERIOD.toSeconds()} seconds.",
                actionRequired = true,
            )
        )
        recordingJob = viewModelScope.launch {
            recorder.getMaxAmplitude().collect { amplitude ->
                Log.d(TAG, "Max amplitude: $amplitude")
                _maxAmplitude.value = amplitude

                // max amplitude should be greater than MAX_AMPLITUDE_THRESHOLD to avoid noise.
                // stop both the timer and the recording when audio is received by the recorder.
                if (amplitude > MAX_AMPLITUDE_THRESHOLD) {
                    logList.add(LogRecordUiModel(message = "Max amplitude: $amplitude"))
                    stopTimer()
                    stopRecording()
                    logList.add(
                        LogRecordUiModel(message = "Audio received by recorder successfully.")
                    )
                    endCurrentRunAndSaveRecord(passed = true)

                    // If the test not finished, the recording test will continue.
                    if (isTestFinished()) {
                        logList.add(
                            LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                        )
                    } else {
                        startRecordingTestAfterDelay()
                    }
                }
            }
        }
    }

    /**
     * Starts the recording test after a delay. This is to avoid the recording being started
     * immediately after stopping.
     */
    fun startRecordingTestAfterDelay() {
        Log.d(TAG, "Start recording test after delay: $DELAY_PERIOD")
        handler.postDelayed(startRecordingTest, DELAY_PERIOD.toMillis())
    }

    /** Stops the recording. */
    fun stopRecording() {
        logList.add(LogRecordUiModel(message = "Stop recording."))
        try {
            recorder.stopRecording()
        } catch (e: Exception) {
            // stop recording will fail if the recorder is not in the right state.
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }
        recordingJob?.cancel()
    }

    /** Starts the timer for the recording test. */
    private fun startTimer() {
        testStartTime = Instant.now()
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
    }

    /** Stops the timer for the recording test. */
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /**
     * Initialize the recording test.
     *
     * This method should be called when the test page is opened. It will start listening to the
     * bluetooth and audio device changes.
     */
    fun initRecordingTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        findBleInputDevice()
    }

    /**
     * Reset the recording test.
     *
     * This method should be called when the test page is closed. It will stop listening to the
     * bluetooth and audio device changes.
     */
    fun resetAndSaveRecordingTest() {
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        // Reset the timer and the recording test.
        stopTimer()
        handler.removeCallbacks(startRecordingTest)
        stopRecording()

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

    /** Check if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.RECORDING.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.RECORDING.name
        val RUNS_REQUIRED = TestItemUiModel.RECORDING.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val TIMEOUT_PERIOD = Duration.ofSeconds(10)
        val DELAY_PERIOD = Duration.ofSeconds(2)
        const val MAX_AMPLITUDE_THRESHOLD = 200
    }
}
