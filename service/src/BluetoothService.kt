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
import android.content.res.Resources
import android.os.HandlerThread
import android.os.UserManager
import android.provider.Settings
import com.android.bluetooth.flags.Flags
import com.android.server.SystemService
import com.android.server.SystemService.TargetUser

val SERVICE_NAME = "bluetooth_manager" // See BluetoothServiceManager.BLUETOOTH_MANAGER_SERVICE

class BluetoothService(context: Context) : SystemService(context) {
    private val mHandlerThread: HandlerThread
    private val mBluetoothManagerService: BluetoothManagerService
    private var mInitialized = false

    init {
        mHandlerThread = HandlerThread("BluetoothManagerService")
        mHandlerThread.start()
        mBluetoothManagerService = BluetoothManagerService(context, mHandlerThread.getLooper())
    }

    private fun initialize(user: TargetUser) {
        if (!mInitialized) {
            Log.i("initialize($user)")
            mBluetoothManagerService.handleOnBootPhase(user.userHandle)
            mInitialized = true
        }
    }

    override fun onStart() {
        if (!Flags.publishBinderOnStart()) {
            return
        }
        publishBinderService(SERVICE_NAME, mBluetoothManagerService.getBinder())
    }

    override fun onBootPhase(phase: Int) {
        if (Flags.publishBinderOnStart()) {
            return
        }
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            publishBinderService(SERVICE_NAME, mBluetoothManagerService.getBinder())
        }
    }

    private fun shouldInitializeBluetooth(): Boolean {
        // HSUM can be simulated on phone with:
        // adb shell cmd user set-system-user-mode-emulation headless
        // and it can be restored with:
        // adb shell cmd user set-system-user-mode-emulation default

        // Not HSUM, we can initialize Bluetooth on system user
        if (!UserManager.isHeadlessSystemUserMode()) {
            Log.i("shouldInitializeBluetooth() -> true: Not HSUM")
            return true
        }

        try {
            // In HSUM, refer to config_hsumBootStrategy to see if we can boot on system user for
            // provisioned device
            val r = Resources.getSystem()
            if (
                r.getInteger(r.getIdentifier("config_hsumBootStrategy", "integer", "android")) ==
                    1 &&
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.DEVICE_PROVISIONED,
                        0,
                    ) == 1
            ) {
                Log.i("shouldInitializeBluetooth() -> true: HSUM provisioned")
                return true
            }
        } catch (_e: Resources.NotFoundException) {
            // Config not found, assuming it's 0 so no need to initialize Bluetooth
        }

        Log.i("shouldInitializeBluetooth() -> false: HSUM")
        return false
    }

    override fun onUserStarting(user: TargetUser) {
        Log.d("onUserStarting($user)")
        if (shouldInitializeBluetooth()) {
            initialize(user)
        }
    }

    override fun onUserSwitching(_from: TargetUser?, to: TargetUser) {
        Log.d("onUserSwitching($to)")
        if (!mInitialized) {
            initialize(to)
        } else {
            mBluetoothManagerService.onSwitchUser(to.userHandle)
        }
    }

    override fun onUserUnlocking(user: TargetUser) {
        mBluetoothManagerService.handleOnUnlockUser(user.userHandle)
    }
}
