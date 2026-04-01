/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.content.AttributionSource;
import android.content.Intent;
import android.os.Binder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioConstants;
import com.android.bluetooth.le_audio.LeAudioUtils;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.IntStream;

class BassClientStateMachine extends StateMachine {
    private static final String TAG = BassClientStateMachine.class.getSimpleName();

    @VisibleForTesting static final byte[] REMOTE_SCAN_STOP = {00};
    @VisibleForTesting static final byte[] REMOTE_SCAN_START = {01};
    private static final byte OPCODE_ADD_SOURCE = 0x02;
    private static final byte OPCODE_UPDATE_SOURCE = 0x03;
    private static final byte OPCODE_SET_BCAST_PIN = 0x04;
    private static final byte OPCODE_REMOVE_SOURCE = 0x05;
    private static final int UPDATE_SOURCE_FIXED_LENGTH = 6;
    private static final int UPDATE_SOURCE_SUBGROUP_FIXED_LENGTH = 5;
    private static final int UPDATE_SOURCE_BIS_SYNC_LENGTH = 4;
    private static final int BROADCAST_SOURCE_ID_LENGTH = 3;

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int CONNECTION_STATE_CHANGED = 3;
    static final int GATT_TXN_PROCESSED = 4;
    static final int READ_BASS_CHARACTERISTICS = 5;
    static final int START_SCAN_OFFLOAD = 6;
    static final int STOP_SCAN_OFFLOAD = 7;
    static final int ADD_BCAST_SOURCE = 8;
    static final int UPDATE_BCAST_SOURCE = 9;
    static final int SET_BCAST_CODE = 10;
    static final int REMOVE_BCAST_SOURCE = 11;
    static final int GATT_TXN_TIMEOUT = 12;
    static final int CONNECT_TIMEOUT = 13;
    static final int SWITCH_BCAST_SOURCE = 14;
    static final int CANCEL_PENDING_SOURCE_OPERATION = 15;
    static final int INITIATE_PA_SYNC_TRANSFER = 16;
    static final int UPDATE_METADATA = 17;
    static final int ENCRYPTION_STATE_CHANGED = 18;

    static final int ATT_WRITE_CMD_HDR_LEN = 3;

    /*key is combination of sourceId, Address and advSid for this hashmap*/
    private final Map<Integer, BluetoothLeBroadcastReceiveState>
            mBluetoothLeBroadcastReceiveStates = new HashMap<>();
    private final Map<Integer, BluetoothLeBroadcastMetadata> mCurrentMetadata = new HashMap<>();
    private final Disconnected mDisconnected = new Disconnected();
    private final Connected mConnected = new Connected();
    private final Connecting mConnecting = new Connecting();
    private final ConnectedProcessing mConnectedProcessing = new ConnectedProcessing();
    private final Map<Integer, LeAudioBroadcastSyncStats> mBroadcastSyncStats =
            new LinkedHashMap<>();

    private final AdapterService mAdapterService;
    private final BluetoothAdapter mAdapter;
    private final ScanController mScanController;

    @VisibleForTesting
    final List<BluetoothGattCharacteristic> mBroadcastCharacteristics = new ArrayList<>();

    @VisibleForTesting BluetoothDevice mDevice;

    private State mCurrentState = mDisconnected;
    private boolean mIsAllowedList = false;
    private int mLastConnectionState = -1;
    @VisibleForTesting boolean mMTUChangeRequested = false;
    @VisibleForTesting boolean mDiscoveryInitiated = false;
    @VisibleForTesting BassClientService mService;
    @VisibleForTesting BluetoothGattCharacteristic mBroadcastScanControlPoint;
    private boolean mBassStateReady = false;
    @VisibleForTesting int mNumOfBroadcastReceiverStates = 0;
    int mNumOfReadyBroadcastReceiverStates = 0;
    @VisibleForTesting int mPendingOperation = -1;
    @VisibleForTesting byte mPendingSourceId = -1;
    @VisibleForTesting BluetoothLeBroadcastMetadata mPendingMetadata = null;

    private final Map<Integer, Boolean> mPendingRemove = new HashMap<>();
    private boolean mForceSB = false;
    @VisibleForTesting byte mNextSourceId = 0;
    private boolean mAllowReconnect = false;
    @VisibleForTesting BluetoothGattTestableWrapper mBluetoothGatt = null;
    BluetoothGattCallback mGattCallback = null;
    IPeriodicAdvertisingCallback mLocalPeriodicAdvCallback = new PACallback();
    int mMaxSingleAttributeWriteValueLen = 0;
    @VisibleForTesting BluetoothLeBroadcastMetadata mPendingSourceToSwitch = null;
    @VisibleForTesting boolean mIsWaitingForEncryption = false;

