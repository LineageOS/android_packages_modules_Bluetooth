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
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

class BluetoothHidHostSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Register an HID Host callback with [callbackId]. */
    @AsyncRpc(description = "Register HID Host Callback.")
    fun registerHidHostCallback(callbackId: String) {
        val intentFilter =
            IntentFilter().apply { addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED) }
        broadcastReceivers[callbackId] =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device =
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    val state =
                        intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothDevice.ERROR)
                    when (intent.action) {
                        BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED ->
                            Utils.postSnippetEvent(
                                callbackId,
                                SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                    }
                }
            }
        context.registerReceiver(broadcastReceivers[callbackId], intentFilter)
    }

    /** Unregisters an HID Host callback with ID [callbackId]. */
    @Rpc(description = "Unregister HID Host callbacks.")
    fun unregisterHidHostCallback(callbackId: String) =
        broadcastReceivers.remove(callbackId)?.let { context.unregisterReceiver(it) }
}
