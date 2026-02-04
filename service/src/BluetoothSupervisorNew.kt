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
import java.io.FileDescriptor
import java.io.PrintWriter

private const val TAG = "BluetoothSupervisorNew"

class BluetoothSupervisorNew(
    private val context: Context,
    private val looper: Looper,
    private val bluetoothComponent: BluetoothComponent,
) : BluetoothSupervisor {
    private var activeBms: BluetoothManagerServiceNew? = null
    private var currentUser: UserHandle? = null

    private var pendingUser: UserHandle? = null // Non-null means a switch is in progress.

    override val api: BluetoothManagerServiceApi = Api()

    init {
        Log.i(TAG, "Created BluetoothSupervisorNew")
    }

    override fun onBluetoothDisallowed() {
        Log.i(TAG, "onBluetoothDisallowed")
    }

    override fun onBootCompleted() {
        Log.i(TAG, "onBootCompleted")
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
        activeBms = BluetoothManagerServiceNew(context, looper, pendingUser!!)
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

    private class Api : BluetoothManagerServiceApi {
        override fun getState(): Int = State.OFF

        override fun waitForState(state: Int): Boolean = false

        override fun registerAdapter(callback: IBluetoothManagerCallback): IBinder? = null

        override fun unregisterAdapter(callback: IBluetoothManagerCallback) {}

        override fun getAddress(): String? = null

        override fun setName(name: String?) {}

        override fun getName(): String? = null

        override fun isBleScanAvailable(): Boolean = false

        override fun isHearingAidProfileSupported(): Boolean = false

        override fun enable(reason: Int, packageName: String): Boolean = false

        override fun enableBle(packageName: String, token: IBinder): Boolean = false

        override fun enableNoAutoConnect(packageName: String): Boolean = false

        override fun disable(packageName: String, persist: Boolean): Boolean = false

        override fun disableBle(packageName: String, token: IBinder): Boolean = false

        override fun factoryReset(): Boolean = false

        override fun isAutoOnSupported(): Boolean = false

        override fun isAutoOnEnabled(): Boolean = false

        override fun setAutoOnEnabled(status: Boolean) {}

        override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<String?>?) {
            writer?.println("BluetoothSupervisorNew: dump not implemented")
        }
    }
}
