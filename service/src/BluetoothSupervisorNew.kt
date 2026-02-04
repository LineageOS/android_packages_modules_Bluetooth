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

private const val TAG = "BluetoothSupervisorNew"

class BluetoothSupervisorNew(
    private val context: Context,
    private val looper: Looper,
    private val bluetoothComponent: BluetoothComponent,
) : BluetoothSupervisor {

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
        Log.i(TAG, "onUserStarting($userHandle)")
    }

    override suspend fun onUserSwitching(userHandle: UserHandle) {
        Log.i(TAG, "onUserSwitching($userHandle)")
    }

    override suspend fun onUserStopping(userHandle: UserHandle) {
        Log.i(TAG, "onUserStopping($userHandle)")
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
