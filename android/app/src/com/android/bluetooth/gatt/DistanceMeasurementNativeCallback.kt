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

import android.bluetooth.BluetoothStatusCodes
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

class DistanceMeasurementNativeCallback(
    adapterService: AdapterService,
    private val manager: DistanceMeasurementManager,
) : NativeCallback(adapterService) {

    fun onDistanceMeasurementStarted(address: String, method: Int) =
        postOnDistanceMeasurementThread {
            onDistanceMeasurementStarted(address, method)
        }

    fun onDistanceMeasurementStopped(address: String, reason: Int, method: Int) =
        postOnDistanceMeasurementThread {
            onDistanceMeasurementStopped(address, convertErrorCode(reason), method)
        }

    fun onDistanceMeasurementResult(
        address: String,
        centimeter: Int,
        errorCentimeter: Int,
        azimuthAngle: Int,
        errorAzimuthAngle: Int,
        altitudeAngle: Int,
        errorAltitudeAngle: Int,
        elapsedRealtimeNanos: Long,
        remoteTxPowerDbm: Int,
        rssiDbm: Int,
        confidenceLevel: Int,
        delayedSpreadMeters: Double,
        detectedAttackLevel: Int,
        velocityMetersPerSecond: Double,
        method: Int,
    ) = postOnDistanceMeasurementThread {
        onDistanceMeasurementResult(
            address,
            centimeter,
            errorCentimeter,
            azimuthAngle,
            errorAzimuthAngle,
            altitudeAngle,
            errorAltitudeAngle,
            elapsedRealtimeNanos,
            remoteTxPowerDbm,
            rssiDbm,
            confidenceLevel,
            delayedSpreadMeters,
            detectedAttackLevel,
            velocityMetersPerSecond,
            method,
        )
    }

    private fun convertErrorCode(errorCode: Int) =
        when (errorCode) {
            REASON_FEATURE_NOT_SUPPORTED_LOCAL -> BluetoothStatusCodes.FEATURE_NOT_SUPPORTED
            REASON_FEATURE_NOT_SUPPORTED_REMOTE ->
                BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED
            REASON_LOCAL_REQUEST -> BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST
            REASON_REMOTE_REQUEST -> BluetoothStatusCodes.REASON_REMOTE_REQUEST
            REASON_DURATION_TIMEOUT -> BluetoothStatusCodes.ERROR_TIMEOUT
            REASON_NO_LE_CONNECTION -> BluetoothStatusCodes.ERROR_NO_LE_CONNECTION
            REASON_INVALID_PARAMETERS -> BluetoothStatusCodes.ERROR_BAD_PARAMETERS
            REASON_INTERNAL_ERROR -> BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL
            else -> BluetoothStatusCodes.ERROR_UNKNOWN
        }

    private fun postOnDistanceMeasurementThread(block: DistanceMeasurementManager.() -> Unit) =
        manager.postOnDistanceMeasurementThread {
            manager.block()
        }

    companion object {
        /**
         * Do not modify without updating distance_measurement_manager.h match up with
         * DistanceMeasurementErrorCode enum of distance_measurement_manager.h
         */
        private const val REASON_FEATURE_NOT_SUPPORTED_LOCAL = 0
        private const val REASON_FEATURE_NOT_SUPPORTED_REMOTE = 1
        private const val REASON_LOCAL_REQUEST = 2
        private const val REASON_REMOTE_REQUEST = 3
        private const val REASON_DURATION_TIMEOUT = 4
        private const val REASON_NO_LE_CONNECTION = 5
        private const val REASON_INVALID_PARAMETERS = 6
        private const val REASON_INTERNAL_ERROR = 7
    }
}
