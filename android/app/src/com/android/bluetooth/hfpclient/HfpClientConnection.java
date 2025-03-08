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
package com.android.bluetooth.hfpclient;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.Objects;
import java.util.UUID;

public class HfpClientConnection extends Connection {
    private static final String TAG = HfpClientConnection.class.getSimpleName();

    private static final String EVENT_SCO_CONNECT = "com.android.bluetooth.hfpclient.SCO_CONNECT";
    private static final String EVENT_SCO_DISCONNECT =
            "com.android.bluetooth.hfpclient.SCO_DISCONNECT";

    private final BluetoothDevice mDevice;
    private HfpClientCall mCurrentCall;
    private final HfpClientConnectionService mConnServ;
    private final HeadsetClientServiceInterface mServiceInterface;

    private int mPreviousCallState = -1;
    private boolean mClosed;
    private boolean mClosing = false;
    private boolean mLocalDisconnect;
    private boolean mAdded;

    // Constructor to be used when there's an existing call (such as that created on the AG or
    // when connection happens and we see calls for the first time).
    public HfpClientConnection(
            BluetoothDevice device,
            HfpClientCall call,
            HfpClientConnectionService connServ,
            HeadsetClientServiceInterface serviceInterface) {
        mDevice = device;
        mConnServ = connServ;
        mServiceInterface = serviceInterface;
        mCurrentCall = requireNonNull(call);

        handleCallChanged();
        finishInitializing();
    }

    // Constructor to be used when a call is initiated on the HF. The call handle is obtained by
    // using the dial() command.
    public HfpClientConnection(
            BluetoothDevice device,
            Uri number,
            HfpClientConnectionService connServ,
            HeadsetClientServiceInterface serviceInterface) {
        mDevice = device;
        mConnServ = connServ;
        mServiceInterface = serviceInterface;
        mCurrentCall = mServiceInterface.dial(mDevice, number.getSchemeSpecificPart());
        if (mCurrentCall == null) {
            close(DisconnectCause.ERROR);
            error("Failed to create the call, dial failed.");
            return;
        }

        setInitializing();
        setDialing();
        finishInitializing();
    }

    void finishInitializing() {
        setAudioModeIsVoip(false);
        Uri number = Uri.fromParts(PhoneAccount.SCHEME_TEL, mCurrentCall.getNumber(), null);
        setAddress(number, TelecomManager.PRESENTATION_ALLOWED);
        setConnectionCapabilities(
                CAPABILITY_SUPPORT_HOLD
                        | CAPABILITY_MUTE
                        | CAPABILITY_SEPARATE_FROM_CONFERENCE
                        | CAPABILITY_DISCONNECT_FROM_CONFERENCE
                        | (getState() == STATE_ACTIVE || getState() == STATE_HOLDING
                                ? CAPABILITY_HOLD
                                : 0));
    }

    public UUID getUUID() {
        return mCurrentCall.getUUID();
    }

    public void onHfpDisconnected() {
        close(DisconnectCause.ERROR);
    }

    public void onAdded() {
        mAdded = true;
    }

    public HfpClientCall getCall() {
        return mCurrentCall;
    }

    public boolean inConference() {
        return mAdded
                && mCurrentCall != null
                && mCurrentCall.isMultiParty()
                && getState() != Connection.STATE_DISCONNECTED;
    }

    public void enterPrivateMode() {
        mServiceInterface.enterPrivateMode(mDevice, mCurrentCall.getId());
        setActive();
    }

    public void updateCall(HfpClientCall call) {
        if (call == null) {
            error("Updating call to a null value.");
            return;
        }
        mCurrentCall = call;
    }

