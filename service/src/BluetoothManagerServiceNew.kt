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

package com.android.server.bluetooth

import android.bluetooth.IBluetoothManagerCallback
import android.bluetooth.State
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import java.io.FileDescriptor
import java.io.PrintWriter

class BluetoothManagerServiceNew(
    private val context: Context,
    private val looper: Looper,
    private val userHandle: UserHandle,
    private var isBootCompleted: Boolean,
) {

    init {
        Log.i(TAG, "Starting for user $userHandle (boot completed=$isBootCompleted)")
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down for user $userHandle")
    }

    suspend fun awaitShutdown() {
        // TODO wait for completion
    }

    fun onBluetoothDisallowed() {
        Log.i(TAG, "onBluetoothDisallowed")
    }

    fun onAirplaneModeChanged(isAirplaneModeOn: Boolean) {
        Log.i(TAG, "onAirplaneModeChanged($isAirplaneModeOn)")
    }

    fun onSatelliteModeChanged(isSatelliteModeOn: Boolean) {
        Log.i(TAG, "onSatelliteModeChanged($isSatelliteModeOn)")
    }

    fun onBootCompleted() {
        Log.i(TAG, "onBootCompleted")
        isBootCompleted = true
    }

    fun onBleScanDisabled() {
        Log.i(TAG, "onBleScanDisabled")
    }

    fun onSettingsRestored(enabled: Boolean) {
        Log.i(TAG, "onSettingsRestored(enabled=$enabled)")
    }

    // API Delegate methods
    fun getState(): Int = State.OFF

    fun waitForState(state: Int): Boolean = false

    fun registerAdapter(callback: IBluetoothManagerCallback): IBinder? = null

    fun unregisterAdapter(callback: IBluetoothManagerCallback) {}

    fun getAddress(): String? = null

    fun setName(name: String?) {}

    fun getName(): String? = null

    fun isBleScanAvailable(): Boolean = false

    fun isHearingAidProfileSupported(): Boolean = false

    fun enable(reason: Int, packageName: String): Boolean = false

    fun enableBle(packageName: String, token: IBinder): Boolean = false

    fun enableNoAutoConnect(packageName: String): Boolean = false

    fun disable(packageName: String, persist: Boolean): Boolean = false

    fun disableBle(packageName: String, token: IBinder): Boolean = false

    fun factoryReset(): Boolean = false

    fun isAutoOnSupported(): Boolean = false

    fun isAutoOnEnabled(): Boolean = false

    fun setAutoOnEnabled(status: Boolean) {}

    fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<String?>?) {
        writer?.println("$TAG for $userHandle")
    }

    companion object {
        private const val TAG = "BluetoothManagerServiceNew"
    }
}
