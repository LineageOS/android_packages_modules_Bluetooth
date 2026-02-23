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

package com.android.bluetooth.btservice

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display

private const val TAG = "DisplayListener"

class DisplayListener(
    private val adapterService: AdapterService,
    looper: Looper,
    private val powerManager: PowerManager,
) : DisplayManager.DisplayListener {
    val displayManager = adapterService.getSystemService(DisplayManager::class.java)
    val handler = Handler(looper)

    init {
        displayManager.registerDisplayListener(this, handler)
    }

    fun close() {
        displayManager.unregisterDisplayListener(this)
        handler.removeMessages(0)
    }

    override fun onDisplayAdded(displayId: Int) {} // Nothing to do

    override fun onDisplayRemoved(displayId: Int) {} // Nothing to do

    override fun onDisplayChanged(displayId: Int) {
        val isScreenOn = isScreenOn()
        val isInteractive = powerManager.isInteractive()
        Log.d(TAG, "Display=$displayId isScreenOn=$isScreenOn isInteractive=$isInteractive")

        val scanController = adapterService.getBluetoothScanController()
        scanController?.doOnScanThread { scanController.onDisplayChanged(isScreenOn) }

        if (isInteractive == isScreenOn) {
            adapterService.adapterSuspend.orElse(null)?.onDisplayChanged(isScreenOn)
        }
    }

    fun isScreenOn() = displayManager.displays.any { it.state == Display.STATE_ON }
}
