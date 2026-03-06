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

package com.android.bluetooth.mcp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_audio.LeAudioPeripheralService
import com.android.bluetooth.media_audio.sink.MediaAudioServer
import com.android.bluetooth.profile.ConnectableProfile
import java.util.HashMap

/** Service for the Media Control Profile (MCP) Client. */
class McpClientService
@JvmOverloads
constructor(
    adapterService: AdapterService,
    looper: Looper = Looper.getMainLooper(),
    initNativeInterface: McpClientNativeInterface? = null,
) : ConnectableProfile(BluetoothProfile.MCP_CLIENT, adapterService) {

    private val handler: Handler = Handler(looper)
    private val nativeCallback = McpClientNativeCallback(adapterService, this)
    private var nativeInterface: McpClientNativeInterface =
        initNativeInterface ?: McpClientNativeInterface(nativeCallback)
    private val stateMachines = HashMap<BluetoothDevice, McpClientStateMachine>()
    // TODO(Flags.mediaAudioServer): Remove ? when flag is cleaned up
    private val mediaAudioServer: MediaAudioServer? =
        if (Flags.mediaAudioServer()) {
            requireNotNull(adapterService.mediaAudioServer.orElse(null))
        } else {
            null
        }

    init {
        Log.d(TAG, "init()")
        nativeInterface.init()
    }

    override fun cleanup() {
        Log.d(TAG, "cleanup()")
        nativeInterface.cleanup()
        synchronized(stateMachines) {
            for (sm in stateMachines.values) {
                sm.doQuit()
                sm.cleanup()
            }
            stateMachines.clear()
        }
    }

    override fun initBinder(): IProfileServiceBinder? {
        return null
    }

    override fun connect(device: BluetoothDevice): Boolean {
        Log.d(TAG, "connect(): $device")
        if (!okToConnect(device)) {
            return false
        }

        synchronized(stateMachines) {
            val sm = getOrCreateStateMachine(device)
            sm.sendMessage(McpClientStateMachine.MESSAGE_CONNECT)
        }
        return true
    }

    override fun disconnect(device: BluetoothDevice): Boolean {
        Log.d(TAG, "disconnect(): $device")
        synchronized(stateMachines) {
            val sm = stateMachines[device]
            if (sm == null) {
                Log.e(TAG, "Ignored disconnect request for $device : no state machine")
                return false
            }
            sm.sendMessage(McpClientStateMachine.MESSAGE_DISCONNECT)
        }
        return true
    }

    override fun getConnectionState(device: BluetoothDevice): Int {
        synchronized(stateMachines) {
            val sm = stateMachines[device] ?: return BluetoothProfile.STATE_DISCONNECTED
            return sm.getConnectionState()
        }
    }

    override fun setConnectionPolicy(device: BluetoothDevice, connectionPolicy: Int): Boolean {
        Log.d(TAG, "setConnectionPolicy: device=$device, policy=$connectionPolicy")
        adapterService.setProfileConnectionPolicy(
            device,
            BluetoothProfile.MCP_CLIENT,
            connectionPolicy,
        )
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device)
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device)
        }
        return true
    }

    private fun getOrCreateStateMachine(device: BluetoothDevice): McpClientStateMachine {
        synchronized(stateMachines) {
            var sm = stateMachines[device]
            if (sm != null) {
                return sm
            }
            Log.d(TAG, "Creating a new state machine for $device")
            sm =
                McpClientStateMachine(
                    this,
                    device,
                    nativeInterface,
                    mediaAudioServer,
                    handler.looper,
                )
            stateMachines[device] = sm
            return sm
        }
    }

    override fun handleBondStateChanged(device: BluetoothDevice, fromState: Int, toState: Int) {
        handler.post { bondStateChanged(device, toState) }
    }

    private fun bondStateChanged(device: BluetoothDevice, bondState: Int) {
        Log.d(TAG, "Bond state changed for device: $device state: $bondState")
        if (bondState != BluetoothDevice.BOND_NONE) {
            return
        }

        synchronized(stateMachines) {
            val sm = stateMachines[device] ?: return
            if (sm.getConnectionState() != BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnecting device because it was unbonded.")
                disconnect(device)
                return
            }
            removeStateMachine(device)
        }
    }

    private fun removeStateMachine(device: BluetoothDevice) {
        synchronized(stateMachines) {
            val sm = stateMachines.remove(device)
            if (sm == null) {
                Log.w(TAG, "removeStateMachine: device $device does not have a state machine")
                return
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: $device")
            sm.doQuit()
            sm.cleanup()
        }
    }

    fun connectionStateChanged(device: BluetoothDevice, fromState: Int, toState: Int) {
        if (toState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d(TAG, "$device disconnected. Remove state machine")
            removeStateMachine(device)
        }
    }

    fun post(consumer: (McpClientService) -> Unit) {
        handler.post {
            if (!isAvailable) {
                Log.e(TAG, "Service is no longer available")
                return@post
            }
            consumer.invoke(this)
        }
    }

    fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        synchronized(stateMachines) {
            val sm = stateMachines[device]
            if (sm == null) {
                if (state != BluetoothProfile.STATE_DISCONNECTED) {
                    val newSm = getOrCreateStateMachine(device)
                    newSm.sendMessage(McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state)
                }
            } else {
                sm.sendMessage(McpClientStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state)
            }
        }
    }

    fun messageFromNative(event: McpStackEvent) {
        Log.d(TAG, "messageFromNative: $event")
        val device = event.device
        if (device == null) {
            Log.e(TAG, "Event device is null: $event")
            return
        }

        synchronized(stateMachines) {
            val sm = stateMachines[device]
            if (sm == null) {
                Log.e(TAG, "messageFromNative: device $device does not have a state machine")
            } else {
                sm.sendMessage(McpClientStateMachine.MESSAGE_STACK_EVENT, event)
            }
        }
    }

    override fun dump(sb: StringBuilder) {
        super.dump(sb)
        synchronized(stateMachines) {
            for (sm in stateMachines.values) {
                sm.dump(sb)
            }
        }
    }

    companion object {
        private val TAG = McpClientService::class.java.simpleName

        @JvmStatic
        fun isEnabled(): Boolean {
            return Flags.leaudioPeripheralMcpLinkAbstractionLayer() &&
                LeAudioPeripheralService.isEnabled()
        }
    }
}
