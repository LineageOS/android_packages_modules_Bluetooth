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
import android.bluetooth.IBluetoothGattServerCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.IAdvertisingSetCallback
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import android.content.Context
import com.android.bluetooth.Util
import com.android.bluetooth.Util.checkProfileAvailable

private const val TAG = GattUtil.TAG_PREFIX + "AdvertiseBinder"

class AdvertiseBinder(
    private val context: Context,
    private var gattService: GattService?,
    private var advertiseManager: AdvertiseManager?,
) : IBluetoothAdvertise.Stub() {

    fun cleanup() {
        gattService = null
        advertiseManager = null
    }

    @RequiresPermission(BLUETOOTH_ADVERTISE)
    private fun withManagerRunOnAdvertiseThread(
        source: AttributionSource,
        block: AdvertiseManager.() -> Unit,
    ) {
        val gatt = gattService ?: return
        val manager = advertiseManager ?: return
        if (!gatt.checkProfileAvailable(TAG)) return
        if (!Util.enforceAdvertisePermissionForDataDelivery(gatt, source, TAG)) return
        manager.doOnAdvertiseThread { manager.block() }
    }

    override fun startAdvertisingSet(
        parameters: AdvertisingSetParameters,
        advertiseData: AdvertiseData?,
        scanResponse: AdvertiseData?,
        periodicParameters: PeriodicAdvertisingParameters?,
        periodicData: AdvertiseData?,
        duration: Int,
        maxExtAdvEvents: Int,
        gattServerCallback: IBluetoothGattServerCallback?,
        callback: IAdvertisingSetCallback,
        source: AttributionSource,
    ) {
        if (
            parameters.ownAddressType != AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT ||
                gattServerCallback != null ||
                parameters.isDirected
        ) {
            context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        }

        withManagerRunOnAdvertiseThread(source) {
            startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                gattServerCallback,
                callback,
                source,
            )
        }
    }

    override fun stopAdvertisingSet(callback: IAdvertisingSetCallback, source: AttributionSource) {
        withManagerRunOnAdvertiseThread(source) { stopAdvertisingSet(callback) }
    }

    override fun getOwnAddress(advertiserId: Int, source: AttributionSource) {
        context.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        withManagerRunOnAdvertiseThread(source) { getOwnAddress(advertiserId) }
    }

    override fun enableAdvertisingSet(
        advertiserId: Int,
        enable: Boolean,
        duration: Int,
        maxExtAdvEvents: Int,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) {
            enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents, source)
        }
    }

    override fun setAdvertisingData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) { setAdvertisingData(advertiserId, data) }
    }

    override fun setScanResponseData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) { setScanResponseData(advertiserId, data) }
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
        withManagerRunOnAdvertiseThread(source) {
            setAdvertisingParameters(advertiserId, parameters)
        }
    }

    override fun setPeriodicAdvertisingParameters(
        advertiserId: Int,
        parameters: PeriodicAdvertisingParameters?,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) {
            setPeriodicAdvertisingParameters(advertiserId, parameters)
        }
    }

    override fun setPeriodicAdvertisingData(
        advertiserId: Int,
        data: AdvertiseData?,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) { setPeriodicAdvertisingData(advertiserId, data) }
    }

    override fun setPeriodicAdvertisingEnable(
        advertiserId: Int,
        enable: Boolean,
        source: AttributionSource,
    ) {
        withManagerRunOnAdvertiseThread(source) {
            setPeriodicAdvertisingEnable(advertiserId, enable)
        }
    }
}
