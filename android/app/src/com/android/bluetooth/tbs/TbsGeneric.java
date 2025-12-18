/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.tbs;

import static com.android.bluetooth.telephony.BluetoothInCallService.BEARER_TECHNOLOGY_GSM;
import static com.android.bluetooth.telephony.BluetoothInCallService.Capability;
import static com.android.bluetooth.telephony.BluetoothInCallService.Result;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeCall;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.le_audio.ContentControlIdKeeper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Container class to store TBS instances */
public class TbsGeneric {
    private static final String TAG = TbsGeneric.class.getSimpleName();

    private static final String UCI = "GTBS";
    private static final String DEFAULT_PROVIDER_NAME = "none";
    /* Use GSM as default technology value. It is used only
     * when bearer is not registered. It will be updated on the phone call
     */
    private static final int DEFAULT_BEARER_TECHNOLOGY = BEARER_TECHNOLOGY_GSM;
    private static final String UNKNOWN_FRIENDLY_NAME = "unknown";

    /** Class representing the pending request sent to the application */
    private static class Request {
        final BluetoothDevice mDevice;
        final List<UUID> mCallIdList;
        final int mRequestedOpcode;
        final int mCallIndex;

        Request(BluetoothDevice device, UUID callId, int requestedOpcode, int callIndex) {
            this.mDevice = device;
            this.mCallIdList = Arrays.asList(callId);
            this.mRequestedOpcode = requestedOpcode;
            this.mCallIndex = callIndex;
        }

        Request(
                BluetoothDevice device,
                List<ParcelUuid> callIds,
                int requestedOpcode,
                int callIndex) {
            this.mDevice = device;
            this.mCallIdList = new ArrayList<>();
            for (ParcelUuid callId : callIds) {
                this.mCallIdList.add(callId.getUuid());
            }
            this.mRequestedOpcode = requestedOpcode;
            this.mCallIndex = callIndex;
        }
    }

    /* Application-registered TBS instance */
    private static class Bearer {
        final String token;
        final TbsService.Callback callback;
        final List<String> mUriSchemes;
        final int capabilities;
        final int ccid;
        final String providerName;
        final int technology;
        Map<UUID, Integer> callIdIndexMap = new HashMap<>();
        final Map<Integer, Request> mRequestMap = new HashMap<>();

        Bearer(
                String token,
                TbsService.Callback callback,
                List<String> uriSchemes,
                int capabilities,
                String providerName,
                int technology,
                int ccid) {
            this.token = token;
            this.callback = callback;
            this.mUriSchemes = uriSchemes;
            this.capabilities = capabilities;
            this.providerName = providerName;
            this.technology = technology;
            this.ccid = ccid;
        }
    }

    private final List<Bearer> mBearerList = new ArrayList<>();
    private final Map<Integer, TbsCall> mCurrentCallsList = new TreeMap<>();
    private final Receiver mReceiver = new Receiver();

    private final AdapterService mAdapterService;
    private final TbsGatt mTbsGatt;

    private boolean mIsInitialized;
    private int mLastIndexAssigned = TbsCall.INDEX_UNASSIGNED;
    private Bearer mForegroundBearer = null;
    private int mLastRequestIdAssigned = 0;
    private List<String> mUriSchemes = new ArrayList<>(Arrays.asList("tel"));
    private int mStoredRingerMode = -1;

    private final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (TbsGeneric.this) {
                if (!mIsInitialized) {
                    Log.w(TAG, "onReceive called while not initialized.");
                    return;
                }

                final String action = intent.getAction();
                if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                    int ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);

                    if (ringerMode < 0 || ringerMode == mStoredRingerMode) return;

                    mStoredRingerMode = ringerMode;

