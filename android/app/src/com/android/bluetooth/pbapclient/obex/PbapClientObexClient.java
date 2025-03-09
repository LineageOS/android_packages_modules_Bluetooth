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

package com.android.bluetooth.pbapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.android.bluetooth.ObexAppParameters;
import com.android.internal.annotations.VisibleForTesting;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ResponseCodes;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bluetooth/pbapclient/PbapClientConnectionHandler is responsible for connecting, disconnecting and
 * downloading contacts from the PBAP PSE when commanded. It receives all direction from the
 * controlling state machine.
 */
class PbapClientObexClient {
    private static final String TAG = PbapClientObexClient.class.getSimpleName();

    static final int MSG_CONNECT = 1;
    static final int MSG_DISCONNECT = 2;
    static final int MSG_REQUEST = 3;

    static final int L2CAP_INVALID_PSM = -1;
    static final int RFCOMM_INVALID_CHANNEL_ID = -1;

    // The following constants are pulled from the Bluetooth Phone Book Access Profile specification
    // 1.1
    private static final byte[] BLUETOOTH_UUID_PBAP_CLIENT =
            new byte[] {
                0x79,
                0x61,
                0x35,
                (byte) 0xf0,
                (byte) 0xf0,
                (byte) 0xc5,
                0x11,
                (byte) 0xd8,
                0x09,
                0x66,
                0x08,
                0x00,
                0x20,
                0x0c,
                (byte) 0x9a,
                0x66
            };

    private static final int PBAP_FEATURES_EXCLUDED = -1;

    public static final int TRANSPORT_NONE = -1;
    public static final int TRANSPORT_RFCOMM = 0;
    public static final int TRANSPORT_L2CAP = 1;

    private final BluetoothDevice mDevice;
    private final int mLocalSupportedFeatures;
    private int mState = STATE_DISCONNECTED;
    private final AtomicInteger mPsm = new AtomicInteger(L2CAP_INVALID_PSM);
    private final AtomicInteger mChannelId = new AtomicInteger(RFCOMM_INVALID_CHANNEL_ID);

    private final Handler mHandler;
    private final HandlerThread mThread;

    private PbapClientSocket mSocket; // Wraps a BluetoothSocket, for testability
    private ClientSession mObexSession;
    private PbapClientObexAuthenticator mAuth = null;

    /** Callback object used to be notified of when a request has been completed. */
    interface Callback {

        /*
         * Detailed State Diagram:
         *                                  +------------------------------+
         *                                  V                              |
         *                +---------- DISCONNECTED -----------+            |
         *                |            ^       ^              |            |
         *                V            |       |              V            |
         *    RFCOMM_CONNECTING        |       |          L2CAP_CONNECTING |
         *    (CONNECTING)             |       |              (CONNECTING) |
         *     |                       |       |                        |  |
         *     |      RFCOMM_DISCONNECTING   L2CAP_DISCONNECTING        |  |
         *     |      (DISCONNECTING)  ^       ^ (DISCONNECTING)        |  |
         *     V                       |       |                        V  |
         *  RFCOMM_CONNECTED -----+    |       |    +----- L2CAP_CONNECTED |
         *  (CONNECTING)          |    |       |    |         (CONNECTING) |
         *                        |    |       |    |                      |
         *                        V    |       |    V                      |
         *                        TRANSPORT_CONNECTED <-+                  |
         *                          (DIS/CONNECTING)    |                  |
         *                                 |            |                  |
         *                                 V            |                  |
         *               ABORT       OBEX_CONNECTING    |                  |
         *                ^  \         (CONNECTING)     |                  |
         *                |   \______      |            |                  |
         *                |          V     V           /-\                 |
         *     PROCESS_REQUEST <------ CONNECTED -----+ | +----------------+
         *                                 |            |
         *                                 V            |
         *                          OBEX_DISCONNECTING -+
         *                            (DISCONNECTING)
         *
         * The above is exactly what's going on under the hood, but is not how
         * we report outward.
         *
         * All the transport specific flavored states are wrapped into single
         * CONNECTING/DISCONNECTING states when reporting outwards, depending
         * on whether we're trying to establish or bring down the connection.
         * The connection is assumed fully connected when the OBEX session is
         * connected.
         */
        /**
         * Notify of a connection state change in the client
         *
         * @param oldState The old state of the client
         * @param newState The new state of the client
         */
        void onConnectionStateChanged(int oldState, int newState);

