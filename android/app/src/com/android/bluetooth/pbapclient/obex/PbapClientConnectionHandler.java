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
package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.ObexAppParameters;
import com.android.bluetooth.R;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ResponseCodes;
import com.android.vcard.VCardEntry;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Bluetooth/pbapclient/PbapClientConnectionHandler is responsible
 * for connecting, disconnecting and downloading contacts from the
 * PBAP PSE when commanded. It receives all direction from the
 * controlling state machine.
 */
class PbapClientConnectionHandler extends Handler {
    private static final String TAG = PbapClientConnectionHandler.class.getSimpleName();

    // Tradeoff: larger BATCH_SIZE leads to faster download rates, while smaller
    // BATCH_SIZE is less prone to IO Exceptions if there is a download in
    // progress when Bluetooth stack is torn down.
    private static final int DEFAULT_BATCH_SIZE = 250;

    static final int MSG_CONNECT = 1;
    static final int MSG_DISCONNECT = 2;
    static final int MSG_DOWNLOAD = 3;

    static final int L2CAP_INVALID_PSM = -1;
    static final int RFCOMM_INVALID_CHANNEL_ID = -1;

    // The following constants are pulled from the Bluetooth Phone Book Access Profile specification
    // 1.1
    private static final byte[] PBAP_TARGET =
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

    private Account mAccount;
    private BluetoothSocket mSocket;
    private final BluetoothDevice mDevice;
    private final int mLocalSupportedFeatures;
    // PSE SDP Record for current device.
    private PbapSdpRecord mPseRec = null;
    private ClientSession mObexSession;
    private PbapClientService mService;
    private PbapClientObexAuthenticator mAuth = null;
    private final PbapClientStateMachineOld mPbapClientStateMachine;
    private boolean mAccountCreated;

    /**
     * Constructs PCEConnectionHandler object
     *
     * @param pceHandlerbuild To build PbapClientConnectionHandler Instance.
     */
    PbapClientConnectionHandler(Builder pceHandlerbuild) {
        super(pceHandlerbuild.mLooper);

        if (Flags.pbapClientStorageRefactor()) {
            Log.w(TAG, "This object is no longer used in this configuration");
        }

        mDevice = pceHandlerbuild.mDevice;
        mLocalSupportedFeatures = pceHandlerbuild.mLocalSupportedFeatures;
        mService = pceHandlerbuild.mService;
        mPbapClientStateMachine = pceHandlerbuild.mClientStateMachine;
        mAuth = new PbapClientObexAuthenticator();
        mAccount =
                new Account(
                        mDevice.getAddress(),
                        mService.getString(R.string.pbap_client_account_type));
    }

    public static class Builder {

        private Looper mLooper;
        private PbapClientService mService;
        private BluetoothDevice mDevice;
        private int mLocalSupportedFeatures;
        private PbapClientStateMachineOld mClientStateMachine;

        public Builder setLooper(Looper loop) {
            this.mLooper = loop;
            return this;
        }

        public Builder setLocalSupportedFeatures(int features) {
            this.mLocalSupportedFeatures = features;
            return this;
        }

        public Builder setClientSM(PbapClientStateMachineOld clientStateMachine) {
            this.mClientStateMachine = clientStateMachine;
            return this;
        }

        public Builder setRemoteDevice(BluetoothDevice device) {
            this.mDevice = device;
            return this;
        }

        public Builder setService(PbapClientService service) {
            this.mService = service;
            return this;
        }

        public PbapClientConnectionHandler build() {
            PbapClientConnectionHandler pbapClientHandler = new PbapClientConnectionHandler(this);
            return pbapClientHandler;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        Log.d(TAG, "Handling Message = " + msg.what);
        switch (msg.what) {
            case MSG_CONNECT:
                mPseRec = (PbapSdpRecord) msg.obj;

                /* To establish a connection, first open a socket and then create an OBEX session */
                if (connectSocket()) {
                    Log.d(TAG, "Socket connected");
                } else {
                    Log.w(TAG, "Socket CONNECT Failure ");
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachineOld.MSG_CONNECTION_FAILED);
                    return;
                }

                if (connectObexSession()) {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachineOld.MSG_CONNECTION_COMPLETE);
                } else {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachineOld.MSG_CONNECTION_FAILED);
                }
                break;

            case MSG_DISCONNECT:
                Log.d(TAG, "Starting Disconnect");
                try {
                    if (mObexSession != null) {
                        Log.d(TAG, "obexSessionDisconnect" + mObexSession);
                        mObexSession.disconnect(null);
                        mObexSession.close();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "DISCONNECT Failure ", e);
                } finally {
                    Log.d(TAG, "Closing Socket");
                    closeSocket();
                }
                Log.d(TAG, "Completing Disconnect");
                if (mAccountCreated) {
                    removeAccount();
                }
                removeCallLog();

                mPbapClientStateMachine.sendMessage(
                        PbapClientStateMachineOld.MSG_CONNECTION_CLOSED);
                break;