    BassClientStateMachine(
            BluetoothDevice device,
            BassClientService svc,
            AdapterService adapterService,
            ScanController scanController,
            Looper looper) {
        super(TAG + "(" + device + ")", looper);
        mDevice = device;
        mService = svc;
        mAdapterService = adapterService;
        mAdapter = mAdapterService.getSystemService(BluetoothManager.class).getAdapter();
        mScanController = scanController;
        addState(mDisconnected);
        addState(mConnected);
        addState(mConnecting);
        addState(mConnectedProcessing);
        setInitialState(mDisconnected);
        final long token = Binder.clearCallingIdentity();
        try {
            mIsAllowedList =
                    DeviceConfig.getBoolean(
                            DeviceConfig.NAMESPACE_BLUETOOTH, "persist.vendor.service.bt.wl", true);
            mForceSB =
                    DeviceConfig.getBoolean(
                            DeviceConfig.NAMESPACE_BLUETOOTH,
                            "persist.vendor.service.bt.forceSB",
                            false);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        start();
    }

    private static class LeAudioBroadcastSyncStats {
        private final BluetoothDevice mDevice;
        private final boolean mIsLocalBroadcast;
        private final int mBroadcastId;
        private final long mSourceAddTime;
        private long mSourcePaSyncedTime;
        private long mSourceBisSyncedTime;
        private int mSyncStatus;

        LeAudioBroadcastSyncStats(
                BluetoothDevice sink,
                BluetoothLeBroadcastMetadata metadata,
                boolean isLocalBroadcast,
                long startTime) {
            this.mDevice = sink;
            this.mIsLocalBroadcast = isLocalBroadcast;
            this.mBroadcastId = metadata.getBroadcastId();
            this.mSourceAddTime = startTime;
            this.mSourcePaSyncedTime = 0;
            this.mSourceBisSyncedTime = 0;
            this.mSyncStatus =
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_SYNC_REQUESTED;
        }

        void updatePaSyncedTime(long paSyncedTime) {
            if (mSourcePaSyncedTime == 0) {
                mSourcePaSyncedTime = paSyncedTime;
            }
        }

        void updateBisSyncedTime(long bisSyncedTime) {
            if (mSourceBisSyncedTime == 0) {
                mSourceBisSyncedTime = bisSyncedTime;
            }
        }

        void updateSyncStatus(int status) {
            if (mSyncStatus
                    != BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_AUDIO_SYNC_SUCCESS) {
                Log.d(
                        TAG,
                        "logBroadcastSyncMetrics: updating from state: "
                                + mSyncStatus
                                + " to "
                                + status);
                mSyncStatus = status;
            }
        }

        void logBroadcastSyncMetrics(long stopTime) {
            long syncDurationMs =
                    (mSourceBisSyncedTime > 0) ? (stopTime - mSourceBisSyncedTime) : 0;
            long latencyPaSyncedMs =
                    (mSourcePaSyncedTime > 0) ? (mSourcePaSyncedTime - mSourceAddTime) : 0;
            long latencyBisSyncedMs =
                    (mSourcePaSyncedTime > 0 && mSourceBisSyncedTime > 0)
                            ? (mSourceBisSyncedTime - mSourcePaSyncedTime)
                            : 0;

            Log.d(
                    TAG,
                    "logBroadcastSyncMetrics: broadcastId: "
                            + mBroadcastId
                            + ", isLocalBroadcast: "
                            + mIsLocalBroadcast
                            + ", syncDurationMs: "
                            + syncDurationMs
                            + ", latencyPaSyncedMs: "
                            + latencyPaSyncedMs
                            + ", latencyBisSyncedMs: "
                            + latencyBisSyncedMs
                            + ", syncStatus: "
                            + mSyncStatus);

            MetricsLogger.getInstance()
                    .logLeAudioBroadcastAudioSync(
                            mDevice,
                            mBroadcastId,
                            mIsLocalBroadcast,
                            syncDurationMs,
                            latencyPaSyncedMs,
                            latencyBisSyncedMs,
                            mSyncStatus);
        }
    }

    static void destroy(BassClientStateMachine stateMachine) {
        Log.i(TAG, "destroy");
        if (stateMachine == null) {
            Log.w(TAG, "destroy(), stateMachine is null");
            return;
        }
        stateMachine.doQuit();
        stateMachine.cleanup();
    }

    public void doQuit() {
        Log.d(TAG, "doQuit for device " + mDevice);
        int currentState = getConnectionState();
        if (Flags.leaudioIntentBroadcastInStateMachineCleanup()
                && currentState != STATE_DISCONNECTED
                && mLastConnectionState != -1) {
            // Broadcast CONNECTION_STATE_CHANGED when state machine is turned off while
            // the device is connected
            broadcastConnectionState(mDevice, currentState, STATE_DISCONNECTED);
        }
        quitNow();
    }

    public void cleanup() {
        Log.d(TAG, "cleanup for device " + mDevice);
        clearCharsCache();

        if (mBluetoothGatt != null) {
            Log.d(TAG, "disconnect gatt");
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mGattCallback = null;
        }
        mPendingOperation = -1;
        mPendingSourceId = -1;
        mPendingMetadata = null;
        mPendingSourceToSwitch = null;
        mCurrentMetadata.clear();
        mPendingRemove.clear();
        mBroadcastSyncStats.clear();
    }

    Boolean hasPendingSourceOperation() {
        return mPendingMetadata != null;
    }

    Boolean hasPendingSourceOperation(int broadcastId) {
        return mPendingMetadata != null && mPendingMetadata.getBroadcastId() == broadcastId;
    }

    private void cancelPendingSourceOperation(int broadcastId) {
        if ((mPendingMetadata != null) && (mPendingMetadata.getBroadcastId() == broadcastId)) {
            Log.d(TAG, "clearPendingSourceOperation: broadcast ID: " + broadcastId);
            mPendingMetadata = null;
        }
    }

    Boolean hasPendingSwitchingSourceOperation() {
        return mPendingSourceToSwitch != null;
    }

    Boolean hasPendingSwitchingSourceOperation(int broadcastId) {
        return mPendingSourceToSwitch != null
                && mPendingSourceToSwitch.getBroadcastId() == broadcastId;
    }

    int getPendingOperationBroadcastId() {
        if (mPendingSourceToSwitch != null) {
            return mPendingSourceToSwitch.getBroadcastId();
        }
        if (mPendingMetadata != null) {
            return mPendingMetadata.getBroadcastId();
        }
        return LeAudioConstants.INVALID_BROADCAST_ID;
    }

    private void setCurrentBroadcastMetadata(
            Integer sourceId, BluetoothLeBroadcastMetadata metadata) {
        if (metadata != null) {
            mCurrentMetadata.put(sourceId, metadata);
        } else {
            mCurrentMetadata.remove(sourceId);
        }
    }

    boolean isPendingRemove(Integer sourceId) {
        return mPendingRemove.getOrDefault(sourceId, false);
    }

    private void setPendingRemove(Integer sourceId, boolean remove) {
        if (remove) {
            mPendingRemove.put(sourceId, remove);
        } else {
            mPendingRemove.remove(sourceId);
        }
    }

    BluetoothLeBroadcastReceiveState getBroadcastReceiveStateForSourceDevice(
            BluetoothDevice srcDevice) {
        List<BluetoothLeBroadcastReceiveState> currentSources = getAllSources();
        BluetoothLeBroadcastReceiveState state = null;
        for (int i = 0; i < currentSources.size(); i++) {
            BluetoothDevice device = currentSources.get(i).getSourceDevice();
            if (device != null && device.equals(srcDevice)) {
                state = currentSources.get(i);
                Log.e(
                        TAG,
                        "getBroadcastReceiveStateForSourceDevice: returns for: "
                                + srcDevice
                                + "&srcInfo"
                                + state);
                return state;
            }
        }
        return null;
    }

    BluetoothLeBroadcastReceiveState getBroadcastReceiveStateForSourceId(int sourceId) {
        List<BluetoothLeBroadcastReceiveState> currentSources = getAllSources();
        for (int i = 0; i < currentSources.size(); i++) {
            if (sourceId == currentSources.get(i).getSourceId()) {
                return currentSources.get(i);
            }
        }
        return null;
    }

    boolean isSyncedToTheSource(int sourceId) {
        BluetoothLeBroadcastReceiveState recvState = getBroadcastReceiveStateForSourceId(sourceId);

        return recvState != null && (isPaSynced(recvState) || isBisSynced(recvState));
    }

    private static boolean isPaSynced(BluetoothLeBroadcastReceiveState recvState) {
        return recvState.getPaSyncState()
                == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED;
    }

    private static boolean isBisSynced(BluetoothLeBroadcastReceiveState recvState) {
        return recvState.getBisSyncState().stream()
                .anyMatch(
                        state ->
                                state != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG
                                        && state
                                                != BassConstants
                                                        .BCAST_RCVR_STATE_BIS_SYNC_NOT_SYNC_TO_BIS);
    }

    private void resetBluetoothGatt() {
        // cleanup mBluetoothGatt
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    private void broadcastReceiverState(BluetoothLeBroadcastReceiveState state, int sourceId) {
        Log.d(TAG, "broadcastReceiverState: " + mDevice);
        mService.getCallbacks().notifyReceiveStateChanged(mDevice, sourceId, state);
    }

    @VisibleForTesting
    static boolean isEmpty(final byte[] data) {
        return IntStream.range(0, data.length).parallel().allMatch(i -> data[i] == 0);
    }

    private void processPASyncState(BluetoothLeBroadcastReceiveState recvState) {
        int serviceData = 0;
        if (recvState == null) {
            Log.e(TAG, "processPASyncState: recvState is null");
            return;
        }
        int state = recvState.getPaSyncState();
        if (state == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST) {
            Log.i(TAG, "Initiate PAST procedure");
            int sourceId = recvState.getSourceId();
            if (mService.isLocalBroadcast(recvState)) {
                int advHandle = recvState.getSourceAdvertisingSid();
                serviceData = 0x000000FF & sourceId;
                serviceData = serviceData << 8;
                // Address we set in the Source Address can differ from the address in the air
                serviceData =
                        serviceData | BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS;
                Log.d(
                        TAG,
                        "Initiate local broadcast PAST for: "
                                + mDevice
                                + ", advSID/Handle: "
                                + advHandle
                                + ", serviceData: "
                                + serviceData);
                final int sd = serviceData;
                mScanController.doOnScanThread(
                        () ->
                                mScanController.transferSetInfo(
                                        mDevice, sd, advHandle, mLocalPeriodicAdvCallback));
            } else {
                int broadcastId = recvState.getBroadcastId();
                PeriodicAdvertisementResult result =
                        mService.getPeriodicAdvertisementResult(
                                recvState.getSourceDevice(), broadcastId);
                if (result != null) {
                    int syncHandle = result.getSyncHandle();
                    if (syncHandle != BassConstants.INVALID_SYNC_HANDLE
                            && syncHandle != BassConstants.PENDING_SYNC_HANDLE) {
                        initiatePaSyncTransfer(syncHandle, sourceId);
                        return;
                    }
                }
                mService.syncRequestForPast(mDevice, broadcastId, sourceId);
            }
        }
    }

    private void initiatePaSyncTransfer(int syncHandle, int sourceId) {
        if (syncHandle != BassConstants.INVALID_SYNC_HANDLE
                && sourceId != BassConstants.INVALID_SOURCE_ID) {
            int serviceData = 0x000000FF & sourceId;
            serviceData = serviceData << 8;
            // advA matches EXT_ADV_ADDRESS
            // also matches source address (as we would have written)
            serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_EXT_ADV_ADDRESS);
            serviceData =
                    serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS);
            Log.d(
                    TAG,
                    "Initiate PAST for: "
                            + mDevice
                            + ", syncHandle: "
                            + syncHandle
                            + ", serviceData: "
                            + serviceData);
            final int sd = serviceData;
            mScanController.doOnScanThread(
                    () -> mScanController.transferSync(mDevice, sd, syncHandle));
        } else {
            Log.e(
                    TAG,
                    "Invalid syncHandle or sourceId for PAST, syncHandle: "
                            + syncHandle
                            + ", sourceId: "
                            + sourceId);
        }
    }

    private void processSyncStateChangeStats(BluetoothLeBroadcastReceiveState recvState) {
        int sourceId = recvState.getSourceId();
        BluetoothLeBroadcastMetadata metaData = getCurrentBroadcastMetadata(sourceId);
        if (metaData == null) {
            Log.d(TAG, "No metadata for sourceId, skip logging");
            return;
        }

        int broadcastId = metaData.getBroadcastId();
        LeAudioBroadcastSyncStats syncStats = mBroadcastSyncStats.get(broadcastId);
        if (syncStats == null) {
            Log.d(TAG, "No stats for sourceId, skip logging");
            return;
        }

        // Check PA state
        int paState = recvState.getPaSyncState();
        if (paState == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED) {
            syncStats.updatePaSyncedTime(SystemClock.elapsedRealtime());
            syncStats.updateSyncStatus(
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_PA_SYNC_SUCCESS);
            // Continue to check other fields
        } else if (paState
                == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_FAILED_TO_SYNCHRONIZE) {
            syncStats.updateSyncStatus(
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_PA_SYNC_FAILED);
            // Update the failure state and continue to let sinks retry PA sync
        } else if (paState == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_NO_PAST) {
            // NO PAST server will not attempt to PA sync, log the failure
            logBroadcastSyncStatsWithStatus(
                    broadcastId,
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_PA_SYNC_NO_PAST);
            return;
        }

        // Check Big encrypt state
        int bigEncryptState = recvState.getBigEncryptionState();
        if (bigEncryptState == BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE) {
            logBroadcastSyncStatsWithStatus(
                    broadcastId,
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_BIG_DECRYPT_FAILED);
            return;
        }

        // Check Bis state
        for (int i = 0; i < recvState.getNumSubgroups(); i++) {
            Long bisState = recvState.getBisSyncState().get(i);
            if (bisState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG
                    && bisState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_NOT_SYNC_TO_BIS) {
                // Any bis synced, update status and break
                syncStats.updateBisSyncedTime(SystemClock.elapsedRealtime());
                syncStats.updateSyncStatus(
                        BluetoothStatsLog
                                .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_AUDIO_SYNC_SUCCESS);
                break;
            } else if (bisState == BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG) {
                logBroadcastSyncStatsWithStatus(
                        broadcastId,
                        BluetoothStatsLog
                                .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_AUDIO_SYNC_FAILED);
                break;
            }
        }
    }

    private void logBroadcastSyncStatsWithStatus(int broadcastId, int status) {
        LeAudioBroadcastSyncStats syncStats = mBroadcastSyncStats.remove(broadcastId);
        if (syncStats != null) {
            if (status
                    != BluetoothStatsLog
                            .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_UNKNOWN) {
                syncStats.updateSyncStatus(status);
            }
            syncStats.logBroadcastSyncMetrics(SystemClock.elapsedRealtime());
        }
    }

    private void logAllBroadcastSyncStatsAndCleanup() {
        for (LeAudioBroadcastSyncStats syncStats : mBroadcastSyncStats.values()) {
            syncStats.logBroadcastSyncMetrics(SystemClock.elapsedRealtime());
        }
        mBroadcastSyncStats.clear();
    }

    private static boolean isSourceAbsent(BluetoothLeBroadcastReceiveState recvState) {
        return recvState == null
                || recvState.getSourceDevice() == null
                || recvState.getSourceDevice().getAddress().equals("00:00:00:00:00:00");
    }

    private static boolean isSourcePresent(BluetoothLeBroadcastReceiveState recvState) {
        return !isSourceAbsent(recvState);
    }

