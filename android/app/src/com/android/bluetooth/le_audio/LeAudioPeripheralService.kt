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
import android.os.RemoteCallbackList
import android.os.SystemProperties
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.profile.ProfileService
import com.android.bluetooth.profile.ProfileService.IProfileServiceBinder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.jvm.JvmOverloads

/**
 * Provides Bluetooth LE Audio Peripheral services to the system.
 *
 * This service object is a lightweight integrator that owns and manages the lifecycle of the core
 * LE Audio peripheral components:
 * - [PeripheralPolicyManager]: The "brain" that manages device state and streaming policy.
 * - [AudioProxy]: The hardware abstraction that interfaces with the Android Audio framework.
 *
 * Its primary role is to plumb JNI callbacks and binder requests to the appropriate components.
 */
class LeAudioPeripheralService
@JvmOverloads
constructor(
    adapterService: AdapterService,
    looper: Looper = Looper.getMainLooper(),
    injectedNativeInterface: LeAudioPeripheralNativeInterface? = null,
) : ProfileService(BluetoothProfile.LE_AUDIO_PERIPHERAL, adapterService) {

    private var audioProxy: AudioProxy
    private var policyManager: PeripheralPolicyManager
    private val handler: Handler = Handler(looper)
    private val nativeInterface: LeAudioPeripheralNativeInterface

    private val leAudioPeripheralCallbacks:
        RemoteCallbackList<IBluetoothLeAudioPeripheralCallback> =
        RemoteCallbackList()

    var leAudioPeripheralNativeIsInitialized = false

    init {
        Log.d(TAG, "init()")
        nativeInterface =
            injectedNativeInterface
                ?: LeAudioPeripheralNativeInterface(
                    LeAudioPeripheralNativeCallback(adapterService, this)
                )
        policyManager = PeripheralPolicyManager(this, nativeInterface, handler)
        audioProxy = AudioProxy(this, handler, adapterService, policyManager)
        policyManager.setAudioProxy(audioProxy)
        audioProxy.init()
        nativeInterface.init()
    }

    override fun cleanup() {
        Log.d(TAG, "cleanup()")
        handler.removeCallbacksAndMessages(null)
        audioProxy.cleanup()
        policyManager.cleanup()
        nativeInterface.cleanup()
    }

    override fun initBinder(): IProfileServiceBinder {
        return LeAudioPeripheralServiceBinder(this)
    }

    fun post(consumer: (LeAudioPeripheralService) -> Unit) {
        handler.post {
            if (!isAvailable) {
                Log.e(TAG, "Service is no longer available")
                return@post
            }
            consumer.invoke(this)
        }
    }

    fun <T> syncPost(function: (LeAudioPeripheralService) -> T, defaultValue: T): T {
        Utils.enforceMainLooperIsNotUsed()

        val task = FutureTask {
            if (!isAvailable) {
                Log.e(TAG, "Service is no longer available")
                return@FutureTask defaultValue
            }
            function.invoke(this)
        }
        handler.post(task)
        try {
            return task.get(1, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: ExecutionException) {
            throw e.cause!!
        }
    }

    fun getActiveDevices(): List<BluetoothDevice> {
        return listOfNotNull(policyManager.activeSinkDevice, policyManager.activeSourceDevice)
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
        leAudioPeripheralCallbacks.register(callback)
    }

    fun unregisterCallback(callback: IBluetoothLeAudioPeripheralCallback) {
        leAudioPeripheralCallbacks.unregister(callback)
    }

    fun setStreamTypesEnabled(device: BluetoothDevice, streamTypes: Int, enabled: Boolean) {
        policyManager.setStreamTypesEnabled(device, streamTypes, enabled)
    }

    fun getEnabledStreamTypes(device: BluetoothDevice): Int {
        return policyManager.getEnabledStreamTypes(device)
    }

    fun handleNativeEventNativeInitialized() {
        leAudioPeripheralNativeIsInitialized = true
    }

    fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            LeAudioPeripheralNativeCallback.CONNECTION_STATE_CONNECTED -> {
                policyManager.handleDeviceConnected(device)
            }

            LeAudioPeripheralNativeCallback.CONNECTION_STATE_DISCONNECTED -> {
                policyManager.handleDeviceDisconnected(device)
            }
        }
    }

    fun updateActiveSinkDevice(newDevice: BluetoothDevice?, previousDevice: BluetoothDevice?) {
        audioProxy.updateActiveAudioDevice(newDevice, previousDevice, StreamDirection.SINK)
    }

    fun updateActiveSourceDevice(newDevice: BluetoothDevice?, previousDevice: BluetoothDevice?) {
        audioProxy.updateActiveAudioDevice(newDevice, previousDevice, StreamDirection.SOURCE)
    }

    fun onStreamEnableRequest(device: BluetoothDevice, requests: List<StreamStartRequestInfo>) {
        policyManager.onStreamEnableRequest(device, requests)
    }

    fun onStreamStarted(device: BluetoothDevice, streamId: Int, audioContextType: Int) {
        policyManager.onStreamStarted(device, streamId, audioContextType)
    }

    fun onStreamMetadataUpdated(device: BluetoothDevice, streamId: Int, audioContextType: Int) {
        policyManager.onStreamMetadataUpdated(device, streamId, audioContextType)
    }

    fun onSinkStreamReady(device: BluetoothDevice) {
        audioProxy.onSinkStreamReady(device)
    }

    fun onSourceStreamReady(device: BluetoothDevice) {
        audioProxy.onSourceStreamReady(device)
    }

    fun onStreamStopped(device: BluetoothDevice, streamId: Int) {
        policyManager.onStreamStopped(device, streamId)
        audioProxy.onStreamStopped(device, streamId)
    }

    fun setAudioProxyForTesting(proxy: AudioProxy) {
        audioProxy = proxy
    }

    fun setPolicyManagerForTesting(manager: PeripheralPolicyManager) {
        policyManager = manager
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

            return Flags.leaudioPeripheralFeature() && (isTmapCtEnabled || isTmapUmrEnabled)
        }
    }
}
