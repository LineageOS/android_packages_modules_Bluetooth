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

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.rpc.RpcDefault
import com.google.android.mobly.snippet.rpc.RpcOptional
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class BluetoothRfcommSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val servers = mutableMapOf<String, BluetoothServerSocket>()
    private val sockets = mutableMapOf<String, BluetoothSocket>()
    private val threadPool = Executors.newCachedThreadPool()
    private val connectingFutures = mutableMapOf<String, Future<*>>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Connects an RFCOMM channel with a service record UUID. */
    @Rpc(description = "Connect an RFCOMM channel with a service record UUID")
    fun rfcommConnectWithUuid(
        address: String,
        secure: Boolean,
        uuid: String,
        @RpcDefault(true.toString(), converter = Utils.BooleanConverter::class)
        blocking: Boolean = true,
    ): String {
        val device = bluetoothAdapter.getRemoteDevice(address)
        val socket =
            if (secure) {
                device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
            } else {
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid))
            }

        val cookie = UUID.randomUUID().toString()
        sockets[cookie] = socket

        if (blocking) {
            socket.connect()
        } else {
            connectingFutures[cookie] = threadPool.submit {
                try {
                    socket.connect()
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Failed to connect to RFCOMM channel", e)
                    throw e
                }
            }
        }

        return cookie
    }

    /** Waits for an RFCOMM channel connection to complete. */
    @Rpc(description = "Wait for an RFCOMM channel connection to complete")
    fun rfcommWaitForConnectionComplete(
        cookie: String,
        @RpcDefault(
            DEFAULT_CONNECTION_TIMEOUT_MILLISECONDS.toString(),
            converter = Utils.LongConverter::class,
        )
        timeoutMilliseconds: Long = DEFAULT_CONNECTION_TIMEOUT_MILLISECONDS,
    ) {
        val future =
            connectingFutures[cookie]
                ?: throw IllegalArgumentException("No connection on cookie $cookie")
        try {
            future.get(timeoutMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            // Throw the cause of the ExecutionException if it exists, otherwise throw the
            // ExecutionException itself.
            throw e.cause ?: e
        }
    }

    /** Listens for an [secure] RFCOMM channel with SDP record [uuid]. */
    @Rpc(description = "Open an RFCOMM server")
    fun rfcommOpenServer(secure: Boolean, uuid: String) {
        val serverSocket =
            if (secure) {
                bluetoothAdapter.listenUsingRfcommWithServiceRecord(uuid, UUID.fromString(uuid))
            } else {
                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    uuid,
                    UUID.fromString(uuid),
                )
            }
        servers[uuid] = serverSocket
    }

    /** Closes an RFCOMM server with SDP record [uuid]. */
    @Rpc(description = "Close an RFCOMM server")
    fun rfcommCloseServer(uuid: String) {
        servers.remove(uuid)?.close()
    }

    /** Waits for an incoming RFCOMM connection from the server with SDP record [uuid]. */
    @Rpc(description = "Wait for an incoming RFCOMM channel")
    fun rfcommWaitConnection(uuid: String): String {
        val socket =
            servers[uuid]?.accept(30000) ?: { throw RuntimeException("No server on uuid $uuid") }
        val cookie = UUID.randomUUID().toString()
        sockets[cookie] = socket as BluetoothSocket
        return cookie
    }

    /** Disconnects an RFCOMM channel with [cookie] */
    @Rpc(description = "Disconnect an RFCOMM channel")
    fun rfcommDisconnect(cookie: String) {
        sockets.remove(cookie)?.close()
        connectingFutures.remove(cookie)?.cancel(true)
    }

    /** Reads [bytesToRead] bytes of data from an RFCOMM channel with [cookie]. */
    @Rpc(description = "Read data from an RFCOMM channel")
    fun rfcommRead(cookie: String, @RpcOptional bytesToRead: Int?): String {
        val socket =
            sockets[cookie] ?: throw IllegalArgumentException("No socket on cookie $cookie")
        var bytesRead = 0
        val buf = ByteArray(bytesToRead ?: 65535)
        if (bytesToRead == null) {
            // If not specified, just read a single packet as long as possible.
            bytesRead = socket.inputStream.read(buf)
        } else {
            // Read until specified length.
            while (bytesRead < bytesToRead) {
                bytesRead += socket.inputStream.read(buf, bytesRead, bytesToRead - bytesRead)
            }
        }
        return Base64.encodeToString(buf, 0, bytesRead, Base64.NO_WRAP)
    }

    /** Writes [data] to an RFCOMM channel with [cookie]. */
    @Rpc(description = "Write data to an RFCOMM channel")
    fun rfcommWrite(cookie: String, data: String) {
        sockets[cookie]?.outputStream?.write(Base64.decode(data, Base64.NO_WRAP))
            ?: throw IllegalArgumentException("No socket on cookie $cookie")
    }

    @VisibleForTesting fun getServers() = this.servers

    private companion object {
        const val TAG = "BluetoothRfcommSnippet"
        const val DEFAULT_CONNECTION_TIMEOUT_MILLISECONDS = 10_000L
    }
}