    private void checkAndUpdateBroadcastCode(BluetoothLeBroadcastReceiveState recvState) {
        Log.d(TAG, "checkAndUpdateBroadcastCode");
        // Whenever receive state indicated code requested, assistant should set the broadcast code
        // Valid code will be checked later in convertRecvStateToSetBroadcastCodeByteArray
        if (recvState.getBigEncryptionState()
                == BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED) {
            Log.d(TAG, "Update the Broadcast now");
            Message m = obtainMessage(BassClientStateMachine.SET_BCAST_CODE);
            m.obj = recvState;
            sendMessage(m);
        }
    }

    private BluetoothLeBroadcastReceiveState parseBroadcastReceiverState(
            byte[] receiverState, int previousSourceId) {
        Log.d(TAG, "parseBroadcastReceiverState: receiverState length: " + receiverState.length);

        BluetoothLeBroadcastReceiveState recvState = null;
        if (receiverState.length == 0) {
            byte[] emptyBluetoothDeviceAddress = Util.getBytesFromAddress("00:00:00:00:00:00");
            if (previousSourceId != BassConstants.INVALID_SOURCE_ID) {
                recvState =
                        new BluetoothLeBroadcastReceiveState(
                                previousSourceId,
                                BluetoothDevice.ADDRESS_TYPE_PUBLIC, // sourceAddressType
                                mAdapterService.getDeviceFromByte(
                                        emptyBluetoothDeviceAddress), // sourceDev
                                0, // sourceAdvertisingSid
                                0, // broadcastId
                                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE, // paSyncState
                                // bigEncryptionState
                                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                                null, // badCode
                                0, // numSubgroups
                                Arrays.asList(new Long[0]), // bisSyncState
                                Arrays.asList(
                                        new BluetoothLeAudioContentMetadata[0]) // subgroupMetadata
                                );
            } else {
                Log.d(TAG, "parseBroadcastReceiverState: unknown sourceId");
            }
        } else {
            byte sourceId = receiverState[BassConstants.BCAST_RCVR_STATE_SRC_ID_IDX];
            byte paSyncState = receiverState[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX];
            byte bigEncryptionStatus = receiverState[BassConstants.BCAST_RCVR_STATE_ENC_STATUS_IDX];
            byte[] badBroadcastCode = null;
            int badBroadcastCodeLen = 0;
            if (bigEncryptionStatus
                    == BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE) {
                badBroadcastCode = new byte[BassConstants.BCAST_RCVR_STATE_BADCODE_SIZE];
                System.arraycopy(
                        receiverState,
                        BassConstants.BCAST_RCVR_STATE_BADCODE_START_IDX,
                        badBroadcastCode,
                        0,
                        BassConstants.BCAST_RCVR_STATE_BADCODE_SIZE);
                badBroadcastCodeLen = BassConstants.BCAST_RCVR_STATE_BADCODE_SIZE;
            }
            byte numSubGroups =
                    receiverState[
                            BassConstants.BCAST_RCVR_STATE_BADCODE_START_IDX + badBroadcastCodeLen];
            int offset = BassConstants.BCAST_RCVR_STATE_BADCODE_START_IDX + badBroadcastCodeLen + 1;
            ArrayList<BluetoothLeAudioContentMetadata> metadataList = new ArrayList<>();
            ArrayList<Long> bisSyncState = new ArrayList<>();
            for (int i = 0; i < numSubGroups; i++) {
                byte[] bisSyncIndex = new byte[Long.BYTES];
                System.arraycopy(
                        receiverState,
                        offset,
                        bisSyncIndex,
                        0,
                        BassConstants.BCAST_RCVR_STATE_BIS_SYNC_SIZE);
                offset += BassConstants.BCAST_RCVR_STATE_BIS_SYNC_SIZE;
                bisSyncState.add((long) Utils.byteArrayToLong(bisSyncIndex));

                int metaDataLength = receiverState[offset++] & 0xFF;
                if (metaDataLength > 0) {
                    Log.d(TAG, "metadata of length: " + metaDataLength + "is available");
                    byte[] metaData = new byte[metaDataLength];
                    System.arraycopy(receiverState, offset, metaData, 0, metaDataLength);
                    offset += metaDataLength;
                    metadataList.add(BluetoothLeAudioContentMetadata.fromRawBytes(metaData));
                } else {
                    metadataList.add(BluetoothLeAudioContentMetadata.fromRawBytes(new byte[0]));
                }
            }
            byte[] broadcastIdBytes = new byte[BROADCAST_SOURCE_ID_LENGTH];
            System.arraycopy(
                    receiverState,
                    BassConstants.BCAST_RCVR_STATE_SRC_BCAST_ID_START_IDX,
                    broadcastIdBytes,
                    0,
                    BROADCAST_SOURCE_ID_LENGTH);
            int broadcastId = LeAudioUtils.parseBroadcastId(broadcastIdBytes);
            byte[] sourceAddress = new byte[BassConstants.BCAST_RCVR_STATE_SRC_ADDR_SIZE];
            System.arraycopy(
                    receiverState,
                    BassConstants.BCAST_RCVR_STATE_SRC_ADDR_START_IDX,
                    sourceAddress,
                    0,
                    BassConstants.BCAST_RCVR_STATE_SRC_ADDR_SIZE);
            byte sourceAddressType =
                    receiverState[BassConstants.BCAST_RCVR_STATE_SRC_ADDR_TYPE_IDX];
            Utils.reverse(sourceAddress);
            String address = Utils.getAddressStringFromByte(sourceAddress);
            BluetoothDevice device = mAdapter.getRemoteLeDevice(address, sourceAddressType);
            byte sourceAdvSid = receiverState[BassConstants.BCAST_RCVR_STATE_SRC_ADV_SID_IDX];
            recvState =
                    new BluetoothLeBroadcastReceiveState(
                            sourceId,
                            (int) sourceAddressType,
                            device,
                            sourceAdvSid,
                            broadcastId,
                            (int) paSyncState,
                            (int) bigEncryptionStatus,
                            badBroadcastCode,
                            numSubGroups,
                            bisSyncState,
                            metadataList);
        }
        return recvState;
    }

    private void updateMetadataWithReceiveStateIfBisSyncStateChanged(
            BluetoothLeBroadcastReceiveState recvState) {
        int sourceId = recvState.getSourceId();
        BluetoothLeBroadcastMetadata metadata = getCurrentBroadcastMetadata(sourceId);

        if (metadata == null
                || metadata.getBroadcastId() != recvState.getBroadcastId()
                || metadata.getSubgroups().size() != recvState.getNumSubgroups()) {
            return;
        }

        BluetoothLeBroadcastMetadata.Builder newMetadataBuilder =
                new BluetoothLeBroadcastMetadata.Builder(metadata);
        newMetadataBuilder.clearSubgroup();

        for (int i = 0; i < recvState.getNumSubgroups(); i++) {
            Long bisSyncState = recvState.getBisSyncState().get(i);
            BluetoothLeBroadcastSubgroup currentMetadataSubgroup = metadata.getSubgroups().get(i);
            BluetoothLeBroadcastSubgroup.Builder newSubgroupBuilder =
                    new BluetoothLeBroadcastSubgroup.Builder(currentMetadataSubgroup);
            newSubgroupBuilder.clearChannel();

            for (BluetoothLeBroadcastChannel channel : currentMetadataSubgroup.getChannels()) {
                BluetoothLeBroadcastChannel channelToAdd = channel;
                if (!channel.isSelected()
                        && (bisSyncState & (1L << (channel.getChannelIndex() - 1))) != 0) {
                    // Only set selected flag to true if the bit is 1 AND if it's not already
                    // true.
                    Log.d(
                            TAG,
                            "updateMetadataWithReceiveStateIfBisSyncStateChanged: Updating"
                                    + " channel as selected, id: "
                                    + channel.getChannelIndex());
                    channelToAdd =
                            new BluetoothLeBroadcastChannel.Builder(channel)
                                    .setSelected(true)
                                    .build();
                }
                newSubgroupBuilder.addChannel(channelToAdd);
            }
            newMetadataBuilder.addSubgroup(newSubgroupBuilder.build());
        }

        setCurrentBroadcastMetadata(sourceId, newMetadataBuilder.build());
    }

