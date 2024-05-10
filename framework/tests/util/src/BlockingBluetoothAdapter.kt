/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.bluetooth.test_utils

import android.Manifest.permission
import android.app.UiAutomation
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/** Utility for controlling the Bluetooth adapter from CTS test. */
object BlockingBluetoothAdapter {
    private val TAG: String = BlockingBluetoothAdapter::class.java.getSimpleName()

    /**
     * ADAPTER_ENABLE_TIMEOUT_MS = AdapterState.BLE_START_TIMEOUT_DELAY +
     * AdapterState.BREDR_START_TIMEOUT_DELAY + (10 seconds of additional delay)
     */
    private val ADAPTER_ENABLE_TIMEOUT = Duration.ofSeconds(18)

    /**
     * ADAPTER_DISABLE_TIMEOUT_MS = AdapterState.BLE_STOP_TIMEOUT_DELAY +
     * AdapterState.BREDR_STOP_TIMEOUT_DELAY
     */
    private val ADAPTER_DISABLE_TIMEOUT = Duration.ofSeconds(5)

    /** Redefined from [BluetoothAdapter] because of hidden APIs */
    const val STATE_BLE_TURNING_ON = 14
    const val STATE_BLE_TURNING_OFF = 16
    private val sStateTimeouts = HashMap<Int, Duration>()

    init {
        sStateTimeouts.put(BluetoothAdapter.STATE_OFF, ADAPTER_DISABLE_TIMEOUT)
        sStateTimeouts.put(BluetoothAdapter.STATE_TURNING_ON, ADAPTER_ENABLE_TIMEOUT)
        sStateTimeouts.put(BluetoothAdapter.STATE_ON, ADAPTER_ENABLE_TIMEOUT)
        sStateTimeouts.put(BluetoothAdapter.STATE_TURNING_OFF, ADAPTER_DISABLE_TIMEOUT)
        sStateTimeouts.put(STATE_BLE_TURNING_ON, ADAPTER_ENABLE_TIMEOUT)
        sStateTimeouts.put(BluetoothAdapter.STATE_BLE_ON, ADAPTER_ENABLE_TIMEOUT)
        sStateTimeouts.put(STATE_BLE_TURNING_OFF, ADAPTER_DISABLE_TIMEOUT)
    }

    private var sAdapterVarsInitialized = false
    private var sBluetoothAdapterLock: ReentrantLock? = null
    private var sConditionAdapterStateReached: Condition? = null
    private var sDesiredState = 0
    private var sAdapterState = 0

