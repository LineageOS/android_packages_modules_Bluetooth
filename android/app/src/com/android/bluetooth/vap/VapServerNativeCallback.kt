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
package com.android.bluetooth.vap

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback
import com.android.internal.annotations.VisibleForTesting
import java.util.Objects
import java.util.function.Consumer

/** Voice Assistant Profile Server Native Callback (from native to Java). */
class VapServerNativeCallback(adapterService: AdapterService, vapServerService: VapServerService) :
    NativeCallback(adapterService) {
    private val mVapServerService: VapServerService
    private val mHandler: Handler

    init {
        mVapServerService = Objects.requireNonNull(vapServerService)
        mHandler = Handler(Looper.getMainLooper())
    }

    private fun sendMessageToService(action: Consumer<VapServerService>) {
        mHandler.post {
            if (!mVapServerService.isAvailable) {
                Log.e(TAG, "Action ignored, service not available.")
                return@post
            }
            action.accept(mVapServerService)
        }
    }

    fun onInitialized() {
        Log.d(TAG, "onInitialized")
        sendMessageToService { service: VapServerService -> service.onInitialized() }
    }

    @VisibleForTesting
    fun onStartVaSession(address: ByteArray) {
        val device = getDevice(address)
        Log.d(TAG, "onStartVaSession: device=$device")
        sendMessageToService { service: VapServerService -> service.onStartVaSession(device) }
    }

    @VisibleForTesting
    fun onStopVaSession(address: ByteArray) {
        val device = getDevice(address)
        Log.d(TAG, "onStopVaSession: device=$device")
        sendMessageToService { service: VapServerService -> service.onStopVaSession(device) }
    }

    companion object {
        private val TAG = VapServerNativeCallback::class.java.simpleName
    }
}
