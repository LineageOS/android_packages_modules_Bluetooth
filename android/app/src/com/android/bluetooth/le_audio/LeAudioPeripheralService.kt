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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.IBluetoothLeAudioPeripheralCallback
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.profile.ProfileService
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.jvm.JvmOverloads

class LeAudioPeripheralService
@JvmOverloads
constructor(adapterService: AdapterService, looper: Looper = Looper.getMainLooper()) :
    ProfileService(BluetoothProfile.LE_AUDIO_PERIPHERAL, adapterService) {

    private val handler: Handler = Handler(looper)

    init {
        Log.d(TAG, "init()")
    }

    fun <T> syncPost(function: (LeAudioPeripheralService) -> T, defaultValue: T): T {
        Utils.enforceMainLooperIsNotUsed()

        val task = FutureTask {
            // Service can become unavailable while the message is being posted
            if (!isAvailable) {
                Log.e(TAG, "Service is no longer available")
                return@FutureTask defaultValue
            }
            function.invoke(this)
        }
        handler.post(task)
        try {
            // Any method calling postAndWait should most likely be done in under 1 seconds.
            return task.get(1, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: ExecutionException) {
            throw e.cause!!
        }
        return defaultValue
    }

    override fun initBinder(): IProfileServiceBinder {
        return LeAudioPeripheralServiceBinder(this)
    }

    override fun cleanup() {
        Log.d(TAG, "cleanup()")
    }

    fun getConnectedDevices(): List<BluetoothDevice> {
        return emptyList()
    }

    fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> {
        return emptyList()
    }

    fun getConnectionState(device: BluetoothDevice): Int {
        return BluetoothProfile.STATE_DISCONNECTED
    }

    fun registerCallback(callback: IBluetoothLeAudioPeripheralCallback) {
        Log.d(TAG, "registerCallback")
    }

    fun unregisterCallback(callback: IBluetoothLeAudioPeripheralCallback) {
        Log.d(TAG, "unregisterCallback")
    }

    fun setStreamTypesEnabled(device: BluetoothDevice, streamTypes: Int, enabled: Boolean) {
        Log.d(
            TAG,
            "setStreamTypesEnabled: device=$device, streamTypes=$streamTypes, enabled=$enabled",
        )
    }

    fun getEnabledStreamTypes(device: BluetoothDevice): Int {
        return 0
    }

    companion object {
        private val TAG = LeAudioPeripheralService::class.java.simpleName

        @JvmStatic
        fun isEnabled(): Boolean {
            val isTmapCtEnabled =
                SystemProperties.getBoolean("bluetooth.profile.tmap.call_terminal.enabled", false)
            val isTmapUmrEnabled =
                SystemProperties.getBoolean(
                    "bluetooth.profile.tmap.unicast_media_receiver.enabled",
                    false,
                )

            Log.d(
                TAG,
                "isTmapCtEnabled: " + isTmapCtEnabled + ", isTmapUmrEnabled: " + isTmapUmrEnabled,
            )
            return Flags.leaudioPeripheralFeature() && (isTmapCtEnabled || isTmapUmrEnabled)
        }
    }
}