    /** Initialize all static state variables */
    private fun initAdapterStateVariables(context: Context) {
        Log.d(TAG, "Initializing adapter state variables")
        val sAdapterReceiver = BluetoothAdapterReceiver()
        sBluetoothAdapterLock = ReentrantLock()
        sConditionAdapterStateReached = sBluetoothAdapterLock!!.newCondition()
        sDesiredState = -1
        sAdapterState = -1
        val filter: IntentFilter = IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED)
        context.registerReceiver(sAdapterReceiver, filter)
        sAdapterVarsInitialized = true
    }

    /**
     * Helper method to wait for the bluetooth adapter to be in a given state
     *
     * Assumes all state variables are initialized. Assumes it's being run with
     * sBluetoothAdapterLock in the locked state.
     */
    @kotlin.Throws(InterruptedException::class)
    private fun waitForAdapterStateLocked(desiredState: Int, adapter: BluetoothAdapter): Boolean {
        val timeout = sStateTimeouts.getOrDefault(desiredState, ADAPTER_ENABLE_TIMEOUT)
        Log.d(TAG, "Waiting for adapter state $desiredState")
        sDesiredState = desiredState

        // Wait until we have reached the desired state
        // Handle spurious wakeup
        while (desiredState != sAdapterState) {
            if (sConditionAdapterStateReached!!.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // Handle spurious wakeup
                continue
            }
            // Handle timeout cases
            // Case 1: situation where state change occurs, but we don't receive the broadcast
            if (
                desiredState >= BluetoothAdapter.STATE_OFF &&
                    desiredState <= BluetoothAdapter.STATE_TURNING_OFF
            ) {
                val currentState = adapter.state
                Log.d(TAG, "desiredState: $desiredState, currentState: $currentState")
                return desiredState == currentState
            } else if (desiredState == BluetoothAdapter.STATE_BLE_ON) {
                Log.d(TAG, "adapter isLeEnabled: " + adapter.isLeEnabled())
                return adapter.isLeEnabled()
            }
            // Case 2: Actual timeout
            Log.e(
                TAG,
                "Timeout while waiting for Bluetooth adapter state " +
                    desiredState +
                    " while current state is " +
                    sAdapterState,
            )
            break
        }
        Log.d(TAG, "Final state while waiting: " + sAdapterState)
        return sAdapterState == desiredState
    }

    /** Utility method to wait on any specific adapter state */
    fun waitForAdapterState(desiredState: Int, adapter: BluetoothAdapter): Boolean {
        sBluetoothAdapterLock!!.lock()
        try {
            return waitForAdapterStateLocked(desiredState, adapter)
        } catch (e: InterruptedException) {
            Log.w(TAG, "waitForAdapterState(): interrupted", e)
        } finally {
            sBluetoothAdapterLock!!.unlock()
        }
        return false
    }

    /** Enables Bluetooth to a Low Energy only mode */
    @JvmStatic
    fun enableBLE(bluetoothAdapter: BluetoothAdapter, context: Context): Boolean {
        if (!sAdapterVarsInitialized) {
            initAdapterStateVariables(context)
        }
        if (bluetoothAdapter.isLeEnabled()) {
            return true
        }
        sBluetoothAdapterLock!!.lock()
        try {
            Log.d(TAG, "Enabling Bluetooth low energy only mode")
            if (!bluetoothAdapter.enableBLE()) {
                Log.e(TAG, "Unable to enable Bluetooth low energy only mode")
                return false
            }
            return waitForAdapterStateLocked(BluetoothAdapter.STATE_BLE_ON, bluetoothAdapter)
        } catch (e: InterruptedException) {
            Log.w(TAG, "enableBLE(): interrupted", e)
        } finally {
            sBluetoothAdapterLock!!.unlock()
        }
        return false
    }

    /** Disable Bluetooth Low Energy mode */
    @JvmStatic
    fun disableBLE(bluetoothAdapter: BluetoothAdapter, context: Context): Boolean {
        if (!sAdapterVarsInitialized) {
            initAdapterStateVariables(context)
        }
        if (bluetoothAdapter.state == BluetoothAdapter.STATE_OFF) {
            return true
        }
        sBluetoothAdapterLock!!.lock()
        try {
            Log.d(TAG, "Disabling Bluetooth low energy")
            bluetoothAdapter.disableBLE()
            return waitForAdapterStateLocked(BluetoothAdapter.STATE_OFF, bluetoothAdapter)
        } catch (e: InterruptedException) {
            Log.w(TAG, "disableBLE(): interrupted", e)
        } finally {
            sBluetoothAdapterLock!!.unlock()
        }
        return false
    }

    /** Enables the Bluetooth Adapter. Return true if it is already enabled or is enabled. */
    @JvmStatic
    fun enableAdapter(bluetoothAdapter: BluetoothAdapter, context: Context): Boolean {
        if (!sAdapterVarsInitialized) {
            initAdapterStateVariables(context)
        }
        if (bluetoothAdapter.isEnabled) {
            return true
        }
        val permissionsAdopted: Set<String> = TestUtils.getAdoptedShellPermissions()
        sBluetoothAdapterLock!!.lock()
        try {
            Log.d(TAG, "Enabling Bluetooth adapter")
            TestUtils.dropPermissionAsShellUid()
            TestUtils.adoptPermissionAsShellUid(
                permission.BLUETOOTH_CONNECT,
                permission.BLUETOOTH_PRIVILEGED,
            )
            bluetoothAdapter.enable()
            return waitForAdapterStateLocked(BluetoothAdapter.STATE_ON, bluetoothAdapter)
        } catch (e: InterruptedException) {
            Log.w(TAG, "enableAdapter(): interrupted", e)
        } finally {
            TestUtils.dropPermissionAsShellUid()
            if (UiAutomation.ALL_PERMISSIONS.equals(permissionsAdopted)) {
                TestUtils.adoptPermissionAsShellUid()
            } else {
                TestUtils.adoptPermissionAsShellUid(*permissionsAdopted.map { it }.toTypedArray())
            }
            sBluetoothAdapterLock!!.unlock()
        }
        return false
    }

    /** Disable the Bluetooth Adapter. Return true if it is already disabled or is disabled. */
    @JvmStatic
    fun disableAdapter(bluetoothAdapter: BluetoothAdapter, context: Context): Boolean {
        return disableAdapter(bluetoothAdapter, true, context)
    }

    /**
     * Disable the Bluetooth Adapter with then option to persist the off state or not.
     *
     * Returns true if the adapter is already disabled or was disabled.
     */
    @JvmStatic
    fun disableAdapter(
        bluetoothAdapter: BluetoothAdapter,
        persist: Boolean,
        context: Context,
    ): Boolean {
        if (!sAdapterVarsInitialized) {
            initAdapterStateVariables(context)
        }
        if (bluetoothAdapter.state == BluetoothAdapter.STATE_OFF) {
            return true
        }
        val permissionsAdopted: Set<String> = TestUtils.getAdoptedShellPermissions()
        sBluetoothAdapterLock!!.lock()
        try {
            Log.d(TAG, "Disabling Bluetooth adapter, persist=$persist")
            TestUtils.dropPermissionAsShellUid()
            TestUtils.adoptPermissionAsShellUid(
                permission.BLUETOOTH_CONNECT,
                permission.BLUETOOTH_PRIVILEGED,
            )
            bluetoothAdapter.disable(persist)
            return waitForAdapterStateLocked(BluetoothAdapter.STATE_OFF, bluetoothAdapter)
        } catch (e: InterruptedException) {
            Log.w(TAG, "disableAdapter(persist=$persist): interrupted", e)
        } finally {
            TestUtils.dropPermissionAsShellUid()
            if (UiAutomation.ALL_PERMISSIONS.equals(permissionsAdopted)) {
                TestUtils.adoptPermissionAsShellUid()
            } else {
                TestUtils.adoptPermissionAsShellUid(*permissionsAdopted.map { it }.toTypedArray())
            }
            sBluetoothAdapterLock!!.unlock()
        }
        return false
    }

    /** Handles BluetoothAdapter state changes and signals when we have reached a desired state */
    private class BluetoothAdapterReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_BLE_STATE_CHANGED.equals(action)) {
                val newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                Log.d(TAG, "Bluetooth adapter state changed: $newState")

                // Signal if the state is set to the one we are waiting on
                sBluetoothAdapterLock!!.lock()
                try {
                    sAdapterState = newState
                    if (sDesiredState == newState) {
                        Log.d(TAG, "Adapter has reached desired state: " + sDesiredState)
                        sConditionAdapterStateReached!!.signal()
                    }
                } finally {
                    sBluetoothAdapterLock!!.unlock()
                }
            }
        }
    }
}
