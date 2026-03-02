/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.util.TimeProvider
import com.android.server.bluetooth.airplane.initialize as initializeAirplaneMode
import com.android.server.bluetooth.satellite.initialize as initializeSatelliteMode
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlinx.coroutines.runBlocking

private const val TAG = "BluetoothSupervisorLegacy"

class BluetoothSupervisorLegacy(
    context: Context,
    private val looper: Looper,
    bluetoothComponent: BluetoothComponent,
    private val bms: BluetoothManagerService =
        BluetoothManagerService(
            context,
            looper,
            BluetoothHciInstance().getInstance(),
            bluetoothComponent,
            TimeProvider.systemClock,
        ),
) : BluetoothSupervisor {

    private var currentUser: UserHandle? = null

    private var initialized = false
    override val api: BluetoothManagerServiceApi = Api(BmsProvider())

    init {
        initializeAirplaneMode(looper, context.contentResolver, this::onAirplaneModeChanged)
        initializeSatelliteMode(looper, context.contentResolver, this::onSatelliteModeChanged)
        Log.i(TAG, "Created BluetoothSupervisorLegacy")
    }

    override fun onRestrictionChange() {
        enforceCorrectThread()
        bms.onRestrictionChange()
    }

    fun onAirplaneModeChanged(isAirplaneModeOn: Boolean) {
        enforceCorrectThread()
        if (!initialized) {
            Log.i(TAG, "onAirplaneModeChanged before initialization - skipping")
            return
        }
        bms.airplaneModeController.onAirplaneModeChanged(isAirplaneModeOn)
    }

    fun onSatelliteModeChanged(isSatelliteModeOn: Boolean) {
        enforceCorrectThread()
        if (!initialized) {
            Log.i(TAG, "onSatelliteModeChanged before initialization - skipping")
            return
        }
        bms.onSatelliteModeChanged(isSatelliteModeOn)
    }

    override fun onBootCompleted() {
        enforceCorrectThread()
        bms.onBootCompleted()
    }

    @VisibleForTesting // Because of BluetoothManagerServiceTest
    fun onUserStartingFromJava(userHandle: UserHandle) = runBlocking { onUserStarting(userHandle) }

    override suspend fun onUserStarting(userHandle: UserHandle) {
        enforceCorrectThread()
        if (initialized) {
            Log.i(TAG, "onUserStarting($userHandle) but already initialized")
            return
        }
        currentUser = userHandle
        bms.handleOnBootPhase(userHandle)
        initialized = true
    }

    override suspend fun onUserSwitching(userHandle: UserHandle) {
        enforceCorrectThread()
        check(initialized) { "Initialize did not happen" }
        if (Flags.switchWhenCurrentUserStop()) {
            if (userHandle == currentUser) {
                Log.i(TAG, "onUserSwitching($userHandle): Nothing to do.")
                return
            }
        }
        currentUser = userHandle
        bms.onUserSwitching(userHandle)
    }

    @VisibleForTesting // Because of BluetoothManagerServiceTest
    fun onUserStoppingFromJava(userHandle: UserHandle) = runBlocking { onUserStopping(userHandle) }

    // See b/446749636:
    // Android is meant to always have a foreground user, but in some situation, onUserStopping can
    // be called before onUserSwitching. This lead to undefined behavior in Bluetooth. To prevent
    // this, we need to emulate a user switch on the current foreground user using
    // `ActivityManager.getCurrentUser()`
    override suspend fun onUserStopping(userHandle: UserHandle) {
        enforceCorrectThread()
        if (userHandle != currentUser) {
            Log.v(TAG, "onUserStopping($userHandle): Nothing to do. currentUser=$currentUser.")
            return
        }
        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())
        if (foregroundUser == userHandle) {
            throw IllegalStateException("onUserStopping($userHandle): No remaining user")
        }
        Log.wtf(TAG, "onUserStopping: Called while being the Bluetooth current user !")
        Log.e(TAG, "onUserStopping: Fallback to onUserSwitching $userHandle => $foregroundUser")
        onUserSwitching(foregroundUser)
    }

    private fun enforceCorrectThread() {
        if (looper == Looper.myLooper()) {
            return
        }
        throw IllegalThreadStateException("Must be called on BluetoothSystemServer looper")
    }

    private inner class BmsProvider {
        fun bms(): BluetoothManagerService {
            enforceCorrectThread()
            return bms
        }

        fun multithreadBms() = bms
    }

    private class Api(private val bmsProvider: BmsProvider) : BluetoothManagerServiceApi {
        private fun bms() = bmsProvider.bms()

        private fun multithreadBms() = bmsProvider.multithreadBms()

        override fun getState() = multithreadBms().state

        override fun waitForState(state: Int) = multithreadBms().waitForState(state)

        override fun registerAdapter(callback: IBluetoothManagerCallback) =
            bms().registerAdapter(callback)

        override fun unregisterAdapter(callback: IBluetoothManagerCallback) =
            bms().unregisterAdapter(callback)

        override fun getAddress() = bms().address

        override fun setName(name: String?) = bms().setName(name)

        override fun getName() = bms().name

        override fun isBleScanAvailable() = bms().isBleScanAvailable()

        override fun enable(reason: Int, packageName: String) = bms().enable(reason, packageName)

        override fun enableBle(packageName: String, token: IBinder) =
            bms().enableBle(packageName, token)

        override fun enableNoAutoConnect(packageName: String) =
            bms().enableNoAutoConnect(packageName)

        override fun disable(packageName: String, persist: Boolean) =
            bms().disable(packageName, persist)

        override fun disableBle(packageName: String, token: IBinder) =
            bms().disableBle(packageName, token)

        override fun factoryReset() = bms().factoryReset(0)

        override fun isAutoOnSupported() = bms().isAutoOnSupported

        override fun isAutoOnEnabled() = bms().isAutoOnEnabled

        override fun setAutoOnEnabled(status: Boolean) = bms().setAutoOnEnabled(status)

        override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<String?>?) =
            bms().dump(fd, writer, args)
    }
}