        /**
         * Notify of a result of a phonebook size request
         *
         * @param responseCode The status of the request
         * @param phonebook The phonebook this update is concerning
         * @param metadata The metadata object, containing size, DB identifier and any version
         *     counters relateding to the phonebook
         */
        void onGetPhonebookMetadataComplete(
                int responseCode, String phonebook, PbapPhonebookMetadata metadata);

        /**
         * Notify of the result of a phonebook download request
         *
         * @param responseCode The status of the request
         * @param phonebook The phonebook this update is concerning
         * @param contacts The list of entries downloaded as an object
         */
        void onPhonebookContactsDownloaded(
                int responseCode, String phonebook, PbapPhonebook contacts);
    }

    private final Callback mCallback;

    /**
     * Constructs a PbapClientObexClient object
     *
     * @param device The device this client should connect to
     * @param supportedFeatures Our local device's supported features
     * @param callback A callback object so you can receive updates on completed requests
     */
    PbapClientObexClient(BluetoothDevice device, int supportedFeatures, Callback callback) {
        this(device, supportedFeatures, callback, null);
    }

    @VisibleForTesting
    PbapClientObexClient(
            BluetoothDevice device, int supportedFeatures, Callback callback, Looper looper) {
        mDevice = requireNonNull(device);
        mCallback = requireNonNull(callback);
        mAuth = new PbapClientObexAuthenticator();
        mLocalSupportedFeatures = supportedFeatures;

        // Allow for injection of test looper
        if (looper == null) {
            mThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            mThread.start();
            looper = mThread.getLooper();
        } else {
            mThread = null;
        }

        mHandler = new PbapClientObexClientHandler(looper);
    }

    /**
     * Get the current connection state of this PBAP Client OBEX client
     *
     * <p>This is thread safe to use
     */
    public synchronized int getConnectionState() {
        return mState;
    }

    /**
     * Set the connection state of this client and notify the callback object of any new states
     *
     * <p>This is thread safe to use
     */
    private void setConnectionState(int newState) {
        int oldState = -1;
        synchronized (this) {
            oldState = mState;
            mState = newState;
        }
        if (oldState != newState) {
            info("Connection state changed, old=" + oldState + ", new=" + mState);
            mCallback.onConnectionStateChanged(oldState, mState);
        }
    }

    /**
     * Determines if this client is connected to the server
     *
     * @return True if connected, False otherwise
     */
    public boolean isConnected() {
        return getConnectionState() == STATE_CONNECTED;
    }

    /**
     * Connect to the remove device's PBAP server
     *
     * <p>This function connects using the L2CAP transport and a provided PSM
     *
     * @param psm The L2CAP PSM to connect on
     */
    public void connectL2cap(int psm) {
        info("connectL2cap(psm=" + psm + ")");
        connect(TRANSPORT_L2CAP, psm);
    }

    /**
     * Connect to the remove device's PBAP server
     *
     * @param channel The RFCOMM channel ID to connect over
     */
    public void connectRfcomm(int channel) {
        info("connectRfcomm(channel=" + channel + ")");
        connect(TRANSPORT_RFCOMM, channel);
    }

    /**
     * Connect to the remove device's PBAP server
     *
     * @param transport The transport id, Transport.L2CAP or Transport.RFCOMM
     * @param psmOrChannel The L2CAP PSM or RFCOMM channel id to be used
     */
    private void connect(int transport, int psmOrChannel) {
        info(
                "connect(transport="
                        + transportToString(transport)
                        + ", channel/psm="
                        + psmOrChannel
                        + ")");
        mHandler.obtainMessage(MSG_CONNECT, transport, psmOrChannel).sendToTarget();
    }