                    if (isSilentModeEnabled()) {
                        mTbsGatt.setSilentModeFlag();
                    } else {
                        mTbsGatt.clearSilentModeFlag();
                    }
                }
            }
        }
    }

    TbsGeneric(AdapterService adapterService, TbsGatt tbsGatt) {
        mAdapterService = requireNonNull(adapterService);
        mTbsGatt = requireNonNull(tbsGatt);

        int ccid =
                ContentControlIdKeeper.acquireCcid(
                        mAdapterService,
                        new ParcelUuid(TbsGatt.UUID_GTBS),
                        BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL);
        if (!isCcidValid(ccid)) {
            Log.e(TAG, " CCID is not valid");
            cleanup();
            return;
        }

        if (!mTbsGatt.init(
                ccid,
                UCI,
                mUriSchemes,
                true,
                true,
                DEFAULT_PROVIDER_NAME,
                DEFAULT_BEARER_TECHNOLOGY,
                mTbsGattCallback)) {
            Log.e(TAG, " TbsGatt init failed");
            cleanup();
            return;
        }

        final var audioManager =
                requireNonNull(mAdapterService.getSystemService(AudioManager.class));
        // read initial value of ringer mode
        mStoredRingerMode = audioManager.getRingerMode();

        if (isSilentModeEnabled()) {
            mTbsGatt.setSilentModeFlag();
        } else {
            mTbsGatt.clearSilentModeFlag();
        }

        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mAdapterService.registerReceiver(mReceiver, filter);

        mIsInitialized = true;
    }

    public synchronized void cleanup() {
        Log.d(TAG, "cleanup");

        if (mIsInitialized) {
            mAdapterService.unregisterReceiver(mReceiver);
        }
        mTbsGatt.cleanup();

        mIsInitialized = false;
    }

    /**
     * Inform TBS GATT instance about authorization change for device.
     *
     * @param device device for which authorization is changed
     */
    public synchronized void onDeviceAuthorizationSet(BluetoothDevice device) {
        // Notify TBS GATT service instance in case of pending operations
        mTbsGatt.onDeviceAuthorizationSet(device);
    }

    /**
     * Set inband ringtone for the device. When set, notification will be sent to given device.
     *
     * @param device device for which inband ringtone has been set
     */
    public synchronized void setInbandRingtoneSupport(BluetoothDevice device) {
        mTbsGatt.setInbandRingtoneFlag(device);
    }

    /**
     * Clear inband ringtone for the device. When set, notification will be sent to given device.
     *
     * @param device device for which inband ringtone has been cleared
     */
    public synchronized void clearInbandRingtoneSupport(BluetoothDevice device) {
        mTbsGatt.clearInbandRingtoneFlag(device);
    }

    private synchronized boolean isSilentModeEnabled() {
        return mStoredRingerMode != AudioManager.RINGER_MODE_NORMAL;
    }

    private synchronized Bearer getBearerByToken(String token) {
        for (Bearer bearer : mBearerList) {
            if (bearer.token.equals(token)) {
                return bearer;
            }
        }
        return null;
    }

    private synchronized Bearer getBearerByCcid(int ccid) {
        for (Bearer bearer : mBearerList) {
            if (bearer.ccid == ccid) {
                return bearer;
            }
        }
        return null;
    }

    private synchronized Bearer getBearerSupportingUri(String uri) {
        for (Bearer bearer : mBearerList) {
            for (String s : bearer.mUriSchemes) {
                if (uri.startsWith(s + ":")) {
                    return bearer;
                }
            }
        }
        return null;
    }

    private synchronized Map.Entry<UUID, Bearer> getCallIdByIndex(int callIndex) {
        for (Bearer bearer : mBearerList) {
            for (Map.Entry<UUID, Integer> callIdToIndex : bearer.callIdIndexMap.entrySet()) {
                if (callIndex == callIdToIndex.getValue()) {
                    return Map.entry(callIdToIndex.getKey(), bearer);
                }
            }
        }
        return null;
    }

    public synchronized boolean addBearer(
            String token,
            TbsService.Callback callback,
            String uci,
            List<String> uriSchemes,
            int capabilities,
            String providerName,
            int technology) {
        Log.d(
                TAG,
                "addBearer: token="
                        + token
                        + " uci="
                        + uci
                        + " uriSchemes="
                        + uriSchemes
                        + " capabilities="
                        + capabilities
                        + " providerName="
                        + providerName
                        + " technology="
                        + technology);
        if (!mIsInitialized) {
            Log.w(TAG, "addBearer called while not initialized.");
            return false;
        }

        if (getBearerByToken(token) != null) {
            Log.w(TAG, "addBearer: token=" + token + " registered already");
            return false;
        }

        // Acquire CCID for TbsObject. The CCID is released on remove()
        Bearer bearer =
                new Bearer(
                        token,
                        callback,
                        uriSchemes,
                        capabilities,
                        providerName,
                        technology,
                        ContentControlIdKeeper.acquireCcid(
                                mAdapterService,
                                new ParcelUuid(UUID.randomUUID()),
                                BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL));
        if (isCcidValid(bearer.ccid)) {
            mBearerList.add(bearer);

            updateUriSchemesSupported();
            if (mForegroundBearer == null) {
                setForegroundBearer(bearer);
            }
        } else {
            Log.e(TAG, "Failed to acquire ccid");
        }

        if (callback != null) {
            Log.d(TAG, "ccid=" + bearer.ccid);
            callback.onBearerRegistered(bearer.ccid);
        }

        return isCcidValid(bearer.ccid);
    }

    public synchronized void removeBearer(String token) {
        Log.d(TAG, "removeBearer: token=" + token);

        if (!mIsInitialized) {
            Log.w(TAG, "removeBearer called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByToken(token);
        if (bearer == null) {
            return;
        }

        // Remove the calls associated with this bearer
        for (Integer callIndex : bearer.callIdIndexMap.values()) {
            mCurrentCallsList.remove(callIndex);
        }

        if (bearer.callIdIndexMap.size() > 0) {
            notifyCclc();
        }

        // Release the ccid acquired
        ContentControlIdKeeper.releaseCcid(mAdapterService, bearer.ccid);

        mBearerList.remove(bearer);

        updateUriSchemesSupported();
        if (mForegroundBearer == bearer) {
            setForegroundBearer(findNewForegroundBearer());
        }
    }

    private synchronized void checkRequestComplete(Bearer bearer, UUID callId, TbsCall tbsCall) {
        // check if there's any pending request related to this call
        Map.Entry<Integer, Request> requestEntry = null;
        if (bearer.mRequestMap.size() > 0) {
            for (Map.Entry<Integer, Request> entry : bearer.mRequestMap.entrySet()) {
                if (entry.getValue().mCallIdList.contains(callId)) {
                    requestEntry = entry;
                }
            }
        }

        if (requestEntry == null) {
            Log.d(TAG, "requestEntry is null");
            return;
        }

        int requestId = requestEntry.getKey();
        Request request = requestEntry.getValue();

        int result;
        if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_TERMINATE) {
            if (mCurrentCallsList.get(request.mCallIndex) == null) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            } else {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            }
        } else if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT) {
            if (tbsCall.getState() != BluetoothLeCall.STATE_INCOMING) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            } else {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            }
        } else if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_HOLD) {
            if (tbsCall.getState() == BluetoothLeCall.STATE_LOCALLY_HELD
                    || tbsCall.getState() == BluetoothLeCall.STATE_LOCALLY_AND_REMOTELY_HELD) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            } else {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            }
        } else if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_RETRIEVE) {
            if (tbsCall.getState() != BluetoothLeCall.STATE_LOCALLY_HELD
                    && tbsCall.getState() != BluetoothLeCall.STATE_LOCALLY_AND_REMOTELY_HELD) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            } else {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            }
        } else if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_ORIGINATE) {
            if (bearer.callIdIndexMap.get(request.mCallIdList.get(0)) != null) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            } else {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            }
        } else if (request.mRequestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_JOIN) {
            /* While joining calls, those that are not in remotely held state should go to active */
            if (bearer.callIdIndexMap.get(callId) == null
                    || (tbsCall.getState() != BluetoothLeCall.STATE_ACTIVE
                            && tbsCall.getState() != BluetoothLeCall.STATE_REMOTELY_HELD)) {
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
            } else {
                /* Check if all of the pending calls transit to required state */
                for (UUID pendingCallId : request.mCallIdList) {
                    Integer callIndex = bearer.callIdIndexMap.get(pendingCallId);
                    TbsCall pendingTbsCall = mCurrentCallsList.get(callIndex);
                    if (pendingTbsCall.getState() != BluetoothLeCall.STATE_ACTIVE
                            && pendingTbsCall.getState() != BluetoothLeCall.STATE_REMOTELY_HELD) {
                        /* Still waiting for more call state updates */
                        return;
                    }
                }
                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
            }
        } else {
            result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
        }

        mTbsGatt.setCallControlPointResult(
                request.mDevice, request.mRequestedOpcode, request.mCallIndex, result);

        bearer.mRequestMap.remove(requestId);
    }

    private synchronized int getTbsResult(int result, int requestedOpcode) {
        if (result == Result.ERROR_UNKNOWN_CALL_ID) {
            return TbsGatt.CALL_CONTROL_POINT_RESULT_INVALID_CALL_INDEX;
        }

        if (result == Result.ERROR_INVALID_URI
                && requestedOpcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_ORIGINATE) {
            return TbsGatt.CALL_CONTROL_POINT_RESULT_INVALID_OUTGOING_URI;
        }

        return TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
    }

    public synchronized void requestResult(int ccid, int requestId, int result) {
        Log.d(TAG, "requestResult: ccid=" + ccid + " requestId=" + requestId + " result=" + result);

        if (!mIsInitialized) {
            Log.w(TAG, "requestResult called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByCcid(ccid);
        if (bearer == null) {
            Log.i(TAG, " Bearer for ccid " + ccid + " does not exist");
            return;
        }

        if (result == Result.SUCCESS) {
            // don't send the success here, wait for state transition instead
            return;
        }

        // check if there's any pending request related to this call
        Request request = bearer.mRequestMap.remove(requestId);
        if (request == null) {
            // already sent response
            return;
        }

        int tbsResult = getTbsResult(result, request.mRequestedOpcode);
        mTbsGatt.setCallControlPointResult(
                request.mDevice, request.mRequestedOpcode, request.mCallIndex, tbsResult);
    }

    public synchronized void callAdded(int ccid, BluetoothLeCall call) {
        Log.d(TAG, "callAdded: ccid=" + ccid + " call=" + call);

        if (!mIsInitialized) {
            Log.w(TAG, "callAdded called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByCcid(ccid);
        if (bearer == null) {
            Log.e(TAG, "callAdded: unknown ccid=" + ccid);
            return;
        }

        UUID callId = call.getUuid();
        if (bearer.callIdIndexMap.containsKey(callId)) {
            Log.e(TAG, "callAdded: uuidId=" + callId + " already on list");
            return;
        }

        Integer callIndex = getFreeCallIndex();
        if (callIndex == null) {
            Log.e(TAG, "callAdded: out of call indices!");
            return;
        }

        bearer.callIdIndexMap.put(callId, callIndex);
        TbsCall tbsCall = TbsCall.create(call);
        mCurrentCallsList.put(callIndex, tbsCall);

        checkRequestComplete(bearer, callId, tbsCall);
        if (tbsCall.isIncoming()) {
            mTbsGatt.setIncomingCall(callIndex, tbsCall.getUri());
        }

        String friendlyName = tbsCall.getFriendlyName();
        if (friendlyName == null) {
            friendlyName = UNKNOWN_FRIENDLY_NAME;
        }
        mTbsGatt.setCallFriendlyName(callIndex, friendlyName);

        notifyCclc();
        if (mForegroundBearer != bearer) {
            setForegroundBearer(bearer);
        }
    }

    public synchronized void callRemoved(int ccid, UUID callId, int reason) {
        Log.d(TAG, "callRemoved: ccid=" + ccid + "reason=" + reason);

        if (!mIsInitialized) {
            Log.w(TAG, "callRemoved called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByCcid(ccid);
        if (bearer == null) {
            Log.e(TAG, "callRemoved: unknown ccid=" + ccid);
            return;
        }

        Integer callIndex = bearer.callIdIndexMap.remove(callId);
        if (callIndex == null) {
            Log.e(TAG, "callIndex: is null for callId" + callId);
            return;
        }

        TbsCall tbsCall = mCurrentCallsList.remove(callIndex);
        if (tbsCall == null) {
            Log.e(TAG, "callRemoved: no such call");
            return;
        }

        checkRequestComplete(bearer, callId, tbsCall);
        mTbsGatt.setTerminationReason(callIndex, reason);
        notifyCclc();

        Integer incomingCallIndex = mTbsGatt.getIncomingCallIndex();
        if (incomingCallIndex != null && incomingCallIndex.equals(callIndex)) {
            mTbsGatt.clearIncomingCall();
            // TODO: check if there's any incoming call more???
        }

        Integer friendlyNameCallIndex = mTbsGatt.getCallFriendlyNameIndex();
        if (friendlyNameCallIndex != null && friendlyNameCallIndex.equals(callIndex)) {
            mTbsGatt.clearFriendlyName();
            // TODO: check if there's any incoming/outgoing call more???
        }
    }

    public synchronized void callStateChanged(int ccid, UUID callId, int state) {
        Log.d(TAG, "callStateChanged: ccid=" + ccid + " callId=" + callId + " state=" + state);

        if (!mIsInitialized) {
            Log.w(TAG, "callStateChanged called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByCcid(ccid);
        if (bearer == null) {
            Log.e(TAG, "callStateChanged: unknown ccid=" + ccid);
            return;
        }

        Integer callIndex = bearer.callIdIndexMap.get(callId);
        if (callIndex == null) {
            Log.e(TAG, "callStateChanged: unknown callId=" + callId);
            return;
        }

        TbsCall tbsCall = mCurrentCallsList.get(callIndex);
        if (tbsCall.getState() == state) {
            return;
        }

        tbsCall.setState(state);

        checkRequestComplete(bearer, callId, tbsCall);
        notifyCclc();

        Integer incomingCallIndex = mTbsGatt.getIncomingCallIndex();
        if (incomingCallIndex != null && incomingCallIndex.equals(callIndex)) {
            mTbsGatt.clearIncomingCall();
            // TODO: check if there's any incoming call more???
        }
    }

    public synchronized void currentCallsList(int ccid, List<BluetoothLeCall> calls) {
        Log.d(TAG, "currentCallsList: ccid=" + ccid + " callsNum=" + calls.size());

        if (!mIsInitialized) {
            Log.w(TAG, "currentCallsList called while not initialized.");
            return;
        }

        Bearer bearer = getBearerByCcid(ccid);
        if (bearer == null) {
            Log.e(TAG, "currentCallsList: unknown ccid=" + ccid);
            return;
        }

        boolean cclc = false;
        Map<UUID, Integer> storedCallIdList = new HashMap<>(bearer.callIdIndexMap);
        bearer.callIdIndexMap = new HashMap<>();
        for (BluetoothLeCall call : calls) {
            UUID callId = call.getUuid();
            Integer callIndex = storedCallIdList.get(callId);
            if (callIndex == null) {
                // new call
                callIndex = getFreeCallIndex();
                if (callIndex == null) {
                    Log.e(TAG, "currentCallsList: out of call indices!");
                    continue;
                }

                mCurrentCallsList.put(callIndex, TbsCall.create(call));
                cclc |= true;
            } else {
                TbsCall tbsCallNew = TbsCall.create(call);
                if (!tbsCallNew.equals(mCurrentCallsList.get(callIndex))) {
                    mCurrentCallsList.replace(callIndex, tbsCallNew);
                    cclc |= true;
                }
            }

            bearer.callIdIndexMap.put(callId, callIndex);
        }

        for (Map.Entry<UUID, Integer> callIdToIndex : storedCallIdList.entrySet()) {
            if (!bearer.callIdIndexMap.containsKey(callIdToIndex.getKey())) {
                mCurrentCallsList.remove(callIdToIndex.getValue());
                cclc |= true;
            }
        }

        if (cclc) {
            notifyCclc();
        }
    }

    private synchronized int processOriginateCall(BluetoothDevice device, String uri) {
        if (uri.startsWith("tel")) {
            /*
             * FIXME: For now, process telephone call originate request here, as
             * BluetoothInCallService might be not running. The BluetoothInCallService is active
             * when there is a call only.
             */
            Log.i(TAG, "originate uri=" + uri);
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, Uri.parse(uri));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mAdapterService.startActivity(intent);
            mTbsGatt.setCallControlPointResult(
                    device,
                    TbsGatt.CALL_CONTROL_POINT_OPCODE_ORIGINATE,
                    TbsCall.INDEX_UNASSIGNED,
                    TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS);
        } else {
            UUID callId = UUID.randomUUID();
            int requestId = mLastRequestIdAssigned + 1;
            Request request =
                    new Request(
                            device,
                            callId,
                            TbsGatt.CALL_CONTROL_POINT_OPCODE_ORIGINATE,
                            TbsCall.INDEX_UNASSIGNED);

            Bearer bearer = getBearerSupportingUri(uri);
            if (bearer == null) {
                return TbsGatt.CALL_CONTROL_POINT_RESULT_INVALID_OUTGOING_URI;
            }

            bearer.callback.onPlaceCall(requestId, callId, uri);

            bearer.mRequestMap.put(requestId, request);
            mLastIndexAssigned = requestId;
        }

        setActiveLeDevice(device);
        return TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
    }

    private final TbsGatt.Callback mTbsGattCallback =
            new TbsGatt.Callback() {

                @Override
                public void onServiceAdded(boolean success) {
                    synchronized (TbsGeneric.this) {
                        Log.d(TAG, "onServiceAdded: success=" + success);
                    }
                }

                @Override
                public boolean isInbandRingtoneEnabled(BluetoothDevice device) {
                    final var leAudio = mAdapterService.getLeAudioService();
                    if (leAudio.isEmpty()) {
                        Log.i(TAG, "LeAudio service not available");
                        return false;
                    }
                    int groupId = leAudio.get().getGroupId(device);
                    return leAudio.get().isInbandRingtoneEnabled(groupId);
                }

                @Override
                public void onCallControlPointRequest(
                        BluetoothDevice device, int opcode, byte[] args) {
                    synchronized (TbsGeneric.this) {
                        Log.d(
                                TAG,
                                "onCallControlPointRequest: device="
                                        + device
                                        + " opcode="
                                        + callControlRequestOpcodeStr(opcode)
                                        + "("
                                        + opcode
                                        + ")"
                                        + " argsLen="
                                        + args.length);

                        if (!mIsInitialized) {
                            Log.w(TAG, "onCallControlPointRequest called while not initialized.");
                            return;
                        }

                        if (shouldBlockTbsForBroadcastReceiver(device)) {
                            Log.w(
                                    TAG,
                                    "Blocking TBS operation for non-primary device in broadcast,"
                                            + " opcode = "
                                            + callControlRequestOpcodeStr(opcode));
                            mTbsGatt.setCallControlPointResult(
                                    device,
                                    opcode,
                                    0,
                                    TbsGatt.CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE);
                            return;
                        }

                        int result;

                        switch (opcode) {
                            case TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT,
                                    TbsGatt.CALL_CONTROL_POINT_OPCODE_TERMINATE,
                                    TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_HOLD,
                                    TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_RETRIEVE -> {
                                if (args.length == 0) {
                                    result =
                                            TbsGatt
                                                    .CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
                                    break;
                                }

                                int callIndex = args[0];
                                Map.Entry<UUID, Bearer> entry = getCallIdByIndex(callIndex);
                                if (entry == null) {
                                    result = TbsGatt.CALL_CONTROL_POINT_RESULT_INVALID_CALL_INDEX;
                                    break;
                                }

                                TbsCall call = mCurrentCallsList.get(callIndex);
                                if (!isCallStateTransitionValid(call.getState(), opcode)) {
                                    result = TbsGatt.CALL_CONTROL_POINT_RESULT_STATE_MISMATCH;
                                    break;
                                }

                                Bearer bearer = entry.getValue();
                                UUID callId = entry.getKey();
                                int requestId = mLastRequestIdAssigned + 1;
                                Request request = new Request(device, callId, opcode, callIndex);
                                if (opcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT) {
                                    setActiveLeDevice(device);
                                    bearer.callback.onAcceptCall(requestId, callId);
                                } else if (opcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_TERMINATE) {
                                    bearer.callback.onTerminateCall(requestId, callId);
                                } else if (opcode == TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_HOLD) {
                                    if ((bearer.capabilities & Capability.HOLD_CALL) == 0) {
                                        result =
                                                TbsGatt
                                                        .CALL_CONTROL_POINT_RESULT_OPCODE_NOT_SUPPORTED;
                                        break;
                                    }
                                    bearer.callback.onHoldCall(requestId, callId);
                                } else {
                                    if ((bearer.capabilities & Capability.HOLD_CALL) == 0) {
                                        result =
                                                TbsGatt
                                                        .CALL_CONTROL_POINT_RESULT_OPCODE_NOT_SUPPORTED;
                                        break;
                                    }
                                    bearer.callback.onUnholdCall(requestId, callId);
                                }

                                bearer.mRequestMap.put(requestId, request);
                                mLastRequestIdAssigned = requestId;

                                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
                            }

                            case TbsGatt.CALL_CONTROL_POINT_OPCODE_ORIGINATE -> {
                                result = processOriginateCall(device, new String(args));
                            }

                            case TbsGatt.CALL_CONTROL_POINT_OPCODE_JOIN -> {
                                // at least 2 call indices are required
                                if (args.length < 2) {
                                    result =
                                            TbsGatt
                                                    .CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
                                    break;
                                }

                                Map.Entry<UUID, Bearer> firstEntry = null;
                                List<ParcelUuid> parcelUuids = new ArrayList<>();
                                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
                                for (int callIndex : args) {
                                    Map.Entry<UUID, Bearer> entry = getCallIdByIndex(callIndex);
                                    if (entry == null) {
                                        result =
                                                TbsGatt
                                                        .CALL_CONTROL_POINT_RESULT_INVALID_CALL_INDEX;
                                        break;
                                    }

                                    // state transition is valid, because a call in any state
                                    // can requested to join

                                    if (firstEntry == null) {
                                        firstEntry = entry;
                                    }

                                    if (firstEntry.getValue() != entry.getValue()) {
                                        Log.w(TAG, "Cannot join calls from different bearers!");
                                        result =
                                                TbsGatt
                                                        .CALL_CONTROL_POINT_RESULT_OPERATION_NOT_POSSIBLE;
                                        break;
                                    }

                                    parcelUuids.add(new ParcelUuid(entry.getKey()));
                                }

                                if (result != TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS) {
                                    break;
                                }

                                List<UUID> callUuids = new ArrayList<>();
                                for (ParcelUuid parcelUuid : parcelUuids) {
                                    callUuids.add(parcelUuid.getUuid());
                                }

                                Bearer bearer = firstEntry.getValue();
                                Request request = new Request(device, parcelUuids, opcode, args[0]);
                                int requestId = mLastRequestIdAssigned + 1;
                                bearer.callback.onJoinCalls(requestId, callUuids);

                                bearer.mRequestMap.put(requestId, request);
                                mLastIndexAssigned = requestId;

                                result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;
                            }

                            default -> {
                                result = TbsGatt.CALL_CONTROL_POINT_RESULT_OPCODE_NOT_SUPPORTED;
                            }
                        }

                        if (result == TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS) {
                            // return here and wait for the request completion from application
                            return;
                        }

                        mTbsGatt.setCallControlPointResult(device, opcode, 0, result);
                    }
                }
            };

    private static String callControlRequestOpcodeStr(int opcode) {
        return switch (opcode) {
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT -> "ACCEPT";
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_TERMINATE -> "TERMINATE";
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_HOLD -> "LOCAL_HOLD";
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_RETRIEVE -> "LOCAL_RETRIEVE";
            default -> "UNKNOWN";
        };
    }

    private static boolean isCcidValid(int ccid) {
        return ccid != ContentControlIdKeeper.CCID_INVALID;
    }

    private static boolean isCallIndexAssigned(int callIndex) {
        return callIndex != TbsCall.INDEX_UNASSIGNED;
    }

    private synchronized Integer getFreeCallIndex() {
        int callIndex = mLastIndexAssigned;
        for (int i = TbsCall.INDEX_MIN; i <= TbsCall.INDEX_MAX; i++) {
            callIndex = (callIndex + 1) % TbsCall.INDEX_MAX;
            if (!isCallIndexAssigned(callIndex)) {
                continue;
            }

            if (mCurrentCallsList.keySet().contains(callIndex)) {
                continue;
            }

            mLastIndexAssigned = callIndex;

            return callIndex;
        }

        return null;
    }

    private synchronized Map.Entry<Integer, TbsCall> getCallByStates(Set<Integer> states) {
        for (Map.Entry<Integer, TbsCall> entry : mCurrentCallsList.entrySet()) {
            if (states.contains(entry.getValue().getState())) {
                return entry;
            }
        }

        return null;
    }

    private synchronized Map.Entry<Integer, TbsCall> getForegroundCall() {
        LinkedHashSet<Integer> states = new LinkedHashSet<>();
        Map.Entry<Integer, TbsCall> foregroundCall;

        if (mCurrentCallsList.size() == 0) {
            return null;
        }

        states.add(BluetoothLeCall.STATE_INCOMING);
        foregroundCall = getCallByStates(states);
        if (foregroundCall != null) {
            return foregroundCall;
        }

        states.clear();
        states.add(BluetoothLeCall.STATE_DIALING);
        states.add(BluetoothLeCall.STATE_ALERTING);
        foregroundCall = getCallByStates(states);
        if (foregroundCall != null) {
            return foregroundCall;
        }

        states.clear();
        states.add(BluetoothLeCall.STATE_ACTIVE);
        foregroundCall = getCallByStates(states);
        if (foregroundCall != null) {
            return foregroundCall;
        }

        return null;
    }

    private synchronized Bearer findNewForegroundBearer() {
        if (mBearerList.size() == 0) {
            return null;
        }

        // the bearer that owns the foreground call
        Map.Entry<Integer, TbsCall> foregroundCall = getForegroundCall();
        if (foregroundCall != null) {
            for (Bearer bearer : mBearerList) {
                if (bearer.callIdIndexMap.values().contains(foregroundCall.getKey())) {
                    return bearer;
                }
            }
        }

        // the last bearer registered
        return mBearerList.get(mBearerList.size() - 1);
    }

    private synchronized void setForegroundBearer(Bearer bearer) {
        Log.d(TAG, "setForegroundBearer: bearer=" + bearer);

        if (bearer == null) {
            mTbsGatt.setBearerProviderName(DEFAULT_PROVIDER_NAME);
            mTbsGatt.setBearerTechnology(DEFAULT_BEARER_TECHNOLOGY);
        } else if (mForegroundBearer == null) {
            mTbsGatt.setBearerProviderName(bearer.providerName);
            mTbsGatt.setBearerTechnology(bearer.technology);
        } else {
            if (!bearer.providerName.equals(mForegroundBearer.providerName)) {
                mTbsGatt.setBearerProviderName(bearer.providerName);
            }

            if (bearer.technology != mForegroundBearer.technology) {
                mTbsGatt.setBearerTechnology(bearer.technology);
            }
        }

        mForegroundBearer = bearer;
    }

    private synchronized void notifyCclc() {
        Log.d(TAG, "notifyCclc");

        mAdapterService
                .getLeAudioService()
                .ifPresent(
                        leAudio -> {
                            if (mCurrentCallsList.size() > 0) {
                                leAudio.setInCall(true);
                            } else {
                                leAudio.setInCall(false);
                            }
                        });

        mTbsGatt.setCallState(mCurrentCallsList);
        mTbsGatt.setBearerListCurrentCalls(mCurrentCallsList);
    }

    private synchronized void updateUriSchemesSupported() {
        List<String> newUriSchemes = new ArrayList<>();
        for (Bearer bearer : mBearerList) {
            newUriSchemes.addAll(bearer.mUriSchemes);
        }

        // filter duplicates
        newUriSchemes = new ArrayList<>(new HashSet<>(newUriSchemes));
        if (newUriSchemes.equals(mUriSchemes)) {
            return;
        }

        mUriSchemes = new ArrayList<>(newUriSchemes);
        mTbsGatt.setBearerUriSchemesSupportedList(mUriSchemes);
    }

    private void setActiveLeDevice(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "setActiveLeDevice: ignore null device");
            return;
        }

        mAdapterService.setActiveDevice(device, BluetoothAdapter.ACTIVE_DEVICE_AUDIO);
    }

    private static boolean isCallStateTransitionValid(int callState, int requestedOpcode) {
        return switch (requestedOpcode) {
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT ->
                    callState == BluetoothLeCall.STATE_INCOMING;
            // Any call can be terminated.
            case TbsGatt.CALL_CONTROL_POINT_OPCODE_TERMINATE -> true;

            case TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_HOLD ->
                    callState == BluetoothLeCall.STATE_INCOMING
                            || callState == BluetoothLeCall.STATE_ACTIVE
                            || callState == BluetoothLeCall.STATE_REMOTELY_HELD;

            case TbsGatt.CALL_CONTROL_POINT_OPCODE_LOCAL_RETRIEVE ->
                    callState == BluetoothLeCall.STATE_LOCALLY_HELD
                            || callState == BluetoothLeCall.STATE_LOCALLY_AND_REMOTELY_HELD;

            default -> {
                Log.e(TAG, "unhandled opcode " + requestedOpcode);
                yield false;
            }
        };
    }

    private boolean shouldBlockTbsForBroadcastReceiver(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "shouldBlockTbsForBroadcastReceiver: Ignore null device");
            return false;
        }

        final var leAudio = mAdapterService.getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.w(TAG, "shouldBlockTbsForBroadcastReceiver: LeAudioService is not available");
            return false;
        }

        return leAudio.get().getLocalBroadcastReceivers().contains(device)
                && !leAudio.get().isPrimaryDevice(device);
    }

    /**
     * Dump status of TBS service along with related objects
     *
     * @param sb string builder object that TBS module will be appending
     */
    public void dump(StringBuilder sb) {
        sb.append("    Ringer Mode: ").append(mStoredRingerMode);

        sb.append("\n    Current call list:");
        for (TbsCall call : mCurrentCallsList.values()) {
            sb.append("\n      Friendly name: ").append(call.getSafeFriendlyName());
            sb.append("\n        State: ").append(TbsCall.stateToString(call.getState()));
            sb.append("\n        URI: ").append(call.getSafeUri());
            sb.append("\n        Flags: ").append(TbsCall.flagsToString(call.getFlags()));
        }

        mTbsGatt.dump(sb);
    }
}
