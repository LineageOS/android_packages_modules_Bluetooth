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

import android.bluetooth.IBluetoothManagerCallback
import android.os.IBinder
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * This interface is used to abstract the BluetoothManagerService into testable components.
 *
 * The goal is to make the binder and the messenger to not directly depend on the service, but to
 * depend on this interface instead.
 */
interface BluetoothManagerServiceApi {
    // getState can be called from any thread
    fun getState(): Int

    // waitForState can be called from any thread
    fun waitForState(state: Int): Boolean

    fun registerAdapter(callback: IBluetoothManagerCallback): IBinder?

    fun unregisterAdapter(callback: IBluetoothManagerCallback)

    fun getAddress(): String?

    fun setName(name: String?)

    fun getName(): String?

    fun isBleScanAvailable(): Boolean

    fun enable(reason: Int, packageName: String): Boolean

    fun enableBle(packageName: String, token: IBinder): Boolean

    fun enableNoAutoConnect(packageName: String): Boolean

    fun disable(packageName: String, persist: Boolean): Boolean

    fun disableBle(packageName: String, token: IBinder): Boolean

    fun factoryReset(): Boolean

    fun isAutoOnSupported(): Boolean

    fun isAutoOnEnabled(): Boolean

    fun setAutoOnEnabled(status: Boolean)

    fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<String?>?)
}
