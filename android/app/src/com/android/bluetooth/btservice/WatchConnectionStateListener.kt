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
import android.companion.CompanionDeviceManager.OnAssociationsChangedListener
import android.os.Handler
import android.os.Looper
import com.android.bluetooth.Utils.isWatch
import com.android.bluetooth.Utils.remoteDeviceIsWatch

/**
 * On Watch device: This class will inform the SystemServer if a device is connected (of any type !)
 *
 * On any other device: This class will inform the SystemServer if a Watch is connected using either
 * the class of device, the metadata, or the CompanionDeviceManager associations
 */
class WatchConnectionStateListener(private val adapterService: AdapterService, looper: Looper) :
    OnAssociationsChangedListener {
    private val connectedDevices: MutableSet<BluetoothDevice> = mutableSetOf()
    private var watchDevicesAssociated: Set<BluetoothDevice> = setOf()
    private var watchStatus = false

    init {
        if (!isWatch(adapterService)) {
            val cdm = adapterService.getCompanionDeviceManager()
            cdm.addOnAssociationsChangedListener(Handler(looper)::post, this)
            onAssociationsChanged(cdm.getAllAssociations())
        }
    }

    private fun computeCurrentWatchStatus(): Boolean {
        if (isWatch(adapterService)) {
            return !connectedDevices.isEmpty()
        }
        return connectedDevices.any { element ->
            element in watchDevicesAssociated || remoteDeviceIsWatch(adapterService, element)
        }
    }

    private fun updateSystemServerIfNeeded() {
        val newWatchStatus = computeCurrentWatchStatus()
        if (newWatchStatus == watchStatus) {
            return
        }
        watchStatus = newWatchStatus
        adapterService.updateWatchConnection(watchStatus)
    }

    override fun onAssociationsChanged(associations: List<AssociationInfo>) {
        watchDevicesAssociated =
            associations
                .filter { info -> info.deviceProfile == DEVICE_PROFILE_WATCH }
                .mapNotNull { info -> info.associatedDevice?.bluetoothDevice }
                .toSet()
        updateSystemServerIfNeeded()
    }

    fun connectedDevice(device: BluetoothDevice) {
        connectedDevices.add(device)
        updateSystemServerIfNeeded()
    }

    fun disconnectedDevice(device: BluetoothDevice) {
        connectedDevices.remove(device)
        updateSystemServerIfNeeded()
    }
}
