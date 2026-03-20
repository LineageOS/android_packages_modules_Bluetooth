/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.UserManager
import com.android.bluetooth.flags.Flags
import com.android.server.SystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// See BluetoothServiceManager.BLUETOOTH_MANAGER_SERVICE
private const val SERVICE_NAME = "bluetooth_manager"
private const val TAG = "BluetoothService"

@kotlin.time.ExperimentalTime
class BluetoothService(context: Context) : SystemService(context) {
    private val looper = HandlerThread("BluetoothSystemServer").apply { start() }.looper
    private val serviceDispatcher = Handler(looper).asCoroutineDispatcher()
    private val scope = CoroutineScope(serviceDispatcher + SupervisorJob())

    private var supervisor: BluetoothSupervisor

    init {
        Log.d(TAG, "Booting now")
        val bluetoothComponent = BluetoothComponent(context)
        // Run BluetoothManagerService on the correct thread even during constructor
        supervisor =
            runBlocking(serviceDispatcher) {
                if (Flags.systemServerMigrateBmsToKotlin()) {
                    BluetoothSupervisorNew(context, looper, bluetoothComponent)
                } else {
                    BluetoothSupervisorLegacy(context, looper, bluetoothComponent)
                }
            }

        launchOnServerThread {
            BluetoothRestriction.initialize(context, looper, supervisor::onRestrictionChange)
        }
    }

    // Run lambda on the BluetoothSystemServer thread without waiting for completion
    private fun launchOnServerThread(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        block()
    }

    override fun onStart() {
        publishBinderService(SERVICE_NAME, ServerBinder(looper, supervisor.api, context))
    }

    override fun onBootPhase(phase: Int) {
        if (phase != SystemService.PHASE_BOOT_COMPLETED) return
        launchOnServerThread { supervisor.onBootCompleted() }
    }

    override fun onUserStarting(user: TargetUser) {
        val isUserVisible =
            context
                .createContextAsUser(user.userHandle, 0)
                .getSystemService(UserManager::class.java)!!
                .isUserVisible
        if (!isUserVisible) {
            Log.i(TAG, "onUserStarting($user): Skipping non visible user")
            return
        }
        Log.i(TAG, "onUserStarting($user): Initializing for visible user")
        launchOnServerThread { supervisor.onUserStarting(user.userHandle) }
    }

    override fun onUserStopping(user: TargetUser) {
        if (!Flags.switchWhenCurrentUserStop()) {
            Log.i(TAG, "onUserStopping($user): Not implemented. Flag Disabled")
            return
        }
        Log.i(TAG, "onUserStopping($user)")
        launchOnServerThread { supervisor.onUserStopping(user.userHandle) }
    }

    override fun onUserStopped(user: TargetUser) {
        Log.i(TAG, "onUserStopped($user): Not implemented")
    }

    override fun onUserSwitching(from: TargetUser?, to: TargetUser) {
        Log.i(TAG, "onUserSwitching($from => $to)")
        launchOnServerThread { supervisor.onUserSwitching(to.userHandle) }
    }
}
