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
import android.bluetooth.BluetoothHeadset
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

class BluetoothHfpAgSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val proxy = Utils.getProfileProxy(context, BluetoothProfile.HEADSET) as BluetoothHeadset
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Register a HFP AG callback with ID [callbackId]. */
    @AsyncRpc(description = "Register HFP AG callbacks.")
    fun registerHfpAgCallback(callbackId: String) {
        val intentFilter =
            IntentFilter().apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)
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
                        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                            postSnippetEvent(
                                callbackId,
                                SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                        }
                        BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                            postSnippetEvent(
                                callbackId,
                                SnippetConstants.HFP_AG_AUDIO_STATE_CHANGED,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                        }
                        BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED -> {
                            postSnippetEvent(callbackId, SnippetConstants.ACTIVE_DEVICE_CHANGED) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                            }
                        }
                    }
                }
            }
        context.registerReceiver(broadcastReceivers[callbackId], intentFilter)
    }

    /** Unregister a HFP AG callback with ID [callbackId]. */
    @Rpc(description = "Unregister HFP AG callbacks.")
    fun unregisterHfpAgCallback(callbackId: String) {
        broadcastReceivers.remove(callbackId)?.let { context.unregisterReceiver(it) }
    }

    /** Sets HFP AG connection policy of device [address] to [policy]. */
    @Rpc(description = "Set HFP AG connection policy.")
    fun setHfpAgConnectionPolicy(address: String, policy: Int): Boolean =
        proxy.setConnectionPolicy(bluetoothAdapter.getRemoteDevice(address), policy)

    /** Gets connected HFP devices list. */
    @Rpc(description = "Get connected HFP devices list")
    fun hfpAgGetConnectedDevices(): List<String> {
        return proxy.connectedDevices.map { it.address }.toList()
    }

    /** Sets the audio route enabled state to [allowed]. */
    @Rpc(description = "Allow audio to be routed to HFP")
    fun hfpAgSetAudioRouteAllowed(allowed: Boolean) {
        proxy.setAudioRouteAllowed(allowed)
    }

    /** Checks if audio is routed to HFP. */
    @Rpc(description = "Check if audio is routed to HFP")
    fun hfpAgGetAudioRouteAllowed(): Int {
        return proxy.audioRouteAllowed
    }

    /** Gets Inband ringing enabled state. */
    @Rpc(description = "Get whether inband ringtone is enabled")
    fun hfpAgGetInbandRingtoneEnabled(): Boolean {
        return BluetoothHeadset::class.java.getMethod("isInbandRingingEnabled").invoke(proxy)
            as Boolean
    }

    /** Starts voice recognition. */
    @Rpc(description = "Start voice recognition")
    fun hfpAgStartVoiceRecognition(address: String): Boolean =
        proxy.startVoiceRecognition(bluetoothAdapter.getRemoteDevice(address))

    /** Stops voice recognition. */
    @Rpc(description = "Stop voice recognition")
    fun hfpAgStopVoiceRecognition(address: String): Boolean =
        proxy.stopVoiceRecognition(bluetoothAdapter.getRemoteDevice(address))

    /** Gets SCO connection state. */
    @Rpc(description = "Get SCO connection state")
    fun hfpAgGetAudioState(address: String): Int =
        proxy.getAudioState(bluetoothAdapter.getRemoteDevice(address))

    /**
     * Sets HFP active device to device in [address]. If [address] is null, clears active device.
     */
    @Rpc(description = "Set HFP active device.")
    fun hfpAgSetActiveDevice(address: String?): Boolean {
        val device: BluetoothDevice? = address?.let { bluetoothAdapter.getRemoteDevice(it) }
        return BluetoothHeadset::class
            .java
            .getMethod("setActiveDevice", BluetoothDevice::class.java)
            .invoke(proxy, device) as Boolean
    }

    companion object {
        const val TAG = "BluetoothHfpAgSnippet"
    }
}
