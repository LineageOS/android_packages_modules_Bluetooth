/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.le_scan

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.android.bluetooth.flags.Flags

private const val TAG = ScanUtil.TAG_PREFIX + "ScanSuspendManager"

/** Class that handles Bluetooth LE scan related operations when the system suspends. */
internal class ScanSuspendManager(
    private val scanController: ScanController,
    private val scanManager: ScanManager,
    looper: Looper,
) {
    private val clientHandler =
        if (!Flags.scanControllerThread()) {
            ClientHandler(looper)
        } else {
            null
        }

    @get:JvmName("isSystemSuspended") var systemSuspended = false

    private fun sendMessage(what: Int) {
        if (Flags.scanControllerThread()) {
            throw IllegalStateException(
                "sendMessage using `clientHandler` should not be called on scan thread"
            )
        }
        clientHandler?.obtainMessage(what)?.sendToTarget()
    }

    fun onSystemSuspendChanged(suspended: Boolean) {
        if (Flags.scanControllerThread()) {
            if (suspended) handleSystemSuspend() else handleSystemResume()
        } else {
            sendMessage(if (suspended) MSG_SYSTEM_SUSPEND else MSG_SYSTEM_RESUME)
        }
    }

    private fun handleSystemSuspend() {
        if (systemSuspended) {
            return
        }
        systemSuspended = true
        Log.d(TAG, "handleSystemSuspend()")
        handleSuspendAllScans()
    }

    private fun handleSystemResume() {
        Log.d(TAG, "handleSystemResume(): Scan will be resumed when screen is on")
        systemSuspended = false
    }

    private fun handleSuspendAllScans() {
        fun suspendScan(client: ScanClient) {
            client.appScanStats?.recordScanSuspend(client.scannerId)
            Log.d(TAG, "Suspend scan for $client")
            scanManager.stopScan(client.scannerId)
            scanManager.suspendedScanQueue.add(client)
        }
        scanManager.regularScanQueue.forEach(::suspendScan)
        scanManager.batchScanQueue.forEach(::suspendScan)
    }

    private inner class ClientHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SYSTEM_SUSPEND -> handleSystemSuspendClientHandlerImpl()
                MSG_SYSTEM_RESUME -> handleSystemResumeClientHandlerImpl()
                else -> Log.e(TAG, "Received an unknown message=${msg.what}")
            }
        }

        fun handleSystemSuspendClientHandlerImpl() = handleSystemSuspend()

        fun handleSystemResumeClientHandlerImpl() = handleSystemResume()
    }

    companion object {
        const val MSG_SYSTEM_SUSPEND = 1
        const val MSG_SYSTEM_RESUME = 2
    }
}
