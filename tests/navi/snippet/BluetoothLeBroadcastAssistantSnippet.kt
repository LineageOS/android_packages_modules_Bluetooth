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
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothLeBroadcastReceiveState
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata
import com.google.android.bluetooth.snippet.BluetoothLeBroadcastMetadataExt.toQrCodeString
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.bluetooth.snippet.Utils.toBluetoothStatusDescription
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.runBlocking

class BluetoothLeBroadcastAssistantSnippet : Snippet {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bassProxy =
        Utils.getProfileProxy(context, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)
            as BluetoothLeBroadcastAssistant
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var callbacks =
        mutableMapOf<String, Pair<BluetoothLeBroadcastAssistant.Callback, BroadcastReceiver>>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    private interface BaseCallback : BluetoothLeBroadcastAssistant.Callback {
        override fun onSearchStarted(reason: Int) = Unit

        override fun onSearchStartFailed(reason: Int) = Unit

        override fun onSearchStopped(reason: Int) = Unit

        override fun onSearchStopFailed(reason: Int) = Unit

        override fun onSourceFound(source: BluetoothLeBroadcastMetadata) = Unit

        override fun onSourceAdded(sink: BluetoothDevice, sourceId: Int, reason: Int) = Unit

        override fun onSourceAddFailed(
            sink: BluetoothDevice,
            source: BluetoothLeBroadcastMetadata,
            reason: Int,
        ) = Unit

        override fun onSourceModified(sink: BluetoothDevice, sourceId: Int, reason: Int) = Unit

        override fun onSourceModifyFailed(sink: BluetoothDevice, sourceId: Int, reason: Int) = Unit

        override fun onSourceRemoved(sink: BluetoothDevice, sourceId: Int, reason: Int) = Unit

        override fun onSourceRemoveFailed(sink: BluetoothDevice, sourceId: Int, reason: Int) = Unit

        override fun onReceiveStateChanged(
            sink: BluetoothDevice,
            sourceId: Int,
            state: BluetoothLeBroadcastReceiveState,
        ) = Unit
    }

    private class BassEventCallback(private val callbackId: String) : BaseCallback {

        override fun onSourceFound(source: BluetoothLeBroadcastMetadata) {
            postSnippetEvent(callbackId, SnippetConstants.BASS_SOURCE_FOUND) {
                putString(SnippetConstants.FIELD_SOURCE, source.toQrCodeString())
            }
        }

        override fun onReceiveStateChanged(
            sink: BluetoothDevice,
            sourceId: Int,
            state: BluetoothLeBroadcastReceiveState,
        ) {
            Log.d(TAG, "onReceiveStateChanged: $sink, $sourceId, $state")
            postSnippetEvent(callbackId, SnippetConstants.BASS_RECEIVE_STATE_CHANGED) {
                putString(SnippetConstants.FIELD_SINK_DEVICE, sink.address)
                putInt(SnippetConstants.FIELD_SOURCE_ID, sourceId)
                putString(SnippetConstants.FIELD_DEVICE, state.sourceDevice.address)
                putInt(SnippetConstants.FIELD_BROADCAST_ID, state.broadcastId)
            }
        }

        override fun onSourceAdded(sink: BluetoothDevice, sourceId: Int, reason: Int) {
            Log.d(TAG, "onSourceAdded: $sink, $sourceId, $reason")
            postSnippetEvent(callbackId, SnippetConstants.BASS_SOURCE_ADDED) {
                putString(SnippetConstants.FIELD_SINK_DEVICE, sink.address)
                putInt(SnippetConstants.FIELD_SOURCE_ID, sourceId)
                putInt(SnippetConstants.FIELD_REASON, reason)
            }
        }

        override fun onSourceRemoved(sink: BluetoothDevice, sourceId: Int, reason: Int) {
            Log.d(TAG, "onSourceRemoved: $sink, $sourceId, $reason")
            postSnippetEvent(callbackId, SnippetConstants.BASS_SOURCE_REMOVED) {
                putString(SnippetConstants.FIELD_SINK_DEVICE, sink.address)
                putInt(SnippetConstants.FIELD_SOURCE_ID, sourceId)
                putInt(SnippetConstants.FIELD_REASON, reason)
            }
        }
    }

