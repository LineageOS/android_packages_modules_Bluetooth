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

import android.Manifest
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
import com.android.bluetooth.Utils
import kotlin.concurrent.Volatile

private val TAG: String = DistanceMeasurementBinder::class.java.simpleName

class DistanceMeasurementBinder(
    private val context: Context,
    private val distanceMeasurementManager: DistanceMeasurementManager,
) : IDistanceMeasurement.Stub() {

    @Volatile private var isAvailable = true

    fun cleanup() {
        isAvailable = false
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_PRIVILEGED]
    )
    private fun getManager(source: AttributionSource, method: String): DistanceMeasurementManager? {
        if (
            !isAvailable ||
                !Utils.callerIsSystemOrActiveOrManagedUser(
                    context,
                    TAG,
                    "DistanceMeasurement $method",
                ) ||
                !Utils.checkConnectPermissionForDataDelivery(
                    context,
                    source,
                    "DistanceMeasurement $method",
                )
        ) {
            return null
        }
        context.enforceCallingOrSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED, null)
        return distanceMeasurementManager
    }

    override fun getSupportedDistanceMeasurementMethods(
        source: AttributionSource
    ): List<DistanceMeasurementMethod> {
        val manager: DistanceMeasurementManager =
            getManager(source, "getSupportedDistanceMeasurementMethods") ?: return emptyList()

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
        val manager: DistanceMeasurementManager =
            getManager(source, "startDistanceMeasurement") ?: return

        manager.postOnDistanceMeasurementThread {
            manager.startDistanceMeasurement(uuid.uuid, distanceMeasurementParams, callback)
        }
    }

    override fun stopDistanceMeasurement(
        uuid: ParcelUuid,
        device: BluetoothDevice,
        method: Int,
        source: AttributionSource,
    ): Int {
        if (!isAvailable) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
        } else if (
            !Utils.callerIsSystemOrActiveOrManagedUser(context, TAG, "stopDistanceMeasurement")
        ) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED
        } else if (
            !Utils.checkConnectPermissionForDataDelivery(
                context,
                source,
                "DistanceMeasurement stopDistanceMeasurement",
            )
        ) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        context.enforceCallingOrSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED, null)

        return distanceMeasurementManager.runOnDistanceMeasurementThreadAndWaitForResult {
            distanceMeasurementManager.stopDistanceMeasurement(uuid.uuid, device, method, false)
        } ?: BluetoothStatusCodes.ERROR_UNKNOWN
    }

    override fun getChannelSoundingMaxSupportedSecurityLevel(
        remoteDevice: BluetoothDevice,
        source: AttributionSource,
    ): Int {
        val manager: DistanceMeasurementManager =
            getManager(source, "getChannelSoundingMaxSupportedSecurityLevel")
                ?: return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN

        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.getChannelSoundingMaxSupportedSecurityLevel(remoteDevice)
        } ?: ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN
    }

    override fun getLocalChannelSoundingMaxSupportedSecurityLevel(source: AttributionSource): Int {
        val manager: DistanceMeasurementManager =
            getManager(source, "getLocalChannelSoundingMaxSupportedSecurityLevel")
                ?: return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN

        return manager.runOnDistanceMeasurementThreadAndWaitForResult {
            manager.getLocalChannelSoundingMaxSupportedSecurityLevel()
        } ?: ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN
    }

    override fun getChannelSoundingSupportedSecurityLevels(source: AttributionSource): IntArray {
        val manager: DistanceMeasurementManager =
            getManager(source, "getChannelSoundingSupportedSecurityLevels") ?: return IntArray(0)

        val result =
            manager.runOnDistanceMeasurementThreadAndWaitForResult {
                manager.getChannelSoundingSupportedSecurityLevels()
            } ?: return IntArray(0)

        return result.stream().mapToInt { i -> i }.toArray()
    }
}
