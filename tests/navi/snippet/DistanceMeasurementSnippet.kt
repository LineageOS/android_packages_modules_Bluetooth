/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ChannelSoundingParams
import android.bluetooth.le.DistanceMeasurementParams
import android.bluetooth.le.DistanceMeasurementResult
import android.bluetooth.le.DistanceMeasurementSession
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.Utils.getOrNull
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.bluetooth.snippet.Utils.toBluetoothStatusDescription
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Snippet for Bluetooth Distance Measurement. */
class DistanceMeasurementSnippet : Snippet {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val distanceMeasurementManager = bluetoothAdapter.distanceMeasurementManager
    private val sessions = mutableMapOf<String, DistanceMeasurementSession>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /**
     * Starts a distance measurement session.
     *
     * @param callbackId The callback ID to use for the session.
     * @param params The parameters for the session.
     */
    @AsyncRpc(description = "Start Distance Measurement")
    fun startDistanceMeasurement(callbackId: String, params: JSONObject) {
        require(distanceMeasurementManager != null)
        val flow = callbackFlow {
            val callback =
                object : DistanceMeasurementSession.Callback {

                    override fun onStartFail(reason: Int) {
                        val unused = trySendBlocking(Pair(null, reason))
                    }

                    override fun onStarted(session: DistanceMeasurementSession) {
                        val unused = trySendBlocking(Pair(session, BluetoothStatusCodes.SUCCESS))
                    }

                    override fun onResult(
                        device: BluetoothDevice,
                        result: DistanceMeasurementResult,
                    ) {
                        postSnippetEvent(callbackId, SnippetConstants.DISTANCE_MEASUREMENT_RESULT) {
                            val doubleFields =
                                mapOf(
                                    SnippetConstants.RESULT_METERS to result.resultMeters,
                                    SnippetConstants.ERROR_METERS to result.errorMeters,
                                    SnippetConstants.AZIMUTH_ANGLE to result.azimuthAngle,
                                    SnippetConstants.ERROR_AZIMUTH_ANGLE to
                                        result.errorAzimuthAngle,
                                    SnippetConstants.ALTITUDE_ANGLE to result.altitudeAngle,
                                    SnippetConstants.ERROR_ALTITUDE_ANGLE to
                                        result.errorAltitudeAngle,
                                ) +
                                    if (Build.VERSION.SDK_INT >= 35) {
                                        mapOf(
                                            SnippetConstants.DELAY_SPREAD_METERS to
                                                result.delaySpreadMeters,
                                            SnippetConstants.CONFIDENCE_LEVEL to
                                                result.confidenceLevel,
                                            SnippetConstants.VELOCITY_METERS_PER_SECOND to
                                                result.velocityMetersPerSecond,
                                        )
                                    } else {
                                        mapOf()
                                    }
                            for ((key, value) in doubleFields) {
                                if (!value.isNaN()) putDouble(key, value)
                            }
                            if (Build.VERSION.SDK_INT >= 35) {
                                putInt(
                                    SnippetConstants.DETECTED_ATTACK_LEVEL,
                                    result.detectedAttackLevel,
                                )
                                putLong(
                                    SnippetConstants.MEASUREMENT_TIMESTAMP_NANOS,
                                    result.measurementTimestampNanos,
                                )
                            }
                        }
                    }

                    override fun onStopped(session: DistanceMeasurementSession, reason: Int) {
                        Log.i(TAG, "onStopped, reason: ${reason.toBluetoothStatusDescription()}")
                    }
                }
            distanceMeasurementManager.startMeasurementSession(
                params.toDistanceMeasurementParams(),
                context.mainExecutor,
                callback,
            )

            awaitClose()
        }
        val (session, reason) = runBlocking { flow.timeout(DEFAULT_OPERATION_TIMEOUT).first() }
        if (session == null) {
            throw RuntimeException(
                "Unable to start distance measurement, reason: ${reason.toBluetoothStatusDescription()}"
            )
        }
        sessions[callbackId] = session
    }

    /**
     * Stops a distance measurement session.
     *
     * @param callbackId The callback ID of the session to stop.
     */
    @Rpc(description = "Start Distance Measurement")
    fun stopDistanceMeasurement(callbackId: String) {
        val status =
            sessions.remove(callbackId)?.stopSession()
                ?: throw IllegalArgumentException("Session $callbackId doesn't exist!")
        if (status != BluetoothStatusCodes.SUCCESS) {
            throw RuntimeException(
                "Unable to stop distance measurement, reason=${status.toBluetoothStatusDescription()}"
            )
        }
    }

    /**
     * Gets supported distance measurement methods.
     *
     * @return A list of supported distance measurement method IDs.
     */
    @Rpc(description = "Get supported distance measurement methods")
    fun getSupportedDistanceMeasurementMethods(): List<Int> =
        distanceMeasurementManager?.supportedMethods?.map { it.methodId } ?: listOf()

    private fun JSONObject.toDistanceMeasurementParams() =
        DistanceMeasurementParams.Builder(
                bluetoothAdapter.getRemoteDevice(getString(SnippetConstants.FIELD_DEVICE))
            )
            .apply {
                getOrNull<Int>(SnippetConstants.DURATION)?.let { setDurationSeconds(it) }
                getOrNull<Int>(SnippetConstants.FREQUENCY)?.let { setFrequency(it) }
                getOrNull<Int>(SnippetConstants.METHOD_ID)?.let { setMethodId(it) }
                getOrNull<JSONObject>(SnippetConstants.CHANNEL_SOUNDING_PARAMS)?.let { csParams ->
                    val builder = ChannelSoundingParams.Builder()
                    csParams.getOrNull<Int>(SnippetConstants.SIGHT_TYPE)?.let {
                        builder.setSightType(it)
                    }
                    csParams.getOrNull<Int>(SnippetConstants.LOCATION_TYPE)?.let {
                        builder.setLocationType(it)
                    }
                    csParams.getOrNull<Int>(SnippetConstants.SECURITY_LEVEL)?.let {
                        builder.setCsSecurityLevel(it)
                    }
                    setChannelSoundingParams(builder.build())
                }
            }
            .build()

    private companion object {
        const val TAG = "DistanceMeasurementSnippet"
        val DEFAULT_OPERATION_TIMEOUT = 5.seconds
    }
}
