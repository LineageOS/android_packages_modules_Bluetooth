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
import android.bluetooth.BluetoothHapClient
import android.bluetooth.BluetoothHapPresetInfo
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

/** Snippet class to adapt [BluetoothHapClient] APIs. */
class BluetoothHapClientSnippet : Snippet {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = bluetoothManager.adapter
    private val proxy =
        Utils.getProfileProxy(context, BluetoothProfile.HAP_CLIENT) as BluetoothHapClient
    private val callbacks =
        mutableMapOf<String, Pair<BluetoothHapClient.Callback, BroadcastReceiver>>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /* Register HAP client callbacks. */
    @AsyncRpc(description = "Register HAP client callbacks.")
    fun registerHapClientCallback(callbackId: String) {
        val callback =
            object : BluetoothHapClient.Callback {
                override fun onPresetInfoChanged(
                    device: BluetoothDevice,
                    presetInfo: List<BluetoothHapPresetInfo>,
                    reason: Int,
                ) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.PRESET_INFO_CHANGED) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                    }
                }

                override fun onPresetSelected(device: BluetoothDevice, index: Int, reason: Int) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.PRESET_SELECTED) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                        putBoolean(SnippetConstants.FIELD_STATUS, true)
                        putInt(SnippetConstants.FIELD_ID, index)
                    }
                }

                override fun onPresetSelectionFailed(device: BluetoothDevice, reason: Int) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.PRESET_SELECTED) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                        putBoolean(SnippetConstants.FIELD_STATUS, false)
                    }
                }

                override fun onPresetSelectionForGroupFailed(groudId: Int, reason: Int) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.PRESET_SELECTED) {
                        putInt(SnippetConstants.FIELD_DEVICE, groudId)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                        putBoolean(SnippetConstants.FIELD_STATUS, false)
                    }
                }

                override fun onSetPresetNameFailed(device: BluetoothDevice, reason: Int) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.SET_PRESET_NAME_FAILED) {
                        putString(SnippetConstants.FIELD_DEVICE, device.address)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                    }
                }

                override fun onSetPresetNameForGroupFailed(groudId: Int, reason: Int) {
                    Utils.postSnippetEvent(callbackId, SnippetConstants.SET_PRESET_NAME_FAILED) {
                        putInt(SnippetConstants.FIELD_DEVICE, groudId)
                        putInt(SnippetConstants.FIELD_REASON, reason)
                    }
                }
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device =
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    when (intent.action) {
                        BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED -> {
                            Utils.postSnippetEvent(
                                callbackId,
                                SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(
                                    SnippetConstants.FIELD_STATE,
                                    intent.getIntExtra(
                                        BluetoothProfile.EXTRA_STATE,
                                        BluetoothDevice.ERROR,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED)
            }
        context.registerReceiver(receiver, filter)
        proxy.registerCallback(context.mainExecutor, callback)
        callbacks[callbackId] = Pair(callback, receiver)
    }

    /* Unregister HAP client callbacks. */
    @Rpc(description = "Unregister HAP client callbacks.")
    fun unregisterHapClientCallback(callbackId: String) {
        callbacks.remove(callbackId)?.let { (callback, receiver) ->
            proxy.unregisterCallback(callback)
            context.unregisterReceiver(receiver)
        }
    }

    /* Get all HAP preset info. */
    @Rpc(description = "Get all HAP preset info.")
    fun getAllHapPresetInfo(address: String): Map<Int, String> =
        proxy.getAllPresetInfo(bluetoothAdapter.getRemoteDevice(address)).associate {
            it.index to it.name
        }

    /* Get active HAP preset index. */
    @Rpc(description = "Get active HAP preset index.")
    fun getActiveHapPresetIndex(address: String): Int =
        proxy.getActivePresetIndex(bluetoothAdapter.getRemoteDevice(address))

    /* Select HAP preset. */
    @Rpc(description = "Select HAP preset.")
    fun selectHapPreset(address: String, index: Int): Unit =
        proxy.selectPreset(bluetoothAdapter.getRemoteDevice(address), index)

    /* Select HAP preset for group. */
    @Rpc(description = "Select HAP preset for group.")
    fun selectHapPresetForGroup(groudId: Int, index: Int): Unit =
        proxy.selectPresetForGroup(groudId, index)

    /* Get HAP group ID of the device. */
    @Rpc(description = "Get HAP group ID of the device.")
    fun getHapGroup(address: String): Int =
        proxy.getHapGroup(bluetoothAdapter.getRemoteDevice(address))

    /* Set HAP connection policy. */
    @Rpc(description = "Set HAP connection policy.")
    fun setHapConnectionPolicy(address: String, policy: Int): Boolean =
        proxy.setConnectionPolicy(bluetoothAdapter.getRemoteDevice(address), policy)
}
