/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth

import com.android.obex.ClientSession
import com.android.obex.HeaderSet
import com.android.obex.ObexTransport
import com.android.obex.Operation
import com.android.obex.ResponseCodes
import com.android.obex.ServerRequestHandler
import com.android.obex.ServerSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.min

private const val TAG = "FakeObexServer"

/** A Fake OBEX Server base class for use when testing OBEX clients and client requests. */
open class FakeObexServer {

    // Cross-connected loops for server-client communication
    private val sendToServer = PipedOutputStream()
    private val receiveFromServer = PipedInputStream()
    private val receiveFromClient = PipedInputStream(sendToServer)
    private val sendToClient = PipedOutputStream(receiveFromServer)

    // Transports for either side
    private val serverTransport = TestObexTransport(receiveFromClient, sendToClient, true)
    private val clientTransport = TestObexTransport(receiveFromServer, sendToServer, true)

    private val requestHandler = TestServiceRequestHandler(this)
    private val serverSession = ServerSession(serverTransport, requestHandler, null)

    /**
     * Get a transport for use with a client.
     *
     * You can use the openInputStream() and openOutputStream() to get the underlying stream objects
     * and inject them into your objects under test.
     */
    fun getClientTransport() = clientTransport

    /**
     * Directly create a session with this server.
     *
     * <p>This can be used to quickly test request objects that need a ClientSession
     */
    fun getClientSession() = ClientSession(clientTransport)

    /**
     * This will close the underlying transport, which will close the streams given to us.
     *
     * <p>By specification, servers themselves cannot issue an OBEX session level disconnect.
     */
    fun close() = serverSession.close()

    // *********************************************************************************************
    // * Server Operations
    // *********************************************************************************************

    open fun onConnect(request: HeaderSet, reply: HeaderSet) = ResponseCodes.OBEX_HTTP_OK

    open fun onDisconnect(request: HeaderSet, reply: HeaderSet) {}

    open fun onGet(op: Operation) = ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED

    open fun onPut(op: Operation) = ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED

    open fun onAbort(request: HeaderSet, reply: HeaderSet) = ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED

    open fun onSetPath(request: HeaderSet, reply: HeaderSet, backup: Boolean, create: Boolean) =
        ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED

    open fun onClose() {}

    /**
     * Send a response to a client with the given headers and payload
     *
     * @param op The Operation object representing the ongoing operation with the client
     * @param replyHeaders The HeaderSet to return to the client
     * @param bytes The payload to send in the response, if any
     */
    fun sendResponse(op: Operation, replyHeaders: HeaderSet, bytes: ByteArray?): Int {
        var responseCode = ResponseCodes.OBEX_HTTP_OK
        var outStream: OutputStream? = null
        var maxChunkSize = 0
        var bytesToWrite = 0
        var bytesWritten = 0

        try {
            op.sendHeaders(replyHeaders) // Do this before getting chunk size
            outStream = op.openOutputStream()
            if (bytes == null) {
                op.noBodyHeader()
                outStream.flush()
            } else {
                maxChunkSize = op.maxPacketSize
                while (bytesWritten < bytes.size) {
                    bytesToWrite = min(maxChunkSize, bytes.size - bytesWritten)
                    outStream.write(bytes, bytesWritten, bytesToWrite)
                    bytesWritten += bytesToWrite
                }
            }
        } catch (e: IOException) {
            responseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR
        } finally {
            // Make sure we close
            try {
                outStream?.close()
            } catch (e: IOException) {
                // drop, as we're closing anyways
            }
        }
        // If we didn't write everything then send the error code
        if (bytes != null && bytesWritten != bytes.size) {
            responseCode = ResponseCodes.OBEX_HTTP_BAD_REQUEST
        }
        // Otherwise, success!
        return responseCode
    }

    // *********************************************************************************************
    // * Transport
    // *********************************************************************************************

    class TestObexTransport(
        private val input: InputStream,
        private val output: OutputStream,
        private val isSrmSupported: Boolean,
    ) : ObexTransport {

        override fun openDataInputStream() = DataInputStream(openInputStream())

        override fun openDataOutputStream() = DataOutputStream(openOutputStream())

        override fun openInputStream() = input

        override fun openOutputStream() = output

        override fun getMaxReceivePacketSize() = -1

        override fun getMaxTransmitPacketSize() = -1

        override fun connect() {}

        override fun create() {}

        override fun disconnect() {}

        override fun listen() {}

        override fun close() {
            input.close()
            output.close()
        }

        override fun isSrmSupported() = isSrmSupported
    }

    // *********************************************************************************************
    // * Request Handler
    // *********************************************************************************************

    /**
     * Internal ServerRequestHandler that delegates calls to the FakeObexServer implementation
     *
     * <p>This is setup this way for easier test syntax, so one can extend the fake without needing
     * to care about the framework specifics
     */
    private class TestServiceRequestHandler(private val server: FakeObexServer) :
        ServerRequestHandler() {

        override fun onConnect(request: HeaderSet, reply: HeaderSet) =
            server.onConnect(request, reply)

        override fun onDisconnect(request: HeaderSet, reply: HeaderSet) =
            server.onDisconnect(request, reply)

        override fun onGet(op: Operation) = server.onGet(op)

        override fun onPut(op: Operation) = server.onPut(op)

        override fun onAbort(request: HeaderSet, reply: HeaderSet) = server.onAbort(request, reply)

        override fun onSetPath(
            request: HeaderSet,
            reply: HeaderSet,
            backup: Boolean,
            create: Boolean,
        ) = server.onSetPath(request, reply, backup, create)

        override fun onClose() = server.onClose()
    }
}