    private void processBroadcastReceiverState(
            byte[] receiverState, BluetoothGattCharacteristic characteristic) {
        Log.d(
                TAG,
                "processBroadcastReceiverState: characteristic:"
                        + characteristic
                        + ", instanceId:"
                        + characteristic.getInstanceId());

        BluetoothLeBroadcastReceiveState prevRecvState =
                mBluetoothLeBroadcastReceiveStates.get(characteristic.getInstanceId());
        if (prevRecvState == null
                && (mBluetoothLeBroadcastReceiveStates.size() == mNumOfBroadcastReceiverStates)) {
            Log.e(TAG, "processBroadcastReceiverState: reached the Max SourceInfos");
            return;
        }

        int prevSourceId = BassConstants.INVALID_SOURCE_ID;
        if (prevRecvState != null) {
            prevSourceId = prevRecvState.getSourceId();
        }

        BluetoothLeBroadcastReceiveState recvState =
                parseBroadcastReceiverState(receiverState, prevSourceId);
        if (recvState == null) {
            Log.d(TAG, "processBroadcastReceiverState: Null recvState");
            return;
        }

        Log.d(
                TAG,
                "processBroadcastReceiverState: device="
                        + mDevice
                        + ", updated receiver state: "
                        + recvState);
        mBluetoothLeBroadcastReceiveStates.put(characteristic.getInstanceId(), recvState);
        int sourceId = recvState.getSourceId();

        if (isSourceAbsent(prevRecvState) && isSourcePresent(recvState)) {
            Log.d(TAG, "processBroadcastReceiverState: Source Addition");
            removeMessages(CANCEL_PENDING_SOURCE_OPERATION);
            if ((mPendingMetadata != null)
                    && (mPendingMetadata.getBroadcastId() == recvState.getBroadcastId())) {
                setCurrentBroadcastMetadata(sourceId, mPendingMetadata);
                mService.getCallbacks()
                        .notifySourceAdded(
                                mDevice, recvState, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                mPendingMetadata = null;
            } else {
                mService.getCallbacks()
                        .notifySourceAdded(
                                mDevice, recvState, BluetoothStatusCodes.REASON_REMOTE_REQUEST);
            }
            checkAndUpdateBroadcastCode(recvState);
            processPASyncState(recvState);
            processSyncStateChangeStats(recvState);
        } else if (isSourcePresent(prevRecvState) && isSourcePresent(recvState)) {
            Log.d(TAG, "processBroadcastReceiverState: Source Update");
            removeMessages(CANCEL_PENDING_SOURCE_OPERATION);
            if ((mPendingMetadata != null)
                    && (mPendingMetadata.getBroadcastId() == recvState.getBroadcastId())) {
                setCurrentBroadcastMetadata(sourceId, mPendingMetadata);
                mPendingMetadata = null;
            }
            mService.getCallbacks()
                    .notifySourceModified(
                            mDevice, sourceId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            checkAndUpdateBroadcastCode(recvState);
            processPASyncState(recvState);
            processSyncStateChangeStats(recvState);

            if (isPendingRemove(sourceId) && !isSyncedToTheSource(sourceId)) {
                Message message = obtainMessage(REMOVE_BCAST_SOURCE);
                message.arg1 = sourceId;
                sendMessage(message);
            }

            if (!Objects.equals(prevRecvState.getBisSyncState(), recvState.getBisSyncState())) {
                // Update metadata channel state if BIS sync state changed
                updateMetadataWithReceiveStateIfBisSyncStateChanged(recvState);
            }
        } else if (isSourcePresent(prevRecvState) && isSourceAbsent(recvState)) {
            BluetoothDevice removedDevice = prevRecvState.getSourceDevice();
            Log.d(TAG, "processBroadcastReceiverState: Source Removal " + removedDevice);
            BluetoothLeBroadcastMetadata metaData = getCurrentBroadcastMetadata(sourceId);
            if (metaData != null) {
                logBroadcastSyncStatsWithStatus(
                        metaData.getBroadcastId(),
                        BluetoothStatsLog
                                .BROADCAST_AUDIO_SYNC_REPORTED__SYNC_STATUS__SYNC_STATUS_UNKNOWN);
            }
            setCurrentBroadcastMetadata(sourceId, null);
            if (mPendingSourceToSwitch != null) {
                // Source remove is triggered by switch source request
                mService.getCallbacks()
                        .notifySourceRemoved(
                                mDevice, sourceId, BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST);
                Log.d(TAG, "processBroadcastReceiverState: Source Switching");
                Message message = obtainMessage(ADD_BCAST_SOURCE);
                message.obj = mPendingSourceToSwitch;
                sendMessage(message);
            } else if (mPendingOperation == REMOVE_BCAST_SOURCE) {
                mService.getCallbacks()
                        .notifySourceRemoved(
                                mDevice, sourceId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            } else {
                if (mPendingOperation == UPDATE_BCAST_SOURCE
                        && hasPendingSourceOperation(prevRecvState.getBroadcastId())) {
                    Log.d(
                            TAG,
                            "processBroadcastReceiverState: Source removed by remote, clear pending"
                                    + " operations for this source");
                    removeMessages(CANCEL_PENDING_SOURCE_OPERATION);
                    mPendingMetadata = null;
                    if (isPendingRemove(sourceId)) {
                        setPendingRemove(sourceId, false);
                    }
                }
                mService.getCallbacks()
                        .notifySourceRemoved(
                                mDevice, sourceId, BluetoothStatusCodes.REASON_REMOTE_REQUEST);
            }
        }
        broadcastReceiverState(recvState, sourceId);
    }

    // Implements callback methods for GATT events that the app cares about.
    // For example, connection change and services discovered.
    final class GattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            boolean isStateChanged = false;
            Log.d(TAG, "onConnectionStateChange : Status=" + status + ", newState=" + newState);
            if (newState == STATE_CONNECTED && getConnectionState() != STATE_CONNECTED) {
                isStateChanged = true;
                Log.w(TAG, "BassClient Connected from Disconnected state: " + mDevice);
                if (mService.okToConnect(mDevice)) {
                    Log.d(TAG, "BassClient Connected to: " + mDevice);
                    if (mBluetoothGatt != null) {
                        Log.d(
                                TAG,
                                "Attempting to start service discovery:"
                                        + mBluetoothGatt.discoverServices());
                        mDiscoveryInitiated = true;
                    }
                } else if (mBluetoothGatt != null) {
                    // Reject the connection
                    Log.w(TAG, "BassClient Connect request rejected: " + mDevice);
                    mBluetoothGatt.disconnect();
                    mBluetoothGatt.close();
                    mBluetoothGatt = null;
                    // force move to disconnected
                    newState = STATE_DISCONNECTED;
                }
            } else if (newState == STATE_DISCONNECTED
                    && getConnectionState() != STATE_DISCONNECTED) {
                isStateChanged = true;
                Log.d(TAG, "Disconnected from Bass GATT server.");
            }
            if (isStateChanged) {
                Message m = obtainMessage(CONNECTION_STATE_CHANGED);
                m.obj = newState;
                sendMessage(m);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered:" + status);
            if (mDiscoveryInitiated) {
                mDiscoveryInitiated = false;
                if (status == BluetoothGatt.GATT_SUCCESS && mBluetoothGatt != null) {
                    mBluetoothGatt.requestMtu(BassConstants.BASS_MAX_BYTES);
                    mMTUChangeRequested = true;
                } else {
                    Log.w(
                            TAG,
                            "onServicesDiscovered received: "
                                    + status
                                    + "mBluetoothGatt"
                                    + mBluetoothGatt);
                    mService.getCallbacks().notifyBassStateSetupFailed(mDevice);
                }
            } else {
                Log.d(TAG, "remote initiated callback");
            }
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS
                    && characteristic.getUuid().equals(BassConstants.BASS_BCAST_RECEIVER_STATE)) {
                Log.d(TAG, "onCharacteristicRead: BASS_BCAST_RECEIVER_STATE: status" + status);
                if (characteristic.getValue() == null) {
                    Log.e(TAG, "Remote receiver state is NULL");
                    return;
                }
                logByteArray(
                        "Received ",
                        characteristic.getValue(),
                        0,
                        characteristic.getValue().length);
                processBroadcastReceiverState(characteristic.getValue(), characteristic);
                mNumOfReadyBroadcastReceiverStates++;
                if (mNumOfReadyBroadcastReceiverStates == mNumOfBroadcastReceiverStates) {
                    // Notify service BASS state ready for operations
                    mBassStateReady = true;
                    mService.getCallbacks().notifyBassStateReady(mDevice);
                }
            }
            // switch to receiving notifications after initial characteristic read
            BluetoothGattDescriptor desc =
                    characteristic.getDescriptor(BassConstants.CLIENT_CHARACTERISTIC_CONFIG);
            if (mBluetoothGatt != null && desc != null) {
                Log.d(TAG, "Setting the value for Desc");
                mBluetoothGatt.setCharacteristicNotification(characteristic, /* enable */ true);
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothGatt.writeDescriptor(desc);
            } else {
                Log.w(TAG, "CCC for " + characteristic + "seem to be not present");
                // at least move the SM to stable state
                Message m = obtainMessage(GATT_TXN_PROCESSED);
                m.arg1 = status;
                sendMessage(m);
            }
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            // Move the SM to connected so further reads happens
            Message m = obtainMessage(GATT_TXN_PROCESSED);
            m.arg1 = status;
            sendMessage(m);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (mMTUChangeRequested && mBluetoothGatt != null) {
                mMTUChangeRequested = false;
                if (Flags.leaudioBassReadCharacteristicsAfterEncryption()) {
                    boolean isEncrypted = mService.isEncrypted(mDevice);
                    if (isEncrypted) {
                        acquireAllBassChars();
                    } else {
                        mIsWaitingForEncryption = true;
                        Log.d(TAG, "MTU changed, waiting for encryption");
                    }
                } else {
                    acquireAllBassChars();
                }
            } else {
                Log.d(
                        TAG,
                        "onMtuChanged is remote initiated trigger, mBluetoothGatt:"
                                + mBluetoothGatt);
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "mtu: " + mtu);
                mMaxSingleAttributeWriteValueLen = mtu - ATT_WRITE_CMD_HDR_LEN;
            } else {
                Log.w(TAG, "onMtuChanged failed: " + status);
                mService.getCallbacks().notifyBassStateSetupFailed(mDevice);
            }
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(BassConstants.BASS_BCAST_RECEIVER_STATE)) {
                if (characteristic.getValue() == null) {
                    Log.e(TAG, "Remote receiver state is NULL");
                    return;
                }
                processBroadcastReceiverState(characteristic.getValue(), characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Message m = obtainMessage(GATT_TXN_PROCESSED);
            m.arg1 = status;
            sendMessage(m);
        }
    }

    /** Internal periodic Advertising manager callback */
    private static final class PACallback extends IPeriodicAdvertisingCallback.Stub {
        @Override
        public void onSyncEstablished(
                int unused1,
                BluetoothDevice unused2,
                int unused3,
                int unused4,
                int unused5,
                int unused6) {}

        @Override
        public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport unused1) {}

        @Override
        public void onSyncLost(int unused1) {}

        @Override
        public void onBigInfoAdvertisingReport(int unused1, boolean unused2) {}

        @Override
        public void onSyncTransferred(BluetoothDevice device, int status) {
            Log.i(TAG, "onSyncTransferred: device=" + device + ", status =" + status);
        }
    }

    /**
     * Connects to the GATT server of the device.
     *
     * @return {@code true} if it successfully connects to the GATT server.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean connectGatt(Boolean autoConnect) {
        if (mGattCallback == null) {
            mGattCallback = new GattCallback();
        }

        mDevice.setAttributionSource(
                (new AttributionSource.Builder(AttributionSource.myAttributionSource()))
                        .setAttributionTag("BassClient")
                        .build());
        BluetoothGatt gatt =
                mDevice.connectGatt(
                        mService,
                        autoConnect,
                        mGattCallback,
                        TRANSPORT_LE,
                        (BluetoothDevice.PHY_LE_1M_MASK
                                | BluetoothDevice.PHY_LE_2M_MASK
                                | BluetoothDevice.PHY_LE_CODED_MASK),
                        null);

        if (gatt != null) {
            mBluetoothGatt = new BluetoothGattTestableWrapper(gatt);
        }

        return mBluetoothGatt != null;
    }

    /** getAllSources */
    public List<BluetoothLeBroadcastReceiveState> getAllSources() {
        return new ArrayList<>(mBluetoothLeBroadcastReceiveStates.values());
    }

