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
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.rpc.RpcDefault
import com.google.android.mobly.snippet.rpc.RpcOptional
import java.util.UUID

class BluetoothL2capSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val servers = mutableMapOf<Int, BluetoothServerSocket>()
    private val sockets = mutableMapOf<String, BluetoothSocket>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /**
     * Connects an L2CAP channel to device with [address] over [transport] on server of [psm], and
     * encrypt if [secure] is true. [addressType] will be used to specify the type of remote
     * address.
     */
    @Rpc(description = "Connect an L2CAP channel")
    fun l2capConnect(
        address: String,
        secure: Boolean,
        psm: Int,
        @RpcDefault(
            BluetoothDevice.ADDRESS_TYPE_RANDOM.toString(),
            converter = Utils.IntConverter::class,
        )
        addressType: Int = BluetoothDevice.ADDRESS_TYPE_RANDOM,
    ): String {
        val device = bluetoothAdapter.getRemoteLeDevice(address, addressType)
        val socket =
            when (secure) {
                true -> device.createL2capChannel(psm)
                false -> device.createInsecureL2capChannel(psm)
            }
        socket.connect()
        val cookie = UUID.randomUUID().toString()
        sockets[cookie] = socket
        return cookie
    }

    /**
     * Listens for L2CAP channel on [psm] requiring encryption if [secure], and returns the
     * allocated PSM.
     */
    @Rpc(description = "Open an L2CAP server")
    fun l2capOpenServer(secure: Boolean, psm: Int): Int {
        val serverSocket =
            when (secure) {
                true -> bluetoothAdapter.listenUsingL2capChannel()
                false -> bluetoothAdapter.listenUsingInsecureL2capChannel()
            }
        servers[serverSocket.psm] = serverSocket
        return serverSocket.psm
    }

    /** Waits for an incoming L2CAP connection on the server of [psm]. */
    @Rpc(description = "Wait for an incoming L2CAP channel")
    fun l2capWaitConnection(psm: Int): String {
        val socket =
            servers[psm]?.accept(30000) ?: { throw RuntimeException("No server on psm $psm") }
        val cookie = UUID.randomUUID().toString()
        sockets[cookie] = socket as BluetoothSocket
        return cookie
    }

    /** Closes the L2CAP server of [psm]. */
    @Rpc(description = "Close an L2CAP server")
    fun l2capCloseServer(psm: Int) {
        if (psm in servers) {
            servers[psm]?.close()
            servers.remove(psm)
        }
    }

    /** Disconnects an L2cap channel with [cookie]. */
    @Rpc(description = "Disconnect an L2cap channel")
    fun l2capDisconnect(cookie: String) {
        if (cookie in sockets) {
            sockets[cookie]?.close()
            sockets.remove(cookie)
        }
    }

    /** Reads data from an L2CAP channel with [cookie]. */
    @Rpc(description = "Read data from an L2CAP channel")
    fun l2capRead(cookie: String, @RpcOptional bytesToRead: Int?): String {
        val socket =
            sockets[cookie] ?: throw IllegalArgumentException("No socket on cookie $cookie")
        var bytesRead = 0
        val buf = ByteArray(bytesToRead ?: socket.maxReceivePacketSize)
        if (bytesToRead == null) {
            // If not specified, just read a single packet as long as possible.
            bytesRead += socket.inputStream.read(buf)
        } else {
            // Read until specified length.
            while (bytesRead < bytesToRead) {
                bytesRead += socket.inputStream.read(buf, bytesRead, bytesToRead - bytesRead)
            }
        }
        return Base64.encodeToString(buf, 0, bytesRead, Base64.NO_WRAP)
    }

    /** Writes [data] to an L2CAP channel with [cookie]. */
    @Rpc(description = "Write data to an L2CAP channel")
    fun l2capWrite(cookie: String, data: String) {
        sockets[cookie]?.outputStream?.write(Base64.decode(data, Base64.NO_WRAP))
            ?: throw IllegalArgumentException("No socket on cookie $cookie")
    }

    companion object {
        const val TAG = "BluetoothL2capSnippet"
    }
}
