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

import android.app.ActivityManager
import android.bluetooth.IBluetoothManagerCallback
import android.bluetooth.State
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import com.android.server.bluetooth.airplane.initialize as initializeAirplaneMode
import com.android.server.bluetooth.satellite.initialize as initializeSatelliteMode
import java.io.FileDescriptor
import java.io.PrintWriter

private const val TAG = "BluetoothSupervisorNew"

@kotlin.time.ExperimentalTime
class BluetoothSupervisorNew(
    private val context: Context,
    private val looper: Looper,
    private val bluetoothComponent: BluetoothComponent,
) : BluetoothSupervisor {
    private var activeBms: BluetoothManagerServiceNew? = null
    private var currentUser: UserHandle? = null
    private var isBootCompleted = false

    private var pendingUser: UserHandle? = null // Non-null means a switch is in progress.

    override val api: BluetoothManagerServiceApi = Api()

    init {
        initializeAirplaneMode(looper, context.contentResolver) { isOn ->
            activeBms?.onAirplaneModeChanged(isOn)
        }
        initializeSatelliteMode(looper, context.contentResolver) { isOn ->
            activeBms?.onSatelliteModeChanged(isOn)
        }
        BleScanSettingListener.initialize(looper, context.contentResolver) {
            activeBms?.onBleScanDisabled()
        }
        BluetoothSystemBroadcastReceiver(
            context,
            looper,
            onSettingsRestored = { enabled -> activeBms?.onSettingsRestored(enabled) },
            onShutdown = {
                Log.i(TAG, "Device shutting down. Stopping service.")
                pendingUser = null
                currentUser = null
                activeBms?.shutdown()
                activeBms = null
            },
        )

        Log.i(TAG, "Instance created, waiting for user")
    }

    override fun onRestrictionChange() {
        Log.i(TAG, "onRestrictionChange")
        activeBms?.onRestrictionChange()
    }

    override fun onBootCompleted() {
        Log.i(TAG, "onBootCompleted")
        isBootCompleted = true
        activeBms?.onBootCompleted()
    }

    override suspend fun onUserStarting(userHandle: UserHandle) {
        if (currentUser != null) {
            Log.i(TAG, "onUserStarting($userHandle): Already running on $currentUser")
            return
        }
        Log.i(TAG, "onUserStarting($userHandle) -> delegating to onUserSwitching")
        onUserSwitching(userHandle)
    }

    override suspend fun onUserSwitching(userHandle: UserHandle) {
        val switchInProgress = pendingUser != null
        pendingUser = userHandle

        if (switchInProgress) {
            Log.i(TAG, "onUserSwitching($userHandle): Request queued. Switch already in progress")
            return
        }

        if (userHandle == currentUser) {
            Log.i(TAG, "onUserSwitching($userHandle): Already the current user. Nothing to do.")
            pendingUser = null
            return
        }

        if (activeBms != null) {
            Log.i(TAG, "Shutting down service for $currentUser")
            activeBms?.shutdown()
            // Suspension point ! Incoming switch will simply update `pendingUser`
            activeBms?.awaitShutdown()
        }

        Log.i(TAG, "Starting service for $pendingUser")
        activeBms =
            BluetoothManagerServiceNew(
                context.createContextAsUser(pendingUser!!, 0),
                looper,
                pendingUser!!,
                bluetoothComponent,
                isBootCompleted,
            )
        currentUser = pendingUser
        pendingUser = null
    }

    // See b/446749636:
    // Android is meant to always have a foreground user, but in some situation, onUserStopping can
    // be called before onUserSwitching. This lead to undefined behavior in Bluetooth. To prevent
    // this, we need to emulate a user switch on the current foreground user using
    // `ActivityManager.getCurrentUser()`
    override suspend fun onUserStopping(userHandle: UserHandle) {
        if (userHandle != currentUser) {
            Log.v(TAG, "onUserStopping($userHandle): Nothing to do. currentUser=$currentUser.")
            return
        }

        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())
        if (foregroundUser == userHandle) {
            // TODO Investigate if this is possible during Android shutdown ?
            throw IllegalStateException("onUserStopping($userHandle): No remaining user")
        }

        Log.wtf(TAG, "onUserStopping: Called while being the Bluetooth current user !")
        Log.e(TAG, "onUserStopping: Fallback to onUserSwitching $userHandle => $foregroundUser")
        onUserSwitching(foregroundUser)
    }

    private inner class Api : BluetoothManagerServiceApi {
        // Helper to safely access the active service or return a default/error
        private fun <T> withBms(default: T, block: BluetoothManagerServiceNew.() -> T): T {
            return activeBms?.run(block) ?: default
        }

        override fun getState(): Int = withBms(State.OFF) { getState() }

        override fun waitForState(state: Int): Boolean = withBms(false) { waitForState(state) }

        override fun registerAdapter(callback: IBluetoothManagerCallback): IBinder? =
            withBms(null) { registerAdapter(callback) }

        override fun unregisterAdapter(callback: IBluetoothManagerCallback) =
            withBms(Unit) { unregisterAdapter(callback) }

        override fun getAddress(): String? = withBms(null) { getAddress() }

        override fun setName(name: String?) = withBms(Unit) { setName(name) }

        override fun getName(): String? = withBms(null) { getName() }

        override fun isBleScanAvailable(): Boolean = withBms(false) { isBleScanAvailable() }

        override fun enable(reason: Int, packageName: String): Boolean =
            withBms(false) { enable(reason, packageName) }

        override fun enableBle(packageName: String, token: IBinder): Boolean =
            withBms(false) { enableBle(packageName, token) }

        override fun enableNoAutoConnect(packageName: String): Boolean =
            withBms(false) { enableNoAutoConnect(packageName) }

        override fun disable(packageName: String, persist: Boolean): Boolean =
            withBms(false) { disable(packageName, persist) }

        override fun disableBle(packageName: String, token: IBinder): Boolean =
            withBms(false) { disableBle(packageName, token) }

        override fun factoryReset(): Boolean = withBms(false) { factoryReset() }

        override fun isAutoOnSupported(): Boolean = withBms(false) { isAutoOnSupported() }

        override fun isAutoOnEnabled(): Boolean = withBms(false) { isAutoOnEnabled() }

        override fun setAutoOnEnabled(status: Boolean) = withBms(Unit) { setAutoOnEnabled(status) }

        override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<String?>?) {
            activeBms?.dump(fd, writer, args) ?: writer?.println("$TAG: No active user")
        }
    }
}