    public void handleCallChanged() {
        HfpClientConference conference = (HfpClientConference) getConference();
        int state = mCurrentCall.getState();

        debug("Got call state change to " + state);
        switch (state) {
            case HfpClientCall.CALL_STATE_ACTIVE:
                setActive();
                if (conference != null) {
                    conference.setActive();
                }
                break;
            case HfpClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD:
            case HfpClientCall.CALL_STATE_HELD:
                setOnHold();
                if (conference != null) {
                    conference.setOnHold();
                }
                break;
            case HfpClientCall.CALL_STATE_DIALING:
            case HfpClientCall.CALL_STATE_ALERTING:
                setDialing();
                break;
            case HfpClientCall.CALL_STATE_INCOMING:
            case HfpClientCall.CALL_STATE_WAITING:
                setRinging();
                break;
            case HfpClientCall.CALL_STATE_TERMINATED:
                if (mPreviousCallState == HfpClientCall.CALL_STATE_INCOMING
                        || mPreviousCallState == HfpClientCall.CALL_STATE_WAITING) {
                    close(DisconnectCause.MISSED);
                } else if (mLocalDisconnect) {
                    close(DisconnectCause.LOCAL);
                } else {
                    close(DisconnectCause.REMOTE);
                }
                break;
            default:
                Log.wtf(TAG, "[" + mDevice + "]Unexpected phone state " + state);
        }
        mPreviousCallState = state;
    }

    public synchronized void close(int cause) {
        debug("Closing call " + mCurrentCall + "state: " + mClosed);
        if (mClosed) {
            return;
        }
        debug("Setting " + mCurrentCall + " to disconnected " + getTelecomCallId());
        setDisconnected(new DisconnectCause(cause));

        mClosed = true;
        mCurrentCall = null;

        destroy();
    }

    public synchronized boolean isClosing() {
        return mClosing;
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    @Override
    public synchronized void onPlayDtmfTone(char c) {
        debug("onPlayDtmfTone " + c + " " + mCurrentCall);
        if (!mClosed) {
            mServiceInterface.sendDTMF(mDevice, (byte) c);
        }
    }

    @Override
    public synchronized void onDisconnect() {
        debug("onDisconnect call: " + mCurrentCall + " state: " + mClosed);
        // The call is not closed so we should send a terminate here.
        if (!mClosed) {
            mServiceInterface.terminateCall(mDevice, mCurrentCall);
            mLocalDisconnect = true;
            mClosing = true;
        }
    }

    @Override
    public void onAbort() {
        debug("onAbort " + mCurrentCall);
        onDisconnect();
    }

    @Override
    public synchronized void onHold() {
        debug("onHold " + mCurrentCall);
        if (!mClosed) {
            mServiceInterface.holdCall(mDevice);
        }
    }

    @Override
    public synchronized void onUnhold() {
        if (mConnServ.getAllConnections().size() > 1) {
            Log.w(TAG, "Ignoring unhold; call hold on the foreground call");
            return;
        }
        debug("onUnhold " + mCurrentCall);
        if (!mClosed) {
            mServiceInterface.acceptCall(mDevice, HeadsetClientServiceInterface.CALL_ACCEPT_HOLD);
        }
    }

    @Override
    public synchronized void onAnswer() {
        debug("onAnswer " + mCurrentCall);
        if (!mClosed) {
            mServiceInterface.acceptCall(mDevice, HeadsetClientServiceInterface.CALL_ACCEPT_NONE);
        }
    }

    @Override
    public synchronized void onReject() {
        debug("onReject " + mCurrentCall);
        if (!mClosed) {
            mServiceInterface.rejectCall(mDevice);
        }
    }

    @Override
    public void onCallEvent(String event, Bundle extras) {
        debug("onCallEvent(" + event + ", " + extras + ")");
        if (mClosed) {
            return;
        }
        switch (event) {
            case EVENT_SCO_CONNECT:
                mServiceInterface.connectAudio(mDevice);
                break;
            case EVENT_SCO_DISCONNECT:
                mServiceInterface.disconnectAudio(mDevice);
                break;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HfpClientConnection h)) {
            return false;
        }

        return Objects.equals(h.getAddress(), getAddress());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getAddress());
    }

    @Override
    public String toString() {
        return "HfpClientConnection{"
                + getAddress()
                + ","
                + stateToString(getState())
                + ","
                + mCurrentCall
                + "}";
    }

    private void debug(String message) {
        Log.d(TAG, "[" + mDevice + "]: " + message);
    }

    private void error(String message) {

        Log.e(TAG, "[" + mDevice + "]: " + message);
    }
}