    /**
     * Get the transport type of this OBEX Client
     *
     * @return The transport id, TRANSPORT_L2CAP, TRANSPORT_RFCOMM, or TRANSPORT_NONE
     */
    public int getTransportType() {
        if (getL2capPsm() != L2CAP_INVALID_PSM) {
            return TRANSPORT_L2CAP;
        }

        if (getRfcommChannelId() != RFCOMM_INVALID_CHANNEL_ID) {
            return TRANSPORT_RFCOMM;
        }

        return TRANSPORT_NONE;
    }

    /**
     * Get the L2CAP PSM of this OBEX Client
     *
     * @return The L2CAP PSM of this OBEX Client, or L2CAP_INVALID_PSM
     */
    public int getL2capPsm() {
        return mPsm.get();
    }

    /**
     * Get the RFCOMM channel id of this OBEX Client
     *
     * @return The RFCOMM channel id of this OBEX Client, or RFCOMM_INVALID_CHANNEL_ID
     */
    public int getRfcommChannelId() {
        return mChannelId.get();
    }

    /** Enqueue a request to download the size of a phonebook */
    public void requestPhonebookMetadata(String phonebook, PbapApplicationParameters params) {
        RequestPullPhonebookMetadata request = new RequestPullPhonebookMetadata(phonebook, params);
        mHandler.obtainMessage(MSG_REQUEST, request).sendToTarget();
    }

    /** Enqueue a request to download the contents of a phonebook */
    public void requestDownloadPhonebook(String phonebook, PbapApplicationParameters params) {
        RequestPullPhonebook request = new RequestPullPhonebook(phonebook, params);
        mHandler.obtainMessage(MSG_REQUEST, request).sendToTarget();
    }

    /**
     * Enqueue a request to disconnect from the remote device.
     *
     * <p>This request will be processed before any further download requests
     */
    public void disconnect() {
        info("disconnect: Enqueue disconnect");

        // Post to front of queue to disconnect immediately in front of any queued downloads
        mHandler.sendMessageAtFrontOfQueue(mHandler.obtainMessage(MSG_DISCONNECT));

        // Quit the handler thread. All future sendMessage() calls will fail and return false
        // Any ongoing request qill be finished, disconnect will be processed, and all other
        // messages will be removed
        if (mThread != null) {
            mThread.quitSafely();
        }
    }

    /**
     * Close this connection immediately by interrupting any ongoing operations and closing the
     * transport.
     *
     * <p>No lifecycle events will be sent. This OBEX Client object will be immediately made
     * unusable
     */
    public void close() {
        info("close: disconnect immediately");
        mHandler.getLooper().getThread().interrupt();
        closeSocket(mSocket);
        if (mThread != null) {
            mThread.quit();
        }
        setConnectionState(STATE_DISCONNECTED);
    }

    /** Handles this PBAP Client OBEX Client's requests */
    private class PbapClientObexClientHandler extends Handler {

        PbapClientObexClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            debug("Handling Message, type=" + messageToString(msg.what));
            switch (msg.what) {
                case MSG_CONNECT:
                    if (getConnectionState() != STATE_DISCONNECTED) {
                        warn("Cannot connect, device not disconnected");
                        return;
                    }

                    // To establish a connection, first open a socket and then create an OBEX
                    // session. The socket can use either the RFCOMM or L2CAP transport, depending
                    // on the capabilities of the server. The callee will have checked the SDP
                    // record and called connect() with the appropriate parameters
                    int transport = (int) msg.arg1;
                    int psmOrChannel = msg.arg2;

                    debug(
                            "Request connect, transport="
                                    + transportToString(transport)
                                    + ", psm/channel="
                                    + psmOrChannel
                                    + ", features="
                                    + mLocalSupportedFeatures);

                    if (transport == TRANSPORT_L2CAP) {
                        mPsm.set(psmOrChannel);
                    } else if (transport == TRANSPORT_RFCOMM) {
                        mChannelId.set(psmOrChannel);
                    } else {
                        error(
                                "Unrecognized transport, type='"
                                        + transportToString(transport)
                                        + "'");
                        return;
                    }

                    setConnectionState(STATE_CONNECTING);

                    mSocket = connectSocket(transport, psmOrChannel);
                    if (mSocket == null) {
                        mPsm.set(L2CAP_INVALID_PSM);
                        mChannelId.set(RFCOMM_INVALID_CHANNEL_ID);
                        setConnectionState(STATE_DISCONNECTED);
                        return;
                    }

                    mObexSession = connectObex(mSocket, mLocalSupportedFeatures);
                    if (mObexSession == null) {
                        closeSocket(mSocket);
                        mSocket = null;
                        mPsm.set(L2CAP_INVALID_PSM);
                        mChannelId.set(RFCOMM_INVALID_CHANNEL_ID);
                        setConnectionState(STATE_DISCONNECTED);
                        return;
                    }

                    setConnectionState(STATE_CONNECTED);
                    break;

                case MSG_DISCONNECT:
                    removeCallbacksAndMessages(null);

                    if (getConnectionState() != STATE_CONNECTED) {
                        warn("Cannot disconnect, device not connected");
                        return;
                    }

                    setConnectionState(STATE_DISCONNECTING);

                    // To disconnect, first bring down the OBEX session, then bring down the
                    // underlying transport/socket. If there are any errors while bringing down the
                    // OBEX session, log them, but move on to the transport anyways. Notify the
                    // callee so they can clean up the data this client has. Remove any pending
                    // messages, as we don't want this object to work after and we're going to quit
                    // safely
                    disconnectObex(mObexSession);
                    mObexSession = null;

                    closeSocket(mSocket);
                    mSocket = null;
                    mPsm.set(L2CAP_INVALID_PSM);
                    mChannelId.set(RFCOMM_INVALID_CHANNEL_ID);

                    setConnectionState(STATE_DISCONNECTED);
                    break;

                case MSG_REQUEST:
                    if (isConnected()) {
                        executeRequest((PbapClientRequest) msg.obj, mObexSession);
                    } else {
                        warn("Cannot issue request. Not connected");
                    }
                    break;

                default:
                    warn("Received unexpected message, id=" + messageToString(msg.what));
            }
        }
    }

    /* Utilize SDP, if available, to create a socket connection over L2CAP, RFCOMM specified
     * channel, or RFCOMM default channel. */
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    private PbapClientSocket connectSocket(int transport, int channelOrPsm) {
        debug(
                "Connect socket, transport="
                        + transportToString(transport)
                        + ", channelOrPsm="
                        + channelOrPsm);
        PbapClientSocket socket;
        try {
            if (transport == TRANSPORT_L2CAP) {
                socket = PbapClientSocket.getL2capSocketForDevice(mDevice, channelOrPsm);
            } else if (transport == TRANSPORT_RFCOMM) {
                socket = PbapClientSocket.getRfcommSocketForDevice(mDevice, channelOrPsm);
            } else {
                error("Failed to create socket, unknown transport requested");
                return null;
            }

            if (socket != null) {
                socket.connect();
                return socket;
            } else {
                error("Failed to create socket");
            }
        } catch (IOException e) {
            error("Exception while connecting transport", e);
        }
        return null;
    }

    /**
     * Connect an OBEX session over the already connected socket.
     *
     * <p>First establish an OBEX Transport abstraction, then establish a Bluetooth Authenticator
     * and finally issue the connect call
     */
    private ClientSession connectObex(PbapClientSocket socket, int supportedFeatures) {
        debug("Connect OBEX session, socket=" + socket + ", features=" + supportedFeatures);
        try {
            PbapClientObexTransport transport = new PbapClientObexTransport(socket);
            ClientSession obexSession = new ClientSession(transport);

            obexSession.setAuthenticator(mAuth);

            HeaderSet headers = new HeaderSet();
            headers.setHeader(HeaderSet.TARGET, BLUETOOTH_UUID_PBAP_CLIENT);

            if (supportedFeatures != PBAP_FEATURES_EXCLUDED) {
                ObexAppParameters oap = new ObexAppParameters();
                oap.add(PbapApplicationParameters.OAP_PBAP_SUPPORTED_FEATURES, supportedFeatures);
                oap.addToHeaderSet(headers);
            }

            HeaderSet responseHeaders = obexSession.connect(headers);

            info(
                    "Connection request response received, response code="
                            + responseHeaders.getResponseCode());
            if (responseHeaders.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                return obexSession;
            }
        } catch (IOException | NullPointerException | IllegalArgumentException e) {
            // Will get NPE if a null mSocket is passed to BluetoothObexTransport.
            // mSocket can be set to null if an abort() --> closeSocket() was called between
            // the calls to connectSocket() and connectObexSession().
            error("Error while connecting OBEX", e);
            closeSocket(socket);
        }
        return null;
    }

    private void executeRequest(PbapClientRequest request, ClientSession obexSession) {
        debug("executeRequest(request=" + request + ")");
        if (!isConnected()) {
            error("Cannot execute request " + request.toString() + ", we're not connected");
            notifyCaller(request);
            return;
        }

        try {
            request.execute(obexSession);
            notifyCaller(request);
        } catch (IOException e) {
            error("Request failed: " + request.toString());
            notifyCaller(request);
            disconnect();
        }
    }

    private void notifyCaller(PbapClientRequest request) {
        int type = request.getType();
        int responseCode = request.getResponseCode();
        String phonebook = null;

        debug("Notifying caller of request result - " + request.toString());
        switch (type) {
            case PbapClientRequest.TYPE_PULL_PHONEBOOK_METADATA:
                phonebook = ((RequestPullPhonebookMetadata) request).getPhonebook();
                PbapPhonebookMetadata metadata =
                        ((RequestPullPhonebookMetadata) request).getMetadata();
                mCallback.onGetPhonebookMetadataComplete(responseCode, phonebook, metadata);
                break;

            case PbapClientRequest.TYPE_PULL_PHONEBOOK:
                phonebook = ((RequestPullPhonebook) request).getPhonebook();
                PbapPhonebook contacts = ((RequestPullPhonebook) request).getContacts();
                mCallback.onPhonebookContactsDownloaded(responseCode, phonebook, contacts);
                break;
        }
    }

    private void disconnectObex(ClientSession obexSession) {
        debug("Disconnect OBEX, session=" + obexSession);
        try {
            obexSession.disconnect(null);
        } catch (IOException e) {
            error("Exception when disconnecting the PBAP Client OBEX session", e);
        }

        try {
            obexSession.close();
        } catch (IOException e) {
            error("Exception when closing the PBAP Client OBEX connection", e);
        }
    }

    private boolean closeSocket(PbapClientSocket socket) {
        debug("Disconnect socket transport");
        try {
            if (socket != null) {
                socket.close();
                return true;
            }
        } catch (IOException e) {
            error("Error when closing socket", e);
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(TAG);
        sb.append(" device=").append(mDevice);
        sb.append(" state=").append(BluetoothProfile.getConnectionStateName(getConnectionState()));

        int transport = getTransportType();
        sb.append(" transport=").append(transportToString(transport));
        if (getTransportType() == TRANSPORT_L2CAP) {
            sb.append(" psm=").append(getL2capPsm());
        } else if (transport == TRANSPORT_RFCOMM) {
            sb.append(" channel=").append(getRfcommChannelId());
        }

        sb.append(">");
        return sb.toString();
    }

    public static String transportToString(int transport) {
        switch (transport) {
            case TRANSPORT_NONE:
                return "TRANSPORT_NONE";
            case TRANSPORT_RFCOMM:
                return "TRANSPORT_RFCOMM";
            case TRANSPORT_L2CAP:
                return "TRANSPORT_L2CAP";
            default:
                return "TRANSPORT_RESERVED (" + transport + ")";
        }
    }

    private static String messageToString(int msg) {
        switch (msg) {
            case MSG_CONNECT:
                return "MSG_CONNECT";
            case MSG_DISCONNECT:
                return "MSG_DISCONNECT";
            case MSG_REQUEST:
                return "MSG_REQUEST";
            default:
                return "MSG_RESERVED (" + msg + ")";
        }
    }

    private void debug(String message) {
        Log.d(TAG, "[" + mDevice + "] " + message);
    }

    private void info(String message) {
        Log.i(TAG, "[" + mDevice + "] " + message);
    }

    private void warn(String message) {
        Log.w(TAG, "[" + mDevice + "] " + message);
    }

    private void error(String message) {
        Log.e(TAG, "[" + mDevice + "] " + message);
    }

    private void error(String message, Exception e) {
        Log.e(TAG, "[" + mDevice + "] " + message, e);
    }
}
