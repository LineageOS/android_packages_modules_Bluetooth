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
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.BluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.BondStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.ConnectionStateListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.DeviceFoundListener
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.DiscoveryFinishedListener
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
 * A view model for pairing test. The pairing time is calculated from the first `createBond` is
 * called to the second target device connects to the LEA profile. Testers need to manually confirm
 * the pairing dialog now.
 *
 * Action flow: https://drive.google.com/file/d/1MV6nK0QO7eVhdfznSnyW9l7d54GY7nZI/view?usp=sharing
 */
@HiltViewModel
class PairingViewModel
@Inject
constructor(
    private val bluetoothManager: IBluetoothManager,
    private val testStateManager: TestStateManager,
    private val assessmentDao: AssessmentDao,
) : ViewModel() {

    private var testStartTime: Instant? = null
    var isStartTestBtnEnabled by mutableStateOf(true)
    val logList = mutableStateListOf<LogRecordUiModel>()
    private val handler = Handler(Looper.getMainLooper())
    private val runRecordList = mutableListOf<Run>()
    private var isScanning = false
    private var connectingDeviceAddress: String? = null

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

    /** After the timeout period has elapsed, this action will restart the pairing test. */
    private val timeoutAction: Runnable = Runnable {
        logList.add(
            LogRecordUiModel(
                message = LogRecordUiModel.getTimeOutString(failedAction = "finish pairing test")
            )
        )
        endCurrentRunAndSaveRecord(passed = false)

        if (isTestFinished()) {
            logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
        } else {
            if (bluetoothManager.isAllTargetDeviceBondStateEqualsTo(BluetoothDevice.BOND_NONE)) {
                // case 1. no device is bonded, restart scanning
                logList.add(
                    LogRecordUiModel(
                        message =
                            LogRecordUiModel.getRunStartString(
                                testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                            )
                    )
                )
                logList.add(
                    LogRecordUiModel(
                        message = PUT_HEADSET_INTO_PAIRING_MODE_STRING,
                        actionRequired = true,
                    )
                )
                isScanning = true
                handler.removeCallbacks(scanTimeoutAction)
                bluetoothManager.scanTargetDevice()
                handler.postDelayed(scanTimeoutAction, SCAN_PERIOD.toMillis())
            } else {
                // case 2. at least one device is bonded, start pairing test
                startPairingTest()
            }
        }
    }

    /**
     * This action will start scanning for the target device. We delayed start scanning to make sure
     * the headset unbonding has been completed.
     */
    private val startScanningAction: Runnable = Runnable {
        logList.add(
            LogRecordUiModel(
                message =
                    LogRecordUiModel.getRunStartString(
                        testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                    )
            )
        )
        logList.add(
            LogRecordUiModel(message = PUT_HEADSET_INTO_PAIRING_MODE_STRING, actionRequired = true)
        )
        isScanning = true
        handler.removeCallbacks(scanTimeoutAction)
        bluetoothManager.scanTargetDevice()
        handler.postDelayed(scanTimeoutAction, SCAN_PERIOD.toMillis())
    }

    /**
     * This action will be executed when the scan period is timed out. It will cancel the discovery
     * and notify the listener that the device is not found.
     */
    private val scanTimeoutAction: Runnable = Runnable {
        isScanning = false
        bluetoothManager.cancelDiscovery()
        deviceFoundListener.onDeviceNotFound()
        Log.d(TAG, "Time out, stop scanning")
    }

    /** End the current run and save the record. */
    fun endCurrentRunAndSaveRecord(passed: Boolean) {
        stopTimer()
        val currentTime = Instant.now()
        testStartTime?.let { startTime ->
            val duration = Duration.between(startTime, currentTime)
            if (passed) {
                logList.add(
                    LogRecordUiModel(message = "Connection duration: ${duration.toMillis()} ms.")
                )
            }
        }

        runRecordList.add(
            Run(
                runId = testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1,
                assessmentId = testStateManager.currentAssessmentId,
                testItemId = TEST_ITEM_ID,
                startTime = testStartTime ?: currentTime,
                endTime = currentTime,
                passed = passed,
            )
        )

        testStartTime = null
        connectingDeviceAddress = null
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
     * The listener for when the target device is found. It will bond the target device and record
     * the time as the start time of the pairing test.
     */
    private val deviceFoundListener =
        object : DeviceFoundListener {
            override fun onDeviceFound(device: BluetoothDevice) {
                isScanning = false
                handler.removeCallbacks(scanTimeoutAction)

                // If the new device is found after the test has started, we will not perform
                // bonding on it.
                if (connectingDeviceAddress != null && connectingDeviceAddress != device.address) {
                    Log.d(TAG, "Device ${device.address} found after the test has started.")
                    return
                }

                testStartTime = Instant.now()
                connectingDeviceAddress = device.address
                startTimer()

                if (!bluetoothManager.bondTargetDevice(device)) {
                    logList.add(
                        LogRecordUiModel(message = "Failed to bond device ${device.address}.")
                    )
                    endCurrentRunAndSaveRecord(passed = false)

                    if (isTestFinished()) {
                        logList.add(
                            LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT)
                        )
                    } else {
                        if (
                            bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                                BluetoothDevice.BOND_NONE
                            )
                        ) {
                            // case 1. no device is bonded, restart scanning
                            logList.add(
                                LogRecordUiModel(
                                    message =
                                        LogRecordUiModel.getRunStartString(
                                            testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                                        )
                                )
                            )
                            logList.add(
                                LogRecordUiModel(
                                    message = PUT_HEADSET_INTO_PAIRING_MODE_STRING,
                                    actionRequired = true,
                                )
                            )
                            isScanning = true
                            handler.removeCallbacks(scanTimeoutAction)
                            bluetoothManager.scanTargetDevice()
                            handler.postDelayed(scanTimeoutAction, SCAN_PERIOD.toMillis())
                        } else {
                            // case 2. at least one device is bonded, remove bond and restart
                            // scanning
                            startPairingTest()
                        }
                    }
                }
            }

            override fun onDeviceNotFound() {
                logList.add(LogRecordUiModel(message = "Cannot find the target device."))
                endCurrentRunAndSaveRecord(passed = false)

                if (isTestFinished()) {
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    // restart scanning
                    logList.add(
                        LogRecordUiModel(
                            message =
                                LogRecordUiModel.getRunStartString(
                                    testStateManager.getFinishedRuns(TEST_ITEM_ID) + 1
                                )
                        )
                    )
                    logList.add(
                        LogRecordUiModel(
                            message = PUT_HEADSET_INTO_PAIRING_MODE_STRING,
                            actionRequired = true,
                        )
                    )
                    isScanning = true
                    handler.removeCallbacks(scanTimeoutAction)
                    bluetoothManager.scanTargetDevice()
                    handler.postDelayed(scanTimeoutAction, SCAN_PERIOD.toMillis())
                }
            }
        }

    /**
     * The listener for when the discovery is finished. It will restart discovery to keep scanning
     * if [isScanning] is true.
     */
    private val discoveryFinishedListener =
        object : DiscoveryFinishedListener {
            override fun onDiscoveryFinished() {
                if (isScanning) {
                    Log.d(TAG, "Discovery finished, restarting scan to continue scanning.")
                    bluetoothManager.scanTargetDevice()
                }
            }
        }

    /**
     * The listener for when the target device bond state changes. It will start scanning for the
     * target device after their bond state changes to bond_none.
     */
    private val bondStateListener =
        object : BondStateListener {
            override fun onBondStateChange(device: BluetoothDevice, isBonded: Boolean) {
                logList.add(
                    LogRecordUiModel(
                        message =
                            "${device.address} ${BluetoothManager.getBondStateString(device.bondState)}"
                    )
                )

                // reset the connectingDeviceAddress to null to prepare for the next pairing test
                // when:
                // 1. the bonding device is bonded.
                // 2. the bonding device is unbonded.
                if (
                    device.bondState == BluetoothDevice.BOND_BONDED ||
                        device.bondState == BluetoothDevice.BOND_NONE
                ) {
                    connectingDeviceAddress = null
                }

                if (!isBonded) {
                    if (
                        bluetoothManager.isAllTargetDeviceBondStateEqualsTo(
                            BluetoothDevice.BOND_NONE
                        )
                    ) {
                        Log.d(TAG, "start scanning for the target device in 2 seconds.")
                        handler.postDelayed(startScanningAction, DELAY_TIME.toMillis())
                    }
                }
            }
        }

    /**
     * The listener for when the target device is connected. It will update the test state and
     * record the connection ending time when all target devices are connected.
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
                if (testStartTime == null) return

                endCurrentRunAndSaveRecord(passed = true)

                if (isTestFinished()) {
                    logList.add(LogRecordUiModel(message = LogRecordUiModel.ALL_RUNS_FINISHED_TEXT))
                } else {
                    handler.postDelayed({ startPairingTest() }, DELAY_TIME.toMillis())
                }
            }

            override fun onAllDevicesDisconnected() {}

            override fun onPartialDevicesConnected() {}

            override fun onError(e: Exception) {
                logList.add(LogRecordUiModel(message = LogRecordUiModel.getExceptionString(e)))
            }
        }

    /**
     * Initialize the pairing test.
     *
     * This method should be called when the test page is opened. It will reset the test state and
     * start listening to the bluetooth device found/bond state changes/connection state changes.
     */
    fun initPairingTest() {
        testStateManager.initFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.addDeviceFoundListener(deviceFoundListener)
        bluetoothManager.addBondStateListener(bondStateListener)
        bluetoothManager.addConnectionStateListener(connectionStateListener)
        bluetoothManager.addDiscoveryFinishedListener(discoveryFinishedListener)
    }

    /**
     * Reset the pairing test.
     *
     * This method should be called when the test page is closed. It will stop listening to the
     * bluetooth device found/bond state changes/connection state changes.
     */
    fun resetAndSavePairingTest() {
        stopTimer()
        testStateManager.resetFinishedRuns(TEST_ITEM_ID)
        bluetoothManager.removeDeviceFoundListener(deviceFoundListener)
        bluetoothManager.removeBondStateListener(bondStateListener)
        bluetoothManager.removeConnectionStateListener(connectionStateListener)
        bluetoothManager.removeDiscoveryFinishedListener(discoveryFinishedListener)

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
     * Start the pairing test.
     *
     * This method should be called when the start test button is clicked. It will reset the bond
     * state of the target devices and start scanning for the target device in the
     * bondStateListener.
     */
    fun startPairingTest() {
        isStartTestBtnEnabled = false
        if (!bluetoothManager.resetTargetDeviceBondState()) {
            logList.add(LogRecordUiModel(message = "Failed to reset bond state."))
            isStartTestBtnEnabled = true
        }
    }

    /** Start the timer for the pairing test. */
    private fun startTimer() {
        handler.postDelayed(timeoutAction, TIMEOUT_PERIOD.toMillis())
    }

    /** Stop the timer for the pairing test. */
    private fun stopTimer() {
        handler.removeCallbacks(timeoutAction)
        handler.removeCallbacks(startScanningAction)
        handler.removeCallbacks(scanTimeoutAction)
        Log.d(TAG, "Timer stopped for $TAG")
    }

    /** Check if the test is finished. */
    private fun isTestFinished(): Boolean =
        testStateManager.getFinishedRuns(TEST_ITEM_ID) >= RUNS_REQUIRED

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.PAIRING_SETTING.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.PAIRING_SETTING.name
        val RUNS_REQUIRED = TestItemUiModel.PAIRING_SETTING.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
        val DELAY_TIME = Duration.ofSeconds(2)
        val SCAN_PERIOD = Duration.ofSeconds(60)
        val TIMEOUT_PERIOD =
            TestItemUiModel.PAIRING_SETTING.qualification.outlierValue + Duration.ofSeconds(5)
        const val PUT_HEADSET_INTO_PAIRING_MODE_STRING = "Please put the headset into pairing mode."
    }
}