    void acquireAllBassChars() {
        clearCharsCache();
        BluetoothGattService service = null;
        if (mBluetoothGatt != null) {
            Log.d(TAG, "getting Bass Service handle");
            service = mBluetoothGatt.getService(BassConstants.BASS_UUID);
        }
        if (service == null) {
            Log.d(TAG, "acquireAllBassChars: BASS service not found");
            return;
        }
        Log.d(TAG, "found BASS_SERVICE");
        List<BluetoothGattCharacteristic> allChars = service.getCharacteristics();
        int numOfChars = allChars.size();
        mNumOfBroadcastReceiverStates = numOfChars - 1;
        Log.d(TAG, "Total number of chars: " + numOfChars);
        for (int i = 0; i < allChars.size(); i++) {
            if (allChars.get(i).getUuid().equals(BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT)) {
                int properties = allChars.get(i).getProperties();

                if (((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0)
                        || ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0)) {
                    Log.w(
                            TAG,
                            "Broadcast Audio Scan Control Point characteristic has invalid "
                                    + "properties!");
                } else {
                    mBroadcastScanControlPoint = allChars.get(i);
                    Log.d(TAG, "Index of ScanCtrlPoint:" + i);
                }
            } else {
                Log.d(TAG, "Reading " + i + "th ReceiverState");
                mBroadcastCharacteristics.add(allChars.get(i));
                Message m = obtainMessage(READ_BASS_CHARACTERISTICS);
                m.obj = allChars.get(i);
                sendMessage(m);
            }
        }
    }

    void clearCharsCache() {
        if (mBroadcastCharacteristics != null) {
            mBroadcastCharacteristics.clear();
        }
        if (mBroadcastScanControlPoint != null) {
            mBroadcastScanControlPoint = null;
        }
        mNumOfBroadcastReceiverStates = 0;
        mNumOfReadyBroadcastReceiverStates = 0;
        if (mBluetoothLeBroadcastReceiveStates != null) {
            mBluetoothLeBroadcastReceiveStates.clear();
        }
        mPendingOperation = -1;
        mPendingMetadata = null;
        mBassStateReady = false;
        mCurrentMetadata.clear();
        mPendingRemove.clear();
    }

    @VisibleForTesting
    class Disconnected extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.d(
                    TAG,
                    "Enter Disconnected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            logAllBroadcastSyncStatsAndCleanup();
            mIsWaitingForEncryption = false;
            clearCharsCache();
            mNextSourceId = 0;
            removeDeferredMessages(DISCONNECT);
            if (mLastConnectionState == -1) {
                Log.d(TAG, "no Broadcast of initial profile state ");
            } else {
                broadcastConnectionState(mDevice, mLastConnectionState, STATE_DISCONNECTED);
                if (mLastConnectionState != STATE_DISCONNECTED) {
                    // Reconnect in background if not disallowed by the service
                    if (mService.okToConnect(mDevice) && mAllowReconnect) {
                        connectGatt(/*autoConnect*/ true);
                    }
                }
            }
        }

