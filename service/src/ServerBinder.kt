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

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST
import android.bluetooth.IBluetoothManager
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_DISABLED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FILTERED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FULL
import android.bluetooth.IBluetoothManagerCallback
import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.permission.PermissionManager
import android.sysprop.BluetoothProperties
import com.android.bluetooth.flags.Flags
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "ServerBinder"

private fun getCallerIdentity(source: AttributionSource): String =
    if (!Flags.rejectEnableFromUnknownRequester()) {
        requireNotNull(source.packageName) { "Unknown package caller. Identify yourself" }
    } else {
        val pkg = requireNotNull(source.packageName) { "Unknown package caller. Identify yourself" }
        val tag = source.attributionTag

        // When called from android system, we must set an attribution tag to allow tracking.
        //      Ex: context.createAttributionContext(SERVICE_NAME)

        if (
            "android" == pkg &&
                android.bluetooth.platform.flags.Flags.strictConfigurationInSystemServer()
        ) {
            "$pkg/" + requireNotNull(tag) { "System generic caller must set the Attribution tag" }
        } else {
            tag?.let { "$pkg/$it" } ?: pkg
        }
    }

class ServerBinder(
    looper: Looper,
    private val api: BluetoothManagerServiceApi,
    context: Context,
    permissionManager: PermissionManager = context.getSystemService(PermissionManager::class.java)!!,
) : IBluetoothManager.Stub() {

    private val serviceDispatcher = Handler(looper).asCoroutineDispatcher()
    private val checker = PermissionChecker(context, permissionManager)

    private fun <T> runOnServerThread(block: suspend () -> T): T {
        // Blocks the current Binder thread  until the coroutine completes
        return runBlocking {
            // Any method should most likely be done in under 1 seconds.
            // But real life shows that the system server thread may sometimes be unwillingly busy.
            // By putting a 10 seconds timeout we make sure this will generate an ANR (on purpose).
            // ANR will be investigated and fixed
            withTimeout(10_000L) { withContext(serviceDispatcher) { block() } }
        }
    }

    override fun registerAdapter(callback: IBluetoothManagerCallback): IBinder? {
        return runOnServerThread { api.registerAdapter(callback) }
    }

    override fun unregisterAdapter(callback: IBluetoothManagerCallback) {
        runOnServerThread { api.unregisterAdapter(callback) }
    }

    override fun getState() = api.getState() // This method is designed to work concurrently

    override fun getAddress(source: AttributionSource): String? {
        return try {
            checker.getAddressAllowed(source)
            runOnServerThread { api.getAddress() }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for getAddress", e)
            IBluetoothManager.DEFAULT_MAC_ADDRESS
        }
    }

    override fun setName(name: String?, source: AttributionSource) {
        try {
            checker.setNameAllowed(source)
            // The Bluetooth Device Name can be up to 248 bytes (see [Vol 2] Part C, Section 4.3.5).
            if (name != null && name.toByteArray(UTF_8).size > 248) {
                throw IllegalArgumentException("Name is too long: $name")
            }

            runOnServerThread { api.setName(name) }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for setName", e)
        }
    }

    override fun getName(source: AttributionSource): String? {
        return try {
            checker.getNameAllowed(source)
            runOnServerThread { api.getName() }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for getName", e)
            null
        }
    }

    override fun isBleScanAvailable() = runOnServerThread { api.isBleScanAvailable() }

    override fun enable(source: AttributionSource): Boolean {
        return try {
            checker.enableAllowed(source, foregroundRequired = true)
            val callerIdentity = getCallerIdentity(source)
            runOnServerThread {
                api.enable(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, callerIdentity)
            }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for enable: ", e)
            false
        }
    }

    override fun enableBle(source: AttributionSource, token: IBinder): Boolean {
        return try {
            checker.enableAllowed(source, foregroundRequired = false)
            val callerIdentity = getCallerIdentity(source)
            runOnServerThread { api.enableBle(callerIdentity, token) }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for enableBle: ", e)
            false
        }
    }

    override fun enableNoAutoConnect(source: AttributionSource): Boolean {
        return try {
            checker.enableAllowed(source, foregroundRequired = false)
            if (!source.isCallingFromNfc()) {
                throw SecurityException("Only NFC is allowed to call enableNoAutoConnect")
            }
            val callerIdentity = getCallerIdentity(source)
            runOnServerThread { api.enableNoAutoConnect(callerIdentity) }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for enableNoAutoConnect: ", e)
            false
        }
    }

    override fun disable(source: AttributionSource, persist: Boolean): Boolean {
        if (!persist) {
            checker.enforcePrivileged()
        }
        return try {
            checker.disableAllowed(source, foregroundRequired = true)
            val callerIdentity = getCallerIdentity(source)
            runOnServerThread { api.disable(callerIdentity, persist) }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for disable: ", e)
            false
        }
    }

    override fun disableBle(source: AttributionSource, token: IBinder): Boolean {
        return try {
            checker.disableAllowed(source, foregroundRequired = false)
            val callerIdentity = getCallerIdentity(source)
            runOnServerThread { api.disableBle(callerIdentity, token) }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for disableBle: ", e)
            false
        }
    }

    override fun factoryReset(source: AttributionSource): Boolean {
        checker.enforcePrivileged()

        return try {
            checker.factoryResetAllowed(source)
            runOnServerThread { api.factoryReset() }
        } catch (e: PermissionChecker.BluetoothPermissionException) {
            Log.e(TAG, "Not allowed for factoryReset", e)
            false
        }
    }

    override fun setBtHciSnoopLogMode(mode: Int) {
        // 100% implemented on Binder thread
        checker.enforcePrivileged()
        val identity = Binder.clearCallingIdentity()
        try {
            BluetoothProperties.snoop_log_mode(
                when (mode) {
                    BT_SNOOP_LOG_MODE_DISABLED -> BluetoothProperties.snoop_log_mode_values.DISABLED
                    BT_SNOOP_LOG_MODE_FILTERED -> BluetoothProperties.snoop_log_mode_values.FILTERED
                    BT_SNOOP_LOG_MODE_FULL -> BluetoothProperties.snoop_log_mode_values.FULL
                    else -> null
                }
            )
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun getBtHciSnoopLogMode(): Int {
        // 100% implemented on Binder thread
        checker.enforcePrivileged()
        val identity = Binder.clearCallingIdentity()
        try {
            return when (
                BluetoothProperties.snoop_log_mode()
                    .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED)
            ) {
                BluetoothProperties.snoop_log_mode_values.FILTERED -> BT_SNOOP_LOG_MODE_FILTERED
                BluetoothProperties.snoop_log_mode_values.FULL -> BT_SNOOP_LOG_MODE_FULL
                else -> BT_SNOOP_LOG_MODE_DISABLED
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun isAutoOnSupported(): Boolean {
        checker.enforcePrivileged()
        return runOnServerThread { api.isAutoOnSupported() }
    }

    override fun isAutoOnEnabled(): Boolean {
        checker.enforcePrivileged()
        return runOnServerThread { api.isAutoOnEnabled() }
    }

    override fun setAutoOnEnabled(status: Boolean) {
        checker.enforcePrivileged()
        return runOnServerThread { api.setAutoOnEnabled(status) }
    }

    override fun handleShellCommand(
        `in`: ParcelFileDescriptor,
        out: ParcelFileDescriptor,
        err: ParcelFileDescriptor,
        args: Array<String>,
    ): Int {
        return ShellCommand(this, api::waitForState)
            .exec(this, `in`.fileDescriptor, out.fileDescriptor, err.fileDescriptor, args)
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<String?>?) {
        checker.enforceDump()
        runOnServerThread { api.dump(fd, writer, args) }
    }
}
