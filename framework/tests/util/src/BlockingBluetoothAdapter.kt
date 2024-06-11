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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_BLE_STATE_CHANGED
import android.bluetooth.BluetoothAdapter.STATE_BLE_ON
import android.bluetooth.BluetoothAdapter.STATE_OFF
import android.bluetooth.BluetoothAdapter.STATE_ON
import android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF
import android.bluetooth.BluetoothAdapter.STATE_TURNING_ON
import android.bluetooth.BluetoothManager
import android.bluetooth.test_utils.Permissions.withPermissions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG: String = "BlockingBluetoothAdapter"
// There is no access to the module only API Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE
private const val BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled"

object BlockingBluetoothAdapter {
    private val context = InstrumentationRegistry.getInstrumentation().getContext()
    @JvmStatic val adapter = context.getSystemService(BluetoothManager::class.java).getAdapter()

    private val state = AdapterStateListener(context, adapter)

    // BLE_START_TIMEOUT_DELAY + BREDR_START_TIMEOUT_DELAY + (10 seconds of additional delay)
    private val stateChangeTimeout = 18.seconds

    init {
        Log.d(TAG, "Started with initial state to $state")
    }

    /** Set Bluetooth in BLE mode. Only works if it was OFF before */
    @JvmStatic
    fun enableBLE(toggleScanSetting: Boolean): Boolean {
        if (!state.eq(STATE_OFF)) {
            throw IllegalStateException("Invalid call to enableBLE while current state is: $state")
        }
        if (toggleScanSetting) {
            Log.d(TAG, "Allowing the scan to be perform while Bluetooth is OFF")
            Settings.Global.putInt(context.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 1)
            for (i in 1..5) {
                if (adapter.isBleScanAlwaysAvailable()) {
                    break
                }
                Log.d(TAG, "Ble scan not yet available… Sleeping 20 ms $i/5")
                Thread.sleep(20)
            }
            if (!adapter.isBleScanAlwaysAvailable()) {
                throw IllegalStateException("Could not enable BLE scan")
            }
        }
        Log.d(TAG, "Call to enableBLE")
        if (!withPermissions(BLUETOOTH_CONNECT).use { adapter.enableBLE() }) {
            Log.e(TAG, "enableBLE: Failed")
            return false
        }
        return state.waitForStateWithTimeout(stateChangeTimeout, STATE_BLE_ON)
    }

    /** Restore Bluetooth to OFF. Only works if it was in BLE_ON due to enableBLE call */
    @JvmStatic
    fun disableBLE(): Boolean {
        if (!state.eq(STATE_BLE_ON)) {
            throw IllegalStateException("Invalid call to disableBLE while current state is: $state")
        }
        Log.d(TAG, "Call to disableBLE")
        if (!withPermissions(BLUETOOTH_CONNECT).use { adapter.disableBLE() }) {
            Log.e(TAG, "disableBLE: Failed")
            return false
        }
        Log.d(TAG, "Disallowing the scan to be perform while Bluetooth is OFF")
        Settings.Global.putInt(context.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 0)
        return state.waitForStateWithTimeout(stateChangeTimeout, STATE_OFF)
    }

    /** Turn Bluetooth ON and wait for state change */
    @JvmStatic
    fun enable(): Boolean {
        if (state.eq(STATE_ON)) {
            Log.i(TAG, "enable: state is already $state")
            return true
        }
        Log.d(TAG, "Call to enable")
        if (
            !withPermissions(BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED).use {
                @Suppress("DEPRECATION") adapter.enable()
            }
        ) {
            Log.e(TAG, "enable: Failed")
            return false
        }
        return state.waitForStateWithTimeout(stateChangeTimeout, STATE_ON)
    }

    /** Turn Bluetooth OFF and wait for state change */
    @JvmStatic
    fun disable(persist: Boolean = true): Boolean {
        if (state.eq(STATE_OFF)) {
            Log.i(TAG, "disable: state is already $state")
            return true
        }
        Log.d(TAG, "Call to disable($persist)")
        if (
            !withPermissions(BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED).use {
                adapter.disable(persist)
            }
        ) {
            Log.e(TAG, "disable: Failed")
            return false
        }
        // Notify that disable was call.
        state.wasDisabled = true
        return state.waitForStateWithTimeout(stateChangeTimeout, STATE_OFF)
    }
}

private class AdapterStateListener(context: Context, private val adapter: BluetoothAdapter) {
    private val STATE_UNKNOWN = -42
    private val STATE_BLE_TURNING_ON = 14 // BluetoothAdapter.STATE_BLE_TURNING_ON
    private val STATE_BLE_TURNING_OFF = 16 // BluetoothAdapter.STATE_BLE_TURNING_OFF

    // Set to true once a call to disable is made, in order to force the differentiation between the
    // various state hidden within STATE_OFF (OFF, BLE_TURNING_ON, BLE_TURNING_OFF)
    // Once true, getter will return STATE_OFF when there has not been any callback sent to it
    var wasDisabled = false

    val adapterStateFlow =
        callbackFlow<Intent> {
                val broadcastReceiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            trySendBlocking(intent)
                        }
                    }
                context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_BLE_STATE_CHANGED))

                awaitClose { context.unregisterReceiver(broadcastReceiver) }
            }
            .map { it.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) }
            .onEach { Log.d(TAG, "State changed to ${nameForState(it)}") }
            .shareIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, 1)

    private fun get(): Int =
        adapterStateFlow.replayCache.getOrElse(0) {
            val state: Int = adapter.getState()
            if (state != STATE_OFF) {
                state
            } else if (adapter.isLeEnabled()) {
                STATE_BLE_ON
            } else if (wasDisabled) {
                STATE_OFF
            } else {
                STATE_UNKNOWN
            }
        }

    fun eq(state: Int): Boolean = state == get()

    override fun toString(): String {
        return nameForState(get())
    }

    // Cts cannot use BluetoothAdapter.nameForState prior to T, some module test on R
    private fun nameForState(state: Int): String {
        return when (state) {
            STATE_UNKNOWN -> "UNKNOWN: State is oneOf(OFF, BLE_TURNING_ON, BLE_TURNING_OFF)"
            STATE_OFF -> "OFF"
            STATE_TURNING_ON -> "TURNING_ON"
            STATE_ON -> "ON"
            STATE_TURNING_OFF -> "TURNING_OFF"
            STATE_BLE_TURNING_ON -> "BLE_TURNING_ON"
            STATE_BLE_ON -> "BLE_ON"
            STATE_BLE_TURNING_OFF -> "BLE_TURNING_OFF"
            else -> "?!?!? ($state) ?!?!? "
        }
    }

    fun waitForStateWithTimeout(timeout: Duration, state: Int): Boolean = runBlocking {
        withTimeoutOrNull(timeout) { adapterStateFlow.filter { it == state }.first() } != null
    }
}
