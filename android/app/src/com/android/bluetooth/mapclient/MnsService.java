/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.bluetooth.mapclient;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.IObexConnectionHandler;
import com.android.bluetooth.ObexServerSockets;
import com.android.bluetooth.Utils;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.obex.ServerSession;

import java.io.IOException;

/** Message Notification Server implementation */
public class MnsService {
    private static final String TAG = MnsService.class.getSimpleName();

    static final int EVENT_REPORT = 1001; // for Client
    private static final int MNS_VERSION = 0x0104; // MAP version 1.4

    private final SocketAcceptor mAcceptThread = new SocketAcceptor();
    private final MapClientService mMapClientService;

    private ObexServerSockets mServerSockets;

    private volatile boolean mShutdown = false; // Used to interrupt socket accept thread
    private int mSdpHandle = -1;

    MnsService(MapClientService service) {
        Log.v(TAG, "MnsService()");
        mMapClientService = service;
        mServerSockets = ObexServerSockets.create(mAcceptThread);
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(TAG, "SdpManagerNativeInterface is not available");
            return;
        }
        mSdpHandle =
                nativeInterface.createMapMnsRecord(
                        "MAP Message Notification Service",
                        mServerSockets.getRfcommChannel(),
                        mServerSockets.getL2capPsm(),
                        MNS_VERSION,
                        MasClient.MAP_SUPPORTED_FEATURES);
    }

    void stop() {
        Log.v(TAG, "stop()");
        mShutdown = true;
        cleanUpSdpRecord();
        if (mServerSockets != null) {
            mServerSockets.shutdown(false);
            mServerSockets = null;
        }
    }

    private void cleanUpSdpRecord() {
        if (mSdpHandle < 0) {
            Log.e(TAG, "cleanUpSdpRecord, SDP record never created");
            return;
        }
        int sdpHandle = mSdpHandle;
        mSdpHandle = -1;
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(
                    TAG,
                    "cleanUpSdpRecord failed, SdpManagerNativeInterface is not available,"
                            + " sdpHandle="
                            + sdpHandle);
            return;
        }
        Log.i(TAG, "cleanUpSdpRecord, mSdpHandle=" + sdpHandle);
        if (!nativeInterface.removeSdpRecord(sdpHandle)) {
            Log.e(TAG, "cleanUpSdpRecord, removeSdpRecord failed, sdpHandle=" + sdpHandle);
        }
    }

    private class SocketAcceptor implements IObexConnectionHandler {

        /**
         * Called when an unrecoverable error occurred in an accept thread. Close down the server
         * socket, and restart. TODO: Change to message, to call start in correct context.
         */
        @Override
        public synchronized void onAcceptFailed() {
            Log.e(TAG, "OnAcceptFailed");
            mServerSockets = null; // Will cause a new to be created when calling start.
            if (mShutdown) {
                Log.e(TAG, "Failed to accept incoming connection - shutdown");
            }
        }

        @Override
        public synchronized boolean onConnect(BluetoothDevice device, BluetoothSocket socket) {
            Log.d(TAG, "onConnect" + device + " SOCKET: " + socket);
            /* Signal to the service that we have received an incoming connection.*/
            MceStateMachine stateMachine = mMapClientService.getMceStateMachineForDevice(device);
            if (stateMachine == null) {
                Log.e(
                        TAG,
                        "Error: NO StateMachine for device: "
                                + device
                                + " (name: "
                                + Utils.getName(device));
                return false;
            } else if (stateMachine.getState() != STATE_CONNECTED) {
                Log.e(
                        TAG,
                        "Error: StateMachine for device: "
                                + device
                                + " (name: "
                                + Utils.getName(device)
                                + ") is not currently CONNECTED : "
                                + stateMachine.getCurrentState());
                return false;
            }
            MnsObexServer srv = new MnsObexServer(stateMachine);
            BluetoothObexTransport transport = new BluetoothObexTransport(socket);
            try {
                new ServerSession(transport, srv, null);
                return true;
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                return false;
            }
        }
    }
}
