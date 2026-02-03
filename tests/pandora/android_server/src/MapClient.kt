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

package com.android.pandora

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothMapClient
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.content.Context
import android.net.Uri
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import pandora.MapClientGrpc.MapClientImplBase
import pandora.MapClientProto.*

@kotlinx.coroutines.ExperimentalCoroutinesApi
class MapClient(context: Context) : MapClientImplBase(), Closeable {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothMapClient =
        getProfileProxy<BluetoothMapClient>(context, BluetoothProfile.MAP_CLIENT)

    override fun setConnectionPolicy(
        request: SetConnectionPolicyRequest,
        responseObserver: StreamObserver<Empty>,
    ) {
        grpcUnary(scope, responseObserver) {
            val policy =
                when (request.policy) {
                    ConnectionPolicy.CONNECTION_POLICY_ALLOWED -> CONNECTION_POLICY_ALLOWED
                    ConnectionPolicy.CONNECTION_POLICY_FORBIDDEN -> CONNECTION_POLICY_FORBIDDEN
                    else -> {
                        throw Status.INVALID_ARGUMENT.withDescription("Invalid connection policy")
                            .asRuntimeException()
                    }
                }

            val device = request.connection.toBluetoothDevice(bluetoothAdapter)

            bluetoothMapClient.setConnectionPolicy(device, policy)

            Empty.getDefaultInstance()
        }
    }

    override fun sendMessage(request: SendMessageRequest, responseObserver: StreamObserver<Empty>) {
        grpcUnary(scope, responseObserver) {
            val device = request.connection.toBluetoothDevice(bluetoothAdapter)

            val phoneNumber = request.phoneNumber
            val contacts = listOf(Uri.parse(phoneNumber))
            val message = request.message

            bluetoothMapClient.sendMessage(device, contacts, message, null, null)
            Empty.getDefaultInstance()
        }
    }

    override fun close() {
        // Cancel the coroutine scope to release resources
        scope.cancel()
    }

    companion object {
        private const val TAG = "PandoraMapClient"
    }
}
