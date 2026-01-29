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
import android.bluetooth.BluetoothHeadsetClient
import android.bluetooth.BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

class BluetoothHfpHfSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val proxy =
        Utils.getProfileProxy(context, BluetoothProfile.HEADSET_CLIENT) as BluetoothHeadsetClient
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Register a HFP HF callback with ID [callbackId]. */
    @AsyncRpc(description = "Register HFP HF callbacks.")
    fun registerHfpHfCallback(callbackId: String) {
        val intentFilter =
            IntentFilter().apply {
                addAction(ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACTION_AUDIO_STATE_CHANGED)
            }
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
                        ACTION_CONNECTION_STATE_CHANGED ->
                            postSnippetEvent(
                                callbackId,
                                SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                        ACTION_AUDIO_STATE_CHANGED ->
                            postSnippetEvent(
                                callbackId,
                                SnippetConstants.HFP_HF_AUDIO_STATE_CHANGED,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                    }
                }
            }
        context.registerReceiver(broadcastReceivers[callbackId], intentFilter)
    }

    /** Unregister a HFP HF callback with ID [callbackId]. */
    @Rpc(description = "Unregister HFP HF callbacks.")
    fun unregisterHfpHfCallback(callbackId: String) =
        broadcastReceivers.remove(callbackId)?.let { context.unregisterReceiver(it) }

    /** Sets HFP HF connection policy of device [address] to [policy]. */
    @Rpc(description = "Set HFP HF connection policy.")
    fun setHfpHfConnectionPolicy(address: String, policy: Int): Boolean =
        proxy.setConnectionPolicy(bluetoothAdapter.getRemoteDevice(address), policy)

    /** Gets connected HFP devices list. */
    @Rpc(description = "Get connected HFP devices list")
    fun hfpHfGetConnectedDevices(): List<String> {
        return proxy.connectedDevices.map { it.address }.toList()
    }

    /** Sets the audio route enabled state to [allowed]. */
    @Rpc(description = "Allow audio to be routed via HFP")
    fun hfpHfSetAudioRouteAllowed(address: String, allowed: Boolean) {
        val device = bluetoothAdapter.getRemoteDevice(address)
        BluetoothHeadsetClient::class
            .java
            .getMethod("setAudioRouteAllowed", BluetoothDevice::class.java, Boolean::class.java)
            .invoke(proxy, device, allowed)
    }

    /** Gets whether audio is allowed via HFP. */
    @Rpc(description = "Get whether audio is allowed via HFP")
    fun hfpHfGetAudioRouteAllowed(address: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHeadsetClient::class
            .java
            .getMethod("getAudioRouteAllowed", BluetoothDevice::class.java)
            .invoke(proxy, device) as Boolean
    }

    companion object {
        const val TAG = "BluetoothHfpHfSnippet"
        // This is a hidden constant in BluetoothHeadsetClient.
        const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
    }
}
