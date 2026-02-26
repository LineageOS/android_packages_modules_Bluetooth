/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.bluetooth;

import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ObexTransport;
import com.android.obex.Operation;
import com.android.obex.ResponseCodes;
import com.android.obex.ServerRequestHandler;
import com.android.obex.ServerSession;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/** A Fake OBEX Server base class for use when testing OBEX clients and client requests. */
public class FakeObexServer {
    private static final String TAG = FakeObexServer.class.getSimpleName();

    // Server streams to talk with client under test
    private final PipedInputStream mReceiveFromClient;
    private final PipedOutputStream mSendToClient;

    // Client streams to talk to server
    private final PipedInputStream mReceiveFromServer;
    private final PipedOutputStream mSendToServer;

    // Transports for either side
    private final TestObexTransport mServerTransport;
    private final TestObexTransport mClientTransport;

    private final TestServiceRequestHandler mRequestHandler;
    private final ServerSession mServerSession;

    public FakeObexServer() throws IOException {

        mSendToServer = new PipedOutputStream();
        mReceiveFromServer = new PipedInputStream();
        mReceiveFromClient = new PipedInputStream(mSendToServer);
        mSendToClient = new PipedOutputStream(mReceiveFromServer);

        // Transports
        mServerTransport = new TestObexTransport(mReceiveFromClient, mSendToClient, true);
        mClientTransport = new TestObexTransport(mReceiveFromServer, mSendToServer, true);

        mRequestHandler = new TestServiceRequestHandler(this);
        mServerSession = new ServerSession(mServerTransport, mRequestHandler, null);
    }

    /**
     * Get a transport for use with a client.
     *
     * <p>You can use the openInputStream() and openOutputStream() to get the underlying stream
     * objects and inject them into your objects under test.
     */
    public ObexTransport getClientTransport() {
        return mClientTransport;
    }

    /**
     * Directly create a session with this server.
     *
     * <p>This can be used to quickly test request objects that need a ClientSession
     */
    public ClientSession getClientSession() throws IOException {
        return new ClientSession(mClientTransport);
    }

    /**
     * This will close the underlying transport, which will close the streams given to us.
     *
     * <p>By specification, servers themselves cannot issue an OBEX session level disconnect.
     */
    public void close() {
        mServerSession.close();
    }

    // *********************************************************************************************
    // * Server Operations
    // *********************************************************************************************

    public int onConnect(final HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_OK;
    }

    public void onDisconnect(final HeaderSet request, HeaderSet reply) {}

    public int onGet(final Operation op) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public int onPut(final Operation op) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public int onAbort(final HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public int onSetPath(
            final HeaderSet request, HeaderSet reply, final boolean backup, final boolean create) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public void onClose() {}

    /**
     * Send a response to a client with the given headers and payload
     *
     * @param op The Operation object representing the ongoing operation with the client
     * @param replyHeaders The HeaderSet to return to the client
     * @param bytes The payload to send in the response, if any
     */
    public final int sendResponse(Operation op, HeaderSet replyHeaders, byte[] bytes) {
        int responseCode = ResponseCodes.OBEX_HTTP_OK;
        OutputStream outStream = null;
        int maxChunkSize = 0;
        int bytesToWrite = 0;
        int bytesWritten = 0;

        try {
            op.sendHeaders(replyHeaders); // Do this before getting chunk size
            outStream = op.openOutputStream();
            if (bytes == null) {
                op.noBodyHeader();
                outStream.flush();
            } else {
                maxChunkSize = op.getMaxPacketSize();
                while (bytesWritten < bytes.length) {
                    bytesToWrite = Math.min(maxChunkSize, bytes.length - bytesWritten);
                    outStream.write(bytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }
            }
        } catch (IOException e) {
            responseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        } finally {
            // Make sure we close
            if (outStream != null) {
                try {
                    outStream.close();
                } catch (IOException e) {
                    // drop, as we're closing anyways
                }
            }
        }
        // If we didn't write everything then send the error code
        if (bytes != null && bytesWritten != bytes.length) {
            responseCode = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        // Otherwise, success!
        return responseCode;
    }

    // *********************************************************************************************
    // * Transport
    // *********************************************************************************************

    public static class TestObexTransport implements ObexTransport {

        private final InputStream mInput;
        private final OutputStream mOutput;
        private boolean mIsSrmSupported;

        public TestObexTransport(InputStream input, OutputStream output, boolean isSrmSupported) {
            mInput = input;
            mOutput = output;
            setSrmSupported(isSrmSupported);
        }

        @Override
        public DataInputStream openDataInputStream() throws IOException {
            return new DataInputStream(openInputStream());
        }

        @Override
        public DataOutputStream openDataOutputStream() throws IOException {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return mInput;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return mOutput;
        }

        @Override
        public int getMaxReceivePacketSize() {
            return -1;
        }

        @Override
        public int getMaxTransmitPacketSize() {
            return -1;
        }

        @Override
        public void connect() throws IOException {}

        @Override
        public void create() throws IOException {}

        @Override
        public void disconnect() throws IOException {}

        @Override
        public void listen() throws IOException {}

        @Override
        public void close() throws IOException {
            mInput.close();
            mOutput.close();
        }

        public boolean isSrmSupported() {
            return mIsSrmSupported;
        }

        public void setSrmSupported(boolean isSrmSupported) {
            mIsSrmSupported = isSrmSupported;
        }
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
    private static class TestServiceRequestHandler extends ServerRequestHandler {
        private final FakeObexServer mServer;

        TestServiceRequestHandler(FakeObexServer server) {
            mServer = server;
        }

        @Override
        public int onConnect(final HeaderSet request, HeaderSet reply) {
            return mServer.onConnect(request, reply);
        }

        @Override
        public void onDisconnect(final HeaderSet request, HeaderSet reply) {
            mServer.onDisconnect(request, reply);
        }

        @Override
        public int onGet(final Operation op) {
            return mServer.onGet(op);
        }

        @Override
        public int onPut(final Operation op) {
            return mServer.onPut(op);
        }

        @Override
        public int onAbort(final HeaderSet request, HeaderSet reply) {
            return mServer.onAbort(request, reply);
        }

        @Override
        public int onSetPath(
                final HeaderSet request,
                HeaderSet reply,
                final boolean backup,
                final boolean create) {
            return mServer.onSetPath(request, reply, backup, create);
        }

        @Override
        public void onClose() {
            mServer.onClose();
        }
    }
}