    /** Registers BASS profile callbacks with [callbackId]. */
    @AsyncRpc(description = "Register BASS profile callbacks")
    fun registerBassCallback(callbackId: String) {
        val callback = BassEventCallback(callbackId)
        bassProxy.registerCallback(context.mainExecutor, callback)
        val broadcastReceiver =
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
                        BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED ->
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
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED),
        )
        callbacks[callbackId] = Pair(callback, broadcastReceiver)
    }

    /** Unregisters BASS profile callbacks. */
    @Rpc(description = "Unregister BASS profile callbacks")
    fun unregisterBassCallback(callbackId: String) {
        callbacks.remove(callbackId)?.let { (callback, broadcastReceiver) ->
            bassProxy.unregisterCallback(callback)
            context.unregisterReceiver(broadcastReceiver)
        }
    }

    /** Starts searching for LE audio Broadcast sources. */
    @Rpc(description = "Start searching for LE audio Broadcast sources")
    fun bassStartSearching() {
        val flow = callbackFlow {
            val callback =
                object : BaseCallback {
                    override fun onSearchStarted(reason: Int) {
                        val unused = trySendBlocking(Pair(true, reason))
                    }

                    override fun onSearchStartFailed(reason: Int) {
                        val unused = trySendBlocking(Pair(false, reason))
                    }
                }
            bassProxy.registerCallback(context.mainExecutor, callback)
            bassProxy.startSearchingForSources(listOf())

            awaitClose { bassProxy.unregisterCallback(callback) }
        }
        val (success, reason) = runBlocking { flow.timeout(DEFAULT_OPERATION_TIMEOUT).first() }
        if (!success) {
            throw RuntimeException(
                "Failed to start broadcast, reason: ${reason.toBluetoothStatusDescription()}"
            )
        }
    }

    /** Stops searching for LE audio Broadcast sources. */
    @Rpc(description = "Stop searching for LE audio Broadcast sources")
    fun bassStopSearching() {
        val flow = callbackFlow {
            val callback =
                object : BaseCallback {
                    override fun onSearchStopped(reason: Int) {
                        val unused = trySendBlocking(Pair(true, reason))
                    }

                    override fun onSearchStopFailed(reason: Int) {
                        val unused = trySendBlocking(Pair(false, reason))
                    }
                }
            bassProxy.registerCallback(context.mainExecutor, callback)
            bassProxy.stopSearchingForSources()

            awaitClose { bassProxy.unregisterCallback(callback) }
        }
        val (success, reason) = runBlocking { flow.timeout(DEFAULT_OPERATION_TIMEOUT).first() }
        if (!success) {
            throw RuntimeException(
                "Failed to stop broadcast, reason: ${reason.toBluetoothStatusDescription()}"
            )
        }
    }

    /** Sets BASS connection policy of device [address] to [policy]. */
    @Rpc(description = "Set BASS connection policy.")
    fun setBassConnectionPolicy(address: String, policy: Int): Boolean =
        bassProxy.setConnectionPolicy(bluetoothAdapter.getRemoteDevice(address), policy)

    /** Adds BIS source present in [sourceMetadataString] on the [sinkAddress] device. */
    @Rpc(description = "Add BIS source on a sink device")
    fun bassAddSource(sinkAddress: String, sourceMetadataString: String): Int {
        val metadata = convertToBroadcastMetadata(sourceMetadataString)
        if (metadata == null) {
            throw RuntimeException("Failed to convert source metadata string to metadata")
        }
        val targetSinkDevice = bluetoothAdapter.getRemoteDevice(sinkAddress)

        val flow = callbackFlow {
            val callback =
                object : BaseCallback {
                    override fun onSourceAdded(sink: BluetoothDevice, sourceId: Int, reason: Int) {
                        if (sink != targetSinkDevice) return
                        val unused = trySendBlocking(Pair(sourceId, reason))
                    }

                    override fun onSourceAddFailed(
                        sink: BluetoothDevice,
                        source: BluetoothLeBroadcastMetadata,
                        reason: Int,
                    ) {
                        if (sink != targetSinkDevice) return
                        val unused = trySendBlocking(Pair(null, reason))
                    }
                }
            bassProxy.registerCallback(context.mainExecutor, callback)
            bassProxy.addSource(targetSinkDevice, metadata, true)

            awaitClose { bassProxy.unregisterCallback(callback) }
        }
        val (sourceId, reason) = runBlocking { flow.timeout(DEFAULT_OPERATION_TIMEOUT).first() }
        if (sourceId == null) {
            throw RuntimeException(
                "Failed to add source to sink device, reason: ${reason.toBluetoothStatusDescription()}"
            )
        }
        return sourceId
    }

    /** Gets all BIS source on the [sinkAddress] device. */
    @Rpc(description = "Get all BIS source on a sink device")
    fun bassGetAllSources(sinkAddress: String): List<Int> {
        val device = bluetoothAdapter.getRemoteDevice(sinkAddress)
        return bassProxy.getAllSources(device).map { it.sourceId }
    }

    /** Removes a BIS source with [sourceId] on the [sinkAddress] device. */
    @Rpc(description = "Remove a BIS source on a sink device")
    fun bassRemoveSource(sinkAddress: String, sourceId: Int) {
        val targetSinkDevice = bluetoothAdapter.getRemoteDevice(sinkAddress)
        val flow = callbackFlow {
            val callback =
                object : BaseCallback {
                    override fun onSourceRemoved(
                        sink: BluetoothDevice,
                        sourceId: Int,
                        reason: Int,
                    ) {
                        if (sink != targetSinkDevice) return
                        val unused = trySendBlocking(Pair(true, reason))
                    }

                    override fun onSourceRemoveFailed(
                        sink: BluetoothDevice,
                        sourceId: Int,
                        reason: Int,
                    ) {
                        if (sink != targetSinkDevice) return
                        val unused = trySendBlocking(Pair(false, reason))
                    }
                }
            bassProxy.registerCallback(context.mainExecutor, callback)
            bassProxy.removeSource(targetSinkDevice, sourceId)

            awaitClose { bassProxy.unregisterCallback(callback) }
        }
        val (success, reason) = runBlocking { flow.timeout(DEFAULT_OPERATION_TIMEOUT).first() }
        if (!success) {
            throw RuntimeException(
                "Failed to add source to sink device, reason: ${reason.toBluetoothStatusDescription()}"
            )
        }
    }

    /** Gets connected devices list. */
    @Rpc(description = "Get connected devices list")
    fun bassGetConnectedDevices(): List<String> = bassProxy.connectedDevices.map { it.address }

    private companion object {
        const val TAG = "BluetoothLeBroadcastAssistantSnippet"
        val DEFAULT_OPERATION_TIMEOUT = 10.seconds
    }
}
