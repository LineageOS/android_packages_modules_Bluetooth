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

package com.android.bluetooth.gatt

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.IDistanceMeasurement
import android.bluetooth.le.ChannelSoundingParams
import android.bluetooth.le.DistanceMeasurementMethod
import android.bluetooth.le.DistanceMeasurementParams
import android.bluetooth.le.IDistanceMeasurementCallback
import android.content.AttributionSource
import android.content.Context
import android.os.ParcelUuid
import com.android.bluetooth.Util
import com.android.bluetooth.Util.callerIsSystemOrActiveOrManagedUser
import com.android.bluetooth.Util.checkProfileAvailable

private const val TAG = GattUtil.TAG_PREFIX + "DistanceMeasurementBinder"

class DistanceMeasurementBinder(
    private val context: Context,
    private var gattService: GattService?,
    private var distanceMeasurementManager: DistanceMeasurementManager?,
) : IDistanceMeasurement.Stub() {

    fun cleanup() {
        gattService = null
        distanceMeasurementManager = null
    }

    private fun getManager(): DistanceMeasurementManager? {
        val gatt = gattService ?: return null
        val manager = distanceMeasurementManager ?: return null
        if (!gatt.checkProfileAvailable(TAG)) return null
        return manager
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    private fun getManagerEnforceConnectAndPrivileged(
        source: AttributionSource,
        method: String,
    ): DistanceMeasurementManager? {
        val manager = getManager()
        if (
            !context.callerIsSystemOrActiveOrManagedUser(TAG, "$TAG $method") ||
                !Util.enforceConnectPermissionForDataDelivery(context, source, "$TAG $method")
        ) {
            return null
        }
        context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        return manager
    }

    override fun getSupportedDistanceMeasurementMethods(
        source: AttributionSource
    ): List<DistanceMeasurementMethod> {
        val method = "getSupportedDistanceMeasurementMethods"
        val manager = getManagerEnforceConnectAndPrivileged(source, method) ?: return emptyList()
        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.getSupportedDistanceMeasurementMethods()
        }
    }

    override fun startDistanceMeasurement(
        uuid: ParcelUuid,
        distanceMeasurementParams: DistanceMeasurementParams,
        callback: IDistanceMeasurementCallback,
        source: AttributionSource,
    ) {
        val method = "startDistanceMeasurement"
        val manager = getManagerEnforceConnectAndPrivileged(source, method) ?: return
        manager.postOnDistanceMeasurementThread {
            manager.startDistanceMeasurement(
                uuid.uuid,
                source.uid,
                distanceMeasurementParams,
                callback,
            )
        }
    }

    override fun stopDistanceMeasurement(
        uuid: ParcelUuid,
        device: BluetoothDevice,
        method: Int,
        source: AttributionSource,
    ): Int {
        val manager = getManager() ?: return BluetoothStatusCodes.ERROR_UNKNOWN

        val methodName = "stopDistanceMeasurement"
        if (!context.callerIsSystemOrActiveOrManagedUser(TAG, methodName)) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED
        } else if (
            !Util.enforceConnectPermissionForDataDelivery(context, source, "$TAG $methodName")
        ) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)

        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.stopDistanceMeasurement(uuid.uuid, device, method, false)
        } ?: BluetoothStatusCodes.ERROR_UNKNOWN
    }

    override fun getChannelSoundingMaxSupportedSecurityLevel(
        remoteDevice: BluetoothDevice,
        source: AttributionSource,
    ): Int {
        val method = "getChannelSoundingMaxSupportedSecurityLevel"
        val manager =
            getManagerEnforceConnectAndPrivileged(source, method)
                ?: return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN

        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.getChannelSoundingMaxSupportedSecurityLevel(remoteDevice)
        } ?: ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN
    }

    override fun getLocalChannelSoundingMaxSupportedSecurityLevel(source: AttributionSource): Int {
        val method = "getLocalChannelSoundingMaxSupportedSecurityLevel"
        val manager =
            getManagerEnforceConnectAndPrivileged(source, method)
                ?: return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN

        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.getLocalChannelSoundingMaxSupportedSecurityLevel()
        } ?: ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN
    }

    override fun getChannelSoundingSupportedSecurityLevels(source: AttributionSource): IntArray {
        val method = "getChannelSoundingSupportedSecurityLevels"
        val manager = getManagerEnforceConnectAndPrivileged(source, method) ?: return IntArray(0)

        val result =
            manager.runOnDistanceMeasurementThreadAndWaitForResult {
                manager.getChannelSoundingSupportedSecurityLevels()
            } ?: return IntArray(0)

        return result.stream().mapToInt { i -> i }.toArray()
    }
}
