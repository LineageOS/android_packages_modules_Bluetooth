/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

class BluetoothHidDeviceSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val proxy =
        Utils.getProfileProxy<BluetoothHidDevice>(context, BluetoothProfile.HID_DEVICE)

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Registers an HID Device App. */
    @AsyncRpc(description = "Registers an HID Device App.")
    fun registerHidDeviceApp(callbackId: String, sdpSettings: BluetoothHidDeviceAppSdpSettings) {
        val callback =
            object : BluetoothHidDevice.Callback() {
                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    Utils.postSnippetEvent(
                        callbackId,
                        SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                    ) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_STATE, state)
                    }
                }
            }
        if (!proxy.registerApp(sdpSettings, null, null, context.mainExecutor, callback)) {
            throw RuntimeException("Failed to register HID Device App.")
        }
    }

    /** Unregisters the HID Device App. */
    @Rpc(description = "Unregisters the HID Device App.")
    fun unregisterHidDeviceApp() {
        if (!proxy.unregisterApp()) {
            throw RuntimeException("Failed to unregister HID Device App.")
        }
    }

    /** Sends a report from the HID Device. */
    @Rpc(description = "Sends a report from the HID Device.")
    fun hidDeviceSendReport(address: String, id: Int, data: ByteArray): Boolean {
        return proxy.sendReport(bluetoothAdapter.getRemoteDevice(address), id, data)
    }

    /** Replies a report from the HID Device. */
    @Rpc(description = "Replies a report from the HID Device.")
    fun hidDeviceReplyReport(address: String, type: Int, id: Int, data: ByteArray): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return proxy.replyReport(device, type.toByte(), id.toByte(), data)
    }

    /** Reports an error from the HID Device. */
    @Rpc(description = "Reports an error from the HID Device.")
    fun hidDeviceReportError(address: String, error: Int): Boolean {
        return proxy.reportError(bluetoothAdapter.getRemoteDevice(address), error.toByte())
    }

    /** Connects to the HID host Device. */
    @Rpc(description = "Connects to the HID Device.")
    fun hidDeviceConnect(address: String): Boolean {
        return proxy.connect(bluetoothAdapter.getRemoteDevice(address))
    }

    /** Disconnects from the HID host Device. */
    @Rpc(description = "Disconnects from the HID Device.")
    fun hidDeviceDisconnect(address: String): Boolean {
        return proxy.disconnect(bluetoothAdapter.getRemoteDevice(address))
    }
}
