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

class BluetoothService(context: Context) : SystemService(context) {
    private val looper = HandlerThread("BluetoothSystemServer").apply { start() }.looper
    private val serviceDispatcher = Handler(looper).asCoroutineDispatcher()
    private val scope = CoroutineScope(serviceDispatcher + SupervisorJob())

    private val mBluetoothManagerService: BluetoothManagerService
    private val serviceApi: BluetoothManagerServiceApi
    private var mInitialized = false

    init {
        Log.d("Booting now")
        val hciInstance =
            if (Flags.hciInstanceNameUseInjected()) {
                BluetoothHciInstance().getInstance()
            } else {
                "default"
            }

        val bluetoothComponent =
            if (Flags.userRestrictionRefactor()) {
                BluetoothComponent(context)
            } else {
                null
            }
        // Run BluetoothManagerService on the correct thread even during constructor
        mBluetoothManagerService =
            runBlocking(serviceDispatcher) {
                BluetoothManagerService(context, looper, hciInstance, bluetoothComponent)
            }
        serviceApi = mBluetoothManagerService.api
        runOnBmsThread {
            if (Flags.userRestrictionRefactor()) {
                BluetoothRestriction.initialize(context, looper, serviceApi::onBluetoothDisallowed)
            }
        }
    }

    // Run any lambda on the BluetoothSystemServer thread without waiting for its completion
    private fun runOnBmsThread(block: suspend CoroutineScope.() -> Unit) = scope.launch { block() }

    private fun initialize(user: TargetUser) {
        if (!mInitialized) {
            Log.i("initialize($user)")
            runOnBmsThread { serviceApi.handleOnBootPhase(user.userHandle) }
            mInitialized = true
        }
    }

    override fun onStart() {
        publishBinderService(SERVICE_NAME, mBluetoothManagerService.binder)
    }

    override fun onUserStarting(user: TargetUser) {
        if (mInitialized) {
            Log.i("onUserStarting($user) but already initialized")
            return
        }
        val isForeground =
            context
                .createContextAsUser(user.userHandle, 0)
                .getSystemService(android.os.UserManager::class.java)!!
                .isUserForeground
        if (!isForeground) {
            Log.i("onUserStarting($user) Skipping non foreground user ")
            return
        }
        Log.i("onUserStarting($user) Initializing for foreground user ")
        runOnBmsThread { serviceApi.handleOnBootPhase(user.userHandle) }
        mInitialized = true
    }

    override fun onUserSwitching(_from: TargetUser?, to: TargetUser) {
        Log.d("onUserSwitching($to)")
        if (!mInitialized) {
            throw IllegalStateException("Initialize did not happen")
        }
        runOnBmsThread { serviceApi.onUserSwitching(to.userHandle) }
    }
}
