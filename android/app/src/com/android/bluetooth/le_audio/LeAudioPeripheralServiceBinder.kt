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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.IBluetoothLeAudioPeripheral
import android.bluetooth.IBluetoothLeAudioPeripheralCallback
import android.content.AttributionSource
import com.android.bluetooth.Util
import com.android.bluetooth.Util.checkCallerIsSystemOrActiveOrManagedUser
import com.android.bluetooth.Util.checkProfileAvailable
import com.android.bluetooth.profile.ProfileService.IProfileServiceBinder

internal class LeAudioPeripheralServiceBinder(svc: LeAudioPeripheralService?) :
    IBluetoothLeAudioPeripheral.Stub(), IProfileServiceBinder {

    private var service: LeAudioPeripheralService? = svc

    override fun cleanup() {
        service = null
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    private fun getServiceAndEnforcePrivileged(
        source: AttributionSource
    ): LeAudioPeripheralService? {
        requireNotNull(source)
        val service = this.service
        if (Util.isInstrumentationTestMode) {
            return service
        }
        if (
            service == null ||
                !service.checkProfileAvailable(TAG) ||
                !service.checkCallerIsSystemOrActiveOrManagedUser(TAG) ||
                !Util.enforceConnectPermissionForDataDelivery(service, source, TAG)
        ) {
            return null
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        return service
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    private fun <T> withService(
        source: AttributionSource,
        defaultValue: T,
        action: (LeAudioPeripheralService) -> T,
    ): T {
        requireNotNull(source)
        val service = getServiceAndEnforcePrivileged(source)
        return service?.syncPost(action, defaultValue) ?: defaultValue
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    private fun withService(source: AttributionSource, action: (LeAudioPeripheralService) -> Unit) {
        withService(source, Unit, action)
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun getConnectedDevices(source: AttributionSource): List<BluetoothDevice> {
        return withService(source, defaultValue = emptyList()) { it.getConnectedDevices() }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun getDevicesMatchingConnectionStates(
        states: IntArray,
        source: AttributionSource,
    ): List<BluetoothDevice> {
        return withService(source, defaultValue = emptyList()) {
            it.getDevicesMatchingConnectionStates(states)
        }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun getConnectionState(device: BluetoothDevice, source: AttributionSource): Int {
        return withService(source, STATE_DISCONNECTED) { it.getConnectionState(device) }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun registerCallback(
        callback: IBluetoothLeAudioPeripheralCallback,
        source: AttributionSource,
    ) {
        withService(source) { it.registerCallback(callback) }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun unregisterCallback(
        callback: IBluetoothLeAudioPeripheralCallback,
        source: AttributionSource,
    ) {
        withService(source) { it.unregisterCallback(callback) }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun setStreamTypesEnabled(
        device: BluetoothDevice,
        streamTypes: Int,
        enabled: Boolean,
        source: AttributionSource,
    ) {
        withService(source) { it.setStreamTypesEnabled(device, streamTypes, enabled) }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    override fun getEnabledStreamTypes(device: BluetoothDevice, source: AttributionSource): Int {
        return withService(source, 0) { it.getEnabledStreamTypes(device) }
    }

    companion object {
        @JvmStatic private val TAG = LeAudioPeripheralServiceBinder::class.java.simpleName
    }
}
