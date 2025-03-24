/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.pandora

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothHapClient
import android.bluetooth.BluetoothHapClient.Callback
import android.bluetooth.BluetoothHapPresetInfo
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import pandora.HAPGrpc.HAPImplBase
import pandora.HapProto.*
import pandora.HostProto.Connection

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Hap(val context: Context) : HAPImplBase(), Closeable {
    private val TAG = "PandoraHap"

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val bluetoothAdapter = bluetoothManager.adapter

    private val bluetoothHapClient =
        getProfileProxy<BluetoothHapClient>(context, BluetoothProfile.HAP_CLIENT)

    private val bluetoothLeAudio =
        getProfileProxy<BluetoothLeAudio>(context, BluetoothProfile.LE_AUDIO)

    private val flow =
        intentFlow(
                context,
                IntentFilter().apply {
                    addAction(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED)
                },
                scope,
            )
            .shareIn(scope, SharingStarted.Eagerly)

    private class PresetInfoChanged(
        var connection: Connection,
        var presetInfoList: List<BluetoothHapPresetInfo>,
        var reason: Int,
    ) {}

    private val mPresetChanged = callbackFlow {
        val callback =
            object : BluetoothHapClient.Callback {
                override fun onPresetSelected(
                    device: BluetoothDevice,
                    presetIndex: Int,
                    reason: Int,
                ) {
                    Log.i(TAG, "$device preset info changed")
                }

                override fun onPresetSelectionFailed(device: BluetoothDevice, reason: Int) {
                    trySend(null)
                }

                override fun onPresetSelectionForGroupFailed(hapGroupId: Int, reason: Int) {
                    trySend(null)
                }

                override fun onPresetInfoChanged(
                    device: BluetoothDevice,
                    presetInfoList: List<BluetoothHapPresetInfo>,
                    reason: Int,
                ) {
                    Log.i(TAG, "$device preset info changed")

                    var infoChanged =
                        PresetInfoChanged(device.toConnection(TRANSPORT_LE), presetInfoList, reason)

                    trySend(infoChanged)
                }

                override fun onSetPresetNameFailed(device: BluetoothDevice, reason: Int) {
                    trySend(null)
                }

                override fun onSetPresetNameForGroupFailed(hapGroupId: Int, reason: Int) {
                    trySend(null)
                }
            }

        bluetoothHapClient.registerCallback(Executors.newSingleThreadExecutor(), callback)

        awaitClose { bluetoothHapClient.unregisterCallback(callback) }
    }

    override fun close() {
        // Deinit the CoroutineScope
        scope.cancel()
    }

    override fun getFeatures(
        request: GetFeaturesRequest,
        responseObserver: StreamObserver<GetFeaturesResponse>,
    ) {
        grpcUnary<GetFeaturesResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "getFeatures(${device})")
            GetFeaturesResponse.newBuilder()
                .setFeatures(bluetoothHapClient.getFeatures(device))
                .build()
        }
    }

    override fun getPresetRecord(
        request: GetPresetRecordRequest,
        responseObserver: StreamObserver<GetPresetRecordResponse>,
    ) {
        grpcUnary<GetPresetRecordResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "getPresetRecord($device, ${request.index})")

            val presetInfo: BluetoothHapPresetInfo? =
                bluetoothHapClient.getPresetInfo(device, request.index)

            if (presetInfo != null) {
                GetPresetRecordResponse.newBuilder()
                    .setPresetRecord(
                        PresetRecord.newBuilder()
                            .setIndex(presetInfo.getIndex())
                            .setName(presetInfo.getName())
                            .setIsWritable(presetInfo.isWritable())
                            .setIsAvailable(presetInfo.isAvailable())
                    )
                    .build()
            } else {
                GetPresetRecordResponse.getDefaultInstance()
            }
        }
    }

    override fun getAllPresetRecords(
        request: GetAllPresetRecordsRequest,
        responseObserver: StreamObserver<GetAllPresetRecordsResponse>,
    ) {
        grpcUnary<GetAllPresetRecordsResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "getAllPresetRecords(${device})")

            GetAllPresetRecordsResponse.newBuilder()
                .addAllPresetRecordList(
                    bluetoothHapClient
                        .getAllPresetInfo(device)
                        .stream()
                        .map { it: BluetoothHapPresetInfo ->
                            PresetRecord.newBuilder()
                                .setIndex(it.getIndex())
                                .setName(it.getName())
                                .setIsWritable(it.isWritable())
                                .setIsAvailable(it.isAvailable())
                                .build()
                        }
                        .toList()
                )
                .build()
        }
    }

    override fun writePresetName(
        request: WritePresetNameRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "writePresetName($device, ${request.index}, ${request.name})")

            bluetoothHapClient.setPresetName(device, request.index, request.name)

            Empty.getDefaultInstance()
        }
    }

    override fun setActivePreset(
        request: SetActivePresetRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "SetActivePreset($device, ${request.index})")

            bluetoothHapClient.selectPreset(device, request.index)

            Empty.getDefaultInstance()
        }
    }

    override fun getActivePresetRecord(
        request: GetActivePresetRecordRequest,
        responseObserver: StreamObserver<GetActivePresetRecordResponse>,
    ) {
        grpcUnary<GetActivePresetRecordResponse>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "GetActivePresetRecord($device)")

            val presetInfo: BluetoothHapPresetInfo? = bluetoothHapClient.getActivePresetInfo(device)

            if (presetInfo != null) {
                GetActivePresetRecordResponse.newBuilder()
                    .setPresetRecord(
                        PresetRecord.newBuilder()
                            .setIndex(presetInfo.getIndex())
                            .setName(presetInfo.getName())
                            .setIsWritable(presetInfo.isWritable())
                            .setIsAvailable(presetInfo.isAvailable())
                    )
                    .build()
            } else {
                GetActivePresetRecordResponse.getDefaultInstance()
            }
        }
    }

    override fun setNextPreset(
        request: SetNextPresetRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "setNextPreset($device)")

            bluetoothHapClient.switchToNextPreset(device)

            Empty.getDefaultInstance()
        }
    }

    override fun setPreviousPreset(
        request: SetPreviousPresetRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "setPreviousPreset($device)")

            bluetoothHapClient.switchToPreviousPreset(device)

            Empty.getDefaultInstance()
        }
    }

    override fun waitPresetChanged(
        request: Empty,
        responseObserver: StreamObserver<WaitPresetChangedResponse>,
    ) {
        grpcUnary<WaitPresetChangedResponse>(scope, responseObserver) {
            val presetChangedReceived = mPresetChanged.first()!!
            val presetRecordList = arrayListOf<PresetRecord>()

            for (presetRecord in presetChangedReceived.presetInfoList) {
                presetRecordList.add(
                    PresetRecord.newBuilder()
                        .setIndex(presetRecord.getIndex())
                        .setName(presetRecord.getName())
                        .setIsWritable(presetRecord.isWritable())
                        .setIsAvailable(presetRecord.isAvailable())
                        .build()
                )
            }

            WaitPresetChangedResponse.newBuilder()
                .setConnection(presetChangedReceived.connection)
                .addAllPresetRecordList(presetRecordList)
                .setReason(presetChangedReceived.reason)
                .build()
        }
    }

    override fun waitPeripheral(
        request: WaitPeripheralRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary<Empty>(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)
            Log.i(TAG, "waitPeripheral(${device}")
            if (bluetoothHapClient.getConnectionState(device) != STATE_CONNECTED) {
                Log.d(TAG, "Manual call to setConnectionPolicy")
                bluetoothHapClient.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED)
                Log.d(TAG, "now waiting for bluetoothHapClient profile connection")
                flow
                    .filter { it.getBluetoothDeviceExtra() == device }
                    .map { it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR) }
                    .filter { it == STATE_CONNECTED }
                    .first()
            }

            Empty.getDefaultInstance()
        }
    }
}
