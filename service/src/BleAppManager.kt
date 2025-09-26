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

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_DIED
import android.os.IBinder
import android.os.RemoteException

private const val TAG = "BleAppManager"

class BleAppManager(
    private val post: (Runnable) -> Unit,
    private val onBleAppRemoved: (Int, String) -> Unit,
) {
    private val bleApps = mutableMapOf<IBinder, ClientDeathRecipient>()

    private inner class ClientDeathRecipient(val packageName: String, val token: IBinder) :
        IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder is dead - posting the unregister of $packageName")
            post { removeBleApp(ENABLE_DISABLE_REASON_APPLICATION_DIED, token, packageName) }
        }

        override fun toString() = packageName
    }

    /** @return true if the app is now being monitored or was already monitored, false otherwise */
    fun addBleApp(token: IBinder, packageName: String): Boolean {
        val header = "addBleApp($token, $packageName):"
        if (bleApps.containsKey(token)) {
            Log.v(TAG, "$header Lifecycle is already monitored")
            return true
        }
        val deathRec = ClientDeathRecipient(packageName, token)
        try {
            token.linkToDeath(deathRec, 0)
        } catch (ex: RemoteException) {
            Log.e(TAG, "$header Already dead")
            return false
        }
        bleApps[token] = deathRec
        Log.v(TAG, "$header Monitoring lifecycle")
        return true
    }

    fun removeBleApp(reason: Int, token: IBinder, packageName: String) {
        val header = "removeBleApp($reason, $token, $packageName):"
        val removedApp = bleApps[token]
        if (removedApp == null) {
            Log.v(TAG, "$header Lifecycle is already un-monitored")
            return
        }
        token.unlinkToDeath(removedApp, 0)
        bleApps.remove(token)
        Log.d(TAG, "$header Lifecycle no longer monitored")
        if (isBleAppPresent()) {
            Log.v(TAG, "$header other BLE apps are preventing an eventual shutdown $this")
            return
        }
        onBleAppRemoved(reason, packageName)
    }

    fun clearBleApps() {
        bleApps.forEach { (token, recipient) -> token.unlinkToDeath(recipient, 0) }
        bleApps.clear()
    }

    fun isBleAppPresent() = bleApps.isNotEmpty()

    override fun toString() = "{" + bleApps.values.joinToString(", ") + "}"
}
