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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.BroadcastOptions
import android.bluetooth.IBluetoothManager.ACTION_LOCAL_NAME_CHANGED
import android.bluetooth.IBluetoothManager.EXTRA_LOCAL_NAME
import android.bluetooth.IBluetoothManagerCallback
import android.bluetooth.State
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.os.PowerExemptionManager.REASON_BLUETOOTH_BROADCAST
import android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings.Global
import android.provider.Settings.Secure
import com.android.bluetooth.util.truncateUtf8String
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.time.Duration.Companion.seconds

// Must match android.provider.Settings.Secure.BLUETOOTH_NAME but cannot depend on the variable
const val BLUETOOTH_NAME = "bluetooth_name"

class BluetoothManagerServiceNew(
    private val context: Context,
    private val looper: Looper,
    private val userHandle: UserHandle,
    private var isBootCompleted: Boolean,
) {
    private val contentResolver = context.contentResolver
    private val state = BluetoothAdapterState()

    private var localName = validateLocalName(Secure.getString(contentResolver, BLUETOOTH_NAME))

    init {
        Log.i(
            TAG,
            "Starting for user $userHandle (boot completed=$isBootCompleted) Name=$localName",
        )
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
    fun getState(): Int = state.get()

    fun waitForState(state: Int): Boolean = false

    fun registerAdapter(callback: IBluetoothManagerCallback): IBinder? = null

    fun unregisterAdapter(callback: IBluetoothManagerCallback) {}

    fun getAddress(): String? = null

    fun getName() = localName

    fun setName(name: String?) {
        val validatedName = validateLocalName(name)
        if (validatedName == localName) {
            return
        }
        if (!state.oneOf(State.OFF)) {
            throw NotImplementedError("setName when Bluetooth is ON") // TODO
        }
        persistentStorageForLocalName(validatedName)
    }

    private fun validateLocalName(_name: String?): String {
        var name = _name
        if (name.isNullOrEmpty()) {
            name = SystemProperties.get("bluetooth.device.default_name")
        }
        if (name.isNullOrEmpty()) {
            name = Global.getString(contentResolver, Global.DEVICE_NAME)
        }
        if (name.isNullOrEmpty()) {
            name = SystemProperties.get("ro.product.model")
        }
        if (name.isNullOrEmpty()) {
            name = "Android"
        }
        // The Bluetooth Device Name can be up to 248 bytes (see [Vol 2] Part C, Section 4.3.5).
        return name.truncateUtf8String(248)
    }

    private fun persistentStorageForLocalName(name: String) {
        Secure.putString(contentResolver, BLUETOOTH_NAME, name)
        val intent =
            Intent(ACTION_LOCAL_NAME_CHANGED)
                .putExtra(EXTRA_LOCAL_NAME, name)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT)
        context.sendBroadcastAsUser(
            intent,
            userHandle,
            BLUETOOTH_CONNECT,
            getTempAllowlistBroadcastOptions(),
        )
        Log.v(TAG, "persistentStorageForLocalName($name): Name updated $localName -> $name")
        localName = name
    }

    fun isBleScanAvailable(): Boolean = false

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

        fun getTempAllowlistBroadcastOptions() =
            BroadcastOptions.makeBasic()
                .apply {
                    setTemporaryAppAllowlist(
                        10.seconds.inWholeMilliseconds,
                        TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                        REASON_BLUETOOTH_BROADCAST,
                        "",
                    )
                }
                .toBundle()
    }
}