            case MSG_DOWNLOAD:
                mAccountCreated = addAccount();
                if (!mAccountCreated) {
                    Log.e(TAG, "Account creation failed.");
                    return;
                }
                if (mPseRec.isRepositorySupported(PbapSdpRecord.REPOSITORY_FAVORITES)) {
                    downloadContacts(PbapPhonebook.FAVORITES_PATH);
                }
                if (mPseRec.isRepositorySupported(PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK)) {
                    downloadContacts(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
                }
                if (mPseRec.isRepositorySupported(PbapSdpRecord.REPOSITORY_SIM_CARD)) {
                    downloadContacts(PbapPhonebook.SIM_PHONEBOOK_PATH);
                }

                Map<String, Integer> callCounter = new HashMap<>();
                downloadCallLog(PbapPhonebook.MCH_PATH, callCounter);
                downloadCallLog(PbapPhonebook.ICH_PATH, callCounter);
                downloadCallLog(PbapPhonebook.OCH_PATH, callCounter);
                break;

            default:
                Log.w(TAG, "Received Unexpected Message");
        }
    }

    @VisibleForTesting
    synchronized void setPseRecord(PbapSdpRecord record) {
        mPseRec = record;
    }

    @VisibleForTesting
    synchronized BluetoothSocket getSocket() {
        return mSocket;
    }

    /* Utilize SDP, if available, to create a socket connection over L2CAP, RFCOMM specified
     * channel, or RFCOMM default channel. */
    @VisibleForTesting
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786
    synchronized boolean connectSocket() {
        try {
            /* Use BluetoothSocket to connect */
            if (mPseRec == null) {
                // BackWardCompatibility: Fall back to create RFCOMM through UUID.
                Log.v(TAG, "connectSocket: UUID: " + BluetoothUuid.PBAP_PSE.getUuid());
                mSocket =
                        mDevice.createRfcommSocketToServiceRecord(BluetoothUuid.PBAP_PSE.getUuid());
            } else if (mPseRec.getL2capPsm() != L2CAP_INVALID_PSM) {
                Log.v(TAG, "connectSocket: PSM: " + mPseRec.getL2capPsm());
                mSocket = mDevice.createL2capSocket(mPseRec.getL2capPsm());
            } else if (mPseRec.getRfcommChannelNumber() != RFCOMM_INVALID_CHANNEL_ID) {
                Log.v(TAG, "connectSocket: channel: " + mPseRec.getRfcommChannelNumber());
                mSocket = mDevice.createRfcommSocket(mPseRec.getRfcommChannelNumber());
            } else {
                Log.w(TAG, "connectSocket: transport PSM or channel ID not specified");
                return false;
            }

            if (mSocket != null) {
                mSocket.connect();
                return true;
            } else {
                Log.w(TAG, "Could not create socket");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting socket", e);
        }
        return false;
    }

    /* Connect an OBEX session over the already connected socket.  First establish an OBEX Transport
     * abstraction, then establish a Bluetooth Authenticator, and finally issue the connect call */
    @VisibleForTesting
    boolean connectObexSession() {
        boolean connectionSuccessful = false;

        try {
            Log.v(TAG, "Start Obex Client Session");
            BluetoothObexTransport transport = new BluetoothObexTransport(mSocket);
            mObexSession = new ClientSession(transport);
            mObexSession.setAuthenticator(mAuth);

            HeaderSet connectionRequest = new HeaderSet();
            connectionRequest.setHeader(HeaderSet.TARGET, PBAP_TARGET);

            if (mPseRec != null) {
                Log.d(TAG, "Remote PbapSupportedFeatures " + mPseRec.getSupportedFeatures());

                ObexAppParameters oap = new ObexAppParameters();

                if (mPseRec.getProfileVersion() >= PbapSdpRecord.VERSION_1_2) {
                    oap.add(
                            PbapApplicationParameters.OAP_PBAP_SUPPORTED_FEATURES,
                            mLocalSupportedFeatures);
                }

                oap.addToHeaderSet(connectionRequest);
            }
            HeaderSet connectionResponse = mObexSession.connect(connectionRequest);

            connectionSuccessful =
                    (connectionResponse.getResponseCode() == ResponseCodes.OBEX_HTTP_OK);
            Log.d(TAG, "Success = " + Boolean.toString(connectionSuccessful));
        } catch (IOException | NullPointerException e) {
            // Will get NPE if a null mSocket is passed to BluetoothObexTransport.
            // mSocket can be set to null if an abort() --> closeSocket() was called between
            // the calls to connectSocket() and connectObexSession().
            Log.w(TAG, "CONNECT Failure ", e);
            closeSocket();
        }
        return connectionSuccessful;
    }

    void abort() {
        // Perform forced cleanup, it is ok if the handler throws an exception this will free the
        // handler to complete what it is doing and finish with cleanup.
        closeSocket();
        this.getLooper().getThread().interrupt();
    }

    private synchronized void closeSocket() {
        try {
            if (mSocket != null) {
                Log.d(TAG, "Closing socket" + mSocket);
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when closing socket", e);
            mSocket = null;
        }
    }

    @VisibleForTesting
    void downloadContacts(String path) {
        try {
            PhonebookPullRequest processor =
                    new PhonebookPullRequest(mPbapClientStateMachine.getContext());

            PbapApplicationParameters params =
                    new PbapApplicationParameters(
                            PbapApplicationParameters.PROPERTIES_ALL,
                            /* format, unused */ (byte) 0,
                            PbapApplicationParameters.RETURN_SIZE_ONLY,
                            /* list start offeset, start from beginning */ 0);

            // Download contacts in batches of size DEFAULT_BATCH_SIZE
            RequestPullPhonebookMetadata requestPbSize =
                    new RequestPullPhonebookMetadata(path, params);
            requestPbSize.execute(mObexSession);

            int numberOfContactsRemaining = requestPbSize.getMetadata().getSize();
            int startOffset = 0;
            if (PbapPhonebook.LOCAL_PHONEBOOK_PATH.equals(path)) {
                // PBAP v1.2.3, Sec 3.1.5. The first contact in pb is owner card 0.vcf, which we
                // do not want to download. The other phonebook objects (e.g., fav) don't have an
                // owner card, so they don't need an offset.
                startOffset = 1;
                // "-1" because Owner Card 0.vcf is also included in /pb, but not in /fav.
                numberOfContactsRemaining -= 1;
            }

            while ((numberOfContactsRemaining > 0)
                    && (startOffset <= PbapApplicationParameters.MAX_PHONEBOOK_SIZE)) {
                int numberOfContactsToDownload =
                        Math.min(
                                Math.min(DEFAULT_BATCH_SIZE, numberOfContactsRemaining),
                                PbapApplicationParameters.MAX_PHONEBOOK_SIZE - startOffset + 1);

                params =
                        new PbapApplicationParameters(
                                PbapApplicationParameters.PROPERTIES_ALL,
                                PbapPhonebook.FORMAT_VCARD_30,
                                numberOfContactsToDownload,
                                startOffset);

                RequestPullPhonebook request = new RequestPullPhonebook(path, params);
                request.execute(mObexSession);
                List<VCardEntry> vcards = request.getList();
                for (VCardEntry v : vcards) {
                    v.setAccount(mAccount);

                    // mark each vcard as a favorite
                    if (PbapPhonebook.FAVORITES_PATH.equals(path)) {
                        v.setStarred(true);
                    }
                }
                processor.setResults(vcards);
                processor.onPullComplete();

                startOffset += numberOfContactsToDownload;
                numberOfContactsRemaining -= numberOfContactsToDownload;
            }
            if ((startOffset > PbapApplicationParameters.MAX_PHONEBOOK_SIZE)
                    && (numberOfContactsRemaining > 0)) {
                Log.w(TAG, "Download contacts incomplete, index exceeded upper limit.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Download contacts failure", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Download contacts failure: " + e.getMessage(), e);
        }
    }

    @VisibleForTesting
    void downloadCallLog(String path, Map<String, Integer> callCounter) {
        try {
            PbapApplicationParameters params =
                    new PbapApplicationParameters(
                            /* properties, unused for call logs */ 0,
                            PbapPhonebook.FORMAT_VCARD_30,
                            0,
                            0);

            RequestPullPhonebook request = new RequestPullPhonebook(path, params);
            request.execute(mObexSession);
            CallLogPullRequest processor =
                    new CallLogPullRequest(
                            mPbapClientStateMachine.getContext(), path, callCounter, mAccount);
            processor.setResults(request.getList());
            processor.onPullComplete();
        } catch (IOException e) {
            Log.e(TAG, "Download call log failure", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Download call log failure: " + e.getMessage(), e);
        }
    }

    @VisibleForTesting
    boolean addAccount() {
        if (mService.addAccount(mAccount)) {
            Log.d(TAG, "Added account " + mAccount);
            return true;
        } else {
            Log.e(TAG, "Failed to add account " + mAccount);
        }
        return false;
    }

    @VisibleForTesting
    void removeAccount() {
        if (mService.removeAccount(mAccount)) {
            Log.d(TAG, "Removed account " + mAccount);
        } else {
            Log.e(TAG, "Failed to remove account " + mAccount);
        }
    }

    @VisibleForTesting
    void removeCallLog() {
        try {
            // need to check call table is exist ?
            if (mService.getContentResolver() == null) {
                Log.d(TAG, "CallLog ContentResolver is not found");
                return;
            }
            mService.getContentResolver()
                    .delete(
                            CallLog.Calls.CONTENT_URI,
                            Calls.PHONE_ACCOUNT_ID + "=?",
                            new String[] {mAccount.name});
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Call Logs could not be deleted, they may not exist yet.");
        }
    }
}
