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

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.annotation.RequiresPermission
import android.bluetooth.IBluetoothAdvertise
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.IAdvertisingSetCallback
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import android.content.Context
import com.android.bluetooth.Utils

private val TAG: String = AdvertiseBinder::class.java.simpleName

class AdvertiseBinder(
    private val context: Context,
    private val advertiseManager: AdvertiseManager,
) : IBluetoothAdvertise.Stub() {

    @Volatile private var isAvailable = true

    fun cleanup() {
        isAvailable = false
    }

    @RequiresPermission(BLUETOOTH_ADVERTISE)
    private fun getManager(source: AttributionSource): AdvertiseManager? {
        if (!isAvailable || !Utils.checkAdvertisePermissionForDataDelivery(context, source, TAG)) {
            return null
        }
        return advertiseManager
    }

    override fun startAdvertisingSet(
        parameters: AdvertisingSetParameters,
        advertiseData: AdvertiseData?,
        scanResponse: AdvertiseData?,
        periodicParameters: PeriodicAdvertisingParameters?,
        periodicData: AdvertiseData?,
        duration: Int,
        maxExtAdvEvents: Int,
        serverIf: Int,
        callback: IAdvertisingSetCallback,
        source: AttributionSource,
    ) {
        if (
            parameters.ownAddressType != AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT ||
                serverIf != 0 ||
                parameters.isDirected
        ) {
            context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        }

        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread {
                manager.startAdvertisingSet(
                    parameters,
                    advertiseData,
                    scanResponse,
                    periodicParameters,
                    periodicData,
                    duration,
                    maxExtAdvEvents,
                    serverIf,
                    callback,
                    source,
                )
            }
        }
    }

    override fun stopAdvertisingSet(callback: IAdvertisingSetCallback, source: AttributionSource) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread { manager.stopAdvertisingSet(callback) }
        }
    }

    override fun getOwnAddress(advertiserId: Int, source: AttributionSource) {
        context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread { manager.getOwnAddress(advertiserId) }
        }
    }

    override fun enableAdvertisingSet(
        advertiserId: Int,
        enable: Boolean,
        duration: Int,
        maxExtAdvEvents: Int,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread {
                manager.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents)
            }
        }
    }

    override fun setAdvertisingData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread { manager.setAdvertisingData(advertiserId, data) }
        }
    }

    override fun setScanResponseData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread { manager.setScanResponseData(advertiserId, data) }
        }
    }

    override fun setAdvertisingParameters(
        advertiserId: Int,
        parameters: AdvertisingSetParameters,
        source: AttributionSource,
    ) {
        if (
            parameters.ownAddressType != AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT ||
                parameters.isDirected
        ) {
            context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        }
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread {
                manager.setAdvertisingParameters(advertiserId, parameters)
            }
        }
    }

    override fun setPeriodicAdvertisingParameters(
        advertiserId: Int,
        parameters: PeriodicAdvertisingParameters?,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread {
                manager.setPeriodicAdvertisingParameters(advertiserId, parameters)
            }
        }
    }

    override fun setPeriodicAdvertisingData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread { manager.setPeriodicAdvertisingData(advertiserId, data) }
        }
    }

    override fun setPeriodicAdvertisingEnable(
        advertiserId: Int,
        enable: Boolean,
        source: AttributionSource,
    ) {
        getManager(source)?.let { manager ->
            manager.doOnAdvertiseThread {
                manager.setPeriodicAdvertisingEnable(advertiserId, enable)
            }
        }
    }
}
