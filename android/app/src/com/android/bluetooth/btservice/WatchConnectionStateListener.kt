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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest.DEVICE_PROFILE_WATCH
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceManager.OnAssociationsChangedListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.bluetooth.Util.isWatch
import com.android.bluetooth.Util.remoteDeviceIsWatch

private const val TAG = "WatchConnectionStateListener"

/**
 * On Watch device: This class will inform the SystemServer if a device is connected (of any type !)
 *
 * On any other device: This class will inform the SystemServer if a Watch is connected using either
 * the class of device, the metadata, or the CompanionDeviceManager associations
 */
class WatchConnectionStateListener(private val adapterService: AdapterService, looper: Looper) :
    OnAssociationsChangedListener {

    // Map value is the bitmask of the connected transport
    private val connectedDevices: MutableMap<BluetoothDevice, Int> = mutableMapOf()
    private var associatedWatches: Set<BluetoothDevice> = setOf()
    private var currentWatchStatus = false

    init {
        if (!adapterService.isWatch()) {
            val cdm = adapterService.getSystemService(CompanionDeviceManager::class.java)
            cdm.addOnAssociationsChangedListener(Handler(looper)::post, this)
            onAssociationsChanged(cdm.allAssociations)
        }
    }

    private fun computeCurrentWatchStatus(): Boolean {
        if (adapterService.isWatch()) {
            return !connectedDevices.isEmpty()
        }
        return connectedDevices.keys.any { device ->
            device in associatedWatches || remoteDeviceIsWatch(adapterService, device)
        }
    }

    private fun updateSystemServerIfNeeded() {
        val log = "connectedDevices=$connectedDevices associatedWatches=$associatedWatches"
        val newWatchStatus = computeCurrentWatchStatus()
        if (newWatchStatus == currentWatchStatus) {
            Log.v(TAG, "Keeping watch status to $currentWatchStatus. $log")
            return
        }
        currentWatchStatus = newWatchStatus
        Log.i(TAG, "Updating watch status to $currentWatchStatus. $log")
        adapterService.updateWatchConnection(currentWatchStatus)
    }

    override fun onAssociationsChanged(associations: List<AssociationInfo>) {
        associatedWatches =
            associations
                .filter { info -> info.deviceProfile == DEVICE_PROFILE_WATCH }
                .mapNotNull { info -> info.associatedDevice?.bluetoothDevice }
                .toSet()
        updateSystemServerIfNeeded()
    }

    fun onDeviceConnected(device: BluetoothDevice, transport: Int) {
        connectedDevices.compute(device) { _, value -> (value ?: 0) or transport }
        updateSystemServerIfNeeded()
    }

    fun onDeviceDisconnected(device: BluetoothDevice, transport: Int) {
        connectedDevices.compute(device) { _, value ->
            val v = (value ?: 0) and (transport).inv()
            if (v != 0) v else null
        }
        updateSystemServerIfNeeded()
    }
}