        @Override
        public void exit() {
            Log.d(
                    TAG,
                    "Exit Disconnected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_DISCONNECTED;
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(
                    TAG,
                    "Disconnected process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));
            switch (message.what) {
                case CONNECT -> {
                    Log.d(TAG, "Connecting to " + mDevice);
                    if (mBluetoothGatt != null) {
                        Log.d(TAG, "clear off, pending wl connection");
                        mBluetoothGatt.disconnect();
                        mBluetoothGatt.close();
                        mBluetoothGatt = null;
                    }
                    mAllowReconnect = true;
                    if (connectGatt(mIsAllowedList)) {
                        transitionTo(mConnecting);
                    } else {
                        Log.e(TAG, "Disconnected: error connecting to " + mDevice);
                    }
                }
                case DISCONNECT -> {
                    // Disconnect if there's an ongoing background connection
                    mAllowReconnect = false;
                    if (mBluetoothGatt != null) {
                        Log.d(TAG, "Cancelling the background connection to " + mDevice);
                        mBluetoothGatt.disconnect();
                        mBluetoothGatt.close();
                        mBluetoothGatt = null;
                    } else {
                        Log.d(TAG, "Disconnected: DISCONNECT ignored: " + mDevice);
                    }
                }
                case CONNECTION_STATE_CHANGED -> {
                    int state = (int) message.obj;
                    Log.w(TAG, "connection state changed:" + state);
                    if (state == STATE_CONNECTED) {
                        Log.d(TAG, "remote/wl connection");
                        transitionTo(mConnected);
                    } else {
                        Log.w(TAG, "Disconnected: Connection failed to " + mDevice);
                    }
                }
                default -> {
                    Log.d(TAG, "DISCONNECTED: not handled message:" + message.what);
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }
    }

    @VisibleForTesting
    class Connecting extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.d(
                    TAG,
                    "Enter Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            sendMessageDelayed(CONNECT_TIMEOUT, mDevice, BassConstants.CONNECT_TIMEOUT_MS);
            broadcastConnectionState(mDevice, mLastConnectionState, STATE_CONNECTING);
        }

        @Override
        public void exit() {
            Log.d(
                    TAG,
                    "Exit Connecting("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTING;
            removeMessages(CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(
                    TAG,
                    "Connecting process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));
            switch (message.what) {
                case CONNECT -> Log.d(TAG, "Already Connecting to " + mDevice);
                case DISCONNECT -> {
                    Log.w(TAG, "Connecting: DISCONNECT deferred: " + mDevice);
                    deferMessage(message);
                }
                case READ_BASS_CHARACTERISTICS -> {
                    Log.w(TAG, "defer READ_BASS_CHARACTERISTICS requested!: " + mDevice);
                    deferMessage(message);
                }
                case CONNECTION_STATE_CHANGED -> {
                    int state = (int) message.obj;
                    Log.w(TAG, "Connecting: connection state changed:" + state);
                    if (state == STATE_CONNECTED) {
                        transitionTo(mConnected);
                    } else {
                        Log.w(TAG, "Connection failed to " + mDevice);
                        resetBluetoothGatt();
                        transitionTo(mDisconnected);
                    }
                }
                case CONNECT_TIMEOUT -> {
                    Log.w(TAG, "CONNECT_TIMEOUT");
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        Log.e(TAG, "Unknown device timeout " + device);
                        break;
                    }
                    resetBluetoothGatt();
                    transitionTo(mDisconnected);
                }
                case ENCRYPTION_STATE_CHANGED -> deferMessage(message);
                default -> {
                    Log.d(TAG, "CONNECTING: not handled message:" + message.what);
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }
    }

    private static long getBisSyncFromChannelPreference(
            List<BluetoothLeBroadcastChannel> channels) {
        long bisSync = 0L;
        for (BluetoothLeBroadcastChannel channel : channels) {
            if (channel.isSelected()) {
                if (channel.getChannelIndex() == 0) {
                    Log.e(TAG, "getBisSyncFromChannelPreference: invalid channel index=0");
                    continue;
                }
                bisSync |= 1L << (channel.getChannelIndex() - 1);
            }
        }

        return bisSync;
    }

    private static byte[] convertMetadataToAddSourceByteArray(
            BluetoothLeBroadcastMetadata metaData) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        BluetoothDevice advSource = metaData.getSourceDevice();

        // Opcode
        stream.write(OPCODE_ADD_SOURCE);

        // Advertiser_Address_Type
        stream.write(metaData.getSourceAddressType());

        // Advertiser_Address
        byte[] bcastSourceAddr = Util.getBytesFromAddress(advSource.getAddress());
        Utils.reverse(bcastSourceAddr);
        stream.write(bcastSourceAddr, 0, 6);

        // Advertising_SID
        stream.write(metaData.getSourceAdvertisingSid());

        // Broadcast_ID
        stream.write(metaData.getBroadcastId() & 0x00000000000000FF);
        stream.write((metaData.getBroadcastId() & 0x000000000000FF00) >>> 8);
        stream.write((metaData.getBroadcastId() & 0x0000000000FF0000) >>> 16);

        // PA_Sync
        stream.write((byte) BassConstants.PA_SYNC_PAST_AVAILABLE);

        // PA_Interval
        stream.write((metaData.getPaSyncInterval() & 0x00000000000000FF));
        stream.write((metaData.getPaSyncInterval() & 0x000000000000FF00) >>> 8);

        // Num_Subgroups
        List<BluetoothLeBroadcastSubgroup> subGroups = metaData.getSubgroups();
        stream.write(metaData.getSubgroups().size());

        for (BluetoothLeBroadcastSubgroup subGroup : subGroups) {
            // BIS_Sync
            long bisSync = getBisSyncFromChannelPreference(subGroup.getChannels());
            if (bisSync == BassConstants.BIS_SYNC_DO_NOT_SYNC_TO_BIS) {
                bisSync = BassConstants.BIS_SYNC_NO_PREFERENCE;
            }
            stream.write((byte) (bisSync & 0x00000000000000FFL));
            stream.write((byte) ((bisSync & 0x000000000000FF00L) >>> 8));
            stream.write((byte) ((bisSync & 0x0000000000FF0000L) >>> 16));
            stream.write((byte) ((bisSync & 0x00000000FF000000L) >>> 24));

            // Metadata_Length
            BluetoothLeAudioContentMetadata metadata = subGroup.getContentMetadata();
            stream.write(metadata.getRawMetadata().length);

            // Metadata
            stream.write(metadata.getRawMetadata(), 0, metadata.getRawMetadata().length);
        }

        byte[] res = stream.toByteArray();
        Log.d(TAG, "BASS bytes dump, ADD_BCAST_SOURCE: " + BassUtils.byteArrayToHexString(res));
        return res;
    }

    private byte[] convertToUpdateSourceByteArray(
            int sourceId,
            BluetoothLeBroadcastMetadata metadata,
            boolean paSync,
            boolean hasChannelPreference) {
        BluetoothLeBroadcastReceiveState existingState =
                getBroadcastReceiveStateForSourceId(sourceId);

        if (existingState == null) {
            Log.e(TAG, "Existing receive state must be known before update");
            return null;
        }

        int numSubgroups =
                (metadata != null)
                        ? metadata.getSubgroups().size()
                        : existingState.getNumSubgroups();

        byte[] res =
                new byte
                        [UPDATE_SOURCE_FIXED_LENGTH
                                + numSubgroups * UPDATE_SOURCE_SUBGROUP_FIXED_LENGTH];
        int offset = 0;
        // Opcode
        res[offset++] = OPCODE_UPDATE_SOURCE;
        // Source_ID
        res[offset++] = (byte) sourceId;
        // PA_Sync
        if (paSync) {
            res[offset++] = (byte) BassConstants.PA_SYNC_PAST_AVAILABLE;
        } else {
            res[offset++] = (byte) BassConstants.PA_SYNC_DO_NOT_SYNC;
        }
        // PA_Interval
        res[offset++] = (byte) 0xFF;
        res[offset++] = (byte) 0xFF;
        // Num_Subgroups
        res[offset++] = (byte) numSubgroups;

        // BIS_Sync
        for (int i = 0; i < numSubgroups; i++) {
            long bisSync = BassConstants.BIS_SYNC_DO_NOT_SYNC_TO_BIS;
            long currentBisIndexValue = BassConstants.BIS_SYNC_NO_PREFERENCE;

            if (i < existingState.getBisSyncState().size()) {
                currentBisIndexValue = existingState.getBisSyncState().get(i);
            }

            if (metadata != null) {
                BluetoothLeBroadcastSubgroup subgroup = metadata.getSubgroups().get(i);
                List<BluetoothLeBroadcastChannel> channels = subgroup.getChannels();

                if (hasChannelPreference || subgroup.hasChannelPreference()) {
                    for (BluetoothLeBroadcastChannel channel : channels) {
                        bisSync |=
                                channel.isSelected() ? (1L << (channel.getChannelIndex() - 1)) : 0L;
                    }
                } else {
                    bisSync = BassConstants.BIS_SYNC_NO_PREFERENCE;
                }
            } else {
                if (paSync) {
                    // Keep using BIS index from remote receive state
                    bisSync = currentBisIndexValue;
                }
            }

            Log.d(
                    TAG,
                    "UPDATE_BCAST_SOURCE: bisSync from: "
                            + currentBisIndexValue
                            + " to: "
                            + bisSync);

            for (int j = 0; j < UPDATE_SOURCE_BIS_SYNC_LENGTH; j++) {
                res[offset++] = (byte) ((bisSync >>> (j * 8)) & 0xFF);
            }
            // Metadata_Length; On Modify source, don't update any Metadata
            res[offset++] = 0;
        }

        Log.d(TAG, "BASS bytes dump, UPDATE_BCAST_SOURCE: " + BassUtils.byteArrayToHexString(res));
        return res;
    }

    private byte[] convertRecvStateToSetBroadcastCodeByteArray(
            BluetoothLeBroadcastReceiveState recvState) {
        byte[] res = new byte[BassConstants.PIN_CODE_CMD_LEN];
        // Opcode
        res[0] = OPCODE_SET_BCAST_PIN;
        // Source_ID
        res[1] = (byte) recvState.getSourceId();
        Log.d(
                TAG,
                "convertRecvStateToSetBroadcastCodeByteArray: Source device : "
                        + recvState.getSourceDevice());
        BluetoothLeBroadcastMetadata metaData =
                getCurrentBroadcastMetadata(recvState.getSourceId());
        if (metaData == null) {
            Log.e(TAG, "Fail to find broadcast source, sourceId = " + recvState.getSourceId());
            return null;
        }
        // Broadcast Code
        byte[] actualPIN = metaData.getBroadcastCode();
        if (actualPIN == null) {
            Log.e(TAG, "actual PIN is null");
            return null;
        } else {
            Log.d(TAG, "byte array broadcast Code:" + Arrays.toString(actualPIN));
            Log.d(TAG, "pinLength:" + actualPIN.length);
            // Broadcast_Code, Fill the PIN code in the Last Position
            // This effectively adds padding zeros to MSB positions when the broadcast code
            // is shorter than 16 octets, skip the first 2 bytes for opcode and source_id.
            System.arraycopy(actualPIN, 0, res, 2, actualPIN.length);
            Log.d(TAG, "BASS bytes dump, SET_BCAST_PIN: " + BassUtils.byteArrayToHexString(res));
        }
        return res;
    }

    private boolean isItRightTimeToUpdateBroadcastPin(byte sourceId) {
        Collection<BluetoothLeBroadcastReceiveState> recvStates =
                mBluetoothLeBroadcastReceiveStates.values();
        Iterator<BluetoothLeBroadcastReceiveState> iterator = recvStates.iterator();
        boolean retval = false;
        if (mForceSB) {
            Log.d(TAG, "force SB is set");
            return true;
        }
        while (iterator.hasNext()) {
            BluetoothLeBroadcastReceiveState state = iterator.next();
            if (state == null) {
                Log.d(TAG, "Source state is null");
                continue;
            }
            if (sourceId == state.getSourceId()
                    && state.getBigEncryptionState()
                            == BluetoothLeBroadcastReceiveState
                                    .BIG_ENCRYPTION_STATE_CODE_REQUIRED) {
                retval = true;
                break;
            }
        }
        Log.d(TAG, "IsItRightTimeToUpdateBroadcastPIN returning:" + retval);
        return retval;
    }

    @VisibleForTesting
    class Connected extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.d(
                    TAG,
                    "Enter Connected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            removeDeferredMessages(CONNECT);
            if (mLastConnectionState != STATE_CONNECTED) {
                broadcastConnectionState(mDevice, mLastConnectionState, STATE_CONNECTED);
            }
        }

        @Override
        public void exit() {
            Log.d(
                    TAG,
                    "Exit Connected("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
            mLastConnectionState = STATE_CONNECTED;
        }

        private void writeBassControlPoint(byte[] value) {
            if (value.length > mMaxSingleAttributeWriteValueLen) {
                mBroadcastScanControlPoint.setWriteType(
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                mBroadcastScanControlPoint.setWriteType(
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }

            mBroadcastScanControlPoint.setValue(value);
            mBluetoothGatt.writeCharacteristic(mBroadcastScanControlPoint);
        }

        private void handleSourceSynchronizationChange(
                int sourceId,
                boolean paSync,
                boolean hasChannelPreference,
                BluetoothLeBroadcastMetadata metadata,
                boolean setPendingRemove) {
            Log.d(
                    TAG,
                    "handleSourceSynchronizationChange: sourceId: "
                            + sourceId
                            + ", paSync: "
                            + paSync
                            + ", hasChannelPreference: "
                            + hasChannelPreference
                            + ", setPendingRemove: "
                            + setPendingRemove
                            + ", metadata: "
                            + metadata);
            // Convert the source from either metadata or remote receive state
            byte[] updateSourceInfo =
                    convertToUpdateSourceByteArray(
                            sourceId, metadata, paSync, hasChannelPreference);
            if (updateSourceInfo == null) {
                Log.e(TAG, "update source: source Info is NULL");
                return;
            }
            if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                writeBassControlPoint(updateSourceInfo);
                mPendingOperation = UPDATE_BCAST_SOURCE;
                mPendingSourceId = (byte) sourceId;

                /* Mark source as pending remove after sink stop synchronization */
                if (setPendingRemove) {
                    setPendingRemove(sourceId, /* remove */ true);
                }

                mPendingMetadata = metadata;
                transitionTo(mConnectedProcessing);
                sendMessageDelayed(
                        GATT_TXN_TIMEOUT, UPDATE_BCAST_SOURCE, BassConstants.GATT_TXN_TIMEOUT_MS);
                // convertToUpdateSourceByteArray ensures receive state valid for sourceId
                sendMessageDelayed(
                        CANCEL_PENDING_SOURCE_OPERATION,
                        getBroadcastReceiveStateForSourceId(sourceId).getBroadcastId(),
                        BassConstants.SOURCE_OPERATION_TIMEOUT_MS);
            } else {
                Log.e(TAG, "UPDATE_BCAST_SOURCE: no Bluetooth Gatt handle, Fatal");
                mService.getCallbacks()
                        .notifySourceModifyFailed(
                                mDevice, sourceId, BluetoothStatusCodes.ERROR_UNKNOWN);
            }
        }

        private void handleSourceRemove(int sourceId) {
            Log.d(TAG, "Removing Broadcast source, sourceId: " + sourceId);
            byte[] removeSourceInfo = new byte[2];
            removeSourceInfo[0] = OPCODE_REMOVE_SOURCE;
            removeSourceInfo[1] = (byte) sourceId;
            if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                if (isPendingRemove(sourceId)) {
                    setPendingRemove(sourceId, /* remove */ false);
                }

                writeBassControlPoint(removeSourceInfo);
                mPendingOperation = REMOVE_BCAST_SOURCE;
                mPendingSourceId = (byte) sourceId;
                transitionTo(mConnectedProcessing);
                sendMessageDelayed(
                        GATT_TXN_TIMEOUT, REMOVE_BCAST_SOURCE, BassConstants.GATT_TXN_TIMEOUT_MS);
            } else {
                Log.e(TAG, "REMOVE_BCAST_SOURCE: no Bluetooth Gatt handle, Fatal");
                mService.getCallbacks()
                        .notifySourceRemoveFailed(
                                mDevice, sourceId, BluetoothStatusCodes.ERROR_UNKNOWN);
                if (mPendingSourceToSwitch != null) {
                    // Switching source failed
                    // Need to notify add source failure for service to cleanup
                    mService.getCallbacks()
                            .notifySourceAddFailed(
                                    mDevice,
                                    mPendingSourceToSwitch,
                                    BluetoothStatusCodes.ERROR_UNKNOWN);
                    mPendingSourceToSwitch = null;
                }
            }
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(
                    TAG,
                    "Connected process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));
            BluetoothLeBroadcastMetadata metaData;
            switch (message.what) {
                case CONNECT -> Log.w(TAG, "Connected: CONNECT ignored: " + mDevice);
                case DISCONNECT -> {
                    Log.d(TAG, "Disconnecting from " + mDevice);
                    mAllowReconnect = false;
                    if (mBluetoothGatt != null) {
                        mService.handleDeviceDisconnection(mDevice, true);
                        mBluetoothGatt.disconnect();
                        mBluetoothGatt.close();
                        mBluetoothGatt = null;
                        transitionTo(mDisconnected);
                    } else {
                        Log.d(TAG, "mBluetoothGatt is null");
                    }
                }
                case CONNECTION_STATE_CHANGED -> {
                    int state = (int) message.obj;
                    Log.w(TAG, "Connected:connection state changed:" + state);
                    if (state == STATE_CONNECTED) {
                        Log.w(TAG, "device is already connected to Bass" + mDevice);
                    } else {
                        Log.w(TAG, "unexpected disconnected from " + mDevice);
                        mService.handleDeviceDisconnection(mDevice, false);
                        resetBluetoothGatt();
                        transitionTo(mDisconnected);
                    }
                }
                case READ_BASS_CHARACTERISTICS -> {
                    BluetoothGattCharacteristic characteristic =
                            (BluetoothGattCharacteristic) message.obj;
                    if (mBluetoothGatt != null) {
                        mBluetoothGatt.readCharacteristic(characteristic);
                        transitionTo(mConnectedProcessing);
                    } else {
                        Log.e(TAG, "READ_BASS_CHARACTERISTICS is ignored, Gatt handle is null");
                    }
                }
                case START_SCAN_OFFLOAD -> {
                    if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                        writeBassControlPoint(REMOTE_SCAN_START);
                        mPendingOperation = message.what;
                        transitionTo(mConnectedProcessing);
                    } else {
                        Log.d(TAG, "no Bluetooth Gatt handle, may need to fetch write");
                    }
                }
                case STOP_SCAN_OFFLOAD -> {
                    if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                        writeBassControlPoint(REMOTE_SCAN_STOP);
                        mPendingOperation = message.what;
                        transitionTo(mConnectedProcessing);
                    } else {
                        Log.d(TAG, "no Bluetooth Gatt handle, may need to fetch write");
                    }
                }
                case SWITCH_BCAST_SOURCE -> {
                    metaData = (BluetoothLeBroadcastMetadata) message.obj;
                    int sourceIdToRemove = message.arg1;
                    // Save pending source to be added once existing source got removed
                    mPendingSourceToSwitch = metaData;
                    // Remove the source first
                    Message msg = obtainMessage(REMOVE_BCAST_SOURCE);
                    msg.arg1 = sourceIdToRemove;
                    sendMessage(msg);
                }
                case ADD_BCAST_SOURCE -> {
                    metaData = (BluetoothLeBroadcastMetadata) message.obj;
                    byte[] addSourceInfo = convertMetadataToAddSourceByteArray(metaData);
                    if (addSourceInfo == null) {
                        Log.e(TAG, "add source: source Info is NULL");
                        break;
                    }
                    if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                        mBroadcastSyncStats.put(
                                metaData.getBroadcastId(),
                                new LeAudioBroadcastSyncStats(
                                        mDevice,
                                        metaData,
                                        mService.isLocalBroadcast(metaData),
                                        SystemClock.elapsedRealtime()));

                        writeBassControlPoint(addSourceInfo);
                        mPendingOperation = message.what;
                        mPendingMetadata = metaData;
                        transitionTo(mConnectedProcessing);
                        sendMessageDelayed(
                                GATT_TXN_TIMEOUT,
                                ADD_BCAST_SOURCE,
                                BassConstants.GATT_TXN_TIMEOUT_MS);
                        sendMessageDelayed(
                                CANCEL_PENDING_SOURCE_OPERATION,
                                metaData.getBroadcastId(),
                                BassConstants.SOURCE_OPERATION_TIMEOUT_MS);
                    } else {
                        Log.e(TAG, "ADD_BCAST_SOURCE: no Bluetooth Gatt handle, Fatal");
                        mService.getCallbacks()
                                .notifySourceAddFailed(
                                        mDevice, metaData, BluetoothStatusCodes.ERROR_UNKNOWN);
                    }
                    if (mPendingSourceToSwitch != null
                            && mPendingSourceToSwitch.getBroadcastId()
                                    == metaData.getBroadcastId()) {
                        // Clear pending source to switch when starting to add this new source
                        mPendingSourceToSwitch = null;
                    }
                }
                case UPDATE_BCAST_SOURCE -> {
                    boolean paSync = (message.arg2 & BassConstants.FLAG_SYNC_PA) != 0;
                    boolean hasChannelPreference =
                            (message.arg2 & BassConstants.FLAG_SYNC_BIS_CHANNEL_PREFERENCE) != 0;

                    handleSourceSynchronizationChange(
                            message.arg1,
                            paSync,
                            hasChannelPreference,
                            (BluetoothLeBroadcastMetadata) message.obj,
                            false /* setPendingRemove */);
                }
                case SET_BCAST_CODE -> {
                    BluetoothLeBroadcastReceiveState recvState =
                            (BluetoothLeBroadcastReceiveState) message.obj;
                    if (!isItRightTimeToUpdateBroadcastPin((byte) recvState.getSourceId())) {
                        Log.d(TAG, "Ignore SET_BCAST now, but restore it for later");
                        break;
                    }
                    byte[] setBroadcastPINcmd =
                            convertRecvStateToSetBroadcastCodeByteArray(recvState);
                    if (setBroadcastPINcmd == null) {
                        Log.e(TAG, "SET_BCAST_CODE: Broadcast code is NULL");
                        break;
                    }
                    if (mBluetoothGatt != null && mBroadcastScanControlPoint != null) {
                        writeBassControlPoint(setBroadcastPINcmd);
                        mPendingOperation = message.what;
                        mPendingSourceId = (byte) recvState.getSourceId();
                        transitionTo(mConnectedProcessing);
                        sendMessageDelayed(
                                GATT_TXN_TIMEOUT,
                                SET_BCAST_CODE,
                                BassConstants.GATT_TXN_TIMEOUT_MS);
                    }
                }
                case REMOVE_BCAST_SOURCE -> {
                    int sourceId = message.arg1;

                    /* In case of source being synced PA or BIS, synchronization needs to be
                     * stopped prior.
                     */
                    if (isSyncedToTheSource(sourceId)) {
                        handleSourceSynchronizationChange(
                                sourceId,
                                false, /* paSync */
                                false, /* hasChannelPreference */
                                null, /* metadata */
                                true /* setPendingRemove*/);
                        break;
                    }

                    handleSourceRemove(sourceId);
                }
                case CANCEL_PENDING_SOURCE_OPERATION -> {
                    int broadcastId = message.arg1;
                    cancelPendingSourceOperation(broadcastId);
                }
                case INITIATE_PA_SYNC_TRANSFER -> {
                    int syncHandle = message.arg1;
                    int sourceIdForPast = message.arg2;
                    initiatePaSyncTransfer(syncHandle, sourceIdForPast);
                }
                case UPDATE_METADATA -> {
                    int sourceIdForUpdateMetadata = message.arg1;
                    metaData = (BluetoothLeBroadcastMetadata) message.obj;
                    setCurrentBroadcastMetadata(sourceIdForUpdateMetadata, metaData);
                    updateMetadataWithReceiveStateIfBisSyncStateChanged(
                            getBroadcastReceiveStateForSourceId(sourceIdForUpdateMetadata));
                }
                case ENCRYPTION_STATE_CHANGED -> {
                    boolean isEncrypted = (message.arg1 == BassConstants.ENCRYPTED);
                    if (isEncrypted && mIsWaitingForEncryption) {
                        Log.d(TAG, "Encrypted, acquiring BASS chars");
                        acquireAllBassChars();
                        mIsWaitingForEncryption = false;
                    } else if (!isEncrypted) {
                        Log.e(TAG, "Encryption failed, notify BASS state setup failed");
                        mIsWaitingForEncryption = false;
                        mService.getCallbacks().notifyBassStateSetupFailed(mDevice);
                    }
                }
                default -> {
                    Log.d(TAG, "CONNECTED: not handled message:" + message.what);
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }
    }

    private static boolean isSuccess(int status) {
        return switch (status) {
            case BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST,
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST,
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST,
                    BluetoothStatusCodes.REASON_SYSTEM_POLICY ->
                    true;
            default -> false;
        };
    }

    void sendPendingCallbacks(int pendingOp, int status) {
        switch (pendingOp) {
            case START_SCAN_OFFLOAD -> Log.d(TAG, "sendPendingCallbacks: START_SCAN_OFFLOAD");
            case ADD_BCAST_SOURCE -> {
                if (!isSuccess(status)) {
                    if (mPendingMetadata != null) {
                        mService.getCallbacks()
                                .notifySourceAddFailed(mDevice, mPendingMetadata, status);
                        mPendingMetadata = null;
                    }
                    removeMessages(CANCEL_PENDING_SOURCE_OPERATION);
                }
            }
            case UPDATE_BCAST_SOURCE -> {
                if (!isSuccess(status)) {
                    mService.getCallbacks()
                            .notifySourceModifyFailed(mDevice, mPendingSourceId, status);
                    mPendingMetadata = null;
                    removeMessages(CANCEL_PENDING_SOURCE_OPERATION);
                }
            }
            case REMOVE_BCAST_SOURCE -> {
                if (!isSuccess(status)) {
                    mService.getCallbacks()
                            .notifySourceRemoveFailed(mDevice, mPendingSourceId, status);
                    if (mPendingSourceToSwitch != null) {
                        // Switching source failed
                        // Need to notify add source failure for service to cleanup
                        mService.getCallbacks()
                                .notifySourceAddFailed(mDevice, mPendingSourceToSwitch, status);
                        mPendingSourceToSwitch = null;
                    }
                }
            }
            case SET_BCAST_CODE -> Log.d(TAG, "sendPendingCallbacks: SET_BCAST_CODE");
            default -> Log.d(TAG, "sendPendingCallbacks: unhandled case");
        }
    }

    // public for testing, but private for non-testing
    @VisibleForTesting
    class ConnectedProcessing extends State {
        @Override
        public void enter() {
            mCurrentState = this;
            Log.d(
                    TAG,
                    "Enter ConnectedProcessing("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
        }

        @Override
        public void exit() {
            /* Pending Metadata will be used to bond with source ID in receiver state notify */
            if (mPendingOperation == REMOVE_BCAST_SOURCE) {
                mPendingMetadata = null;
            }

            Log.d(
                    TAG,
                    "Exit ConnectedProcessing("
                            + mDevice
                            + "): "
                            + messageWhatToString(getCurrentMessage().what));
        }

        @Override
        public boolean processMessage(Message message) {
            Log.d(
                    TAG,
                    "ConnectedProcessing process message("
                            + mDevice
                            + "): "
                            + messageWhatToString(message.what));
            switch (message.what) {
                case CONNECT -> Log.w(TAG, "CONNECT request is ignored" + mDevice);
                case DISCONNECT -> {
                    Log.w(TAG, "DISCONNECT requested!: " + mDevice);
                    mAllowReconnect = false;
                    if (mBluetoothGatt != null) {
                        mService.handleDeviceDisconnection(mDevice, true);
                        mBluetoothGatt.disconnect();
                        mBluetoothGatt.close();
                        mBluetoothGatt = null;
                        transitionTo(mDisconnected);
                    } else {
                        Log.d(TAG, "mBluetoothGatt is null");
                    }
                }
                case READ_BASS_CHARACTERISTICS -> {
                    Log.w(TAG, "defer READ_BASS_CHARACTERISTICS requested!: " + mDevice);
                    deferMessage(message);
                }
                case CONNECTION_STATE_CHANGED -> {
                    int state = (int) message.obj;
                    Log.w(TAG, "ConnectedProcessing: connection state changed:" + state);
                    if (state == STATE_CONNECTED) {
                        Log.w(TAG, "should never happen from this state");
                    } else {
                        Log.w(TAG, "Unexpected disconnection " + mDevice);
                        mService.handleDeviceDisconnection(mDevice, false);
                        resetBluetoothGatt();
                        transitionTo(mDisconnected);
                    }
                }
                case GATT_TXN_PROCESSED -> {
                    removeMessages(GATT_TXN_TIMEOUT);
                    int status = (int) message.arg1;
                    Log.d(TAG, "GATT transaction processed for" + mDevice);
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendPendingCallbacks(
                                mPendingOperation, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                    } else {
                        sendPendingCallbacks(mPendingOperation, BluetoothStatusCodes.ERROR_UNKNOWN);
                    }
                    transitionTo(mConnected);
                }
                case GATT_TXN_TIMEOUT -> {
                    Log.d(TAG, "GATT transaction timeout for" + mDevice);
                    sendPendingCallbacks(mPendingOperation, BluetoothStatusCodes.ERROR_UNKNOWN);
                    mPendingOperation = -1;
                    mPendingSourceId = -1;
                    if ((message.arg1 == UPDATE_BCAST_SOURCE)
                            || (message.arg1 == ADD_BCAST_SOURCE)) {
                        mPendingMetadata = null;
                    }
                    transitionTo(mConnected);
                }
                case START_SCAN_OFFLOAD,
                        STOP_SCAN_OFFLOAD,
                        ADD_BCAST_SOURCE,
                        UPDATE_BCAST_SOURCE,
                        SET_BCAST_CODE,
                        REMOVE_BCAST_SOURCE,
                        SWITCH_BCAST_SOURCE,
                        INITIATE_PA_SYNC_TRANSFER,
                        UPDATE_METADATA -> {
                    Log.d(
                            TAG,
                            "defer the message: "
                                    + messageWhatToString(message.what)
                                    + ", so that it will be processed later");
                    deferMessage(message);
                }
                case CANCEL_PENDING_SOURCE_OPERATION -> {
                    int broadcastId = message.arg1;
                    cancelPendingSourceOperation(broadcastId);
                }
                case ENCRYPTION_STATE_CHANGED -> deferMessage(message);
                default -> {
                    Log.d(TAG, "ConnectedProcessing: not handled message:" + message.what);
                    return NOT_HANDLED;
                }
            }
            return HANDLED;
        }
    }

    void broadcastConnectionState(BluetoothDevice device, int fromState, int toState) {
        Log.d(TAG, "broadcastConnectionState " + device + ": " + fromState + "->" + toState);
        if (fromState == STATE_CONNECTED && toState == STATE_CONNECTED) {
            Log.d(TAG, "CONNECTED->CONNECTED: Ignore");
            return;
        }

        mService.handleConnectionStateChanged(device, fromState, toState);
        Intent intent = new Intent(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, fromState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, toState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mService.getBaseContext()
                .sendBroadcastMultiplePermissions(
                        intent,
                        new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
                        Util.getTempBroadcastOptions());
    }

    int getConnectionState() {
        String currentState = mCurrentState.getName();
        return switch (currentState) {
            case "Disconnected" -> STATE_DISCONNECTED;
            case "Connecting" -> STATE_CONNECTING;
            case "Connected", "ConnectedProcessing" -> STATE_CONNECTED;
            default -> {
                Log.e(TAG, "Bad currentState: " + currentState);
                yield STATE_DISCONNECTED;
            }
        };
    }

    int getMaximumSourceCapacity() {
        return mNumOfBroadcastReceiverStates;
    }

    boolean isBassStateReady() {
        return mBassStateReady;
    }

    BluetoothLeBroadcastMetadata getCurrentBroadcastMetadata(Integer sourceId) {
        return mCurrentMetadata.getOrDefault(sourceId, null);
    }

    BluetoothDevice getDevice() {
        return mDevice;
    }

    synchronized boolean isConnected() {
        return (mCurrentState == mConnected) || (mCurrentState == mConnectedProcessing);
    }

    public static String messageWhatToString(int what) {
        return switch (what) {
            case CONNECT -> "CONNECT";
            case DISCONNECT -> "DISCONNECT";
            case CONNECTION_STATE_CHANGED -> "CONNECTION_STATE_CHANGED";
            case GATT_TXN_PROCESSED -> "GATT_TXN_PROCESSED";
            case READ_BASS_CHARACTERISTICS -> "READ_BASS_CHARACTERISTICS";
            case START_SCAN_OFFLOAD -> "START_SCAN_OFFLOAD";
            case STOP_SCAN_OFFLOAD -> "STOP_SCAN_OFFLOAD";
            case ADD_BCAST_SOURCE -> "ADD_BCAST_SOURCE";
            case UPDATE_BCAST_SOURCE -> "UPDATE_BCAST_SOURCE";
            case SET_BCAST_CODE -> "SET_BCAST_CODE";
            case REMOVE_BCAST_SOURCE -> "REMOVE_BCAST_SOURCE";
            case SWITCH_BCAST_SOURCE -> "SWITCH_BCAST_SOURCE";
            case CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            case CANCEL_PENDING_SOURCE_OPERATION -> "CANCEL_PENDING_SOURCE_OPERATION";
            case INITIATE_PA_SYNC_TRANSFER -> "INITIATE_PA_SYNC_TRANSFER";
            case UPDATE_METADATA -> "UPDATE_METADATA";
            case ENCRYPTION_STATE_CHANGED -> "ENCRYPTION_STATE_CHANGED";
            default -> Integer.toString(what);
        };
    }

    /** Dump info */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice);
        ProfileService.println(sb, "mCurrentState: " + mCurrentState.getName());
        ProfileService.println(sb, "  StateMachine: " + this);
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
        scanner.close();
        for (Map.Entry<Integer, BluetoothLeBroadcastReceiveState> entry :
                mBluetoothLeBroadcastReceiveStates.entrySet()) {
            BluetoothLeBroadcastReceiveState state = entry.getValue();
            sb.append(state);
        }
    }

    private static void logByteArray(String prefix, byte[] value, int offset, int count) {
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = offset; i < count; i++) {
            builder.append(String.format("0x%02X", value[i]));
            if (i != value.length - 1) {
                builder.append(", ");
            }
        }
        Log.d(TAG, builder.toString());
    }

    /** Mockable wrapper of {@link BluetoothGatt}. */
    @VisibleForTesting
    public static class BluetoothGattTestableWrapper {
        public final BluetoothGatt mWrappedBluetoothGatt;

        BluetoothGattTestableWrapper(BluetoothGatt bluetoothGatt) {
            mWrappedBluetoothGatt = bluetoothGatt;
        }

        /** See {@link BluetoothGatt#getServices()}. */
        public List<BluetoothGattService> getServices() {
            return mWrappedBluetoothGatt.getServices();
        }

        /** See {@link BluetoothGatt#getService(UUID)}. */
        @Nullable
        public BluetoothGattService getService(UUID uuid) {
            return mWrappedBluetoothGatt.getService(uuid);
        }

        /** See {@link BluetoothGatt#discoverServices()}. */
        public boolean discoverServices() {
            return mWrappedBluetoothGatt.discoverServices();
        }

        /** See {@link BluetoothGatt#readCharacteristic( BluetoothGattCharacteristic)}. */
        public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
            return mWrappedBluetoothGatt.readCharacteristic(characteristic);
        }

        /**
         * See {@link BluetoothGatt#writeCharacteristic( BluetoothGattCharacteristic, byte[], int)}
         * .
         */
        public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
            return mWrappedBluetoothGatt.writeCharacteristic(characteristic);
        }

        /** See {@link BluetoothGatt#readDescriptor(BluetoothGattDescriptor)}. */
        public boolean readDescriptor(BluetoothGattDescriptor descriptor) {
            return mWrappedBluetoothGatt.readDescriptor(descriptor);
        }

        /** See {@link BluetoothGatt#writeDescriptor(BluetoothGattDescriptor, byte[])}. */
        public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
            return mWrappedBluetoothGatt.writeDescriptor(descriptor);
        }

        /** See {@link BluetoothGatt#requestMtu(int)}. */
        public boolean requestMtu(int mtu) {
            return mWrappedBluetoothGatt.requestMtu(mtu);
        }

        /** See {@link BluetoothGatt#setCharacteristicNotification}. */
        public boolean setCharacteristicNotification(
                BluetoothGattCharacteristic characteristic, boolean enable) {
            return mWrappedBluetoothGatt.setCharacteristicNotification(characteristic, enable);
        }

        /** See {@link BluetoothGatt#disconnect()}. */
        public void disconnect() {
            mWrappedBluetoothGatt.disconnect();
        }

        /** See {@link BluetoothGatt#close()}. */
        public void close() {
            mWrappedBluetoothGatt.close();
        }
    }
}
