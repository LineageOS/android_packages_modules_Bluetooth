/*
 * Copyright (c) 2016 The Android Open Source Project
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

// Bluetooth Headset Client State Machine
//                (Disconnected)
//                  |        ^
//          CONNECT |        | DISCONNECTED
//                  V        |
//          (Connecting)   (Disconnecting)
//                  |        ^
//        CONNECTED |        | DISCONNECT
//                  V        |
//                  (Connected)
//                  |        |
//    CONNECT_AUDIO |        | DISCONNECT_AUDIO
//                  |        |
//                  (Audio_On)

package com.android.bluetooth.hfpclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClient.NetworkServiceState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.hfp.BluetoothHfpProtoEnums;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HeadsetClientStateMachine extends StateMachine {
    private static final String TAG = HeadsetClientStateMachine.class.getSimpleName();

    static final int NO_ACTION = 0;
    static final int IN_BAND_RING_ENABLED = 1;

    // external actions
    public static final int AT_OK = 0;
    public static final int CONNECT = 1;
    public static final int DISCONNECT = 2;
    public static final int CONNECT_AUDIO = 3;
    public static final int DISCONNECT_AUDIO = 4;
    public static final int VOICE_RECOGNITION_START = 5;
    public static final int VOICE_RECOGNITION_STOP = 6;
    public static final int SET_MIC_VOLUME = 7;
    public static final int SET_SPEAKER_VOLUME = 8;
    public static final int DIAL_NUMBER = 10;
    public static final int ACCEPT_CALL = 12;
    public static final int REJECT_CALL = 13;
    public static final int HOLD_CALL = 14;
    public static final int TERMINATE_CALL = 15;
    public static final int ENTER_PRIVATE_MODE = 16;
    public static final int SEND_DTMF = 17;
    public static final int EXPLICIT_CALL_TRANSFER = 18;
    public static final int DISABLE_NREC = 20;
    public static final int SEND_VENDOR_AT_COMMAND = 21;
    public static final int SEND_BIEV = 22;
    public static final int SEND_ANDROID_AT_COMMAND = 23;

    // internal actions
    @VisibleForTesting static final int QUERY_CURRENT_CALLS = 50;
    @VisibleForTesting static final int QUERY_OPERATOR_NAME = 51;
    @VisibleForTesting static final int SUBSCRIBER_INFO = 52;
    @VisibleForTesting static final int CONNECTING_TIMEOUT = 53;
    @VisibleForTesting static final int DISCONNECTING_TIMEOUT = 54;

    // special action to handle terminating specific call from multiparty call
    static final int TERMINATE_SPECIFIC_CALL = 53;

    // Timeouts.
    @VisibleForTesting static final int CONNECTING_TIMEOUT_MS = 10000; // 10s
    @VisibleForTesting static final int DISCONNECTING_TIMEOUT_MS = 10000; // 10s
    private static final int ROUTING_DELAY_MS = 250;

    static final int HF_ORIGINATED_CALL_ID = -1;
    private static final long OUTGOING_TIMEOUT_MILLI = 10 * 1000; // 10 seconds
    private static final long QUERY_CURRENT_CALLS_WAIT_MILLIS = 2 * 1000; // 2 seconds

    // Keep track of audio routing across all devices.
    private static boolean sAudioIsRouted = false;

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Connected mConnected;
    private final Disconnecting mDisconnecting;
    private final AudioOn mAudioOn;
    private State mPrevState;

    private final AdapterService mAdapterService;
    private final HeadsetClientService mService;
    private final HeadsetService mHeadsetService;

    // Set of calls that represent the accurate state of calls that exists on AG and the calls that
    // are currently in process of being notified to the AG from HF.
    @VisibleForTesting final Map<Integer, HfpClientCall> mCalls = new ConcurrentHashMap<>();
    // Set of calls received from AG via the AT+CLCC command. We use this map to update the mCalls
    // which is eventually used to inform the telephony stack of any changes to call on HF.
    private final Map<Integer, HfpClientCall> mCallsUpdate = new ConcurrentHashMap<>();

    private int mIndicatorNetworkState;
    private int mIndicatorNetworkType;
    private int mIndicatorNetworkSignal;
    private int mIndicatorBatteryLevel;
    private boolean mInBandRing;

    private String mOperatorName;
    @VisibleForTesting String mSubscriberInfo;

    // queue of send actions (pair action, action_data)
    @VisibleForTesting ArrayDeque<Pair<Integer, Object>> mQueuedActions;

    @VisibleForTesting int mAudioState;
    // Indicates whether audio can be routed to the device
    private boolean mAudioRouteAllowed;

    private final boolean mClccPollDuringCall;

    public int mAudioPolicyRemoteSupported;
    private BluetoothSinkAudioPolicy mHsClientAudioPolicy;
    private final int mConnectingTimePolicyProperty;
    private final int mInBandRingtonePolicyProperty;
    private final boolean mForceSetAudioPolicyProperty;

    @VisibleForTesting boolean mAudioWbs;

    @VisibleForTesting boolean mAudioSWB;

    private int mVoiceRecognitionActive;

    // currently connected device
    @VisibleForTesting BluetoothDevice mCurrentDevice = null;

    // general peer features and call handling features
    @VisibleForTesting int mPeerFeatures;
    @VisibleForTesting int mChldFeatures;

    // This is returned when requesting focus from AudioManager
    private AudioFocusRequest mAudioFocusRequest;

    private final AudioManager mAudioManager;
    private final NativeInterface mNativeInterface;
    private final VendorCommandResponseProcessor mVendorProcessor;

    // Accessor for the states, useful for reusing the state machines
    public IState getDisconnectedState() {
        return mDisconnected;
    }

    // Get if in band ring is currently enabled on device.
    public boolean getInBandRing() {
        return mInBandRing;
    }

    public void dump(StringBuilder sb) {
        if (mCurrentDevice != null) {
            ProfileService.println(sb, "==== StateMachine for " + mCurrentDevice + " ====");
            ProfileService.println(
                    sb,
                    "  mCurrentDevice: "
                            + mCurrentDevice
                            + "("
                            + Utils.getName(mCurrentDevice)
                            + ") "
                            + this.toString());
        }
        ProfileService.println(sb, "  mAudioState: " + mAudioState);
        ProfileService.println(sb, "  mAudioWbs: " + mAudioWbs);
        ProfileService.println(sb, "  mAudioSWB: " + mAudioSWB);
        ProfileService.println(sb, "  mIndicatorNetworkState: " + mIndicatorNetworkState);
        ProfileService.println(sb, "  mIndicatorNetworkType: " + mIndicatorNetworkType);
        ProfileService.println(sb, "  mIndicatorNetworkSignal: " + mIndicatorNetworkSignal);
        ProfileService.println(sb, "  mIndicatorBatteryLevel: " + mIndicatorBatteryLevel);
        ProfileService.println(sb, "  mOperatorName: " + mOperatorName);
        ProfileService.println(sb, "  mSubscriberInfo: " + mSubscriberInfo);
        ProfileService.println(sb, "  mAudioRouteAllowed: " + mAudioRouteAllowed);
        ProfileService.println(sb, "  mAudioPolicyRemoteSupported: " + mAudioPolicyRemoteSupported);
        ProfileService.println(sb, "  mHsClientAudioPolicy: " + mHsClientAudioPolicy);
        ProfileService.println(sb, "  mInBandRing: " + mInBandRing);

        ProfileService.println(sb, "  mCalls:");
        if (mCalls != null) {
            for (HfpClientCall call : mCalls.values()) {
                ProfileService.println(sb, "    " + call);
            }
        }

        ProfileService.println(sb, "  mCallsUpdate:");
        if (mCallsUpdate != null) {
            for (HfpClientCall call : mCallsUpdate.values()) {
                ProfileService.println(sb, "    " + call);
            }
        }

        // Dump the state machine logs
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        super.dump(new FileDescriptor(), printWriter, new String[] {});
        printWriter.flush();
        stringWriter.flush();
        ProfileService.println(sb, "  StateMachineLog:");
        Scanner scanner = new Scanner(stringWriter.toString());
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            ProfileService.println(sb, "    " + line);
        }
    }

    @Override
    protected String getLogRecString(Message msg) {
        StringBuilder builder = new StringBuilder();
        builder.append(getMessageName(msg.what));
        builder.append(": ");
        builder.append("arg1=")
                .append(msg.arg1)
                .append(", arg2=")
                .append(msg.arg2)
                .append(", obj=")
                .append(msg.obj);
        return builder.toString();
    }

    @VisibleForTesting
    static String getMessageName(int what) {
        switch (what) {
            case StackEvent.STACK_EVENT:
                return "STACK_EVENT";
            case CONNECT:
                return "CONNECT";
            case DISCONNECT:
                return "DISCONNECT";
            case CONNECT_AUDIO:
                return "CONNECT_AUDIO";
            case DISCONNECT_AUDIO:
                return "DISCONNECT_AUDIO";
            case VOICE_RECOGNITION_START:
                return "VOICE_RECOGNITION_START";
            case VOICE_RECOGNITION_STOP:
                return "VOICE_RECOGNITION_STOP";
            case SET_MIC_VOLUME:
                return "SET_MIC_VOLUME";
            case SET_SPEAKER_VOLUME:
                return "SET_SPEAKER_VOLUME";
            case DIAL_NUMBER:
                return "DIAL_NUMBER";
            case ACCEPT_CALL:
                return "ACCEPT_CALL";
            case REJECT_CALL:
                return "REJECT_CALL";
            case HOLD_CALL:
                return "HOLD_CALL";
            case TERMINATE_CALL:
                return "TERMINATE_CALL";
            case ENTER_PRIVATE_MODE:
                return "ENTER_PRIVATE_MODE";
            case SEND_DTMF:
                return "SEND_DTMF";
            case EXPLICIT_CALL_TRANSFER:
                return "EXPLICIT_CALL_TRANSFER";
            case DISABLE_NREC:
                return "DISABLE_NREC";
            case SEND_VENDOR_AT_COMMAND:
                return "SEND_VENDOR_AT_COMMAND";
            case SEND_BIEV:
                return "SEND_BIEV";
            case QUERY_CURRENT_CALLS:
                return "QUERY_CURRENT_CALLS";
            case QUERY_OPERATOR_NAME:
                return "QUERY_OPERATOR_NAME";
            case SUBSCRIBER_INFO:
                return "SUBSCRIBER_INFO";
            case CONNECTING_TIMEOUT:
                return "CONNECTING_TIMEOUT";
            case DISCONNECTING_TIMEOUT:
                return "DISCONNECTING_TIMEOUT";
            default:
                return "UNKNOWN(" + what + ")";
        }
    }

    @VisibleForTesting
    void addQueuedAction(int action) {
        addQueuedAction(action, 0);
    }

    private void addQueuedAction(int action, Object data) {
        mQueuedActions.add(new Pair<Integer, Object>(action, data));
    }

    private void addQueuedAction(int action, int data) {
        mQueuedActions.add(new Pair<Integer, Object>(action, data));
    }

    @VisibleForTesting
    HfpClientCall getCall(int... states) {
        debug("getFromCallsWithStates states:" + Arrays.toString(states));
        for (HfpClientCall c : mCalls.values()) {
            for (int s : states) {
                if (c.getState() == s) {
                    return c;
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    int callsInState(int state) {
        int i = 0;
        for (HfpClientCall c : mCalls.values()) {
            if (c.getState() == state) {
                i++;
            }
        }

        return i;
    }

    private void sendCallChangedIntent(HfpClientCall c) {
        debug("sendCallChangedIntent " + c);
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_CALL_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        if (mService.getPackageManager().hasSystemFeature(FEATURE_WATCH)) {
            debug("Send legacy call");
            intent.putExtra(
                    BluetoothHeadsetClient.EXTRA_CALL, HeadsetClientService.toLegacyCall(c));
        } else {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_CALL, c);
        }

        mService.sendBroadcast(
                intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
        HfpClientConnectionService.onCallChanged(c.getDevice(), c);
    }

    private void sendNetworkStateChangedIntent(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        NetworkServiceState networkServiceState =
                new NetworkServiceState(
                        device,
                        mIndicatorNetworkState == HeadsetClientHalConstants.NETWORK_STATE_AVAILABLE,
                        mOperatorName,
                        mIndicatorNetworkSignal,
                        mIndicatorNetworkType == HeadsetClientHalConstants.SERVICE_TYPE_ROAMING);

        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_NETWORK_SERVICE_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHeadsetClient.EXTRA_NETWORK_SERVICE_STATE, networkServiceState);

        mService.sendBroadcastMultiplePermissions(
                intent,
                new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
                Utils.getTempBroadcastOptions());
    }

    private boolean queryCallsStart() {
        debug("queryCallsStart");
        mNativeInterface.queryCurrentCalls(mCurrentDevice);
        addQueuedAction(QUERY_CURRENT_CALLS, 0);
        return true;
    }

    private void queryCallsDone() {
        debug("queryCallsDone");
        // mCalls has two types of calls:
        // (a) Calls that are received from AG of a previous iteration of queryCallsStart()
        // (b) Calls that are outgoing initiated from HF
        // mCallsUpdate has all calls received from queryCallsUpdate() in current iteration of
        // queryCallsStart().
        //
        // We use the following steps to make sure that calls are update correctly.
        //
        // If there are no calls initiated from HF (i.e. ID = -1) then:
        // 1. All IDs which are common in mCalls & mCallsUpdate are updated and the upper layers are
        // informed of the change calls (if any changes).
        // 2. All IDs that are in mCalls but *not* in mCallsUpdate will be removed from mCalls and
        // the calls should be terminated
        // 3. All IDs that are new in mCallsUpdated should be added as new calls to mCalls.
        //
        // If there is an outgoing HF call, it is important to associate that call with one of the
        // mCallsUpdated calls hence,
        // 1. If from the above procedure we get N extra calls (i.e. {3}):
        // choose the first call as the one to associate with the HF call.

        // Create set of IDs for added calls, removed calls and consistent calls.
        // WARN!!! Java Map -> Set has association hence changes to Set are reflected in the Map
        // itself (i.e. removing an element from Set removes it from the Map hence use copy).
        Set<Integer> currCallIdSet = new HashSet<Integer>();
        currCallIdSet.addAll(mCalls.keySet());
        // Remove the entry for unassigned call.
        currCallIdSet.remove(HF_ORIGINATED_CALL_ID);

        Set<Integer> newCallIdSet = new HashSet<Integer>();
        newCallIdSet.addAll(mCallsUpdate.keySet());

        // Added.
        Set<Integer> callAddedIds = new HashSet<Integer>();
        callAddedIds.addAll(newCallIdSet);
        callAddedIds.removeAll(currCallIdSet);

        // Removed.
        Set<Integer> callRemovedIds = new HashSet<Integer>();
        callRemovedIds.addAll(currCallIdSet);
        callRemovedIds.removeAll(newCallIdSet);

        // Retained.
        Set<Integer> callRetainedIds = new HashSet<Integer>();
        callRetainedIds.addAll(currCallIdSet);
        callRetainedIds.retainAll(newCallIdSet);

        debug(
                "currCallIdSet "
                        + mCalls.keySet()
                        + " newCallIdSet "
                        + newCallIdSet
                        + " callAddedIds "
                        + callAddedIds
                        + " callRemovedIds "
                        + callRemovedIds
                        + " callRetainedIds "
                        + callRetainedIds);

        // First thing is to try to associate the outgoing HF with a valid call.
        Integer hfOriginatedAssoc = -1;
        if (mCalls.containsKey(HF_ORIGINATED_CALL_ID)) {
            HfpClientCall c = mCalls.get(HF_ORIGINATED_CALL_ID);
            long cCreationElapsed = c.getCreationElapsedMilli();
            if (callAddedIds.size() > 0) {
                debug("Associating the first call with HF originated call");
                hfOriginatedAssoc = (Integer) callAddedIds.toArray()[0];
                mCalls.put(hfOriginatedAssoc, mCalls.get(HF_ORIGINATED_CALL_ID));
                mCalls.remove(HF_ORIGINATED_CALL_ID);

                // Adjust this call in above sets.
                callAddedIds.remove(hfOriginatedAssoc);
                callRetainedIds.add(hfOriginatedAssoc);
            } else if (SystemClock.elapsedRealtime() - cCreationElapsed > OUTGOING_TIMEOUT_MILLI) {
                warn("Outgoing call did not see a response, clear the calls and send CHUP");
                // We send a terminate because we are in a bad state and trying to
                // recover.
                terminateCall();

                // Clean out the state for outgoing call.
                for (Integer idx : mCalls.keySet()) {
                    HfpClientCall c1 = mCalls.get(idx);
                    c1.setState(HfpClientCall.CALL_STATE_TERMINATED);
                    sendCallChangedIntent(c1);
                }
                mCalls.clear();

                // We return here, if there's any update to the phone we should get a
                // follow up by getting some call indicators and hence update the calls.
                return;
            }
        }

        debug(
                "ADJUST: currCallIdSet "
                        + mCalls.keySet()
                        + " newCallIdSet "
                        + newCallIdSet
                        + " callAddedIds "
                        + callAddedIds
                        + " callRemovedIds "
                        + callRemovedIds
                        + " callRetainedIds "
                        + callRetainedIds);

        // Terminate & remove the calls that are done.
        for (Integer idx : callRemovedIds) {
            HfpClientCall c = mCalls.remove(idx);
            c.setState(HfpClientCall.CALL_STATE_TERMINATED);
            sendCallChangedIntent(c);
        }

        // Add the new calls.
        for (Integer idx : callAddedIds) {
            HfpClientCall c = mCallsUpdate.get(idx);
            mCalls.put(idx, c);
            sendCallChangedIntent(c);
        }

        // Update the existing calls.
        for (Integer idx : callRetainedIds) {
            HfpClientCall cOrig = mCalls.get(idx);
            HfpClientCall cUpdate = mCallsUpdate.get(idx);

            // If any of the fields differs, update and send intent
            if (!cOrig.getNumber().equals(cUpdate.getNumber())
                    || cOrig.getState() != cUpdate.getState()
                    || cOrig.isMultiParty() != cUpdate.isMultiParty()) {

                // Update the necessary fields.
                cOrig.setNumber(cUpdate.getNumber());
                cOrig.setState(cUpdate.getState());
                cOrig.setMultiParty(cUpdate.isMultiParty());

                // Send update with original object (UUID, idx).
                sendCallChangedIntent(cOrig);
            }
        }

        if (mCalls.size() > 0) {
            // Continue polling even if not enabled until the new outgoing call is associated with
            // a valid call on the phone. The polling would at most continue until
            // OUTGOING_TIMEOUT_MILLI. This handles the potential scenario where the phone creates
            // and terminates a call before the first QUERY_CURRENT_CALLS completes.
            if (mClccPollDuringCall || (mCalls.containsKey(HF_ORIGINATED_CALL_ID))) {
                sendMessageDelayed(
                        QUERY_CURRENT_CALLS,
                        mService.getResources()
                                .getInteger(R.integer.hfp_clcc_poll_interval_during_call));
            } else {
                if (getCall(HfpClientCall.CALL_STATE_INCOMING) != null) {
                    debug("Still have incoming call; polling");
                    sendMessageDelayed(QUERY_CURRENT_CALLS, QUERY_CURRENT_CALLS_WAIT_MILLIS);
                } else {
                    removeMessages(QUERY_CURRENT_CALLS);
                }
            }
        }

        mCallsUpdate.clear();
    }

    private void queryCallsUpdate(
            int id, int state, String number, boolean multiParty, boolean outgoing) {
        debug("queryCallsUpdate: " + id);
        mCallsUpdate.put(
                id,
                new HfpClientCall(
                        mCurrentDevice, id, state, number, multiParty, outgoing, mInBandRing));
    }

    private void acceptCall(int flag) {
        int action = -1;

        debug("acceptCall: (" + flag + ")");

        HfpClientCall c =
                getCall(HfpClientCall.CALL_STATE_INCOMING, HfpClientCall.CALL_STATE_WAITING);
        if (c == null) {
            c =
                    getCall(
                            HfpClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD,
                            HfpClientCall.CALL_STATE_HELD);

            if (c == null) {
                return;
            }
        }

        debug("Call to accept: " + c);
        switch (c.getState()) {
            case HfpClientCall.CALL_STATE_INCOMING:
                if (flag != BluetoothHeadsetClient.CALL_ACCEPT_NONE) {
                    return;
                }
                action = HeadsetClientHalConstants.CALL_ACTION_ATA;
                break;
            case HfpClientCall.CALL_STATE_WAITING:
                if (callsInState(HfpClientCall.CALL_STATE_ACTIVE) == 0) {
                    // if no active calls present only plain accept is allowed
                    if (flag != BluetoothHeadsetClient.CALL_ACCEPT_NONE) {
                        return;
                    }
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_2;
                    break;
                }

                // if active calls are present then we have the option to either terminate the
                // existing call or hold the existing call. We hold the other call by default.
                if (flag == BluetoothHeadsetClient.CALL_ACCEPT_HOLD
                        || flag == BluetoothHeadsetClient.CALL_ACCEPT_NONE) {
                    debug("Accepting call with accept and hold");
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_2;
                } else if (flag == BluetoothHeadsetClient.CALL_ACCEPT_TERMINATE) {
                    debug("Accepting call with accept and reject");
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_1;
                } else {
                    error("Accept call with invalid flag: " + flag);
                    return;
                }
                break;
            case HfpClientCall.CALL_STATE_HELD:
                if (flag == BluetoothHeadsetClient.CALL_ACCEPT_HOLD) {
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_2;
                } else if (flag == BluetoothHeadsetClient.CALL_ACCEPT_TERMINATE) {
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_1;
                } else if (getCall(HfpClientCall.CALL_STATE_ACTIVE) != null) {
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_3;
                } else {
                    action = HeadsetClientHalConstants.CALL_ACTION_CHLD_2;
                }
                break;
            case HfpClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD:
                action = HeadsetClientHalConstants.CALL_ACTION_BTRH_1;
                break;
            case HfpClientCall.CALL_STATE_ALERTING:
            case HfpClientCall.CALL_STATE_ACTIVE:
            case HfpClientCall.CALL_STATE_DIALING:
            default:
                return;
        }

        if (flag == BluetoothHeadsetClient.CALL_ACCEPT_HOLD) {
            // When unholding a call over Bluetooth make sure to route audio.
            routeHfpAudio(true);
        }

        if (mNativeInterface.handleCallAction(mCurrentDevice, action, 0)) {
            addQueuedAction(ACCEPT_CALL, action);
        } else {
            error("ERROR: Couldn't accept a call, action:" + action);
        }
    }

    private void rejectCall() {
        int action;

        debug("rejectCall");

        HfpClientCall c =
                getCall(
                        HfpClientCall.CALL_STATE_INCOMING,
                        HfpClientCall.CALL_STATE_WAITING,
                        HfpClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD,
                        HfpClientCall.CALL_STATE_HELD);
        if (c == null) {
            debug("No call to reject, returning.");
            return;
        }

        switch (c.getState()) {
            case HfpClientCall.CALL_STATE_INCOMING:
                action = HeadsetClientHalConstants.CALL_ACTION_CHUP;
                break;
            case HfpClientCall.CALL_STATE_WAITING:
            case HfpClientCall.CALL_STATE_HELD:
                action = HeadsetClientHalConstants.CALL_ACTION_CHLD_0;
                break;
            case HfpClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD:
                action = HeadsetClientHalConstants.CALL_ACTION_BTRH_2;
                break;
            case HfpClientCall.CALL_STATE_ACTIVE:
            case HfpClientCall.CALL_STATE_DIALING:
            case HfpClientCall.CALL_STATE_ALERTING:
            default:
                return;
        }

        if (mNativeInterface.handleCallAction(mCurrentDevice, action, 0)) {
            debug("Reject call action " + action);
            addQueuedAction(REJECT_CALL, action);
        } else {
            error("ERROR: Couldn't reject a call, action:" + action);
        }
    }

    private void holdCall() {
        int action;

        debug("holdCall");

        HfpClientCall c = getCall(HfpClientCall.CALL_STATE_INCOMING);
        if (c != null) {
            action = HeadsetClientHalConstants.CALL_ACTION_BTRH_0;
        } else {
            c = getCall(HfpClientCall.CALL_STATE_ACTIVE);
            if (c == null) {
                return;
            }

            action = HeadsetClientHalConstants.CALL_ACTION_CHLD_2;
        }

        if (mNativeInterface.handleCallAction(mCurrentDevice, action, 0)) {
            addQueuedAction(HOLD_CALL, action);
        } else {
            error("ERROR: Couldn't hold a call, action:" + action);
        }
    }

    private void terminateCall() {
        debug("terminateCall");

        int action = HeadsetClientHalConstants.CALL_ACTION_CHUP;

        HfpClientCall c =
                getCall(
                        HfpClientCall.CALL_STATE_DIALING,
                        HfpClientCall.CALL_STATE_ALERTING,
                        HfpClientCall.CALL_STATE_ACTIVE);
        if (c == null) {
            // If the call being terminated is currently held, switch the action to CHLD_0
            c = getCall(HfpClientCall.CALL_STATE_HELD);
            action = HeadsetClientHalConstants.CALL_ACTION_CHLD_0;
        }
        if (c != null) {
            if (mNativeInterface.handleCallAction(mCurrentDevice, action, 0)) {
                addQueuedAction(TERMINATE_CALL, action);
            } else {
                error("ERROR: Couldn't terminate outgoing call");
            }
        }
    }

    @VisibleForTesting
    void enterPrivateMode(int idx) {
        debug("enterPrivateMode: " + idx);

        HfpClientCall c = mCalls.get(idx);

        if (c == null || c.getState() != HfpClientCall.CALL_STATE_ACTIVE || !c.isMultiParty()) {
            return;
        }

        if (mNativeInterface.handleCallAction(
                mCurrentDevice, HeadsetClientHalConstants.CALL_ACTION_CHLD_2X, idx)) {
            addQueuedAction(ENTER_PRIVATE_MODE, c);
        } else {
            error("ERROR: Couldn't enter private " + " id:" + idx);
        }
    }

    @VisibleForTesting
    void explicitCallTransfer() {
        debug("explicitCallTransfer");

        // can't transfer call if there is not enough call parties
        if (mCalls.size() < 2) {
            return;
        }

        if (mNativeInterface.handleCallAction(
                mCurrentDevice, HeadsetClientHalConstants.CALL_ACTION_CHLD_4, -1)) {
            addQueuedAction(EXPLICIT_CALL_TRANSFER);
        } else {
            error("ERROR: Couldn't transfer call");
        }
    }

    public Bundle getCurrentAgFeaturesBundle() {
        Bundle b = new Bundle();
        if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_3WAY)
                == HeadsetClientHalConstants.PEER_FEAT_3WAY) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_3WAY_CALLING, true);
        }
        if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_VREC)
                == HeadsetClientHalConstants.PEER_FEAT_VREC) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_VOICE_RECOGNITION, true);
        }
        if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_REJECT)
                == HeadsetClientHalConstants.PEER_FEAT_REJECT) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_REJECT_CALL, true);
        }
        if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_ECC)
                == HeadsetClientHalConstants.PEER_FEAT_ECC) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_ECC, true);
        }

        // add individual CHLD support extras
        if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC)
                == HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_ACCEPT_HELD_OR_WAITING_CALL, true);
        }
        if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_REL)
                == HeadsetClientHalConstants.CHLD_FEAT_REL) {
            b.putBoolean(
                    BluetoothHeadsetClient.EXTRA_AG_FEATURE_RELEASE_HELD_OR_WAITING_CALL, true);
        }
        if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_REL_ACC)
                == HeadsetClientHalConstants.CHLD_FEAT_REL_ACC) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_RELEASE_AND_ACCEPT, true);
        }
        if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_MERGE)
                == HeadsetClientHalConstants.CHLD_FEAT_MERGE) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_MERGE, true);
        }
        if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH)
                == HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH) {
            b.putBoolean(BluetoothHeadsetClient.EXTRA_AG_FEATURE_MERGE_AND_DETACH, true);
        }

        return b;
    }

    public Set<Integer> getCurrentAgFeatures() {
        HashSet<Integer> features = new HashSet<>();

        if (isSupported(mPeerFeatures, HeadsetClientHalConstants.PEER_FEAT_3WAY)) {
            features.add(HeadsetClientHalConstants.PEER_FEAT_3WAY);
        }
        if (isSupported(mPeerFeatures, HeadsetClientHalConstants.PEER_FEAT_VREC)) {
            features.add(HeadsetClientHalConstants.PEER_FEAT_VREC);
        }
        if (isSupported(mPeerFeatures, HeadsetClientHalConstants.PEER_FEAT_REJECT)) {
            features.add(HeadsetClientHalConstants.PEER_FEAT_REJECT);
        }
        if (isSupported(mPeerFeatures, HeadsetClientHalConstants.PEER_FEAT_ECC)) {
            features.add(HeadsetClientHalConstants.PEER_FEAT_ECC);
        }

        // add individual CHLD support extras
        if (isSupported(mChldFeatures, HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC)) {
            features.add(HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC);
        }
        if (isSupported(mChldFeatures, HeadsetClientHalConstants.CHLD_FEAT_REL)) {
            features.add(HeadsetClientHalConstants.CHLD_FEAT_REL);
        }
        if (isSupported(mChldFeatures, HeadsetClientHalConstants.CHLD_FEAT_REL_ACC)) {
            features.add(HeadsetClientHalConstants.CHLD_FEAT_REL_ACC);
        }
        if (isSupported(mChldFeatures, HeadsetClientHalConstants.CHLD_FEAT_MERGE)) {
            features.add(HeadsetClientHalConstants.CHLD_FEAT_MERGE);
        }
        if (isSupported(mChldFeatures, HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH)) {
            features.add(HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH);
        }

        return features;
    }

    private static boolean isSupported(int bitfield, int mask) {
        return (bitfield & mask) == mask;
    }

    HeadsetClientStateMachine(
            AdapterService adapterService,
            HeadsetClientService context,
            HeadsetService headsetService,
            Looper looper,
            NativeInterface nativeInterface) {
        super(TAG, looper);
        mAdapterService = requireNonNull(adapterService);
        mService = requireNonNull(context);
        mNativeInterface = nativeInterface;
        mAudioManager = mService.getAudioManager();
        mHeadsetService = headsetService;

        mVendorProcessor = new VendorCommandResponseProcessor(mService, mNativeInterface);

        mAudioState = BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
        mAudioWbs = false;
        mAudioSWB = false;
        mVoiceRecognitionActive = HeadsetClientHalConstants.VR_STATE_STOPPED;

        mAudioRouteAllowed =
                context.getResources()
                        .getBoolean(R.bool.headset_client_initial_audio_route_allowed);

        mAudioRouteAllowed =
                SystemProperties.getBoolean(
                        "bluetooth.headset_client.initial_audio_route.enabled", mAudioRouteAllowed);

        mClccPollDuringCall =
                SystemProperties.getBoolean(
                        "bluetooth.hfp.clcc_poll_during_call.enabled",
                        mService.getResources().getBoolean(R.bool.hfp_clcc_poll_during_call));

        mHsClientAudioPolicy = new BluetoothSinkAudioPolicy.Builder().build();
        mConnectingTimePolicyProperty =
                getAudioPolicySystemProp(
                        "bluetooth.headset_client.audio_policy.connecting_time.config");
        mInBandRingtonePolicyProperty =
                getAudioPolicySystemProp(
                        "bluetooth.headset_client.audio_policy.in_band_ringtone.config");
        mForceSetAudioPolicyProperty =
                SystemProperties.getBoolean(
                        "bluetooth.headset_client.audio_policy.force_enabled", false);

        mIndicatorNetworkState = HeadsetClientHalConstants.NETWORK_STATE_NOT_AVAILABLE;
        mIndicatorNetworkType = HeadsetClientHalConstants.SERVICE_TYPE_HOME;
        mIndicatorNetworkSignal = 0;
        mIndicatorBatteryLevel = 0;

        mOperatorName = null;
        mSubscriberInfo = null;

        mQueuedActions = new ArrayDeque<>();

        mCalls.clear();
        mCallsUpdate.clear();

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mAudioOn = new AudioOn();
        mDisconnecting = new Disconnecting();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mConnected);
        addState(mAudioOn, mConnected);
        if (Flags.hfpClientDisconnectingState()) {
            addState(mDisconnecting);
        }

        setInitialState(mDisconnected);
    }

    static HeadsetClientStateMachine make(
            AdapterService adapterService,
            HeadsetClientService context,
            HeadsetService headsetService,
            Looper looper,
            NativeInterface nativeInterface) {
        Log.d(TAG, "make");
        HeadsetClientStateMachine hfcsm =
                new HeadsetClientStateMachine(
                        adapterService, context, headsetService, looper, nativeInterface);
        hfcsm.start();
        return hfcsm;
    }

    synchronized void routeHfpAudio(boolean enable) {
        debug("hfp_enable=" + enable);
        if (enable && !sAudioIsRouted) {
            mAudioManager.setHfpEnabled(true);
        } else if (!enable) {
            mAudioManager.setHfpEnabled(false);
        }
        sAudioIsRouted = enable;
    }

    private AudioFocusRequest requestAudioFocus() {
        AudioAttributes streamAttributes =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
        AudioFocusRequest focusRequest =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(streamAttributes)
                        .build();
        int focusRequestStatus = mAudioManager.requestAudioFocus(focusRequest);
        String s =
                (focusRequestStatus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        ? "AudioFocus granted"
                        : "AudioFocus NOT granted";
        debug("AudioManager requestAudioFocus returned: " + s);
        return focusRequest;
    }

    public void doQuit() {
        debug("doQuit");
        if (mCurrentDevice != null) {
            mNativeInterface.disconnect(mCurrentDevice);
        }
        routeHfpAudio(false);
        returnAudioFocusIfNecessary();
        quitNow();
    }

    private void returnAudioFocusIfNecessary() {
        if (mAudioFocusRequest == null) return;
        mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        mAudioFocusRequest = null;
    }

    class Disconnected extends State {
        @Override
        public void enter() {
            debug(
                    "Enter Disconnected: from state="
                            + mPrevState
                            + ", message="
                            + getMessageName(getCurrentMessage().what));

            // cleanup
            mIndicatorNetworkState = HeadsetClientHalConstants.NETWORK_STATE_NOT_AVAILABLE;
            mIndicatorNetworkType = HeadsetClientHalConstants.SERVICE_TYPE_HOME;
            mIndicatorNetworkSignal = 0;
            mIndicatorBatteryLevel = 0;
            mInBandRing = false;

            mAudioWbs = false;
            mAudioSWB = false;

            // will be set on connect

            mOperatorName = null;
            mSubscriberInfo = null;

            mQueuedActions = new ArrayDeque<>();

            mCalls.clear();
            mCallsUpdate.clear();

            mPeerFeatures = 0;
            mChldFeatures = 0;

            removeMessages(QUERY_CURRENT_CALLS);

            if (mPrevState == mConnecting) {
                broadcastConnectionState(mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
            } else if (mPrevState == mConnected || mPrevState == mAudioOn) {
                broadcastConnectionState(mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTED);
            } else if (Flags.hfpClientDisconnectingState()) {
                if (mPrevState == mDisconnecting) {
                    broadcastConnectionState(
                            mCurrentDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
                }
            } else if (mPrevState != null) {
                // null is the default state before Disconnected
                error(
                        "Disconnected: Illegal state transition from "
                                + mPrevState.getName()
                                + " to Disconnected, mCurrentDevice="
                                + mCurrentDevice);
            }
            if (mHeadsetService != null && mCurrentDevice != null) {
                mHeadsetService.updateInbandRinging(mCurrentDevice, false);
            }
            mCurrentDevice = null;
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            debug("Disconnected process message: " + message.what);

            if (mCurrentDevice != null) {
                error("ERROR: current device not null in Disconnected");
                return NOT_HANDLED;
            }

            switch (message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mNativeInterface.connect(device)) {
                        // No state transition is involved, fire broadcast immediately
                        broadcastConnectionState(device, STATE_DISCONNECTED, STATE_DISCONNECTED);
                        break;
                    }
                    mCurrentDevice = device;
                    transitionTo(mConnecting);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case StackEvent.STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    debug("Stack event type: " + event.type);
                    switch (event.type) {
                        case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            debug(
                                    "Disconnected: Connection "
                                            + event.device
                                            + " state changed:"
                                            + event.valueInt);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            error("Disconnected: Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in Disconnected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetClientHalConstants.CONNECTION_STATE_CONNECTED:
                    warn("HFPClient Connecting from Disconnected state");
                    if (okToConnect(device)) {
                        info("Incoming AG accepted");
                        mCurrentDevice = device;
                        transitionTo(mConnecting);
                    } else {
                        info(
                                "Incoming AG rejected. connectionPolicy="
                                        + mService.getConnectionPolicy(device)
                                        + " bondState="
                                        + mAdapterService.getBondState(device));
                        // reject the connection and stay in Disconnected state
                        // itself
                        mNativeInterface.disconnect(device);
                        // the other profile connection should be initiated
                        // No state transition is involved, fire broadcast immediately
                        broadcastConnectionState(device, STATE_DISCONNECTED, STATE_DISCONNECTED);
                    }
                    break;
                case HeadsetClientHalConstants.CONNECTION_STATE_CONNECTING:
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTING:
                default:
                    info("ignoring state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            debug("Exit Disconnected: " + getMessageName(getCurrentMessage().what));
            mPrevState = this;
        }
    }

    class Connecting extends State {
        @Override
        public void enter() {
            debug("Enter Connecting: " + getMessageName(getCurrentMessage().what));
            // This message is either consumed in processMessage or
            // removed in exit. It is safe to send a CONNECTING_TIMEOUT here since
            // the only transition is when connection attempt is initiated.
            sendMessageDelayed(CONNECTING_TIMEOUT, CONNECTING_TIMEOUT_MS);
            if (mPrevState == mDisconnected) {
                broadcastConnectionState(mCurrentDevice, STATE_CONNECTING, STATE_DISCONNECTED);
            } else {
                String prevStateName = mPrevState == null ? "null" : mPrevState.getName();
                error(
                        "Connecting: Illegal state transition from "
                                + prevStateName
                                + " to Connecting");
            }
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            debug("Connecting process message: " + message.what);

            switch (message.what) {
                case CONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT:
                    deferMessage(message);
                    break;
                case StackEvent.STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    debug("Connecting: event type: " + event.type);
                    switch (event.type) {
                        case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            debug(
                                    "Connecting: Connection "
                                            + event.device
                                            + " state changed:"
                                            + event.valueInt);
                            processConnectionEvent(
                                    event.valueInt, event.valueInt2, event.valueInt3, event.device);
                            break;
                        case StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED:
                        case StackEvent.EVENT_TYPE_NETWORK_STATE:
                        case StackEvent.EVENT_TYPE_ROAMING_STATE:
                        case StackEvent.EVENT_TYPE_NETWORK_SIGNAL:
                        case StackEvent.EVENT_TYPE_BATTERY_LEVEL:
                        case StackEvent.EVENT_TYPE_CALL:
                        case StackEvent.EVENT_TYPE_CALLSETUP:
                        case StackEvent.EVENT_TYPE_CALLHELD:
                        case StackEvent.EVENT_TYPE_RESP_AND_HOLD:
                        case StackEvent.EVENT_TYPE_CLIP:
                        case StackEvent.EVENT_TYPE_CALL_WAITING:
                        case StackEvent.EVENT_TYPE_VOLUME_CHANGED:
                        case StackEvent.EVENT_TYPE_IN_BAND_RINGTONE:
                            deferMessage(message);
                            break;
                        case StackEvent.EVENT_TYPE_CMD_RESULT:
                            debug(
                                    "Connecting: CMD_RESULT valueInt:"
                                            + event.valueInt
                                            + " mQueuedActions.size="
                                            + mQueuedActions.size());
                            if (!mQueuedActions.isEmpty()) {
                                debug("queuedAction:" + mQueuedActions.peek().first);
                            }
                            Pair<Integer, Object> queuedAction = mQueuedActions.poll();
                            if (queuedAction == null || queuedAction.first == NO_ACTION) {
                                break;
                            }
                            switch (queuedAction.first) {
                                case SEND_ANDROID_AT_COMMAND:
                                    if (event.valueInt == StackEvent.CMD_RESULT_TYPE_OK) {
                                        warn("Received OK instead of +ANDROID");
                                    } else {
                                        warn("Received ERROR instead of +ANDROID");
                                    }
                                    setAudioPolicyRemoteSupported(false);
                                    transitionTo(mConnected);
                                    break;
                                default:
                                    warn("Ignored CMD Result");
                                    break;
                            }
                            break;

                        case StackEvent.EVENT_TYPE_UNKNOWN_EVENT:
                            if (mVendorProcessor.isAndroidAtCommand(event.valueString)
                                    && processAndroidSlcCommand(event.valueString, event.device)) {
                                transitionTo(mConnected);
                            } else {
                                error(
                                        "Unknown event :"
                                                + event.valueString
                                                + " for device "
                                                + event.device);
                            }
                            break;

                        case StackEvent.EVENT_TYPE_SUBSCRIBER_INFO:
                        case StackEvent.EVENT_TYPE_CURRENT_CALLS:
                        case StackEvent.EVENT_TYPE_OPERATOR_NAME:
                        default:
                            error("Connecting: ignoring stack event: " + event.type);
                            break;
                    }
                    break;
                case CONNECTING_TIMEOUT:
                    // We timed out trying to connect, transition to disconnected.
                    warn("Connection timeout for " + mCurrentDevice);
                    transitionTo(mDisconnected);
                    break;

                default:
                    warn("Message not handled " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in Connecting state
        private void processConnectionEvent(
                int state, int peerFeat, int chldFeat, BluetoothDevice device) {
            switch (state) {
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    transitionTo(mDisconnected);
                    break;

                case HeadsetClientHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    debug("HFPClient Connected from Connecting state");

                    mPeerFeatures = peerFeat;
                    mChldFeatures = chldFeat;

                    // We do not support devices which do not support enhanced call status (ECS).
                    if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_ECS) == 0) {
                        mNativeInterface.disconnect(device);
                        return;
                    }

                    // Send AT+NREC to remote if supported by audio
                    if (HeadsetClientHalConstants.HANDSFREECLIENT_NREC_SUPPORTED
                            && ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_ECNR)
                                    == HeadsetClientHalConstants.PEER_FEAT_ECNR)) {
                        if (mNativeInterface.sendATCmd(
                                mCurrentDevice,
                                HeadsetClientHalConstants.HANDSFREECLIENT_AT_CMD_NREC,
                                1,
                                0,
                                null)) {
                            addQueuedAction(DISABLE_NREC);
                        } else {
                            error("Failed to send NREC");
                        }
                    }

                    int amVol = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                    deferMessage(
                            obtainMessage(HeadsetClientStateMachine.SET_SPEAKER_VOLUME, amVol, 0));
                    // Mic is either in ON state (full volume) or OFF state. There is no way in
                    // Android to change the MIC volume.
                    deferMessage(
                            obtainMessage(
                                    HeadsetClientStateMachine.SET_MIC_VOLUME,
                                    mAudioManager.isMicrophoneMute() ? 0 : 15,
                                    0));
                    // query subscriber info
                    deferMessage(obtainMessage(HeadsetClientStateMachine.SUBSCRIBER_INFO));

                    if (!queryRemoteSupportedFeatures()) {
                        warn("Couldn't query Android AT remote supported!");
                        transitionTo(mConnected);
                    }
                    break;

                case HeadsetClientHalConstants.CONNECTION_STATE_CONNECTED:
                    if (!mCurrentDevice.equals(device)) {
                        warn("incoming connection event, device: " + device);
                        // No state transition is involved, fire broadcast immediately
                        broadcastConnectionState(
                                mCurrentDevice, STATE_DISCONNECTED, STATE_CONNECTING);
                        broadcastConnectionState(device, STATE_CONNECTING, STATE_DISCONNECTED);

                        mCurrentDevice = device;
                    }
                    break;
                case HeadsetClientHalConstants.CONNECTION_STATE_CONNECTING:
                    /* outgoing connecting started */
                    debug("outgoing connection started, ignore");
                    break;
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTING:
                default:
                    error("Incorrect state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            debug("Exit Connecting: " + getMessageName(getCurrentMessage().what));
            removeMessages(CONNECTING_TIMEOUT);
            mPrevState = this;
        }
    }

    class Connected extends State {
        int mCommandedSpeakerVolume = -1;

        @Override
        public void enter() {
            debug("Enter Connected: " + getMessageName(getCurrentMessage().what));
            mAudioWbs = false;
            mAudioSWB = false;
            mCommandedSpeakerVolume = -1;

            if (mPrevState == mConnecting) {
                broadcastConnectionState(mCurrentDevice, STATE_CONNECTED, STATE_CONNECTING);
                if (mHeadsetService != null) {
                    mHeadsetService.updateInbandRinging(mCurrentDevice, true);
                }
                MetricsLogger.logProfileConnectionEvent(
                        BluetoothMetricsProto.ProfileId.HEADSET_CLIENT);
            } else if (mPrevState != mAudioOn) {
                String prevStateName = mPrevState == null ? "null" : mPrevState.getName();
                error(
                        "Connected: Illegal state transition from "
                                + prevStateName
                                + " to Connected");
            }
            mService.updateBatteryLevel();
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            debug("Connected process message: " + message.what);
            if (mCurrentDevice == null) {
                error("ERROR: mCurrentDevice is null in Connected");
                return NOT_HANDLED;
            }

            switch (message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice.equals(device)) {
                        // already connected to this device, do nothing
                        break;
                    }
                    mNativeInterface.connect(device);
                    break;
                case DISCONNECT:
                    BluetoothDevice dev = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(dev)) {
                        break;
                    }
                    if (Flags.hfpClientDisconnectingState()) {
                        if (!mNativeInterface.disconnect(mCurrentDevice)) {
                            warn("disconnectNative failed for " + mCurrentDevice);
                        }
                        transitionTo(mDisconnecting);
                    } else if (!mNativeInterface.disconnect(dev)) {
                        error("disconnectNative failed for " + dev);
                    }
                    break;

                case CONNECT_AUDIO:
                    if (!mNativeInterface.connectAudio(mCurrentDevice)) {
                        error("ERROR: Couldn't connect Audio for device");
                        // No state transition is involved, fire broadcast immediately
                        broadcastAudioState(
                                mCurrentDevice,
                                BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED,
                                BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED);
                    } else { // We have successfully sent a connect request!
                        mAudioState = BluetoothHeadsetClient.STATE_AUDIO_CONNECTING;
                    }
                    break;

                case DISCONNECT_AUDIO:
                    if (!mNativeInterface.disconnectAudio(mCurrentDevice)) {
                        error("ERROR: Couldn't disconnect Audio for device");
                    }
                    break;

                case VOICE_RECOGNITION_START:
                    if (mVoiceRecognitionActive == HeadsetClientHalConstants.VR_STATE_STOPPED) {
                        if (mNativeInterface.startVoiceRecognition(mCurrentDevice)) {
                            addQueuedAction(VOICE_RECOGNITION_START);
                        } else {
                            error("ERROR: Couldn't start voice recognition");
                        }
                    }
                    break;

                case VOICE_RECOGNITION_STOP:
                    if (mVoiceRecognitionActive == HeadsetClientHalConstants.VR_STATE_STARTED) {
                        if (mNativeInterface.stopVoiceRecognition(mCurrentDevice)) {
                            addQueuedAction(VOICE_RECOGNITION_STOP);
                        } else {
                            error("ERROR: Couldn't stop voice recognition");
                        }
                    }
                    break;

                case SEND_VENDOR_AT_COMMAND:
                    {
                        int vendorId = message.arg1;
                        String atCommand = (String) (message.obj);
                        mVendorProcessor.sendCommand(vendorId, atCommand, mCurrentDevice);
                        break;
                    }

                case SEND_BIEV:
                    {
                        if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_HF_IND)
                                == HeadsetClientHalConstants.PEER_FEAT_HF_IND) {
                            int indicatorID = message.arg1;
                            int value = message.arg2;
                            mNativeInterface.sendATCmd(
                                    mCurrentDevice,
                                    HeadsetClientHalConstants.HANDSFREECLIENT_AT_CMD_BIEV,
                                    indicatorID,
                                    value,
                                    null);
                        }
                        break;
                    }

                    // Called only for Mute/Un-mute - Mic volume change is not allowed.
                case SET_MIC_VOLUME:
                    break;
                case SET_SPEAKER_VOLUME:
                    // This message should always contain the volume in AudioManager max normalized.
                    int amVol = message.arg1;
                    int hfVol = mService.amToHfVol(amVol);
                    if (amVol != mCommandedSpeakerVolume) {
                        debug("Volume" + amVol + ":" + mCommandedSpeakerVolume);
                        // Volume was changed by a 3rd party
                        mCommandedSpeakerVolume = -1;
                        if (mNativeInterface.setVolume(
                                mCurrentDevice, HeadsetClientHalConstants.VOLUME_TYPE_SPK, hfVol)) {
                            addQueuedAction(SET_SPEAKER_VOLUME);
                        }
                    }
                    break;
                case DIAL_NUMBER:
                    // Add the call as an outgoing call.
                    HfpClientCall c = (HfpClientCall) message.obj;
                    mCalls.put(HF_ORIGINATED_CALL_ID, c);

                    if (mNativeInterface.dial(mCurrentDevice, c.getNumber())) {
                        addQueuedAction(DIAL_NUMBER, c.getNumber());
                        // Start looping on calling current calls.
                        sendMessage(QUERY_CURRENT_CALLS);
                    } else {
                        Log.e(TAG, "ERROR: Cannot dial with a given number:" + c.toString());
                        // Set the call to terminated remove.
                        c.setState(HfpClientCall.CALL_STATE_TERMINATED);
                        sendCallChangedIntent(c);
                        mCalls.remove(HF_ORIGINATED_CALL_ID);
                    }
                    break;
                case ACCEPT_CALL:
                    acceptCall(message.arg1);
                    break;
                case REJECT_CALL:
                    rejectCall();
                    break;
                case HOLD_CALL:
                    holdCall();
                    break;
                case TERMINATE_CALL:
                    terminateCall();
                    break;
                case ENTER_PRIVATE_MODE:
                    enterPrivateMode(message.arg1);
                    break;
                case EXPLICIT_CALL_TRANSFER:
                    explicitCallTransfer();
                    break;
                case SEND_DTMF:
                    if (mNativeInterface.sendDtmf(mCurrentDevice, (byte) message.arg1)) {
                        addQueuedAction(SEND_DTMF);
                    } else {
                        error("ERROR: Couldn't send DTMF");
                    }
                    break;
                case SUBSCRIBER_INFO:
                    if (mNativeInterface.retrieveSubscriberInfo(mCurrentDevice)) {
                        addQueuedAction(SUBSCRIBER_INFO);
                    } else {
                        error("ERROR: Couldn't retrieve subscriber info");
                    }
                    break;
                case QUERY_CURRENT_CALLS:
                    removeMessages(QUERY_CURRENT_CALLS);
                    debug("mClccPollDuringCall=" + mClccPollDuringCall);
                    // If there are ongoing calls periodically check their status.
                    if (mCalls.size() > 1 && mClccPollDuringCall) {
                        sendMessageDelayed(
                                QUERY_CURRENT_CALLS,
                                mService.getResources()
                                        .getInteger(R.integer.hfp_clcc_poll_interval_during_call));
                    } else if (mCalls.size() > 0) {
                        sendMessageDelayed(QUERY_CURRENT_CALLS, QUERY_CURRENT_CALLS_WAIT_MILLIS);
                    }
                    queryCallsStart();
                    break;
                case StackEvent.STACK_EVENT:
                    Intent intent = null;
                    StackEvent event = (StackEvent) message.obj;
                    debug("Connected: event type: " + event.type);

                    switch (event.type) {
                        case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            debug(
                                    "Connected: Connection state changed: "
                                            + event.device
                                            + ": "
                                            + event.valueInt);
                            processConnectionEvent(message, event.valueInt, event.device);
                            break;
                        case StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED:
                            debug(
                                    "Connected: Audio state changed: "
                                            + event.device
                                            + ": "
                                            + event.valueInt);
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        case StackEvent.EVENT_TYPE_NETWORK_STATE:
                            debug("Connected: Network state: " + event.valueInt);
                            mIndicatorNetworkState = event.valueInt;

                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_NETWORK_STATUS, event.valueInt);

                            if (mIndicatorNetworkState
                                    == HeadsetClientHalConstants.NETWORK_STATE_NOT_AVAILABLE) {
                                mOperatorName = null;
                                intent.putExtra(
                                        BluetoothHeadsetClient.EXTRA_OPERATOR_NAME, mOperatorName);
                            }

                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            sendNetworkStateChangedIntent(event.device);

                            if (mIndicatorNetworkState
                                    == HeadsetClientHalConstants.NETWORK_STATE_AVAILABLE) {
                                if (mNativeInterface.queryCurrentOperatorName(mCurrentDevice)) {
                                    addQueuedAction(QUERY_OPERATOR_NAME);
                                } else {
                                    error("ERROR: Couldn't query operator name");
                                }
                            }
                            break;
                        case StackEvent.EVENT_TYPE_ROAMING_STATE:
                            mIndicatorNetworkType = event.valueInt;

                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_NETWORK_ROAMING, event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            sendNetworkStateChangedIntent(event.device);
                            break;
                        case StackEvent.EVENT_TYPE_NETWORK_SIGNAL:
                            mIndicatorNetworkSignal = event.valueInt;

                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH,
                                    event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            sendNetworkStateChangedIntent(event.device);
                            break;
                        case StackEvent.EVENT_TYPE_BATTERY_LEVEL:
                            mIndicatorBatteryLevel = event.valueInt;
                            mService.handleBatteryLevelChanged(event.device, event.valueInt);

                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_BATTERY_LEVEL, event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            break;
                        case StackEvent.EVENT_TYPE_OPERATOR_NAME:
                            mOperatorName = event.valueString;

                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_OPERATOR_NAME, event.valueString);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            sendNetworkStateChangedIntent(event.device);
                            break;
                        case StackEvent.EVENT_TYPE_VR_STATE_CHANGED:
                            int oldState = mVoiceRecognitionActive;
                            mVoiceRecognitionActive = event.valueInt;
                            broadcastVoiceRecognitionStateChanged(
                                    event.device, oldState, mVoiceRecognitionActive);
                            break;
                        case StackEvent.EVENT_TYPE_CALL:
                        case StackEvent.EVENT_TYPE_CALLSETUP:
                        case StackEvent.EVENT_TYPE_CALLHELD:
                        case StackEvent.EVENT_TYPE_RESP_AND_HOLD:
                        case StackEvent.EVENT_TYPE_CLIP:
                        case StackEvent.EVENT_TYPE_CALL_WAITING:
                            sendMessage(QUERY_CURRENT_CALLS);
                            break;
                        case StackEvent.EVENT_TYPE_CURRENT_CALLS:
                            queryCallsUpdate(
                                    event.valueInt,
                                    event.valueInt3,
                                    event.valueString,
                                    event.valueInt4
                                            == HeadsetClientHalConstants.CALL_MPTY_TYPE_MULTI,
                                    event.valueInt2
                                            == HeadsetClientHalConstants.CALL_DIRECTION_OUTGOING);
                            break;
                        case StackEvent.EVENT_TYPE_VOLUME_CHANGED:
                            if (event.valueInt == HeadsetClientHalConstants.VOLUME_TYPE_SPK) {
                                mCommandedSpeakerVolume = mService.hfToAmVol(event.valueInt2);
                                debug("AM volume set to " + mCommandedSpeakerVolume);
                                boolean show_volume =
                                        SystemProperties.getBoolean(
                                                "bluetooth.hfp_volume_control.enabled", true);
                                mAudioManager.setStreamVolume(
                                        AudioManager.STREAM_VOICE_CALL,
                                        +mCommandedSpeakerVolume,
                                        show_volume ? AudioManager.FLAG_SHOW_UI : 0);
                            } else if (event.valueInt
                                    == HeadsetClientHalConstants.VOLUME_TYPE_MIC) {
                                mAudioManager.setMicrophoneMute(event.valueInt2 == 0);
                            }
                            break;
                        case StackEvent.EVENT_TYPE_CMD_RESULT:
                            Pair<Integer, Object> queuedAction = mQueuedActions.poll();

                            // should not happen but...
                            if (queuedAction == null || queuedAction.first == NO_ACTION) {
                                break;
                            }

                            debug(
                                    "Connected: command result: "
                                            + event.valueInt
                                            + " queuedAction: "
                                            + queuedAction.first);

                            switch (queuedAction.first) {
                                case QUERY_CURRENT_CALLS:
                                    queryCallsDone();
                                    break;
                                case VOICE_RECOGNITION_START:
                                    if (event.valueInt == AT_OK) {
                                        oldState = mVoiceRecognitionActive;
                                        mVoiceRecognitionActive =
                                                HeadsetClientHalConstants.VR_STATE_STARTED;
                                        broadcastVoiceRecognitionStateChanged(
                                                event.device, oldState, mVoiceRecognitionActive);
                                    }
                                    break;
                                case VOICE_RECOGNITION_STOP:
                                    if (event.valueInt == AT_OK) {
                                        oldState = mVoiceRecognitionActive;
                                        mVoiceRecognitionActive =
                                                HeadsetClientHalConstants.VR_STATE_STOPPED;
                                        broadcastVoiceRecognitionStateChanged(
                                                event.device, oldState, mVoiceRecognitionActive);
                                    }
                                    break;
                                case SEND_ANDROID_AT_COMMAND:
                                    debug("Connected: Received OK for AT+ANDROID");
                                    break;
                                default:
                                    warn("Unhandled AT OK " + event);
                                    break;
                            }

                            break;
                        case StackEvent.EVENT_TYPE_SUBSCRIBER_INFO:
                            mSubscriberInfo = event.valueString;
                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_SUBSCRIBER_INFO, mSubscriberInfo);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            break;
                        case StackEvent.EVENT_TYPE_IN_BAND_RINGTONE:
                            intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
                            mInBandRing = event.valueInt == IN_BAND_RING_ENABLED;
                            intent.putExtra(
                                    BluetoothHeadsetClient.EXTRA_IN_BAND_RING, event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(
                                    intent,
                                    BLUETOOTH_CONNECT,
                                    Utils.getTempBroadcastOptions().toBundle());
                            debug(event.device.toString() + "onInBandRing" + event.valueInt);
                            break;
                        case StackEvent.EVENT_TYPE_RING_INDICATION:
                            // Ringing is not handled at this indication and rather should be
                            // implemented (by the client of this service). Use the
                            // CALL_STATE_INCOMING (and similar) handle ringing.
                            break;
                        case StackEvent.EVENT_TYPE_UNKNOWN_EVENT:
                            if (!mVendorProcessor.processEvent(event.valueString, event.device)) {
                                error(
                                        "Unknown event :"
                                                + event.valueString
                                                + " for device "
                                                + event.device);
                            }
                            break;
                        default:
                            error("Unknown stack event: " + event.type);
                            break;
                    }

                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void broadcastVoiceRecognitionStateChanged(
                BluetoothDevice device, int oldState, int newState) {
            if (oldState == newState) {
                return;
            }
            Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
            intent.putExtra(BluetoothHeadsetClient.EXTRA_VOICE_RECOGNITION, newState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            mService.sendBroadcast(
                    intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
        }

        // in Connected state
        private void processConnectionEvent(Message message, int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    debug("Connected disconnects.");
                    // AG disconnects
                    if (mCurrentDevice.equals(device)) {
                        if (Flags.hfpClientDisconnectingState()) {
                            transitionTo(mDisconnecting);
                            // message is deferred to be processed in the disconnecting state
                            deferMessage(message);
                        } else {
                            transitionTo(mDisconnected);
                        }
                    } else {
                        error("Disconnected from unknown device: " + device);
                    }
                    break;
                default:
                    error("Connection State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        // in Connected state
        private void processAudioEvent(int state, BluetoothDevice device) {
            // message from old device
            if (!mCurrentDevice.equals(device)) {
                error("Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HeadsetClientHalConstants.AUDIO_STATE_CONNECTED,
                        HeadsetClientHalConstants.AUDIO_STATE_CONNECTED_LC3,
                        HeadsetClientHalConstants.AUDIO_STATE_CONNECTED_MSBC:
                    mAudioSWB = state == HeadsetClientHalConstants.AUDIO_STATE_CONNECTED_LC3;
                    mAudioWbs = state == HeadsetClientHalConstants.AUDIO_STATE_CONNECTED_MSBC;
                    debug("mAudioRouteAllowed=" + mAudioRouteAllowed);
                    if (!mAudioRouteAllowed) {
                        info("Audio is not allowed! Disconnect SCO.");
                        sendMessage(HeadsetClientStateMachine.DISCONNECT_AUDIO);
                        // Don't continue connecting!
                        return;
                    }

                    // Audio state is split in two parts, the audio focus is maintained by the
                    // entity exercising this service (typically the Telecom stack) and audio
                    // routing is handled by the bluetooth stack itself. The only reason to do so is
                    // because Bluetooth SCO connection from the HF role is not entirely supported
                    // for routing and volume purposes.
                    // NOTE: All calls here are routed via AudioManager methods which changes the
                    // routing at the Audio HAL level.

                    if (mService.isScoRouted()) {
                        StackEvent event =
                                new StackEvent(StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
                        event.valueInt = state;
                        event.device = device;
                        sendMessageDelayed(StackEvent.STACK_EVENT, event, ROUTING_DELAY_MS);
                        break;
                    }

                    mAudioState = BluetoothHeadsetClient.STATE_AUDIO_CONNECTED;

                    // We need to set the volume after switching into HFP mode as some Audio HALs
                    // reset the volume to a known-default on mode switch.
                    final int amVol = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                    final int hfVol = mService.amToHfVol(amVol);

                    debug("hfp_enable=true mAudioSWB is " + mAudioSWB);
                    debug("hfp_enable=true mAudioWbs is " + mAudioWbs);

                    if (mAudioSWB) {
                        debug("Setting sampling rate as 32000");
                        mAudioManager.setHfpSamplingRate(32000);
                    } else if (mAudioWbs) {
                        debug("Setting sampling rate as 16000");
                        mAudioManager.setHfpSamplingRate(16000);
                    } else {
                        debug("Setting sampling rate as 8000");
                        mAudioManager.setHfpSamplingRate(8000);
                    }
                    debug("hf_volume " + hfVol);
                    routeHfpAudio(true);
                    mAudioFocusRequest = requestAudioFocus();
                    mAudioManager.setHfpVolume(hfVol);
                    transitionTo(mAudioOn);
                    break;

                case HeadsetClientHalConstants.AUDIO_STATE_CONNECTING:
                    // No state transition is involved, fire broadcast immediately
                    broadcastAudioState(
                            device, BluetoothHeadsetClient.STATE_AUDIO_CONNECTING, mAudioState);
                    mAudioState = BluetoothHeadsetClient.STATE_AUDIO_CONNECTING;
                    break;

                case HeadsetClientHalConstants.AUDIO_STATE_DISCONNECTED:
                    // No state transition is involved, fire broadcast immediately
                    broadcastAudioState(
                            device, BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED, mAudioState);
                    mAudioState = BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
                    break;

                default:
                    error("Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            debug("Exit Connected: " + getMessageName(getCurrentMessage().what));
            mPrevState = this;
        }
    }

    class Disconnecting extends State {
        @Override
        public void enter() {
            debug(
                    "Disconnecting: enter disconnecting from state="
                            + mPrevState
                            + ", message="
                            + getMessageName(getCurrentMessage().what));
            if (mPrevState == mConnected || mPrevState == mAudioOn) {
                broadcastConnectionState(mCurrentDevice, STATE_DISCONNECTING, STATE_CONNECTED);
            } else {
                String prevStateName = mPrevState == null ? "null" : mPrevState.getName();
                error(
                        "Disconnecting: Illegal state transition from "
                                + prevStateName
                                + " to Disconnecting");
            }
            sendMessageDelayed(DISCONNECTING_TIMEOUT, DISCONNECTING_TIMEOUT_MS);
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            debug("Disconnecting: Process message: " + message.what);

            switch (message.what) {
                    // Deferring messages as state machine objects are meant to be reused and after
                    // disconnect is complete we want honor other message requests
                case CONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT:
                case DISCONNECT_AUDIO:
                    deferMessage(message);
                    break;

                case DISCONNECTING_TIMEOUT:
                    // We timed out trying to disconnect, force transition to disconnected.
                    warn("Disconnecting: Disconnection timeout for " + mCurrentDevice);
                    transitionTo(mDisconnected);
                    break;

                case StackEvent.STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;

                    switch (event.type) {
                        case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            debug(
                                    "Disconnecting: Connection state changed: "
                                            + event.device
                                            + ": "
                                            + event.valueInt);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            error("Disconnecting: Unknown stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    warn("Disconnecting: Message not handled " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        transitionTo(mDisconnected);
                    } else {
                        error("Disconnecting: Disconnected from unknown device: " + device);
                    }
                    break;
                default:
                    error(
                            "Disconnecting: Connection State Device: "
                                    + device
                                    + " bad state: "
                                    + state);
                    break;
            }
        }

        @Override
        public void exit() {
            debug("Disconnecting: Exit Disconnecting: " + getMessageName(getCurrentMessage().what));
            removeMessages(DISCONNECTING_TIMEOUT);
            mPrevState = this;
        }
    }

    class AudioOn extends State {
        @Override
        public void enter() {
            debug("Enter AudioOn: " + getMessageName(getCurrentMessage().what));
            broadcastAudioState(
                    mCurrentDevice,
                    BluetoothHeadsetClient.STATE_AUDIO_CONNECTED,
                    BluetoothHeadsetClient.STATE_AUDIO_CONNECTING);
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            debug("AudioOn process message: " + message.what);
            if (mCurrentDevice == null) {
                error("ERROR: mCurrentDevice is null in Connected");
                return NOT_HANDLED;
            }

            switch (message.what) {
                case DISCONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(device)) {
                        break;
                    }
                    deferMessage(message);
                    /*
                     * fall through - disconnect audio first then expect
                     * deferred DISCONNECT message in Connected state
                     */
                case DISCONNECT_AUDIO:
                    /*
                     * just disconnect audio and wait for
                     * StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED, that triggers State
                     * Machines state changing
                     */
                    if (mNativeInterface.disconnectAudio(mCurrentDevice)) {
                        routeHfpAudio(false);
                        returnAudioFocusIfNecessary();
                    }
                    break;

                case HOLD_CALL:
                    holdCall();
                    break;

                case StackEvent.STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    debug("AudioOn: event type: " + event.type);
                    switch (event.type) {
                        case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            debug(
                                    "AudioOn connection state changed"
                                            + event.device
                                            + ": "
                                            + event.valueInt);
                            processConnectionEvent(message, event.valueInt, event.device);
                            break;
                        case StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED:
                            debug(
                                    "AudioOn audio state changed"
                                            + event.device
                                            + ": "
                                            + event.valueInt);
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in AudioOn state. Can AG disconnect RFCOMM prior to SCO? Handle this
        private void processConnectionEvent(Message message, int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        processAudioEvent(
                                HeadsetClientHalConstants.AUDIO_STATE_DISCONNECTED, device);
                        if (Flags.hfpClientDisconnectingState()) {
                            transitionTo(mDisconnecting);
                            // message is deferred to be processed in the disconnecting state
                            deferMessage(message);
                        } else {
                            transitionTo(mDisconnected);
                        }

                    } else {
                        error("Disconnected from unknown device: " + device);
                    }
                    break;
                default:
                    error("Connection State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        // in AudioOn state
        private void processAudioEvent(int state, BluetoothDevice device) {
            if (!mCurrentDevice.equals(device)) {
                error("Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HeadsetClientHalConstants.AUDIO_STATE_DISCONNECTED:
                    removeMessages(DISCONNECT_AUDIO);
                    mAudioState = BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
                    // Audio focus may still be held by the entity controlling the actual call
                    // (such as Telecom) and hence this will still keep the call around, there
                    // is not much we can do here since dropping the call without user consent
                    // even if the audio connection snapped may not be a good idea.
                    routeHfpAudio(false);
                    returnAudioFocusIfNecessary();
                    transitionTo(mConnected);
                    break;

                default:
                    error("Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            debug("Exit AudioOn: " + getMessageName(getCurrentMessage().what));
            mPrevState = this;
            broadcastAudioState(
                    mCurrentDevice,
                    BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED,
                    BluetoothHeadsetClient.STATE_AUDIO_CONNECTED);
        }
    }

    public synchronized int getConnectionState(BluetoothDevice device) {
        if (device == null || !device.equals(mCurrentDevice)) {
            return STATE_DISCONNECTED;
        }

        IState currentState = getCurrentState();
        if (currentState == mConnecting) {
            return STATE_CONNECTING;
        }

        if (currentState == mConnected || currentState == mAudioOn) {
            return STATE_CONNECTED;
        }

        if (Flags.hfpClientDisconnectingState()) {
            if (currentState == mDisconnecting) {
                return STATE_DISCONNECTING;
            }
        }
        return STATE_DISCONNECTED;
    }

    @VisibleForTesting
    void broadcastAudioState(BluetoothDevice device, int newState, int prevState) {
        int sco_codec = BluetoothHfpProtoEnums.SCO_CODEC_CVSD;
        if (mAudioSWB) {
            sco_codec = BluetoothHfpProtoEnums.SCO_CODEC_LC3;
        } else if (mAudioWbs) {
            sco_codec = BluetoothHfpProtoEnums.SCO_CODEC_MSBC;
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_SCO_CONNECTION_STATE_CHANGED,
                mAdapterService.obfuscateAddress(device),
                getConnectionStateFromAudioState(newState),
                sco_codec,
                mAdapterService.getMetricId(device));
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AUDIO_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        if (newState == BluetoothHeadsetClient.STATE_AUDIO_CONNECTED) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_AUDIO_WBS, mAudioWbs);
        }
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(
                intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());

        debug("Audio state " + device + ": " + prevState + "->" + newState);
        HfpClientConnectionService.onAudioStateChanged(device, newState, prevState);
    }

    @VisibleForTesting
    boolean processAndroidSlcCommand(String atString, BluetoothDevice device) {
        if (!mCurrentDevice.equals(device) || atString.lastIndexOf("+ANDROID:") < 0) {
            return false;
        }

        // Check if it is +ANDROID: (<feature1>,...),(<feature2>, ...) the reply for AT+ANDROID=?
        while (true) {
            int indexUpperBucket = atString.indexOf("(");
            int indexLowerBucket = atString.indexOf(")");

            if (indexUpperBucket < 0
                    || indexLowerBucket < 0
                    || indexUpperBucket >= indexLowerBucket) {
                break;
            }
            String feature = atString.substring(indexUpperBucket + 1, indexLowerBucket);
            debug("processAndroidSlcCommand: feature=[" + feature + "]");
            processAndroidAtFeature(feature.split(","));

            atString = atString.substring(indexLowerBucket + 1);
        }
        return true;
    }

    private void processAndroidAtFeature(String[] args) {
        if (args.length < 1) {
            error("processAndroidAtFeature: Invalid feature length");
            return;
        }

        String featureId = args[0];
        if (featureId.equals(BluetoothSinkAudioPolicy.HFP_SET_SINK_AUDIO_POLICY_ID)) {
            info(
                    "processAndroidAtFeature:"
                            + BluetoothSinkAudioPolicy.HFP_SET_SINK_AUDIO_POLICY_ID
                            + " supported");
            setAudioPolicyRemoteSupported(true);

            // Send default policies to the remote if it supports
            if (getForceSetAudioPolicyProperty()) {
                setAudioPolicy(
                        new BluetoothSinkAudioPolicy.Builder(mHsClientAudioPolicy)
                                .setActiveDevicePolicyAfterConnection(mConnectingTimePolicyProperty)
                                .setInBandRingtonePolicy(mInBandRingtonePolicyProperty)
                                .build());
            }
        }
    }

    // This method does not check for error condition (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState, int prevState) {
        debug("Connection state " + device + ": " + prevState + "->" + newState);
        /*
         * Notifying the connection state change of the profile before sending
         * the intent for connection state change, as it was causing a race
         * condition, with the UI not being updated with the correct connection
         * state.
         */
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        // add feature extras when connected
        if (newState == STATE_CONNECTED) {
            if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_3WAY)
                    == HeadsetClientHalConstants.PEER_FEAT_3WAY) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_3WAY_CALLING, true);
            }
            if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_VREC)
                    == HeadsetClientHalConstants.PEER_FEAT_VREC) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_VOICE_RECOGNITION, true);
            }
            if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_REJECT)
                    == HeadsetClientHalConstants.PEER_FEAT_REJECT) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_REJECT_CALL, true);
            }
            if ((mPeerFeatures & HeadsetClientHalConstants.PEER_FEAT_ECC)
                    == HeadsetClientHalConstants.PEER_FEAT_ECC) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_ECC, true);
            }

            // add individual CHLD support extras
            if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC)
                    == HeadsetClientHalConstants.CHLD_FEAT_HOLD_ACC) {
                intent.putExtra(
                        BluetoothHeadsetClient.EXTRA_AG_FEATURE_ACCEPT_HELD_OR_WAITING_CALL, true);
            }
            if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_REL)
                    == HeadsetClientHalConstants.CHLD_FEAT_REL) {
                intent.putExtra(
                        BluetoothHeadsetClient.EXTRA_AG_FEATURE_RELEASE_HELD_OR_WAITING_CALL, true);
            }
            if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_REL_ACC)
                    == HeadsetClientHalConstants.CHLD_FEAT_REL_ACC) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_RELEASE_AND_ACCEPT, true);
            }
            if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_MERGE)
                    == HeadsetClientHalConstants.CHLD_FEAT_MERGE) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_MERGE, true);
            }
            if ((mChldFeatures & HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH)
                    == HeadsetClientHalConstants.CHLD_FEAT_MERGE_DETACH) {
                intent.putExtra(BluetoothHeadsetClient.EXTRA_AG_FEATURE_MERGE_AND_DETACH, true);
            }
        }

        mService.sendBroadcastMultiplePermissions(
                intent,
                new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
                Utils.getTempBroadcastOptions());

        HfpClientConnectionService.onConnectionStateChanged(device, newState, prevState);
    }

    boolean isConnected() {
        IState currentState = getCurrentState();
        return (currentState == mConnected || currentState == mAudioOn);
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return deviceList;
        }
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.HFP_AG)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for (int state : states) {
                    if (connectionState == state) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    boolean okToConnect(BluetoothDevice device) {
        int connectionPolicy = mService.getConnectionPolicy(device);
        boolean ret = false;
        // check connection policy and accept or reject the connection. if connection policy is
        // undefined
        // it is likely that our SDP has not completed and peer is initiating
        // the
        // connection. Allow this connection, provided the device is bonded
        if ((CONNECTION_POLICY_FORBIDDEN < connectionPolicy)
                || ((CONNECTION_POLICY_UNKNOWN == connectionPolicy)
                        && (mAdapterService.getBondState(device) != BluetoothDevice.BOND_NONE))) {
            ret = true;
        }
        return ret;
    }

    boolean isAudioOn() {
        return (getCurrentState() == mAudioOn);
    }

    synchronized int getAudioState(BluetoothDevice device) {
        if (mCurrentDevice == null || !mCurrentDevice.equals(device)) {
            return BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
        }
        return mAudioState;
    }

    List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized (this) {
            if (isConnected()) {
                devices.add(mCurrentDevice);
            }
        }
        return devices;
    }

    @VisibleForTesting
    byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    public List<HfpClientCall> getCurrentCalls() {
        return new ArrayList<HfpClientCall>(mCalls.values());
    }

    public Bundle getCurrentAgEvents() {
        Bundle b = new Bundle();
        b.putInt(BluetoothHeadsetClient.EXTRA_NETWORK_STATUS, mIndicatorNetworkState);
        b.putInt(BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH, mIndicatorNetworkSignal);
        b.putInt(BluetoothHeadsetClient.EXTRA_NETWORK_ROAMING, mIndicatorNetworkType);
        b.putInt(BluetoothHeadsetClient.EXTRA_BATTERY_LEVEL, mIndicatorBatteryLevel);
        b.putString(BluetoothHeadsetClient.EXTRA_OPERATOR_NAME, mOperatorName);
        b.putString(BluetoothHeadsetClient.EXTRA_SUBSCRIBER_INFO, mSubscriberInfo);
        return b;
    }

    @VisibleForTesting
    static int getConnectionStateFromAudioState(int audioState) {
        switch (audioState) {
            case BluetoothHeadsetClient.STATE_AUDIO_CONNECTED:
                return BluetoothAdapter.STATE_CONNECTED;
            case BluetoothHeadsetClient.STATE_AUDIO_CONNECTING:
                return BluetoothAdapter.STATE_CONNECTING;
            case BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED:
                return BluetoothAdapter.STATE_DISCONNECTED;
        }
        return BluetoothAdapter.STATE_DISCONNECTED;
    }

    private void debug(String message) {
        Log.d(TAG, "[" + mCurrentDevice + "]: " + message);
    }

    private void info(String message) {
        Log.i(TAG, "[" + mCurrentDevice + "]: " + message);
    }

    private void warn(String message) {

        Log.w(TAG, "[" + mCurrentDevice + "]: " + message);
    }

    private void error(String message) {

        Log.e(TAG, "[" + mCurrentDevice + "]: " + message);
    }

    public void setAudioRouteAllowed(boolean allowed) {
        mAudioRouteAllowed = allowed;

        int establishPolicy =
                allowed
                        ? BluetoothSinkAudioPolicy.POLICY_ALLOWED
                        : BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED;

        /*
         * Backward compatibility for mAudioRouteAllowed
         *
         * Set default policies if
         *  1. need to set audio policy from system props
         *  2. remote device supports audio policy
         */
        if (getForceSetAudioPolicyProperty()) {
            // set call establish policy and connecting policy to POLICY_ALLOWED if allowed=true,
            // otherwise set them to the default values
            int connectingTimePolicy =
                    allowed
                            ? BluetoothSinkAudioPolicy.POLICY_ALLOWED
                            : getConnectingTimePolicyProperty();

            setAudioPolicy(
                    new BluetoothSinkAudioPolicy.Builder(mHsClientAudioPolicy)
                            .setCallEstablishPolicy(establishPolicy)
                            .setActiveDevicePolicyAfterConnection(connectingTimePolicy)
                            .setInBandRingtonePolicy(getInBandRingtonePolicyProperty())
                            .build());
        } else {
            setAudioPolicy(
                    new BluetoothSinkAudioPolicy.Builder(mHsClientAudioPolicy)
                            .setCallEstablishPolicy(establishPolicy)
                            .build());
        }
    }

    public boolean getAudioRouteAllowed() {
        return mAudioRouteAllowed;
    }

    private static String createMaskString(BluetoothSinkAudioPolicy policies) {
        StringBuilder mask = new StringBuilder();
        mask.append(BluetoothSinkAudioPolicy.HFP_SET_SINK_AUDIO_POLICY_ID);
        mask.append(",").append(policies.getCallEstablishPolicy());
        mask.append(",").append(policies.getActiveDevicePolicyAfterConnection());
        mask.append(",").append(policies.getInBandRingtonePolicy());
        return mask.toString();
    }

    /**
     * sets the {@link BluetoothSinkAudioPolicy} object device and send to the remote device using
     * Android specific AT commands.
     *
     * @param policies to be set policies
     */
    public void setAudioPolicy(BluetoothSinkAudioPolicy policies) {
        debug("setAudioPolicy: " + policies);
        mHsClientAudioPolicy = policies;

        if (getAudioPolicyRemoteSupported() != BluetoothStatusCodes.FEATURE_SUPPORTED) {
            info("Audio Policy feature not supported!");
            return;
        }

        if (!mNativeInterface.sendAndroidAt(
                mCurrentDevice, "+ANDROID=" + createMaskString(policies))) {
            error("ERROR: Couldn't send call audio policies");
            return;
        }
        addQueuedAction(SEND_ANDROID_AT_COMMAND);
    }

    private boolean queryRemoteSupportedFeatures() {
        info("queryRemoteSupportedFeatures");
        if (!mNativeInterface.sendAndroidAt(mCurrentDevice, "+ANDROID=?")) {
            error("ERROR: Couldn't send audio policy feature query");
            return false;
        }
        addQueuedAction(SEND_ANDROID_AT_COMMAND);
        return true;
    }

    /**
     * sets the audio policy feature support status
     *
     * @param supported support status
     */
    public void setAudioPolicyRemoteSupported(boolean supported) {
        if (supported) {
            mAudioPolicyRemoteSupported = BluetoothStatusCodes.FEATURE_SUPPORTED;
        } else {
            mAudioPolicyRemoteSupported = BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }
    }

    /**
     * gets the audio policy feature support status
     *
     * @return int support status
     */
    public int getAudioPolicyRemoteSupported() {
        return mAudioPolicyRemoteSupported;
    }

    /** handles the value of {@link BluetoothSinkAudioPolicy} from system property */
    private static int getAudioPolicySystemProp(String propKey) {
        int mProp = SystemProperties.getInt(propKey, BluetoothSinkAudioPolicy.POLICY_UNCONFIGURED);
        if (mProp < BluetoothSinkAudioPolicy.POLICY_UNCONFIGURED
                || mProp > BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED) {
            mProp = BluetoothSinkAudioPolicy.POLICY_UNCONFIGURED;
        }
        return mProp;
    }

    @VisibleForTesting
    boolean getForceSetAudioPolicyProperty() {
        return mForceSetAudioPolicyProperty;
    }

    @VisibleForTesting
    int getConnectingTimePolicyProperty() {
        return mConnectingTimePolicyProperty;
    }

    @VisibleForTesting
    int getInBandRingtonePolicyProperty() {
        return mInBandRingtonePolicyProperty;
    }
}
