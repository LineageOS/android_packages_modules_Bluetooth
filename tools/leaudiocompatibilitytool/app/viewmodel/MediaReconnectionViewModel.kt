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
import android.media.MediaRouter
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

@HiltViewModel
class MediaReconnectionViewModel
@Inject
constructor(
    private val mediaController: IMediaController,
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {
    var isStartTestBtnEnabled by mutableStateOf(false)
        private set

    val logList = mutableStateListOf<LogRecordUiModel>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentMediaRouteIsBluetooth = false
    private var isAllDevicesConnected = false
    private var testStartTime: Instant? = null
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

    // After the timeout period has elapsed, this action will check if the media route is bluetooth
    // and if all devices are connected. If not, it will fail the test.
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private val timeoutAction = Runnable {
        if (!currentMediaRouteIsBluetooth) {
            logList.add(
                LogRecordUiModel(
                    message =
                        LogRecordUiModel.getTimeOutString(
                            failedAction = "route media to bluetooth headset"
                        )
                )
            )
        }
        if (!isAllDevicesConnected) {
            logList.add(
                LogRecordUiModel(
                    message = LogRecordUiModel.getTimeOutString(failedAction = "reconnect devices")
                )
            )
        }

        endCurrentRunAndSaveRecord(passed = false)

        if (isTestFinished()) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        } else {
            logList.add(LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true))
            try {
                isStartTestBtnEnabled =
                    bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                        BluetoothDevice.BOND_BONDED
                    ) &&
                        bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                            BluetoothProfile.STATE_DISCONNECTED
                        )
            } catch (e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }
            if (isStartTestBtnEnabled) {
                logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
            }
        }
    }

    /** The callback for MediaRouter events. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    val mediaRouterCallback =
        object : MediaRouter.Callback() {
            // Update test state when the media route is bluetooth.
            override fun onRouteSelected(
                router: MediaRouter,
                type: Int,
                route: MediaRouter.RouteInfo,
            ) {
                Log.d(
                    TAG,
                    "MediaRouter - onRouteSelected: ${route.name}, route: ${route.deviceType}",
                )
                currentMediaRouteIsBluetooth = route.isBluetooth()
                if (currentMediaRouteIsBluetooth) {
                    updateTestState()
                }
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                type: Int,
                route: MediaRouter.RouteInfo,
            ) {
                Log.d(
                    TAG,
                    "MediaRouter - onRouteUnselected: ${route.name} route: ${route.deviceType}",
                )
                currentMediaRouteIsBluetooth = !route.isBluetooth()
            }

            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {}

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {}

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {}

            override fun onRouteGrouped(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                group: MediaRouter.RouteGroup,
                index: Int,
            ) {}

            override fun onRouteUngrouped(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                group: MediaRouter.RouteGroup,
            ) {}

            override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {}

            // Helper to check if a route is Bluetooth
            private fun MediaRouter.RouteInfo.isBluetooth(): Boolean {
                return this.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH
            }
        }

    // The callback for Bluetooth connection state changes.
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private val connectionStateListener =
        object : ConnectionStateListener {
            override fun onConnectionStateChange(device: BluetoothDevice, isConnected: Boolean) {
                logList.add(
                    LogRecordUiModel(message = "${device.address} is connected: $isConnected")
                )
            }

            // Updates the test state when all devices are connected.
            override fun onAllDevicesConnected() {
                Log.d(TAG, "All devices connected.")
                updateReconnectBtnState(enabled = false)
                isAllDevicesConnected = true
                updateTestState()
            }

            override fun onAllDevicesDisconnected() {
                Log.d(TAG, "All devices disconnected.")
                updateReconnectBtnState(enabled = true)
                isAllDevicesConnected = false
            }

            override fun onPartialDevicesConnected() {
                Log.d(TAG, "Partial devices connected.")
                updateReconnectBtnState(enabled = false)
                isAllDevicesConnected = false
            }

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }

            // The start test button will be enabled when all devices are bonded but disconnected.
            private fun updateReconnectBtnState(enabled: Boolean) {
                if (isTestFinished()) return
                isStartTestBtnEnabled =
                    enabled &&
                        bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                            BluetoothDevice.BOND_BONDED
                        )

                if (isStartTestBtnEnabled) {
                    logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
                }
            }
        }

    /**
     * Ends the current run and saves the test result.
     *
     * This method will be called when the test is finished or the timeout period has elapsed. It
     * will save the test result to the database and reset the test start time.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
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
     * Updates the test state when the media route is bluetooth and all devices are connected.
     *
     * This method will be called when the media route is bluetooth and all devices are connected.
     * It will stop the timer, reset the test start time, and check if the test is finished.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun updateTestState() {
        // If the test has not started, do nothing.
        if (testStartTime == null) {
            return
        }

        // If the media route is not bluetooth or not all devices are connected, do nothing.
        if (!(currentMediaRouteIsBluetooth && isAllDevicesConnected)) {
            return
        }
        logList.add(LogRecordUiModel(message = "Media successfully routed to bluetooth headset."))

        endCurrentRunAndSaveRecord(passed = true)
        if (isTestFinished()) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        } else {
            logList.add(LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true))
        }
    }

    /** Starts the music and starts the reconnection countdown. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun startMusicAndReconnectionCountdown() {
        if (!mediaController.isPlaying()) {
            mediaController.playMusic()
        }
        isStartTestBtnEnabled = false
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getRunStartString(
                        testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                    )
            )
        )
        startTimer()
    }

    /**
     * Initializes the media reconnection test by registering the media router callback for route
     * changes.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun initMediaReconnectionTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        mediaController.initMediaSession(TEST_ITEM_ID.toString())
        mediaController.addMediaRouterCallback(mediaRouterCallback)
        bluetoothManager.addConnectionStateListener(connectionStateListener)

        try {
            isStartTestBtnEnabled =
                bluetoothManager.isAllTargetDeviceBondStateEqualsTo(BluetoothDevice.BOND_BONDED) &&
                    bluetoothManager.isAllTargetDeviceConnectionStateEqualsTo(
                        BluetoothProfile.STATE_DISCONNECTED
                    )
            if (isStartTestBtnEnabled) {
                logList.add(LogRecordUiModel(message = OPEN_CASE_STRING, actionRequired = true))
            } else {
                logList.add(LogRecordUiModel(message = CLOSE_CASE_STRING, actionRequired = true))
            }
        } catch (e: Exception) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
        }
    }

    /**
     * Resets the media reconnection test by removing the media router callback and saving the media
     * reconnection test result.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    fun resetAndSaveMediaReconnectionTest() {
        stopTimer()
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        mediaController.pauseMusic()
        mediaController.resetMediaSession(TEST_ITEM_ID.toString())
        mediaController.removeMediaRouterCallback(mediaRouterCallback)
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

    /** Starts the timer for the media reconnection test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun startTimer() {
        testStartTime = Instant.now()
        Log.d(TAG, "Timer started for $TAG")
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
    }

    /** Stops the timer for the media reconnection test. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Checks if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.MEDIA_RECONNECTION.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.MEDIA_RECONNECTION.name
        val RUNS_REQUIRED = TestItemUiModel.MEDIA_RECONNECTION.qualification.runsRequired
        val TIMEOUT_PERIOD = Duration.ofSeconds(20)
        const val OPEN_CASE_STRING =
            "Press the `Start Testing` button and trigger bluetooth reconnection from the headset in the same time."
        const val CLOSE_CASE_STRING = "Please disconnect the headset."
        val TAG = "${TEST_ITEM_NAME} Testing"
    }
}
