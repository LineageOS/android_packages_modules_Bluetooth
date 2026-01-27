/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import pandora.RFCOMMGrpc.RFCOMMImplBase
import pandora.RfcommProto.*

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Rfcomm(val context: Context) : RFCOMMImplBase(), Closeable {

    private val TAG = "PandoraRfcomm"
    private val _bufferSize = 512

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val bluetoothAdapter = bluetoothManager.adapter

    private var currentCookie = 0x12FC0 // Non-zero cookie RFCo(mm)

    data class Connection(
        val connection: BluetoothSocket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
    )

    private var serverMap: HashMap<Int, BluetoothServerSocket> = hashMapOf()
    private var connectionMap: HashMap<Int, Connection> = hashMapOf()

    override fun close() {
        scope.cancel()
    }

    override fun connectToServer(
        request: ConnectionRequest,
        responseObserver: StreamObserver<ConnectionResponse>,
    ) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "Connect: address=${request.address}")

            val device = request.address.toBluetoothDevice(bluetoothAdapter)
            val clientSocket =
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(request.uuid))

            try {
                clientSocket.connect()
            } catch (e: IOException) {
                Log.e(TAG, "Connect failed with exception: $e")
                throw RuntimeException("RFCOMM connect failure")
            }

            Log.i(TAG, "connected: socket= $clientSocket")
            val connectedClientSocket = currentCookie++
            val tmpIn = clientSocket.inputStream!!
            val tmpOut = clientSocket.outputStream!!
            connectionMap[connectedClientSocket] = Connection(clientSocket, tmpIn, tmpOut)

            ConnectionResponse.newBuilder()
                .setConnection(RfcommConnection.newBuilder().setId(connectedClientSocket).build())
                .build()
        }
    }

    override fun disconnect(
        request: DisconnectionRequest,
        responseObserver: StreamObserver<DisconnectionResponse>,
    ) {
        grpcUnary(scope, responseObserver) {
            val id = request.connection.id
            Log.i(TAG, "Disconnect: id=${id}")

            if (!connectionMap.containsKey(id)) {
                throw RuntimeException("Unknown connection identifier")
            }

            connectionMap[id]!!.connection.close()
            connectionMap.remove(id)

            DisconnectionResponse.newBuilder().build()
        }
    }

    override fun startServer(
        request: StartServerRequest,
        responseObserver: StreamObserver<StartServerResponse>,
    ) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "StartServer")

            val serverSocket =
                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    request.name,
                    UUID.fromString(request.uuid),
                )
            val serverSocketCookie = currentCookie++
            serverMap[serverSocketCookie] = serverSocket

            StartServerResponse.newBuilder()
                .setServer(ServerId.newBuilder().setId(serverSocketCookie).build())
                .build()
        }
    }

    override fun acceptConnection(
        request: AcceptConnectionRequest,
        responseObserver: StreamObserver<AcceptConnectionResponse>,
    ) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "Accept: id=${request.server.id}")

            val acceptedSocket: BluetoothSocket =
                try {
                    serverMap[request.server.id]!!.accept(2000)
                } catch (e: IOException) {
                    Log.e(TAG, "Accept failed with exception", e)
                    throw RuntimeException("RFCOMM accept failure")
                }

            Log.i(TAG, "accepted: socket=$acceptedSocket")
            val acceptedSocketCookie = currentCookie++
            val tmpIn = acceptedSocket.inputStream!!
            val tmpOut = acceptedSocket.outputStream!!
            connectionMap[acceptedSocketCookie] = Connection(acceptedSocket, tmpIn, tmpOut)

            AcceptConnectionResponse.newBuilder()
                .setConnection(RfcommConnection.newBuilder().setId(acceptedSocketCookie).build())
                .build()
        }
    }

    override fun send(request: TxRequest, responseObserver: StreamObserver<TxResponse>) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "Send: id=${request.connection.id}")

            if (request.data.isEmpty) {
                throw RuntimeException("Invalid data parameter")
            }
            if (!connectionMap.containsKey(request.connection.id)) {
                throw RuntimeException("Unknown connection identifier")
            }

            val data = request.data!!.toByteArray()
            val socketOut = connectionMap[request.connection.id]!!.outputStream

            withContext(Dispatchers.IO) {
                try {
                    socketOut.write(data)
                    socketOut.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while writing output stream", e)
                }
            }

            TxResponse.newBuilder().build()
        }
    }

    override fun receive(request: RxRequest, responseObserver: StreamObserver<RxResponse>) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "Receive: id=${request.connection.id}")

            if (!connectionMap.containsKey(request.connection.id)) {
                throw RuntimeException("Unknown connection identifier")
            }

            val data = ByteArray(_bufferSize)
            val socketIn = connectionMap[request.connection.id]!!.inputStream

            withContext(Dispatchers.IO) {
                try {
                    socketIn.read(data)
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while reading from input stream", e)
                }
            }

            RxResponse.newBuilder().setData(ByteString.copyFrom(data)).build()
        }
    }
}
