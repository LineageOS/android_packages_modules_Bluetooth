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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothUuid
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.le_audio.ContentControlIdKeeper
import com.android.bluetooth.profile.ProfileService
import com.android.internal.annotations.VisibleForTesting
import java.util.Objects

/** Provides Bluetooth Voice Assistant profile, as a service. */
class VapServerService
@JvmOverloads
constructor(
    adapterService: AdapterService,
    looper: Looper? = null,
    nativeInterface: VapServerNativeInterface? = null,
) : ProfileService(BluetoothProfile.VAP_SERVER, adapterService) {
    private val mAssistantSettingObserver: AssistantSettingObserver
    private val mHandler: Handler
    private val mNativeInterface: VapServerNativeInterface

    init {
        mNativeInterface =
            nativeInterface
                ?: VapServerNativeInterface(VapServerNativeCallback(adapterService, this))
        Log.d(TAG, " VapServerService(): service is starting")
        mHandler = Handler(looper ?: Objects.requireNonNull(Looper.getMainLooper()))

        // Initialize native interface
        mNativeInterface.init()
        mAssistantSettingObserver = AssistantSettingObserver()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("assistant"),
            false,
            mAssistantSettingObserver,
        )
    }

    override fun initBinder(): IProfileServiceBinder? {
        return null
    }

    override fun cleanup() {
        Log.i(TAG, "Cleanup VapServer Service")

        // Unregister Handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null)

        // Cleanup GATT interface
        mNativeInterface.cleanup()
        contentResolver.unregisterContentObserver(mAssistantSettingObserver)
    }

    private inner class AssistantSettingObserver : ContentObserver(mHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            val vaName = currentVaName
            Log.d(TAG, " Voice Assistant changed")
            mNativeInterface.setVaName(vaName)
        }
    }

    /** Process a change in the bonding state for a device */
    fun handleBondStateChanged(device: BluetoothDevice, fromState: Int, toState: Int) {
        mHandler.post { bondStateChanged(device, toState) }
    }

    @VisibleForTesting
    fun bondStateChanged(device: BluetoothDevice, bondState: Int) {
        Log.d(TAG, "Bond state changed for device: $device state: $bondState")
        if (bondState != BluetoothDevice.BOND_NONE) {
            return
        }
    }

    override fun dump(sb: StringBuilder) {
        super.dump(sb)
    }

    fun setCcid() {
        val ccid =
            ContentControlIdKeeper.acquireCcid(
                adapterService,
                BluetoothUuid.VAP,
                BluetoothLeAudio.CONTEXT_TYPE_VOICE_ASSISTANTS,
            )
        if (ccid == ContentControlIdKeeper.CCID_INVALID) {
            Log.e(TAG, "Unable to acquire valid CCID!")
            return
        }
        Log.d(TAG, "CCID acquired: $ccid")
        mNativeInterface.setCcid(ccid)
    }

    fun setVaName() {
        val vaName = currentVaName
        mNativeInterface.setVaName(vaName)
    }

    val currentVaName: String?
        get() {
            // Get Default Digital Assistant from Settings
            val assistantName =
                Settings.Secure.getString(applicationContext.contentResolver, "assistant")
            if (TextUtils.isEmpty(assistantName)) {
                return null
            }
            var vaName = assistantName
            val parts =
                assistantName.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size == 2) {
                vaName = parts[0]
            }
            Log.d(TAG, " va Name:$vaName")
            return vaName
        }

    fun activateVoiceRecognition(device: BluetoothDevice): Boolean {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        intent.putExtra(BluetoothProfile.EXTRA_PROFILE, BluetoothProfile.LE_AUDIO)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        Log.d(TAG, "activateVoiceRecognition: ")
        try {
            adapterService.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "activateVoiceRecognition, failed due to activity not found for $intent")
            return false
        }
        return true
    }

    fun deactivateVoiceRecognition(device: BluetoothDevice): Boolean {
        Log.d(TAG, "deactivateVoiceRecognition: ")
        val intent = Intent(Intent.ACTION_STOP_VOICE_COMMAND)
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        intent.putExtra(BluetoothProfile.EXTRA_PROFILE, BluetoothProfile.LE_AUDIO)
        adapterService.sendBroadcast(intent)
        return true
    }

    /** The profile is initialized. */
    fun onInitialized() {
        Log.d(TAG, "onInitialized")
        setCcid()
        Log.d(TAG, "Calling setVaName after initialization")
        setVaName()
    }

    /**
     * Called when Voice Assistant session is started from the remote device.
     *
     * @param device the remote device
     */
    fun onStartVaSession(device: BluetoothDevice) {
        Log.d(TAG, "start VA session by remote Headset:$device")
        if (!activateVoiceRecognition(device)) {
            Log.w(TAG, "start VA session by remote Headset: failed request from $device")
        }
    }

    /**
     * Called when Voice Assistant session is stopped from the remote device.
     *
     * @param device the remote device
     */
    fun onStopVaSession(device: BluetoothDevice) {
        Log.d(TAG, "stop VA session by remote Headset:$device")
        if (!deactivateVoiceRecognition(device)) {
            Log.w(TAG, "stop VA session by remote Headset: failed request from $device")
        }
    }

    companion object {
        private val TAG = VapServerService::class.java.simpleName

        @JvmStatic
        fun isEnabled(): Boolean {
            return SystemProperties.getBoolean("bluetooth.profile.vap.server.enabled", false)
        }
    }
}
