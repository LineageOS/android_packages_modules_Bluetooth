/*
 * Copyright 2022 The Android Open Source Project
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
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static com.android.bluetooth.flags.Flags.leaudioBassScanWithInternalScanController;
import static com.android.bluetooth.flags.Flags.leaudioBigDependsOnAudioState;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastApiGetLocalMetadata;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastPreventResumeInterruption;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastResyncHelper;
import static com.android.bluetooth.flags.Flags.leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator;
import static com.android.bluetooth.flags.Flags.leaudioSortScansToSyncByFails;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeBroadcastAssistant;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.PeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.le_scan.ScanController;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Broadcast Assistant Scan Service */
public class BassClientService extends ProfileService {
    static final String TAG = BassClientService.class.getSimpleName();

    private static final int MAX_ACTIVE_SYNCED_SOURCES_NUM = 4;
    private static final int MAX_BIS_DISCOVERY_TRIES_NUM = 5;

    private static final int STATUS_LOCAL_STREAM_REQUESTED = 0;
    private static final int STATUS_LOCAL_STREAM_STREAMING = 1;
    private static final int STATUS_LOCAL_STREAM_SUSPENDED = 2;
    private static final int STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE = 3;

    // Do not modify without updating the HAL bt_le_audio.h files.
    // Match up with BroadcastState enum of bt_le_audio.h
    private static final int BROADCAST_STATE_STOPPED = 0;
    private static final int BROADCAST_STATE_CONFIGURING = 1;
    private static final int BROADCAST_STATE_PAUSED = 2;
    private static final int BROADCAST_STATE_ENABLING = 3;
    private static final int BROADCAST_STATE_DISABLING = 4;
    private static final int BROADCAST_STATE_STOPPING = 5;
    private static final int BROADCAST_STATE_STREAMING = 6;

    @VisibleForTesting static final int MESSAGE_SYNC_TIMEOUT = 1;
    @VisibleForTesting static final int MESSAGE_BIG_MONITOR_TIMEOUT = 2;
    @VisibleForTesting static final int MESSAGE_BROADCAST_MONITOR_TIMEOUT = 3;
    @VisibleForTesting static final int MESSAGE_SYNC_LOST_TIMEOUT = 4;

    /* 1 minute timeout for primary device reconnection in Private Broadcast case */
    private static final int DIALING_OUT_TIMEOUT_MS = 60000;

    // 30 secs timeout for keeping PSYNC active when searching is stopped
    private static final Duration sSyncActiveTimeout = Duration.ofSeconds(30);

    // 30 minutes timeout for monitoring BIG resynchronization
    private static final Duration sBigMonitorTimeout = Duration.ofMinutes(30);

    // 5 minutes timeout for monitoring broadcaster
    private static final Duration sBroadcasterMonitorTimeout = Duration.ofMinutes(5);

    // 5 seconds timeout for sync Lost notification
    private static final Duration sSyncLostTimeout = Duration.ofSeconds(5);

    private enum PauseType {
        HOST_INTENTIONAL,
        SINK_UNINTENTIONAL
    }

    private static BassClientService sService;

    private final Map<BluetoothDevice, BassClientStateMachine> mStateMachines = new HashMap<>();
    private final Object mSearchScanCallbackLock = new Object();
    private final Map<Integer, ScanResult> mCachedBroadcasts = new HashMap<>();

    private final List<Integer> mActiveSyncedSources = new ArrayList<>();
    private final Map<Integer, PeriodicAdvertisingCallback> mPeriodicAdvCallbacksMap =
            new HashMap<>();
    private final PriorityQueue<SourceSyncRequest> mSourceSyncRequestsQueue =
            new PriorityQueue<>(sSourceSyncRequestComparator);
    private final Map<Integer, Integer> mSyncFailureCounter = new HashMap<>();
    private final Map<Integer, Integer> mBisDiscoveryCounterMap = new HashMap<>();
    private final List<AddSourceData> mPendingSourcesToAdd = new ArrayList<>();

    private final Map<BluetoothDevice, List<Pair<Integer, Object>>> mPendingGroupOp =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mGroupManagedSources =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mActiveSourceMap = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Map<Integer, BluetoothLeBroadcastMetadata>>
            mBroadcastMetadataMap = new ConcurrentHashMap<>();
    private final Set<BluetoothDevice> mPausedBroadcastSinks = ConcurrentHashMap.newKeySet();
    private final Map<BluetoothDevice, Pair<Integer, Integer>> mSinksWaitingForPast =
            new HashMap<>();
    private final Map<Integer, PauseType> mPausedBroadcastIds = new HashMap<>();
    private final Map<Integer, HashSet<BluetoothDevice>> mLocalBroadcastReceivers =
            new ConcurrentHashMap<>();
    private final BassScanCallbackWrapper mBassScanCallback = new BassScanCallbackWrapper();

    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private final HandlerThread mStateMachinesThread;
    private final HandlerThread mCallbackHandlerThread;
    private final Callbacks mCallbacks;

    private BluetoothLeScannerWrapper mBluetoothLeScannerWrapper = null;
    private DialingOutTimeoutEvent mDialingOutTimeoutEvent = null;

    /* Caching the PeriodicAdvertisementResult from Broadcast source */
    /* This is stored at service so that each device state machine can access
    and use it as needed. Once the periodic sync in cancelled, this data will be
    removed to ensure stable data won't used */
    /* syncHandle, broadcastSrcDevice */
    private final Map<Integer, BluetoothDevice> mSyncHandleToDeviceMap = new HashMap<>();
    /*syncHandle, parsed BaseData data*/
    private final Map<Integer, BaseData> mSyncHandleToBaseDataMap = new HashMap<>();
    /*syncHandle, broadcast id */
    private final Map<Integer, Integer> mSyncHandleToBroadcastIdMap = new HashMap<>();
    /*bcastSrcDevice, corresponding broadcast id and PeriodicAdvertisementResult*/
    private final Map<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>>
            mPeriodicAdvertisementResultMap = new HashMap<>();
    private ScanCallback mSearchScanCallback = null;
    private boolean mIsAssistantActive = false;
    private boolean mIsAllowedContextOfActiveGroupModified = false;
    Optional<Integer> mUnicastSourceStreamStatus = Optional.empty();

    private static final int LOG_NB_EVENTS = 100;
    private static final BluetoothEventLogger sEventLogger =
            new BluetoothEventLogger(LOG_NB_EVENTS, TAG + " event log");

    @VisibleForTesting ServiceFactory mServiceFactory = new ServiceFactory();

    private class BassScanCallbackWrapper extends IScannerCallback.Stub {
        private static final int SCANNER_ID_NOT_INITIALIZED = -2;
        private static final int SCANNER_ID_INITIALIZING = -1;

        private final List<ScanFilter> mBaasUuidFilters = new ArrayList<ScanFilter>();
        private int mScannerId = SCANNER_ID_NOT_INITIALIZED;

        void registerAndStartScan(List<ScanFilter> filters) {
            synchronized (this) {
                if (mScannerId == SCANNER_ID_INITIALIZING) {
                    Log.d(TAG, "registerAndStartScan: Scanner is already initializing");
                    mCallbacks.notifySearchStartFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }
                ScanController controller = mAdapterService.getBluetoothScanController();
                if (controller == null) {
                    Log.d(TAG, "registerAndStartScan: ScanController is null");
                    mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    return;
                }
                if (filters != null) {
                    mBaasUuidFilters.addAll(filters);
                }

                if (!BassUtils.containUuid(mBaasUuidFilters, BassConstants.BAAS_UUID)) {
                    byte[] serviceData = {0x00, 0x00, 0x00}; // Broadcast_ID
                    byte[] serviceDataMask = {0x00, 0x00, 0x00};

                    mBaasUuidFilters.add(
                            new ScanFilter.Builder()
                                    .setServiceData(
                                            BassConstants.BAAS_UUID, serviceData, serviceDataMask)
                                    .build());
                }

                mScannerId = SCANNER_ID_INITIALIZING;
                controller.registerScannerInternal(this, getAttributionSource(), null);
            }
        }

        void stopScanAndUnregister() {
            synchronized (this) {
                ScanController controller = mAdapterService.getBluetoothScanController();
                if (controller == null) {
                    Log.d(TAG, "stopScanAndUnregister: ScanController is null");
                    mCallbacks.notifySearchStopFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    return;
                }
                controller.stopScanInternal(mScannerId);
                controller.unregisterScannerInternal(mScannerId);
                mBaasUuidFilters.clear();
                mScannerId = SCANNER_ID_NOT_INITIALIZED;
            }
        }

        boolean isBroadcastAudioAnnouncementScanActive() {
            synchronized (this) {
                return mScannerId >= 0;
            }
        }

        @Override
        public void onScannerRegistered(int status, int scannerId) {
            Log.d(TAG, "onScannerRegistered: Status: " + status + ", id:" + scannerId);
            synchronized (this) {
                if (status != BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "onScannerRegistered: Scanner registration failed: " + status);
                    mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    mScannerId = SCANNER_ID_NOT_INITIALIZED;
                    return;
                }
                mScannerId = scannerId;

                ScanSettings settings =
                        new ScanSettings.Builder()
                                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .setLegacy(false)
                                .build();

                ScanController controller = mAdapterService.getBluetoothScanController();
                if (controller == null) {
                    Log.d(TAG, "onScannerRegistered: ScanController is null");
                    mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    return;
                }
                controller.startScanInternal(scannerId, settings, mBaasUuidFilters);
                mCallbacks.notifySearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            }
        }

        @Override
        public void onScanResult(ScanResult result) {
            log("onScanResult:" + result);
            synchronized (this) {
                if (mScannerId < 0) {
                    Log.d(TAG, "onScanResult: Ignoring result as scan stopped.");
                    return;
                }
            }

            Integer broadcastId = BassUtils.getBroadcastId(result);
            if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
                Log.d(TAG, "onScanResult: Broadcast ID is invalid");
                return;
            }

            log("Broadcast Source Found:" + result.getDevice());
            sEventLogger.logd(TAG, "Broadcast Source Found: Broadcast ID: " + broadcastId);

            synchronized (mSearchScanCallbackLock) {
                if (!mCachedBroadcasts.containsKey(broadcastId)) {
                    log("selectBroadcastSource: broadcastId " + broadcastId);
                    mCachedBroadcasts.put(broadcastId, result);
                    addSelectSourceRequest(broadcastId, /* hasPriority */ false);
                } else {
                    if (mTimeoutHandler.isStarted(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT)) {
                        mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_SYNC_LOST_TIMEOUT, sSyncLostTimeout);
                    }
                    if (isSinkUnintentionalPauseType(broadcastId)) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> batchResults) {}

        @Override
        public void onFoundOrLost(boolean onFound, ScanResult scanResult) {}

        @Override
        public void onScanManagerErrorCallback(int errorCode) {
            Log.d(TAG, "onScanManagerErrorCallback: errorCode = " + errorCode);
            synchronized (this) {
                if (mScannerId < 0) {
                    return;
                }
            }
            mScannerId = SCANNER_ID_NOT_INITIALIZED;
            informConnectedDeviceAboutScanOffloadStop();
        }
    }

    @VisibleForTesting
    final Handler mHandler =
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MESSAGE_SYNC_TIMEOUT:
                            {
                                log("MESSAGE_SYNC_TIMEOUT");
                                clearAllSyncData();
                                break;
                            }
                        default:
                            break;
                    }
                }
            };

    @VisibleForTesting public final TimeoutHandler mTimeoutHandler = new TimeoutHandler();

    @VisibleForTesting
    public final class TimeoutHandler {
        private final Map<Integer, Handler> mHandlers = new HashMap<>();

        @VisibleForTesting
        public Handler getOrCreateHandler(int broadcastId) {
            return mHandlers.computeIfAbsent(
                    broadcastId,
                    key ->
                            new Handler(Looper.getMainLooper()) {
                                @Override
                                public void handleMessage(Message msg) {
                                    switch (msg.what) {
                                        case MESSAGE_SYNC_LOST_TIMEOUT:
                                            {
                                                log("MESSAGE_SYNC_LOST_TIMEOUT");
                                                // fall through
                                            }
                                        case MESSAGE_BROADCAST_MONITOR_TIMEOUT:
                                            {
                                                log("MESSAGE_BROADCAST_MONITOR_TIMEOUT");
                                                if (getActiveSyncedSources()
                                                        .contains(
                                                                getSyncHandleForBroadcastId(
                                                                        broadcastId))) {
                                                    break;
                                                }
                                                // Clear from cache to make possible sync again
                                                // (only during active searching)
                                                synchronized (mSearchScanCallbackLock) {
                                                    if (isSearchInProgress()) {
                                                        mCachedBroadcasts.remove(broadcastId);
                                                    }
                                                }
                                                log(
                                                        "Notify broadcast source lost, broadcast"
                                                                + " id: "
                                                                + broadcastId);
                                                mCallbacks.notifySourceLost(broadcastId);
                                                if (!isSinkUnintentionalPauseType(broadcastId)) {
                                                    break;
                                                }
                                                // fall through
                                            }
                                        case MESSAGE_BIG_MONITOR_TIMEOUT:
                                            {
                                                log("MESSAGE_BIG_MONITOR_TIMEOUT");
                                                stopSourceReceivers(broadcastId);
                                                break;
                                            }
                                        default:
                                            break;
                                    }
                                    Handler handler = getOrCreateHandler(broadcastId);
                                    if (!hasAnyMessagesOrCallbacks(handler)) {
                                        mHandlers.remove(broadcastId);
                                    }
                                }
                            });
        }

        void start(int broadcastId, int msg, Duration duration) {
            Handler handler = getOrCreateHandler(broadcastId);
            log(
                    "Started timeout: "
                            + ("broadcastId: " + broadcastId)
                            + (", msg: " + msg)
                            + (", duration: " + duration));
            handler.sendEmptyMessageDelayed(msg, duration.toMillis());
        }

        void stop(int broadcastId, int msg) {
            if (!mHandlers.containsKey(broadcastId)) {
                return;
            }
            Handler handler = getOrCreateHandler(broadcastId);
            handler.removeMessages(msg);
            if (!hasAnyMessagesOrCallbacks(handler)) {
                mHandlers.remove(broadcastId);
            }
        }

        void stopAll() {
            for (Handler handler : mHandlers.values()) {
                handler.removeCallbacksAndMessages(null);
            }
            mHandlers.clear();
        }

        void stopAll(int msg) {
            Iterator<Map.Entry<Integer, Handler>> iterator = mHandlers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Handler> entry = iterator.next();
                Handler handler = entry.getValue();
                handler.removeMessages(msg);
                if (!hasAnyMessagesOrCallbacks(handler)) {
                    iterator.remove();
                }
            }
        }

        boolean isStarted(int broadcastId, int msg) {
            if (!mHandlers.containsKey(broadcastId)) {
                return false;
            }
            Handler handler = getOrCreateHandler(broadcastId);
            return handler.hasMessages(msg);
        }

        @SuppressLint("NewApi") // Api is protected by flag check and the lint is wrong
        private static boolean hasAnyMessagesOrCallbacks(Handler handler) {
            if (android.os.Flags.mainlineVcnPlatformApi()) {
                return handler.hasMessagesOrCallbacks();
            } else {
                return handler.hasMessages(MESSAGE_SYNC_LOST_TIMEOUT)
                        || handler.hasMessages(MESSAGE_BROADCAST_MONITOR_TIMEOUT)
                        || handler.hasMessages(MESSAGE_BIG_MONITOR_TIMEOUT);
            }
        }
    }

    public BassClientService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        requireNonNull(mBluetoothAdapter);

        mStateMachinesThread = new HandlerThread("BassClientService.StateMachines");
        mStateMachinesThread.start();
        mCallbackHandlerThread = new HandlerThread(TAG);
        mCallbackHandlerThread.start();
        mCallbacks = new Callbacks(mCallbackHandlerThread.getLooper());

        setBassClientService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false);
    }

    private static class SourceSyncRequest {
        private final ScanResult mScanResult;
        private final boolean mHasPriority;
        private final int mSyncFailureCounter;

        SourceSyncRequest(ScanResult scanResult, boolean hasPriority, int syncFailureCounter) {
            this.mScanResult = scanResult;
            this.mHasPriority = hasPriority;
            this.mSyncFailureCounter = syncFailureCounter;
        }

        public ScanResult getScanResult() {
            return mScanResult;
        }

        public int getRssi() {
            return mScanResult.getRssi();
        }

        public boolean hasPriority() {
            return mHasPriority;
        }

        public int getFailsCounter() {
            return mSyncFailureCounter;
        }

        @Override
        public String toString() {
            return "SourceSyncRequest{"
                    + "mScanResult="
                    + mScanResult
                    + ", mHasPriority="
                    + mHasPriority
                    + ", mSyncFailureCounter="
                    + mSyncFailureCounter
                    + '}';
        }
    }

    private static final Comparator<SourceSyncRequest> sSourceSyncRequestComparator =
            new Comparator<SourceSyncRequest>() {
                @Override
                public int compare(SourceSyncRequest ssr1, SourceSyncRequest ssr2) {
                    if (ssr1.hasPriority() && !ssr2.hasPriority()) {
                        return -1;
                    } else if (!ssr1.hasPriority() && ssr2.hasPriority()) {
                        return 1;
                    } else if (leaudioSortScansToSyncByFails()
                            && (ssr1.getFailsCounter() != ssr2.getFailsCounter())) {
                        return Integer.compare(ssr1.getFailsCounter(), ssr2.getFailsCounter());
                    } else {
                        return Integer.compare(ssr2.getRssi(), ssr1.getRssi());
                    }
                }
            };

    private static class AddSourceData {
        final BluetoothDevice mSink;
        final BluetoothLeBroadcastMetadata mSourceMetadata;
        final boolean mIsGroupOp;

        AddSourceData(
                BluetoothDevice sink,
                BluetoothLeBroadcastMetadata sourceMetadata,
                boolean isGroupOp) {
            mSink = sink;
            mSourceMetadata = sourceMetadata;
            mIsGroupOp = isGroupOp;
        }
    }

    void updatePeriodicAdvertisementResultMap(
            BluetoothDevice device,
            int addressType,
            int syncHandle,
            int advSid,
            int advInterval,
            int bId,
            PublicBroadcastData pbData,
            String broadcastName) {
        log("updatePeriodicAdvertisementResultMap: device: " + device);
        log("updatePeriodicAdvertisementResultMap: syncHandle: " + syncHandle);
        log("updatePeriodicAdvertisementResultMap: advSid: " + advSid);
        log("updatePeriodicAdvertisementResultMap: addressType: " + addressType);
        log("updatePeriodicAdvertisementResultMap: advInterval: " + advInterval);
        log("updatePeriodicAdvertisementResultMap: broadcastId: " + bId);
        log("updatePeriodicAdvertisementResultMap: broadcastName: " + broadcastName);
        log("mSyncHandleToDeviceMap" + mSyncHandleToDeviceMap);
        log("mPeriodicAdvertisementResultMap" + mPeriodicAdvertisementResultMap);
        HashMap<Integer, PeriodicAdvertisementResult> paResMap =
                mPeriodicAdvertisementResultMap.get(device);
        if (paResMap == null
                || (bId != BassConstants.INVALID_BROADCAST_ID && !paResMap.containsKey(bId))) {
            log("PAResmap: add >>>");
            mSyncHandleToDeviceMap.put(syncHandle, device);
            updateSyncHandleForBroadcastId(syncHandle, bId);
            PeriodicAdvertisementResult paRes =
                    new PeriodicAdvertisementResult(
                            device,
                            addressType,
                            syncHandle,
                            advSid,
                            advInterval,
                            bId,
                            pbData,
                            broadcastName);
            if (paRes != null) {
                paRes.print();
                mPeriodicAdvertisementResultMap.putIfAbsent(device, new HashMap<>());
                mPeriodicAdvertisementResultMap.get(device).put(bId, paRes);
            }
        } else {
            log("PAResmap: update >>>");
            if (bId == BassConstants.INVALID_BROADCAST_ID) {
                // Update when onSyncEstablished, try to retrieve valid broadcast id
                bId = getBroadcastIdForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);

                if (bId == BassConstants.INVALID_BROADCAST_ID || !paResMap.containsKey(bId)) {
                    Log.e(TAG, "PAResmap: error! no valid broadcast id found>>>");
                    return;
                }

                int oldBroadcastId = getBroadcastIdForSyncHandle(syncHandle);
                if (oldBroadcastId != BassConstants.INVALID_BROADCAST_ID && oldBroadcastId != bId) {
                    log(
                            "updatePeriodicAdvertisementResultMap: SyncEstablished on the"
                                    + " same syncHandle="
                                    + syncHandle
                                    + ", before syncLost");
                    log("Notify broadcast source lost, broadcast id: " + oldBroadcastId);
                    mCallbacks.notifySourceLost(oldBroadcastId);
                    clearAllDataForSyncHandle(syncHandle);
                    mCachedBroadcasts.remove(oldBroadcastId);
                }
            }
            PeriodicAdvertisementResult paRes = paResMap.get(bId);
            if (advSid != BassConstants.INVALID_ADV_SID) {
                paRes.updateAdvSid(advSid);
            }
            if (syncHandle != BassConstants.INVALID_SYNC_HANDLE
                    && syncHandle != BassConstants.PENDING_SYNC_HANDLE) {
                mSyncHandleToDeviceMap
                        .entrySet()
                        .removeIf(entry -> entry.getValue().equals(device));
                mSyncHandleToDeviceMap.put(syncHandle, device);
                paRes.updateSyncHandle(syncHandle);
                if (paRes.getBroadcastId() != BassConstants.INVALID_BROADCAST_ID) {
                    // broadcast successfully synced
                    // update the sync handle for the broadcast source
                    updateSyncHandleForBroadcastId(syncHandle, paRes.getBroadcastId());
                }
            }
            if (addressType != BassConstants.INVALID_ADV_ADDRESS_TYPE) {
                paRes.updateAddressType(addressType);
            }
            if (advInterval != BassConstants.INVALID_ADV_INTERVAL) {
                paRes.updateAdvInterval(advInterval);
            }
            if (bId != BassConstants.INVALID_BROADCAST_ID) {
                paRes.updateBroadcastId(bId);
            }
            if (pbData != null) {
                paRes.updatePublicBroadcastData(pbData);
            }
            if (broadcastName != null) {
                paRes.updateBroadcastName(broadcastName);
            }
            paRes.print();
            paResMap.replace(bId, paRes);
        }
        log(">>mPeriodicAdvertisementResultMap" + mPeriodicAdvertisementResultMap);
    }

    PeriodicAdvertisementResult getPeriodicAdvertisementResult(
            BluetoothDevice device, int broadcastId) {
        if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
            Log.e(TAG, "getPeriodicAdvertisementResult: invalid broadcast id");
            return null;
        }

        if (mPeriodicAdvertisementResultMap.containsKey(device)) {
            return mPeriodicAdvertisementResultMap.get(device).get(broadcastId);
        }
        return null;
    }

    void clearNotifiedFlags() {
        log("clearNotifiedFlags");
        for (Map.Entry<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>> entry :
                mPeriodicAdvertisementResultMap.entrySet()) {
            HashMap<Integer, PeriodicAdvertisementResult> value = entry.getValue();
            for (PeriodicAdvertisementResult result : value.values()) {
                result.setNotified(false);
                result.print();
            }
        }
    }

    void updateBase(int syncHandleMap, BaseData base) {
        log("updateBase : mSyncHandleToBaseDataMap>>");
        mSyncHandleToBaseDataMap.put(syncHandleMap, base);
    }

    BaseData getBase(int syncHandleMap) {
        BaseData base = mSyncHandleToBaseDataMap.get(syncHandleMap);
        log("getBase returns " + base);
        return base;
    }

    void removeActiveSyncedSource(Integer syncHandle) {
        log("removeActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle == null) {
            // remove all sources
            mActiveSyncedSources.clear();
        } else {
            mActiveSyncedSources.removeIf(e -> e.equals(syncHandle));
        }
        sEventLogger.logd(TAG, "Broadcast Source Unsynced: syncHandle= " + syncHandle);
    }

    void addActiveSyncedSource(Integer syncHandle) {
        log("addActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle != BassConstants.INVALID_SYNC_HANDLE) {
            if (!mActiveSyncedSources.contains(syncHandle)) {
                mActiveSyncedSources.add(syncHandle);
            }
        }
        sEventLogger.logd(TAG, "Broadcast Source Synced: syncHandle= " + syncHandle);
    }

    List<Integer> getActiveSyncedSources() {
        log("getActiveSyncedSources: sources num: " + mActiveSyncedSources.size());
        return mActiveSyncedSources;
    }

    ScanResult getCachedBroadcast(int broadcastId) {
        return mCachedBroadcasts.get(broadcastId);
    }

    public Callbacks getCallbacks() {
        return mCallbacks;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothLeBroadcastAssistantBinder(this);
    }

    @Override
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    public void cleanup() {
        Log.i(TAG, "Cleanup BassClient Service");

        mUnicastSourceStreamStatus = Optional.empty();

        if (mDialingOutTimeoutEvent != null) {
            mHandler.removeCallbacks(mDialingOutTimeoutEvent);
            mDialingOutTimeoutEvent = null;
        }

        if (mIsAssistantActive) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService != null) {
                leAudioService.activeBroadcastAssistantNotification(false);
            }
            mIsAssistantActive = false;
        }

        if (mIsAllowedContextOfActiveGroupModified) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService != null) {
                leAudioService.setActiveGroupAllowedContextMask(
                        BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
            }
            mIsAllowedContextOfActiveGroupModified = false;
        }

        synchronized (mStateMachines) {
            for (BassClientStateMachine sm : mStateMachines.values()) {
                BassObjectsFactory.getInstance().destroyStateMachine(sm);
            }
            mStateMachines.clear();
        }
        mCallbackHandlerThread.quitSafely();
        mStateMachinesThread.quitSafely();

        mHandler.removeCallbacksAndMessages(null);
        mTimeoutHandler.stopAll();

        setBassClientService(null);
        synchronized (mSearchScanCallbackLock) {
            if (leaudioBassScanWithInternalScanController()) {
                if (isSearchInProgress()) {
                    mBassScanCallback.stopScanAndUnregister();
                }
            } else {
                if (mBluetoothLeScannerWrapper != null && mSearchScanCallback != null) {
                    mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback);
                }
                mBluetoothLeScannerWrapper = null;
                mSearchScanCallback = null;
            }
            clearAllSyncData();
        }

        mLocalBroadcastReceivers.clear();
        mPendingGroupOp.clear();
        mBroadcastMetadataMap.clear();
        mPausedBroadcastSinks.clear();
    }

    BluetoothDevice getDeviceForSyncHandle(int syncHandle) {
        return mSyncHandleToDeviceMap.get(syncHandle);
    }

    Integer getSyncHandleForBroadcastId(int broadcastId) {
        Integer syncHandle = BassConstants.INVALID_SYNC_HANDLE;
        for (Map.Entry<Integer, Integer> entry : mSyncHandleToBroadcastIdMap.entrySet()) {
            Integer value = entry.getValue();
            if (value == broadcastId) {
                syncHandle = entry.getKey();
                break;
            }
        }
        return syncHandle;
    }

    Integer getBroadcastIdForSyncHandle(int syncHandle) {
        if (mSyncHandleToBroadcastIdMap.containsKey(syncHandle)) {
            return mSyncHandleToBroadcastIdMap.get(syncHandle);
        }
        return BassConstants.INVALID_BROADCAST_ID;
    }

    void updateSyncHandleForBroadcastId(int syncHandle, int broadcastId) {
        mSyncHandleToBroadcastIdMap.entrySet().removeIf(entry -> entry.getValue() == broadcastId);
        mSyncHandleToBroadcastIdMap.put(syncHandle, broadcastId);
        log("Updated mSyncHandleToBroadcastIdMap: " + mSyncHandleToBroadcastIdMap);
    }

    private static synchronized void setBassClientService(BassClientService instance) {
        Log.d(TAG, "setBassClientService(): set to: " + instance);
        sService = instance;
    }

    private void enqueueSourceGroupOp(BluetoothDevice sink, Integer msgId, Object obj) {
        log("enqueueSourceGroupOp device: " + sink + ", msgId: " + msgId);

        mPendingGroupOp.compute(
                sink,
                (key, opsToModify) -> {
                    List<Pair<Integer, Object>> operations =
                            (opsToModify == null)
                                    ? new ArrayList<>()
                                    : new ArrayList<>(opsToModify);
                    operations.add(new Pair<>(msgId, obj));
                    return operations;
                });
    }

    private static boolean isSuccess(int status) {
        boolean ret = false;
        switch (status) {
            case BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST:
            case BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST:
            case BluetoothStatusCodes.REASON_REMOTE_REQUEST:
            case BluetoothStatusCodes.REASON_SYSTEM_POLICY:
                ret = true;
                break;
            default:
                break;
        }
        return ret;
    }

    private boolean isAnyPendingAddSourceOperation() {
        for (BluetoothDevice device : getConnectedDevices()) {
            List<Pair<Integer, Object>> operations = mPendingGroupOp.get(device);
            if (operations == null) {
                continue;
            }

            boolean isAnyPendingAddSourceOperationForDevice =
                    operations.stream()
                            .anyMatch(e -> e.first.equals(BassClientStateMachine.ADD_BCAST_SOURCE));

            if (isAnyPendingAddSourceOperationForDevice) {
                return true;
            }
        }

        return false;
    }

    private void checkForPendingGroupOpRequest(
            BluetoothDevice sink, int reason, int reqMsg, Object obj) {
        log(
                "checkForPendingGroupOpRequest device: "
                        + sink
                        + ", reason: "
                        + reason
                        + ", reqMsg: "
                        + reqMsg);

        AtomicBoolean shouldUpdateAssistantActive = new AtomicBoolean(false);

        mPendingGroupOp.computeIfPresent(
                sink,
                (key, opsToModify) -> {
                    List<Pair<Integer, Object>> operations = new ArrayList<>(opsToModify);

                    switch (reqMsg) {
                        case BassClientStateMachine.ADD_BCAST_SOURCE:
                            if (obj == null) {
                                return operations;
                            }
                            // Identify the operation by operation type and broadcastId
                            if (isSuccess(reason)) {
                                BluetoothLeBroadcastReceiveState sourceState =
                                        (BluetoothLeBroadcastReceiveState) obj;
                                if (removeMatchingOperation(operations, reqMsg, obj)) {
                                    setSourceGroupManaged(sink, sourceState.getSourceId(), true);
                                }
                            } else {
                                removeMatchingOperation(operations, reqMsg, obj);
                                shouldUpdateAssistantActive.set(true);
                            }
                            break;
                        case BassClientStateMachine.REMOVE_BCAST_SOURCE:
                            // Identify the operation by operation type and sourceId
                            removeMatchingOperation(operations, reqMsg, obj);
                            Integer sourceId = (Integer) obj;
                            setSourceGroupManaged(sink, sourceId, false);
                            break;
                        default:
                            break;
                    }
                    return operations;
                });

        if (shouldUpdateAssistantActive.get()
                && !isAnyPendingAddSourceOperation()
                && mIsAssistantActive
                && mPausedBroadcastSinks.isEmpty()) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            mIsAssistantActive = false;
            mUnicastSourceStreamStatus = Optional.empty();

            if (leAudioService != null) {
                leAudioService.activeBroadcastAssistantNotification(false);
            }
        }
    }

    private static boolean removeMatchingOperation(
            List<Pair<Integer, Object>> operations, int reqMsg, Object obj) {
        return operations.removeIf(
                m -> m.first.equals(reqMsg) && isMatchingOperation(m.second, obj));
    }

    private static boolean isMatchingOperation(Object operationData, Object obj) {
        if (obj instanceof BluetoothLeBroadcastReceiveState) {
            return ((BluetoothLeBroadcastMetadata) operationData).getBroadcastId()
                    == ((BluetoothLeBroadcastReceiveState) obj).getBroadcastId();
        } else if (obj instanceof BluetoothLeBroadcastMetadata) {
            return ((BluetoothLeBroadcastMetadata) operationData).getBroadcastId()
                    == ((BluetoothLeBroadcastMetadata) obj).getBroadcastId();
        } else if (obj instanceof Integer) {
            return obj.equals(operationData);
        }
        return false;
    }

    private boolean isDevicePartOfActiveUnicastGroup(BluetoothDevice device) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return false;
        }

        return (leAudioService.getActiveGroupId() != LE_AUDIO_GROUP_ID_INVALID)
                && (leAudioService.getActiveDevices().contains(device));
    }

    private static boolean isEmptyBluetoothDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Device is null!");
            return true;
        }

        return device.getAddress().equals("00:00:00:00:00:00");
    }

    private boolean hasAnyConnectedDeviceExternalBroadcastSource() {
        for (BluetoothDevice device : getConnectedDevices()) {
            // Check if any connected device has add some source
            if (getAllSources(device).stream()
                    .anyMatch(receiveState -> (!isLocalBroadcast(receiveState)))) {
                return true;
            }
        }

        return false;
    }

    private boolean isAnyConnectedDeviceSwitchingSource() {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = getOrCreateStateMachine(device);
                // Need to check both mPendingSourceToSwitch and mPendingMetadata
                // to guard the whole source switching flow
                if (sm != null
                        && (sm.hasPendingSwitchingSourceOperation()
                                || sm.hasPendingSourceOperation())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkAndSetGroupAllowedContextMask(BluetoothDevice sink) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        /* Don't bother active group (external broadcaster scenario) with SOUND EFFECTS */
        if (!mIsAllowedContextOfActiveGroupModified && isDevicePartOfActiveUnicastGroup(sink)) {
            leAudioService.setActiveGroupAllowedContextMask(
                    BluetoothLeAudio.CONTEXTS_ALL & ~BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS,
                    BluetoothLeAudio.CONTEXTS_ALL);
            mIsAllowedContextOfActiveGroupModified = true;
        }
    }

    private void checkAndResetGroupAllowedContextMask() {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        /* Restore allowed context mask for Unicast */
        if (mIsAllowedContextOfActiveGroupModified
                && !hasAnyConnectedDeviceExternalBroadcastSource()
                && !isAnyConnectedDeviceSwitchingSource()) {
            leAudioService.setActiveGroupAllowedContextMask(
                    BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
            mIsAllowedContextOfActiveGroupModified = false;
        }
    }

    void syncRequestForPast(BluetoothDevice sink, int broadcastId, int sourceId) {
        log(
                "syncRequestForPast sink: "
                        + sink
                        + ", broadcastId: "
                        + broadcastId
                        + ", sourceId: "
                        + sourceId);

        if (!leaudioBroadcastResyncHelper()) {
            return;
        }
        synchronized (mSinksWaitingForPast) {
            mSinksWaitingForPast.put(sink, new Pair<Integer, Integer>(broadcastId, sourceId));
        }
        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
    }

    private void localNotifyReceiveStateChanged(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState) {
        int broadcastId = receiveState.getBroadcastId();
        // If sink has external broadcast synced && not paused by the host
        if (leaudioBroadcastResyncHelper()
                && !isLocalBroadcast(receiveState)
                && !isEmptyBluetoothDevice(receiveState.getSourceDevice())
                && !isHostPauseType(broadcastId)) {

            // If sink actively synced (PA or BIG) or waiting for PA
            if (isReceiverActive(receiveState)
                    || receiveState.getPaSyncState()
                            == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST) {
                // Clear paused broadcast sink (not need to resume manually)
                mPausedBroadcastSinks.remove(sink);

                // If all sinks for this broadcast are actively synced (PA or BIG) and there is no
                // more sinks to resume then stop monitoring
                if (isAllReceiversActive(broadcastId) && mPausedBroadcastSinks.isEmpty()) {
                    stopBigMonitoring(broadcastId, /* hostInitiated */ false);
                }
                // If broadcast not paused (monitored) yet
            } else if (!mPausedBroadcastIds.containsKey(broadcastId)) {
                // And BASS has data to start synchronization
                if (mCachedBroadcasts.containsKey(broadcastId)) {
                    // Try to sync to it and start BIG monitoring
                    mPausedBroadcastIds.put(broadcastId, PauseType.SINK_UNINTENTIONAL);
                    cacheSuspendingSources(broadcastId);
                    mTimeoutHandler.stop(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
                    mTimeoutHandler.start(
                            broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT, sBigMonitorTimeout);
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                }
            }
            // If paused by host then stop active sync, it could be not stopped, if during previous
            // stop there was pending past request
        } else if (leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()
                && isHostPauseType(broadcastId)) {
            stopActiveSync(broadcastId);
            // If sink unsynced then remove potentially waiting past and check if any broadcast
            // monitoring should be stopped for all broadcast Ids
        } else if (isEmptyBluetoothDevice(receiveState.getSourceDevice())) {
            synchronized (mSinksWaitingForPast) {
                mSinksWaitingForPast.remove(sink);
            }
            checkAndStopBigMonitoring();
        }

        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        boolean isAssistantActive;
        if (leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()) {
            isAssistantActive = hasPrimaryDeviceManagedExternalBroadcast();
        } else {
            isAssistantActive = areReceiversReceivingOnlyExternalBroadcast(getConnectedDevices());
        }

        if (isAssistantActive) {
            /* Assistant become active */
            if (!mIsAssistantActive) {
                mIsAssistantActive = true;
                leAudioService.activeBroadcastAssistantNotification(true);
            }

            checkAndSetGroupAllowedContextMask(sink);
        } else {
            /* Assistant become inactive */
            if (mIsAssistantActive
                    && mPausedBroadcastSinks.isEmpty()
                    && !isSinkUnintentionalPauseType(broadcastId)) {
                mIsAssistantActive = false;
                mUnicastSourceStreamStatus = Optional.empty();
                leAudioService.activeBroadcastAssistantNotification(false);
            }

            /* Restore allowed context mask for unicast in case if last connected broadcast
             * delegator device which has external source removes this source
             */
            checkAndResetGroupAllowedContextMask();
        }
    }

    private void localNotifySourceAdded(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState) {
        if (!isLocalBroadcast(receiveState)) {
            return;
        }

        int broadcastId = receiveState.getBroadcastId();

        /* Track devices bonded to local broadcast for further broadcast status handling when sink
         * device is:
         *     - disconnecting (if no more receivers, broadcast can be stopped)
         *     - connecting (resynchronize if connection lost)
         */
        if (mLocalBroadcastReceivers.containsKey(broadcastId)) {
            mLocalBroadcastReceivers.get(broadcastId).add(sink);
        } else {
            mLocalBroadcastReceivers.put(
                    broadcastId, new HashSet<BluetoothDevice>(Arrays.asList(sink)));
        }
    }

    private void localNotifySourceAddFailed(
            BluetoothDevice sink, BluetoothLeBroadcastMetadata source) {
        removeSinkMetadata(sink, source.getBroadcastId());
    }

    private void setSourceGroupManaged(BluetoothDevice sink, int sourceId, boolean isGroupOp) {
        log("setSourceGroupManaged device: " + sink);
        if (isGroupOp) {
            if (!mGroupManagedSources.containsKey(sink)) {
                mGroupManagedSources.put(sink, new ArrayList<>());
            }
            mGroupManagedSources.get(sink).add(sourceId);
        } else {
            List<Integer> sources = mGroupManagedSources.get(sink);
            if (sources != null) {
                sources.removeIf(e -> e.equals(sourceId));
            }
        }
    }

    private Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>
            getGroupManagedDeviceSources(BluetoothDevice sink, Integer sourceId) {
        log("getGroupManagedDeviceSources device: " + sink + " sourceId: " + sourceId);
        Map map = new HashMap<BluetoothDevice, Integer>();

        if (mGroupManagedSources.containsKey(sink)
                && mGroupManagedSources.get(sink).contains(sourceId)) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + sink);
                return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(
                        null, null);
            }

            BluetoothLeBroadcastMetadata metadata =
                    stateMachine.getCurrentBroadcastMetadata(sourceId);
            if (metadata != null) {
                int broadcastId = metadata.getBroadcastId();

                for (BluetoothDevice device : getTargetDeviceList(sink, /* isGroupOp */ true)) {
                    List<BluetoothLeBroadcastReceiveState> sources =
                            getOrCreateStateMachine(device).getAllSources();

                    // For each device, find the source ID having this broadcast ID
                    Optional<BluetoothLeBroadcastReceiveState> receiver =
                            sources.stream()
                                    .filter(e -> e.getBroadcastId() == broadcastId)
                                    .findAny();
                    if (receiver.isPresent()) {
                        map.put(device, receiver.get().getSourceId());
                    } else {
                        // Put invalid source ID if the remote doesn't have it
                        map.put(device, BassConstants.INVALID_SOURCE_ID);
                    }
                }
                return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(
                        metadata, map);
            } else {
                Log.e(
                        TAG,
                        "Couldn't find broadcast metadata for device: "
                                + sink
                                + ", and sourceId:"
                                + sourceId);
            }
        }

        // Just put this single device if this source is not group managed
        map.put(sink, sourceId);
        return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(null, map);
    }

    private List<BluetoothDevice> getTargetDeviceList(BluetoothDevice device, boolean isGroupOp) {
        if (isGroupOp) {
            CsipSetCoordinatorService csipClient = mServiceFactory.getCsipSetCoordinatorService();
            if (csipClient != null) {
                // Check for coordinated set of devices in the context of CAP
                List<BluetoothDevice> csipDevices =
                        csipClient.getGroupDevicesOrdered(device, BluetoothUuid.CAP);
                if (!csipDevices.isEmpty()) {
                    return csipDevices;
                } else {
                    Log.w(TAG, "CSIP group is empty.");
                }
            } else {
                Log.e(TAG, "CSIP service is null. No grouping information available.");
            }
        }

        List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(device);
        return devices;
    }

    private int checkDuplicateSourceAdditionAndGetSourceId(
            BluetoothDevice device, BluetoothLeBroadcastMetadata metaData) {
        int sourceId = BassConstants.INVALID_SOURCE_ID;
        List<BluetoothLeBroadcastReceiveState> currentAllSources = getAllSources(device);
        for (int i = 0; i < currentAllSources.size(); i++) {
            BluetoothLeBroadcastReceiveState state = currentAllSources.get(i);
            if (metaData.getSourceDevice().equals(state.getSourceDevice())
                    && metaData.getSourceAddressType() == state.getSourceAddressType()
                    && metaData.getSourceAdvertisingSid() == state.getSourceAdvertisingSid()
                    && metaData.getBroadcastId() == state.getBroadcastId()) {
                sourceId = state.getSourceId();
                log("DuplicatedSourceAddition: for " + device + " metaData: " + metaData);
                break;
            }
        }
        return sourceId;
    }

    private boolean hasRoomForBroadcastSourceAddition(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;
        synchronized (mStateMachines) {
            stateMachine = getOrCreateStateMachine(device);
        }
        if (stateMachine == null) {
            log("stateMachine is null");
            return false;
        }
        boolean isRoomAvailable = false;
        List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
        if (sources.size() < stateMachine.getMaximumSourceCapacity()) {
            isRoomAvailable = true;
        } else {
            for (BluetoothLeBroadcastReceiveState recvState : sources) {
                if (isEmptyBluetoothDevice(recvState.getSourceDevice())) {
                    isRoomAvailable = true;
                    break;
                }
            }
        }
        log("isRoomAvailable: " + isRoomAvailable);
        return isRoomAvailable;
    }

    private Integer getSourceIdToRemove(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;

        synchronized (mStateMachines) {
            stateMachine = getOrCreateStateMachine(device);
        }
        if (stateMachine == null) {
            log("stateMachine is null");
            return BassConstants.INVALID_SOURCE_ID;
        }
        List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
        if (sources.isEmpty()) {
            log("sources is empty");
            return BassConstants.INVALID_SOURCE_ID;
        }

        Integer sourceId = BassConstants.INVALID_SOURCE_ID;
        // Select the source by checking if there is one with PA not synced
        Optional<BluetoothLeBroadcastReceiveState> receiver =
                sources.stream()
                        .filter(
                                e ->
                                        (e.getPaSyncState()
                                                != BluetoothLeBroadcastReceiveState
                                                        .PA_SYNC_STATE_SYNCHRONIZED))
                        .findAny();
        if (receiver.isPresent()) {
            sourceId = receiver.get().getSourceId();
        } else {
            // If all sources are synced, continue to pick the 1st source
            sourceId = sources.get(0).getSourceId();
        }
        return sourceId;
    }

    private BassClientStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine != null) {
                return stateMachine;
            }

            log("Creating a new state machine for " + device);
            stateMachine =
                    BassObjectsFactory.getInstance()
                            .makeStateMachine(
                                    device,
                                    this,
                                    mAdapterService,
                                    mStateMachinesThread.getLooper());
            if (stateMachine != null) {
                mStateMachines.put(device, stateMachine);
            }

            return stateMachine;
        }
    }

    class DialingOutTimeoutEvent implements Runnable {
        Integer mBroadcastId;

        DialingOutTimeoutEvent(Integer broadcastId) {
            mBroadcastId = broadcastId;
        }

        @Override
        public void run() {
            mDialingOutTimeoutEvent = null;

            if (getBassClientService() == null) {
                Log.e(TAG, "DialingOutTimeoutEvent: No Bass service");
                return;
            }

            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService == null) {
                Log.d(TAG, "DialingOutTimeoutEvent: No available LeAudioService");
                return;
            }

            sEventLogger.logd(TAG, "Broadcast timeout: " + mBroadcastId);
            mLocalBroadcastReceivers.remove(mBroadcastId);
            leAudioService.stopBroadcast(mBroadcastId);
        }

        public boolean isScheduledForBroadcast(Integer broadcastId) {
            return mBroadcastId.equals(broadcastId);
        }
    }

    /**
     * Get the BassClientService instance
     *
     * @return BassClientService instance
     */
    public static synchronized BassClientService getBassClientService() {
        if (sService == null) {
            Log.w(TAG, "getBassClientService(): service is NULL");
            return null;
        }
        if (!sService.isAvailable()) {
            Log.w(TAG, "getBassClientService(): service is not available");
            return null;
        }
        return sService;
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            BassClientStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            log("removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }

        // Cleanup device cache
        mPendingGroupOp.remove(device);
        mGroupManagedSources.remove(device);
        mActiveSourceMap.remove(device);
    }

    private void handleReconnectingAudioSharingModeDevice(BluetoothDevice device) {
        /* In case of reconnecting Audio Sharing mode device */
        if (mDialingOutTimeoutEvent != null) {
            for (Map.Entry<Integer, HashSet<BluetoothDevice>> entry :
                    mLocalBroadcastReceivers.entrySet()) {
                Integer broadcastId = entry.getKey();
                HashSet<BluetoothDevice> devices = entry.getValue();

                /* If associated with any broadcast, try to remove pending timeout callback */
                if ((mDialingOutTimeoutEvent.isScheduledForBroadcast(broadcastId))
                        && (devices.contains(device))) {
                    Log.i(
                            TAG,
                            "connectionStateChanged: reconnected previously synced device: "
                                    + device);
                    mHandler.removeCallbacks(mDialingOutTimeoutEvent);
                    mDialingOutTimeoutEvent = null;
                    break;
                }
            }
        }
    }

    private void informConnectedDeviceAboutScanOffloadStop() {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
                if (stateMachine == null) {
                    Log.w(
                            TAG,
                            "informConnectedDeviceAboutScanOffloadStop: Can't get state "
                                    + "machine for device: "
                                    + device);
                    continue;
                }
                stateMachine.sendMessage(BassClientStateMachine.STOP_SCAN_OFFLOAD);
            }
        }
    }

    private int validateParametersForSourceOperation(
            BassClientStateMachine stateMachine, BluetoothDevice device) {
        if (stateMachine == null) {
            log("validateParameters: stateMachine is null for device: " + device);
            return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }

        if (getConnectionState(device) != STATE_CONNECTED) {
            log("validateParameters: device is not connected, device: " + device);
            return BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    private int validateParametersForSourceOperation(
            BassClientStateMachine stateMachine,
            BluetoothDevice device,
            BluetoothLeBroadcastMetadata metadata) {
        int status = validateParametersForSourceOperation(stateMachine, device);
        if (status != BluetoothStatusCodes.SUCCESS) {
            return status;
        }

        if (metadata == null) {
            log("validateParameters: metadata is null for device: " + device);
            return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }

        byte[] code = metadata.getBroadcastCode();
        if ((code != null) && (code.length != 0)) {
            if ((code.length > 16) || (code.length < 4)) {
                log(
                        "validateParameters: Invalid broadcast code length: "
                                + code.length
                                + ", should be between 4 and 16 octets");
                return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
            }
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    private int validateParametersForSourceOperation(
            BassClientStateMachine stateMachine, BluetoothDevice device, Integer sourceId) {
        int status = validateParametersForSourceOperation(stateMachine, device);
        if (status != BluetoothStatusCodes.SUCCESS) {
            return status;
        }

        if (sourceId == BassConstants.INVALID_SOURCE_ID) {
            log("validateParameters: no such sourceId for device: " + device);
            return BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    private int validateParametersForSourceOperation(
            BassClientStateMachine stateMachine,
            BluetoothDevice device,
            BluetoothLeBroadcastMetadata metadata,
            Integer sourceId) {
        int status = validateParametersForSourceOperation(stateMachine, device, metadata);
        if (status != BluetoothStatusCodes.SUCCESS) {
            return status;
        }

        if (sourceId == BassConstants.INVALID_SOURCE_ID) {
            log("validateParameters: no such sourceId for device: " + device);
            return BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

    synchronized void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (!isAvailable()) {
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        if ((device == null) || (fromState == toState)) {
            Log.e(
                    TAG,
                    "connectionStateChanged: unexpected invocation. device="
                            + device
                            + " fromState="
                            + fromState
                            + " toState="
                            + toState);
            return;
        }

        sEventLogger.logd(
                TAG,
                "connectionStateChanged: device: "
                        + device
                        + ", fromState= "
                        + BluetoothProfile.getConnectionStateName(fromState)
                        + ", toState= "
                        + BluetoothProfile.getConnectionStateName(toState));

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            mPendingGroupOp.remove(device);
            mPausedBroadcastSinks.remove(device);
            synchronized (mSinksWaitingForPast) {
                mSinksWaitingForPast.remove(device);
            }
            synchronized (mPendingSourcesToAdd) {
                mPendingSourcesToAdd.removeIf(
                        pendingSourcesToAdd -> pendingSourcesToAdd.mSink.equals(device));
            }

            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                log("Unbonded " + device + ". Removing state machine");
                removeStateMachine(device);
            }

            checkAndStopBigMonitoring();
            removeSinkMetadataFromGroupIfWholeUnsynced(device);

            if (getConnectedDevices().isEmpty()
                    || (mPausedBroadcastSinks.isEmpty()
                            && mSinksWaitingForPast.isEmpty()
                            && mPendingSourcesToAdd.isEmpty()
                            && !isAnyConnectedDeviceSwitchingSource())) {
                synchronized (mSearchScanCallbackLock) {
                    // when searching is stopped then clear all sync data
                    if (!isSearchInProgress()) {
                        clearAllSyncData();
                    }
                }
            }

            /* Restore allowed context mask for unicast in case if last connected broadcast
             * delegator device which has external source disconnects.
             */
            checkAndResetGroupAllowedContextMask();
        } else if (toState == STATE_CONNECTED) {
            handleReconnectingAudioSharingModeDevice(device);
        }
    }

    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        log("Bond state changed for device: " + device + " state: " + bondState);

        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            BassClientStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnecting device because it was unbonded.");
                disconnect(device);
                return;
            }
            removeStateMachine(device);
        }
    }

    /**
     * Connects the bass profile to the passed in device
     *
     * @param device is the device with which we will connect the Bass profile
     * @return true if BAss profile successfully connected, false otherwise
     */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            Log.e(TAG, "connect: device is null");
            return false;
        }
        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "connect: connection policy set to forbidden");
            return false;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + device);
                return false;
            }

            stateMachine.sendMessage(BassClientStateMachine.CONNECT);
        }
        return true;
    }

    /**
     * Disconnects BassClient profile for the passed in device
     *
     * @param device is the device with which we want to disconnected the BAss client profile
     * @return true if Bass client profile successfully disconnected, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            Log.e(TAG, "disconnect: device is null");
            return false;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + device);
                return false;
            }

            stateMachine.sendMessage(BassClientStateMachine.DISCONNECT);
        }
        return true;
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connection policy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    /**
     * Get connection state of remote device
     *
     * @param sink the remote device
     * @return connection state
     */
    public int getConnectionState(BluetoothDevice sink) {
        synchronized (mStateMachines) {
            BassClientStateMachine sm = getOrCreateStateMachine(sink);
            if (sm == null) {
                log("getConnectionState returns STATE_DISC");
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /**
     * Get a list of all LE Audio Broadcast Sinks with the specified connection states.
     *
     * @param states states array representing the connection states
     * @return a list of devices that match the provided connection states
     */
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.BASS)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                BassClientStateMachine sm = getOrCreateStateMachine(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Get a list of all LE Audio Broadcast Sinks connected with the LE Audio Broadcast Assistant.
     *
     * @return list of connected devices
     */
    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (BassClientStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            log("getConnectedDevices: " + devices);
            return devices;
        }
    }

    /**
     * Set the connectionPolicy of the Broadcast Audio Scan Service profile.
     *
     * <p>The connection policy can be one of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        boolean setSuccessfully =
                mDatabaseManager.setProfileConnectionPolicy(
                        device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT, connectionPolicy);
        if (setSuccessfully && connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (setSuccessfully && connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return setSuccessfully;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device paired bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(
                device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
    }

    /**
     * Register callbacks that will be invoked during scan offloading.
     *
     * @param cb callbacks to be invoked
     */
    public void registerCallback(IBluetoothLeBroadcastAssistantCallback cb) {
        Log.i(TAG, "registerCallback");
        mCallbacks.register(cb);
    }

    /**
     * Unregister callbacks that are invoked during scan offloading.
     *
     * @param cb callbacks to be unregistered
     */
    public void unregisterCallback(IBluetoothLeBroadcastAssistantCallback cb) {
        Log.i(TAG, "unregisterCallback");
        mCallbacks.unregister(cb);
    }

    /**
     * Search for LE Audio Broadcast Sources on behalf of all devices connected via Broadcast Audio
     * Scan Service, filtered by filters
     *
     * @param filters ScanFilters for finding exact Broadcast Source
     */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    public void startSearchingForSources(List<ScanFilter> filters) {
        log("startSearchingForSources");

        if (!BluetoothMethodProxy.getInstance()
                .initializePeriodicAdvertisingManagerOnDefaultAdapter()) {
            Log.e(TAG, "Failed to initialize Periodic Advertising Manager on Default Adapter");
            mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
            return;
        }

        synchronized (mSearchScanCallbackLock) {
            if (!leaudioBassScanWithInternalScanController()) {
                if (mBluetoothLeScannerWrapper == null) {
                    mBluetoothLeScannerWrapper =
                            BassObjectsFactory.getInstance()
                                    .getBluetoothLeScannerWrapper(mBluetoothAdapter);
                }
                if (mBluetoothLeScannerWrapper == null) {
                    Log.e(TAG, "startLeScan: cannot get BluetoothLeScanner");
                    mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    return;
                }
            }
            if (isSearchInProgress()) {
                Log.e(TAG, "LE Scan has already started");
                mCallbacks.notifySearchStartFailed(
                        BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                return;
            }
            if (!leaudioBassScanWithInternalScanController()) {
                mSearchScanCallback =
                        new ScanCallback() {
                            @Override
                            public void onScanResult(int callbackType, ScanResult result) {
                                log("onScanResult:" + result);
                                synchronized (mSearchScanCallbackLock) {
                                    // check mSearchScanCallback because even after
                                    // mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback) that
                                    // callback could be called
                                    if (mSearchScanCallback == null) {
                                        log("onScanResult: scanner already stopped");
                                        return;
                                    }
                                    if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
                                        // Should not happen
                                        Log.e(TAG, "LE Scan has already started");
                                        return;
                                    }
                                    Integer broadcastId = BassUtils.getBroadcastId(result);
                                    if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
                                        Log.d(TAG, "onScanResult: Broadcast ID is invalid");
                                        return;
                                    }

                                    log("Broadcast Source Found:" + result.getDevice());
                                    sEventLogger.logd(
                                            TAG,
                                            "Broadcast Source Found: Broadcast ID: " + broadcastId);

                                    if (!mCachedBroadcasts.containsKey(broadcastId)) {
                                        log("selectBroadcastSource: broadcastId " + broadcastId);
                                        mCachedBroadcasts.put(broadcastId, result);
                                        addSelectSourceRequest(
                                                broadcastId, /* hasPriority */ false);
                                    } else {
                                        if (leaudioBroadcastResyncHelper()
                                                && mTimeoutHandler.isStarted(
                                                        broadcastId, MESSAGE_SYNC_LOST_TIMEOUT)) {
                                            mTimeoutHandler.stop(
                                                    broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
                                            mTimeoutHandler.start(
                                                    broadcastId,
                                                    MESSAGE_SYNC_LOST_TIMEOUT,
                                                    sSyncLostTimeout);
                                        }
                                        if (isSinkUnintentionalPauseType(broadcastId)) {
                                            addSelectSourceRequest(
                                                    broadcastId, /* hasPriority= */ true);
                                        }
                                    }
                                }
                            }

                            public void onScanFailed(int errorCode) {
                                Log.e(TAG, "Scan Failure:" + errorCode);
                                informConnectedDeviceAboutScanOffloadStop();
                            }
                        };
            }
            mSyncFailureCounter.clear();
            mHandler.removeMessages(MESSAGE_SYNC_TIMEOUT);
            if (leaudioBroadcastResyncHelper()) {
                if (leaudioBroadcastPreventResumeInterruption()) {
                    // Collect broadcasts which should be sync and/or cache should remain.
                    // Broadcasts, which has to be synced, needs to have cache available.
                    // Broadcasts which only cache should remain (i.e. because of potential resume)
                    // has to be synced too to show it on the list before resume.
                    LinkedHashSet<Integer> broadcastsToSync = new LinkedHashSet<>();

                    // Keep already synced broadcasts
                    broadcastsToSync.addAll(getBroadcastIdsOfSyncedBroadcasters());

                    // Sync to the broadcasts already synced with sinks
                    broadcastsToSync.addAll(getExternalBroadcastsActiveOnSinks());

                    // Sync to the broadcasts waiting for PAST
                    broadcastsToSync.addAll(getBroadcastIdsWaitingForPAST());

                    // Sync to the broadcasts waiting for adding source (could be by resume too).
                    broadcastsToSync.addAll(getBroadcastIdsWaitingForAddSource());

                    // Sync to the paused broadcasts (INTENTIONAL and UNINTENTIONAL) based on the
                    // mPausedBroadcastSinks as mPausedBroadcastIds could be already removed by
                    // resume execution
                    broadcastsToSync.addAll(getPausedBroadcastIdsBasedOnSinks());

                    log("Broadcasts to sync on start: " + broadcastsToSync);

                    // Add broadcasts to sync queue
                    for (int broadcastId : broadcastsToSync) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }

                    // When starting scan, clear the previously cached broadcast scan results,
                    // skip broadcast already added to sync
                    mCachedBroadcasts.keySet().removeIf(key -> !broadcastsToSync.contains(key));

                    printAllSyncData();
                } else {
                    // Sync to the broadcasts already synced with sinks
                    Set<Integer> syncedBroadcasts = getExternalBroadcastsActiveOnSinks();
                    for (int syncedBroadcast : syncedBroadcasts) {
                        addSelectSourceRequest(syncedBroadcast, /* hasPriority */ true);
                    }
                    // when starting scan, clear the previously cached broadcast scan results
                    mCachedBroadcasts
                            .keySet()
                            .removeIf(
                                    key ->
                                            !mPausedBroadcastIds.containsKey(key)
                                                    || !mPausedBroadcastIds
                                                            .get(key)
                                                            .equals(PauseType.SINK_UNINTENTIONAL));
                }
            } else {
                // When starting scan, clear the previously cached broadcast scan results
                mCachedBroadcasts.clear();
            }
            // Clear previous sources notify flag before scanning new result
            // this is to make sure the active sources are notified even if already synced
            clearNotifiedFlags();

            for (BluetoothDevice device : getConnectedDevices()) {
                synchronized (mStateMachines) {
                    BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
                    if (stateMachine == null) {
                        Log.w(
                                TAG,
                                "startSearchingForSources: Can't get state machine for "
                                        + "device: "
                                        + device);
                        continue;
                    }
                    stateMachine.sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
                }
            }

            if (leaudioBassScanWithInternalScanController()) {
                mBassScanCallback.registerAndStartScan(filters);
                // Invoke search callbacks in onScannerRegistered
            } else {
                ScanSettings settings =
                        new ScanSettings.Builder()
                                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .setLegacy(false)
                                .build();
                if (filters == null) {
                    filters = new ArrayList<ScanFilter>();
                }
                if (!BassUtils.containUuid(filters, BassConstants.BAAS_UUID)) {
                    byte[] serviceData = {0x00, 0x00, 0x00}; // Broadcast_ID
                    byte[] serviceDataMask = {0x00, 0x00, 0x00};

                    filters.add(
                            new ScanFilter.Builder()
                                    .setServiceData(
                                            BassConstants.BAAS_UUID, serviceData, serviceDataMask)
                                    .build());
                }
                mBluetoothLeScannerWrapper.startScan(filters, settings, mSearchScanCallback);
                mCallbacks.notifySearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            }
            sEventLogger.logd(TAG, "startSearchingForSources");
        }
    }

    /** Stops an ongoing search for nearby Broadcast Sources */
    public void stopSearchingForSources() {
        log("stopSearchingForSources");
        synchronized (mSearchScanCallbackLock) {
            if (leaudioBassScanWithInternalScanController()) {
                if (!isSearchInProgress()) {
                    Log.e(TAG, "stopSearchingForSources: Scan not started yet");
                    mCallbacks.notifySearchStopFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }
                mBassScanCallback.stopScanAndUnregister();
            } else {
                if (mBluetoothLeScannerWrapper == null || mSearchScanCallback == null) {
                    Log.e(TAG, "stopSearchingForSources: Scan not started yet");
                    mCallbacks.notifySearchStopFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }
                mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback);
                mBluetoothLeScannerWrapper = null;
                mSearchScanCallback = null;
            }

            if (leaudioBroadcastPreventResumeInterruption()) {
                printAllSyncData();

                // Collect broadcasts which should stay synced after search stops
                HashSet<Integer> broadcastsToKeepSynced = new HashSet<>();

                // Keep broadcasts waiting for PAST
                broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForPAST());

                // Keep broadcasts waiting for adding source (could be by resume too)
                broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForAddSource());

                // Keep broadcast unintentionally paused
                broadcastsToKeepSynced.addAll(getUnintentionallyPausedBroadcastIds());

                log("Broadcasts to keep on stop: " + broadcastsToKeepSynced);

                // Remove all other broadcasts from sync queue if not in broadcastsToKeepSynced
                synchronized (mSourceSyncRequestsQueue) {
                    Iterator<SourceSyncRequest> iterator = mSourceSyncRequestsQueue.iterator();
                    while (iterator.hasNext()) {
                        SourceSyncRequest sourceSyncRequest = iterator.next();
                        Integer queuedBroadcastId =
                                BassUtils.getBroadcastId(sourceSyncRequest.getScanResult());
                        if (!broadcastsToKeepSynced.contains(queuedBroadcastId)) {
                            iterator.remove();
                        }
                    }
                }

                // Collect broadcasts (sync handles) which should be unsynced (not in keep list)
                List<Integer> syncHandlesToRemove =
                        new ArrayList<>(mSyncHandleToBroadcastIdMap.keySet());
                for (int broadcastId : broadcastsToKeepSynced) {
                    syncHandlesToRemove.remove(getSyncHandleForBroadcastId(broadcastId));
                    // Add again, as unintentionally paused broadcasts were monitored in
                    // onScanResult during scanning, now need to be monitored in the sync loop
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                }

                // Unsync not needed broadcasts
                for (int syncHandleToRemove : syncHandlesToRemove) {
                    cancelActiveSync(syncHandleToRemove);
                }

                mSyncFailureCounter.clear();

                printAllSyncData();
            } else {
                clearAllSyncData();
            }

            informConnectedDeviceAboutScanOffloadStop();
            sEventLogger.logd(TAG, "stopSearchingForSources");
            mCallbacks.notifySearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);

            if (!leaudioBroadcastPreventResumeInterruption()) {
                for (Map.Entry<Integer, PauseType> entry : mPausedBroadcastIds.entrySet()) {
                    Integer broadcastId = entry.getKey();
                    PauseType pauseType = entry.getValue();
                    if (pauseType != PauseType.HOST_INTENTIONAL) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                }
            }
        }
    }

    private void printAllSyncData() {
        Log.v(
                TAG,
                "printAllSyncData"
                        + ("\n mActiveSyncedSources: " + mActiveSyncedSources)
                        + ("\n mPeriodicAdvCallbacksMap: " + mPeriodicAdvCallbacksMap)
                        + ("\n mSyncHandleToBaseDataMap: " + mSyncHandleToBaseDataMap)
                        + ("\n mBisDiscoveryCounterMap: " + mBisDiscoveryCounterMap)
                        + ("\n mSyncHandleToDeviceMap: " + mSyncHandleToDeviceMap)
                        + ("\n mSyncHandleToBroadcastIdMap: " + mSyncHandleToBroadcastIdMap)
                        + ("\n mPeriodicAdvertisementResultMap: " + mPeriodicAdvertisementResultMap)
                        + ("\n mSourceSyncRequestsQueue: " + mSourceSyncRequestsQueue)
                        + ("\n mSyncFailureCounter: " + mSyncFailureCounter)
                        + ("\n mPendingSourcesToAdd: " + mPendingSourcesToAdd)
                        + ("\n mSinksWaitingForPast: " + mSinksWaitingForPast)
                        + ("\n mPausedBroadcastIds: " + mPausedBroadcastIds)
                        + ("\n mPausedBroadcastSinks: " + mPausedBroadcastSinks)
                        + ("\n mCachedBroadcasts: " + mCachedBroadcasts)
                        + ("\n mBroadcastMetadataMap: " + mBroadcastMetadataMap));
    }

    private void clearAllSyncData() {
        log("clearAllSyncData");
        synchronized (mSourceSyncRequestsQueue) {
            mTimeoutHandler.stopAll(MESSAGE_SYNC_LOST_TIMEOUT);
            mSourceSyncRequestsQueue.clear();
            mSyncFailureCounter.clear();
            if (!leaudioBroadcastPreventResumeInterruption()) {
                mPendingSourcesToAdd.clear();
            }

            cancelActiveSync(null);
            mActiveSyncedSources.clear();
            mPeriodicAdvCallbacksMap.clear();
            mBisDiscoveryCounterMap.clear();

            mSyncHandleToDeviceMap.clear();
            mSyncHandleToBaseDataMap.clear();
            mSyncHandleToBroadcastIdMap.clear();
            mPeriodicAdvertisementResultMap.clear();
        }
    }

    /**
     * Return true if a search has been started by this application
     *
     * @return true if a search has been started by this application
     */
    public boolean isSearchInProgress() {
        synchronized (mSearchScanCallbackLock) {
            if (leaudioBassScanWithInternalScanController()) {
                return mBassScanCallback.isBroadcastAudioAnnouncementScanActive();
            } else {
                return mSearchScanCallback != null;
            }
        }
    }

    /** Internal periodic Advertising manager callback */
    final class PACallback extends PeriodicAdvertisingCallback {
        @Override
        public void onSyncEstablished(
                int syncHandle,
                BluetoothDevice device,
                int advertisingSid,
                int skip,
                int timeout,
                int status) {
            int broadcastId = getBroadcastIdForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);
            log(
                    "onSyncEstablished syncHandle: "
                            + syncHandle
                            + ", broadcastId: "
                            + broadcastId
                            + ", device: "
                            + device
                            + ", advertisingSid: "
                            + advertisingSid
                            + ", skip: "
                            + skip
                            + ", timeout: "
                            + timeout
                            + ", status: "
                            + status);

            if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
                Log.w(TAG, "onSyncEstablished unexpected call, no pending synchronization");
                handleSelectSourceRequest();
                return;
            }

            final int ERROR_CODE_SUCCESS = 0x00;
            if (status != ERROR_CODE_SUCCESS) {
                log("onSyncEstablished failed for broadcast id: " + broadcastId);
                boolean notifiedOfLost = false;
                synchronized (mPendingSourcesToAdd) {
                    Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
                    while (iterator.hasNext()) {
                        AddSourceData pendingSourcesToAdd = iterator.next();
                        if (pendingSourcesToAdd.mSourceMetadata.getBroadcastId() == broadcastId) {
                            if (!notifiedOfLost) {
                                notifiedOfLost = true;
                                mCallbacks.notifySourceLost(broadcastId);
                            }
                            mCallbacks.notifySourceAddFailed(
                                    pendingSourcesToAdd.mSink,
                                    pendingSourcesToAdd.mSourceMetadata,
                                    BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);
                            iterator.remove();
                        }
                    }
                }
                synchronized (mSourceSyncRequestsQueue) {
                    int failsCounter = mSyncFailureCounter.getOrDefault(broadcastId, 0) + 1;
                    mSyncFailureCounter.put(broadcastId, failsCounter);
                }

                // It has to be cleared before calling addSelectSourceRequest to properly add it as
                // it is a duplicate
                clearAllDataForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);

                if (isSinkUnintentionalPauseType(broadcastId)) {
                    if (!mTimeoutHandler.isStarted(
                            broadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT)) {
                        mTimeoutHandler.start(
                                broadcastId,
                                MESSAGE_BROADCAST_MONITOR_TIMEOUT,
                                sBroadcasterMonitorTimeout);
                    }
                    if (!isSearchInProgress()) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                } else {
                    // Clear from cache to make possible sync again (only during active searching)
                    synchronized (mSearchScanCallbackLock) {
                        if (isSearchInProgress()) {
                            mCachedBroadcasts.remove(broadcastId);
                        }
                    }
                }

                handleSelectSourceRequest();
                return;
            }

            synchronized (mSourceSyncRequestsQueue) {
                // updates syncHandle, advSid
                // set other fields as invalid or null
                updatePeriodicAdvertisementResultMap(
                        device,
                        BassConstants.INVALID_ADV_ADDRESS_TYPE,
                        syncHandle,
                        advertisingSid,
                        BassConstants.INVALID_ADV_INTERVAL,
                        BassConstants.INVALID_BROADCAST_ID,
                        null,
                        null);
                addActiveSyncedSource(syncHandle);

                if (!leaudioBroadcastResyncHelper()) {
                    synchronized (mSearchScanCallbackLock) {
                        // when searching is stopped then start timer to stop active syncs
                        if (!isSearchInProgress()) {
                            mHandler.removeMessages(MESSAGE_SYNC_TIMEOUT);
                            log("Started MESSAGE_SYNC_TIMEOUT");
                            mHandler.sendEmptyMessageDelayed(
                                    MESSAGE_SYNC_TIMEOUT, sSyncActiveTimeout.toMillis());
                        }
                    }
                } else {
                    mTimeoutHandler.stop(broadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT);
                }

                // update valid sync handle in mPeriodicAdvCallbacksMap
                if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.PENDING_SYNC_HANDLE)) {
                    PeriodicAdvertisingCallback paCb =
                            mPeriodicAdvCallbacksMap.get(BassConstants.PENDING_SYNC_HANDLE);
                    mPeriodicAdvCallbacksMap.put(syncHandle, paCb);
                    mPeriodicAdvCallbacksMap.remove(BassConstants.PENDING_SYNC_HANDLE);
                }

                mBisDiscoveryCounterMap.put(syncHandle, MAX_BIS_DISCOVERY_TRIES_NUM);
            }
            synchronized (mSinksWaitingForPast) {
                Iterator<Map.Entry<BluetoothDevice, Pair<Integer, Integer>>> iterator =
                        mSinksWaitingForPast.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BluetoothDevice, Pair<Integer, Integer>> entry = iterator.next();
                    BluetoothDevice sinkDevice = entry.getKey();
                    int broadcastIdForPast = entry.getValue().first;
                    if (broadcastId == broadcastIdForPast) {
                        int sourceId = entry.getValue().second;
                        synchronized (mStateMachines) {
                            BassClientStateMachine sm = getOrCreateStateMachine(sinkDevice);
                            Message message =
                                    sm.obtainMessage(
                                            BassClientStateMachine.INITIATE_PA_SYNC_TRANSFER);
                            message.arg1 = syncHandle;
                            message.arg2 = sourceId;
                            sm.sendMessage(message);
                        }
                        synchronized (mPendingSourcesToAdd) {
                            Iterator<AddSourceData> addIterator = mPendingSourcesToAdd.iterator();
                            while (addIterator.hasNext()) {
                                AddSourceData pendingSourcesToAdd = addIterator.next();
                                if (pendingSourcesToAdd.mSourceMetadata.getBroadcastId()
                                                == broadcastId
                                        && pendingSourcesToAdd.mSink.equals(sinkDevice)) {
                                    addIterator.remove();
                                }
                            }
                        }
                        iterator.remove();
                    }
                }
            }
            synchronized (mPendingSourcesToAdd) {
                List<AddSourceData> pendingSourcesToAdd = new ArrayList<>();
                Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
                while (iterator.hasNext()) {
                    AddSourceData pendingSourceToAdd = iterator.next();
                    if (pendingSourceToAdd.mSourceMetadata.getBroadcastId() == broadcastId) {
                        boolean addSource = true;
                        if (pendingSourceToAdd.mIsGroupOp && !pendingSourcesToAdd.isEmpty()) {
                            List<BluetoothDevice> deviceGroup =
                                    getTargetDeviceList(
                                            pendingSourceToAdd.mSink, /* isGroupOp */ true);
                            for (AddSourceData addSourceData : pendingSourcesToAdd) {
                                if (addSourceData.mIsGroupOp
                                        && deviceGroup.contains(addSourceData.mSink)) {
                                    addSource = false;
                                }
                            }
                        }
                        if (addSource) {
                            pendingSourcesToAdd.add(pendingSourceToAdd);
                        }
                        iterator.remove();
                    }
                }
                for (AddSourceData addSourceData : pendingSourcesToAdd) {
                    addSource(
                            addSourceData.mSink,
                            addSourceData.mSourceMetadata,
                            addSourceData.mIsGroupOp);
                }
            }
            handleSelectSourceRequest();
        }

        @Override
        public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
            int syncHandle = report.getSyncHandle();
            log("onPeriodicAdvertisingReport " + syncHandle);
            Integer bisCounter = mBisDiscoveryCounterMap.get(syncHandle);

            // Parse the BIS indices from report's service data
            if (bisCounter != null && bisCounter != 0) {
                if (parseScanRecord(syncHandle, report.getData())) {
                    mBisDiscoveryCounterMap.put(syncHandle, 0);
                } else {
                    bisCounter--;
                    mBisDiscoveryCounterMap.put(syncHandle, bisCounter);
                    if (bisCounter == 0) {
                        cancelActiveSync(syncHandle);
                    }
                }
            }

            if (leaudioBigDependsOnAudioState()) {
                BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
                if (srcDevice == null) {
                    log("No device found.");
                    return;
                }
                PeriodicAdvertisementResult result =
                        getPeriodicAdvertisementResult(
                                srcDevice, getBroadcastIdForSyncHandle(syncHandle));
                if (result == null) {
                    log("No PA record found");
                    return;
                }
                BaseData baseData = getBase(syncHandle);
                if (baseData == null) {
                    log("No BaseData found");
                    return;
                }
                PublicBroadcastData pbData = result.getPublicBroadcastData();
                if (pbData == null) {
                    log("No public broadcast data found, wait for BIG");
                    return;
                }
                if (!result.isNotified()) {
                    result.setNotified(true);
                    BluetoothLeBroadcastMetadata metaData =
                            getBroadcastMetadataFromBaseData(
                                    baseData, srcDevice, syncHandle, pbData.isEncrypted());
                    log("Notify broadcast source found");
                    mCallbacks.notifySourceFound(metaData);
                }
            }
        }

        @Override
        public void onSyncLost(int syncHandle) {
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            log("OnSyncLost: syncHandle=" + syncHandle + ", broadcastID=" + broadcastId);
            clearAllDataForSyncHandle(syncHandle);
            if (broadcastId != BassConstants.INVALID_BROADCAST_ID) {
                synchronized (mSourceSyncRequestsQueue) {
                    int failsCounter = mSyncFailureCounter.getOrDefault(broadcastId, 0) + 1;
                    mSyncFailureCounter.put(broadcastId, failsCounter);
                }
                mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
                if (isSinkUnintentionalPauseType(broadcastId)) {
                    if (!mTimeoutHandler.isStarted(
                            broadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT)) {
                        mTimeoutHandler.start(
                                broadcastId,
                                MESSAGE_BROADCAST_MONITOR_TIMEOUT,
                                sBroadcasterMonitorTimeout);
                    }
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                } else {
                    if (leaudioBroadcastResyncHelper()) {
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_SYNC_LOST_TIMEOUT, sSyncLostTimeout);
                    } else {
                        // Clear from cache to make possible sync again (only during active
                        // searching)
                        synchronized (mSearchScanCallbackLock) {
                            if (isSearchInProgress()) {
                                mCachedBroadcasts.remove(broadcastId);
                            }
                        }
                        log("Notify broadcast source lost, broadcast id: " + broadcastId);
                        mCallbacks.notifySourceLost(broadcastId);
                    }
                }
            }
        }

        @Override
        public void onBigInfoAdvertisingReport(int syncHandle, boolean encrypted) {
            log(
                    "onBIGInfoAdvertisingReport: syncHandle="
                            + syncHandle
                            + ", encrypted ="
                            + encrypted);
            BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
            if (srcDevice == null) {
                log("No device found.");
                return;
            }
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            PeriodicAdvertisementResult result =
                    getPeriodicAdvertisementResult(srcDevice, broadcastId);
            if (result == null) {
                log("No PA record found");
                return;
            }
            BaseData baseData = getBase(syncHandle);
            if (baseData == null) {
                log("No BaseData found");
                return;
            }
            if (!result.isNotified()) {
                result.setNotified(true);
                BluetoothLeBroadcastMetadata metaData =
                        getBroadcastMetadataFromBaseData(
                                baseData, srcDevice, syncHandle, encrypted);
                log("Notify broadcast source found");
                mCallbacks.notifySourceFound(metaData);
            }
            if (isSinkUnintentionalPauseType(broadcastId)) {
                resumeReceiversSourceSynchronization();
            }
        }
    }

    private void clearAllDataForSyncHandle(Integer syncHandle) {
        synchronized (mSourceSyncRequestsQueue) {
            removeActiveSyncedSource(syncHandle);
            mPeriodicAdvCallbacksMap.remove(syncHandle);
            mSyncHandleToBaseDataMap.remove(syncHandle);
            mBisDiscoveryCounterMap.remove(syncHandle);
            BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
            mSyncHandleToDeviceMap.remove(syncHandle);
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            synchronized (mPendingSourcesToAdd) {
                mPendingSourcesToAdd.removeIf(
                        pendingSourcesToAdd ->
                                pendingSourcesToAdd.mSourceMetadata.getBroadcastId()
                                        == broadcastId);
            }
            synchronized (mSinksWaitingForPast) {
                mSinksWaitingForPast
                        .entrySet()
                        .removeIf(entry -> entry.getValue().first == broadcastId);
            }
            mSyncHandleToBroadcastIdMap.remove(syncHandle);
            if (srcDevice != null) {
                mPeriodicAdvertisementResultMap.get(srcDevice).remove(broadcastId);
                if (mPeriodicAdvertisementResultMap.get(srcDevice).isEmpty()) {
                    mPeriodicAdvertisementResultMap.remove(srcDevice);
                }
            }
        }
    }

    private BluetoothLeBroadcastMetadata getBroadcastMetadataFromBaseData(
            BaseData baseData, BluetoothDevice device, int syncHandle, boolean encrypted) {
        BluetoothLeBroadcastMetadata.Builder metaData = new BluetoothLeBroadcastMetadata.Builder();
        int index = 0;
        for (BaseData.BaseInformation baseLevel2 : baseData.levelTwo()) {
            BluetoothLeBroadcastSubgroup.Builder subGroup =
                    new BluetoothLeBroadcastSubgroup.Builder();
            for (int j = 0; j < baseLevel2.mNumSubGroups; j++) {
                BaseData.BaseInformation baseLevel3 = baseData.levelThree().get(index++);
                BluetoothLeBroadcastChannel.Builder channel =
                        new BluetoothLeBroadcastChannel.Builder();
                channel.setChannelIndex(baseLevel3.mIndex);
                channel.setSelected(false);
                try {
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                    baseLevel3.mCodecConfigInfo));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid metadata, adding empty data. Error: " + e);
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
                }
                subGroup.addChannel(channel.build());
            }
            byte[] arrayCodecId = baseLevel2.mCodecId;
            long codeId =
                    ((long) (arrayCodecId[4] & 0xff)) << 32
                            | (arrayCodecId[3] & 0xff) << 24
                            | (arrayCodecId[2] & 0xff) << 16
                            | (arrayCodecId[1] & 0xff) << 8
                            | (arrayCodecId[0] & 0xff);
            subGroup.setCodecId(codeId);
            try {
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                baseLevel2.mCodecConfigInfo));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid config, adding empty one. Error: " + e);
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
            }

            try {
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(baseLevel2.mMetaData));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid metadata, adding empty one. Error: " + e);
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(new byte[0]));
            }

            metaData.addSubgroup(subGroup.build());
        }
        metaData.setSourceDevice(device, device.getAddressType());
        byte[] arrayPresentationDelay = baseData.levelOne().mPresentationDelay;
        int presentationDelay =
                (int)
                        ((arrayPresentationDelay[2] & 0xff) << 16
                                | (arrayPresentationDelay[1] & 0xff) << 8
                                | (arrayPresentationDelay[0] & 0xff));
        metaData.setPresentationDelayMicros(presentationDelay);
        PeriodicAdvertisementResult result =
                getPeriodicAdvertisementResult(device, getBroadcastIdForSyncHandle(syncHandle));
        if (result != null) {
            int broadcastId = result.getBroadcastId();
            log("broadcast ID: " + broadcastId);
            metaData.setBroadcastId(broadcastId);
            metaData.setSourceAdvertisingSid(result.getAdvSid());

            PublicBroadcastData pbData = result.getPublicBroadcastData();
            if (pbData != null) {
                metaData.setPublicBroadcast(true);
                metaData.setAudioConfigQuality(pbData.getAudioConfigQuality());
                try {
                    metaData.setPublicBroadcastMetadata(
                            BluetoothLeAudioContentMetadata.fromRawBytes(pbData.getMetadata()));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid public metadata, adding empty one. Error " + e);
                    metaData.setPublicBroadcastMetadata(null);
                }
            }

            String broadcastName = result.getBroadcastName();
            if (broadcastName != null) {
                metaData.setBroadcastName(broadcastName);
            }

            // update the rssi value
            ScanResult scanRes = getCachedBroadcast(broadcastId);
            if (scanRes != null) {
                metaData.setRssi(scanRes.getRssi());
            }
        }
        metaData.setEncrypted(encrypted);

        return metaData.build();
    }

    /**
     * @param syncHandle syncHandle to unsync source and clean up all data for it. Null is used to
     *     clean up all pending and established broadcast syncs.
     */
    private void cancelActiveSync(Integer syncHandle) {
        log("cancelActiveSync: syncHandle = " + syncHandle);
        if (syncHandle == null
                || (leaudioBroadcastResyncHelper()
                        && syncHandle == BassConstants.PENDING_SYNC_HANDLE)) {
            // cancel the pending sync request
            unsyncSource(BassConstants.PENDING_SYNC_HANDLE);
        }
        List<Integer> activeSyncedSrc = new ArrayList<>(getActiveSyncedSources());

        /* Stop sync if there is some running */
        if (!activeSyncedSrc.isEmpty()
                && (syncHandle == null || activeSyncedSrc.contains(syncHandle))) {
            if (syncHandle != null) {
                // only one source needs to be unsynced
                unsyncSource(syncHandle);
            } else {
                // unsync all the sources
                for (int handle : activeSyncedSrc) {
                    unsyncSource(handle);
                }
            }
        }
        printAllSyncData();
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    private boolean unsyncSource(int syncHandle) {
        log("unsyncSource: syncHandle: " + syncHandle);
        if (mPeriodicAdvCallbacksMap.containsKey(syncHandle)) {
            try {
                BluetoothMethodProxy.getInstance()
                        .periodicAdvertisingManagerUnregisterSync(
                                BassClientPeriodicAdvertisingManager
                                        .getPeriodicAdvertisingManager(),
                                mPeriodicAdvCallbacksMap.get(syncHandle));
            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "unregisterSync:IllegalArgumentException");
                return false;
            }
        } else {
            log("calling unregisterSync, not found syncHandle: " + syncHandle);
        }
        clearAllDataForSyncHandle(syncHandle);
        return true;
    }

    boolean parseBaseData(int syncHandle, byte[] serviceData) {
        log("parseBaseData" + Arrays.toString(serviceData));
        BaseData base = BaseData.parseBaseData(serviceData);
        if (base != null) {
            updateBase(syncHandle, base);
            base.print();
            return true;
        } else {
            Log.e(TAG, "Seems BASE is not in parsable format");
        }
        return false;
    }

    boolean parseScanRecord(int syncHandle, ScanRecord record) {
        int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
        log(
                "parseScanRecord: syncHandle="
                        + syncHandle
                        + ", broadcastID="
                        + broadcastId
                        + ", record="
                        + record);
        Map<ParcelUuid, byte[]> bmsAdvDataMap = record.getServiceData();
        if (bmsAdvDataMap != null) {
            for (Map.Entry<ParcelUuid, byte[]> entry : bmsAdvDataMap.entrySet()) {
                log(
                        "ParcelUUid = "
                                + entry.getKey()
                                + ", Value = "
                                + Arrays.toString(entry.getValue()));
            }
        }
        byte[] advData = record.getServiceData(BassConstants.BASIC_AUDIO_UUID);
        if (advData != null) {
            return parseBaseData(syncHandle, advData);
        } else {
            Log.e(TAG, "No service data in Scan record");
        }
        return false;
    }

    void addSelectSourceRequest(int broadcastId, boolean hasPriority) {
        sEventLogger.logd(
                TAG,
                "Add Select Broadcast Source, broadcastId: "
                        + broadcastId
                        + ", hasPriority: "
                        + hasPriority);

        if (getActiveSyncedSources().contains(getSyncHandleForBroadcastId(broadcastId))) {
            log("addSelectSourceRequest: Already synced");
            return;
        }

        if (isAddedToSelectSourceRequest(broadcastId, hasPriority)) {
            log("addSelectSourceRequest: Already added");
            return;
        }

        ScanResult scanRes = getCachedBroadcast(broadcastId);
        if (scanRes == null) {
            log("addSelectSourceRequest: ScanResult empty");
            return;
        }

        ScanRecord scanRecord = scanRes.getScanRecord();
        if (scanRecord == null) {
            log("addSelectSourceRequest: ScanRecord empty");
            return;
        }

        mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
        synchronized (mSourceSyncRequestsQueue) {
            if (!mSyncFailureCounter.containsKey(broadcastId)) {
                mSyncFailureCounter.put(broadcastId, 0);
            }
            mSourceSyncRequestsQueue.add(
                    new SourceSyncRequest(
                            scanRes, hasPriority, mSyncFailureCounter.get(broadcastId)));
        }

        handleSelectSourceRequest();
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    private void handleSelectSourceRequest() {
        PeriodicAdvertisingCallback paCb;
        ScanResult scanRes;
        int broadcastId = BassConstants.INVALID_BROADCAST_ID;
        PublicBroadcastData pbData = null;
        List<Integer> activeSyncedSrc;
        String broadcastName;

        synchronized (mSourceSyncRequestsQueue) {
            if (mSourceSyncRequestsQueue.isEmpty()) {
                return;
            } else if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.PENDING_SYNC_HANDLE)) {
                log("handleSelectSourceRequest: already pending sync");
                return;
            }

            scanRes = mSourceSyncRequestsQueue.poll().getScanResult();
            ScanRecord scanRecord = scanRes.getScanRecord();

            sEventLogger.logd(TAG, "Select Broadcast Source, result: " + scanRes);

            broadcastId = BassUtils.getBroadcastId(scanRecord);
            if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
                Log.e(TAG, "Invalid broadcast ID");
                handleSelectSourceRequest();
                return;
            }

            // Avoid duplicated sync request if the same broadcast BIG is synced
            activeSyncedSrc = new ArrayList<>(getActiveSyncedSources());
            if (activeSyncedSrc.contains(getSyncHandleForBroadcastId(broadcastId))) {
                log("Skip duplicated sync request to broadcast id: " + broadcastId);
                handleSelectSourceRequest();
                return;
            }

            pbData = BassUtils.getPublicBroadcastData(scanRecord);
            broadcastName = BassUtils.getBroadcastName(scanRecord);
            paCb = new PACallback();
            // put PENDING_SYNC_HANDLE and update it in onSyncEstablished
            mPeriodicAdvCallbacksMap.put(BassConstants.PENDING_SYNC_HANDLE, paCb);
            updatePeriodicAdvertisementResultMap(
                    scanRes.getDevice(),
                    scanRes.getDevice().getAddressType(),
                    BassConstants.PENDING_SYNC_HANDLE,
                    BassConstants.INVALID_ADV_SID,
                    scanRes.getPeriodicAdvertisingInterval(),
                    broadcastId,
                    pbData,
                    broadcastName);
        }

        // Check if there are resources for sync
        if (activeSyncedSrc.size() >= MAX_ACTIVE_SYNCED_SOURCES_NUM) {
            log("handleSelectSourceRequest: reached max allowed active source");
            if (!leaudioBroadcastResyncHelper()) {
                int syncHandle = activeSyncedSrc.get(0);
                // removing the 1st synced source before proceeding to add new
                cancelActiveSync(syncHandle);
            } else {
                Boolean canceledActiveSync = false;
                int broadcastIdToLostMonitoring = BassConstants.INVALID_BROADCAST_ID;
                for (int syncHandle : activeSyncedSrc) {
                    if (!isAnyReceiverSyncedToBroadcast(getBroadcastIdForSyncHandle(syncHandle))) {
                        canceledActiveSync = true;
                        broadcastIdToLostMonitoring = getBroadcastIdForSyncHandle(syncHandle);
                        cancelActiveSync(syncHandle);
                        break;
                    }
                }
                if (!canceledActiveSync) {
                    int syncHandle = activeSyncedSrc.get(0);
                    // removing the 1st synced source before proceeding to add new
                    broadcastIdToLostMonitoring = getBroadcastIdForSyncHandle(syncHandle);
                    cancelActiveSync(syncHandle);
                }
                mTimeoutHandler.start(
                        broadcastIdToLostMonitoring, MESSAGE_SYNC_LOST_TIMEOUT, sSyncLostTimeout);
            }
        }

        try {
            BluetoothMethodProxy.getInstance()
                    .periodicAdvertisingManagerRegisterSync(
                            BassClientPeriodicAdvertisingManager.getPeriodicAdvertisingManager(),
                            scanRes,
                            0,
                            BassConstants.PSYNC_TIMEOUT,
                            paCb,
                            null);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "registerSync:IllegalArgumentException");
            clearAllDataForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);
            handleSelectSourceRequest();
            return;
        }
    }

    private void storeSinkMetadata(
            BluetoothDevice device, int broadcastId, BluetoothLeBroadcastMetadata metadata) {
        if (device == null
                || broadcastId == BassConstants.INVALID_BROADCAST_ID
                || metadata == null) {
            Log.e(
                    TAG,
                    "Failed to store Sink Metadata, invalid parameters (device: "
                            + device
                            + ", broadcastId: "
                            + broadcastId
                            + ", metadata: "
                            + metadata
                            + ")");
            return;
        }

        mBroadcastMetadataMap.compute(
                device,
                (key, existingMap) -> {
                    if (existingMap == null) {
                        existingMap = new ConcurrentHashMap<>();
                    }
                    existingMap.put(broadcastId, metadata);
                    return existingMap;
                });
    }

    private void removeSinkMetadataHelper(BluetoothDevice device, int broadcastId) {
        mBroadcastMetadataMap.compute(
                device,
                (key, existingMap) -> {
                    if (existingMap != null) {
                        existingMap.remove(broadcastId);
                        if (existingMap.isEmpty()) {
                            return null;
                        }
                    } else {
                        Log.d(
                                TAG,
                                "There is no metadata related to sink (device: "
                                        + device
                                        + ", broadcastId: "
                                        + broadcastId);
                    }
                    return existingMap;
                });
    }

    private void removeSinkMetadata(BluetoothDevice device, int broadcastId) {
        if (device == null || broadcastId == BassConstants.INVALID_BROADCAST_ID) {
            Log.e(
                    TAG,
                    "Failed to remove Sink Metadata, invalid parameters (device: "
                            + device
                            + ", broadcastId: "
                            + broadcastId
                            + ")");
            return;
        }

        removeSinkMetadataHelper(device, broadcastId);
        removeSinkMetadataFromGroupIfWholeUnsynced(device, broadcastId);
    }

    private void removeSinkMetadata(BluetoothDevice device) {
        if (device == null) {
            Log.e(
                    TAG,
                    "Failed to remove Sink Metadata, invalid parameters (device: " + device + ")");
            return;
        }

        mBroadcastMetadataMap.remove(device);
        removeSinkMetadataFromGroupIfWholeUnsynced(device);
    }

    /**
     * Removes sink metadata from a group if all other sinks (except the given device) are unsynced
     * from the given broadcast and not paused by the host. If this condition is met, sink metadata
     * is removed from the entire group, including the given device.
     *
     * @param device The Bluetooth device for which group synchronization with the broadcast should
     *     be checked. The given device is skipped in the check because even if its sink metadata
     *     has been removed, it may still be synchronized with the broadcast.
     * @param broadcastId The broadcast ID to check against.
     */
    private void removeSinkMetadataFromGroupIfWholeUnsynced(
            BluetoothDevice device, int broadcastId) {
        if (device == null || broadcastId == BassConstants.INVALID_BROADCAST_ID) {
            Log.e(
                    TAG,
                    "Failed to remove Sink Metadata, invalid parameters (device: "
                            + device
                            + ", broadcastId: "
                            + broadcastId
                            + ")");
            return;
        }

        List<BluetoothDevice> sinks = getTargetDeviceList(device, /* isGroupOp */ true);
        boolean removeSinks = true;
        // Check if all others sinks than this device are unsynced and not paused by host
        // This device is removed or should be removed, so it has to be skipped in that check
        for (BluetoothDevice sink : sinks) {
            if (sink.equals(device)) {
                continue;
            }
            if (getAllSources(sink).stream().anyMatch(rs -> (rs.getBroadcastId() == broadcastId))
                    || (isHostPauseType(broadcastId) && !mPausedBroadcastSinks.isEmpty())) {
                removeSinks = false;
                break;
            }
        }
        // Then remove such metadata from all of them
        if (removeSinks) {
            for (BluetoothDevice sink : sinks) {
                removeSinkMetadataHelper(sink, broadcastId);
            }
        }
    }

    /**
     * Removes sink metadata from a group if all other sinks (except the given device) are unsynced
     * from any broadcast and not paused by the host. If this condition is met, sink metadata is
     * removed from the entire group, including the given device.
     *
     * @param device The Bluetooth device for which group synchronization with the broadcasts should
     *     be checked. The given device is skipped in the check because even if its sink metadata
     *     has been removed, it may still be synchronized with the broadcast.
     */
    private void removeSinkMetadataFromGroupIfWholeUnsynced(BluetoothDevice device) {
        if (device == null) {
            Log.e(
                    TAG,
                    "Failed to remove Sink Metadata, invalid parameter (device: " + device + ")");
            return;
        }

        List<BluetoothDevice> sinks = getTargetDeviceList(device, /* isGroupOp */ true);
        // Check sync for broadcastIds from all sinks in group as device could be already removed
        for (BluetoothDevice sink : sinks) {
            List<Integer> broadcastIds =
                    new ArrayList<>(
                            mBroadcastMetadataMap
                                    .getOrDefault(sink, Collections.emptyMap())
                                    .keySet());
            // Check all broadcastIds sync for each sink and remove metadata if group unsynced
            for (Integer broadcastId : broadcastIds) {
                // The device is used intentionally instead of a sink, even if we use broadcastIds
                // from other sinks
                removeSinkMetadataFromGroupIfWholeUnsynced(device, broadcastId);
            }
        }
    }

    private void checkIfBroadcastIsSuspendedBySourceRemovalAndClearData(
            BluetoothDevice device, BassClientStateMachine stateMachine) {
        if (!mPausedBroadcastSinks.contains(device)) {
            return;
        }
        Map<Integer, BluetoothLeBroadcastMetadata> entry = mBroadcastMetadataMap.get(device);
        if (entry == null) {
            return;
        }
        if (entry.keySet().size() >= stateMachine.getMaximumSourceCapacity()) {
            for (Integer broadcastId : entry.keySet()) {
                // Found broadcastId which is paused by host but not synced
                if (!getAllSources(device).stream()
                                .anyMatch(rs -> (rs.getBroadcastId() == broadcastId))
                        && isHostPauseType(broadcastId)) {
                    stopBigMonitoring(broadcastId, /* hostInitiated */ false);
                    removeSinkMetadata(device, broadcastId);
                    return;
                }
            }
        }
    }

    private Boolean isAddedToSelectSourceRequest(int broadcastId, boolean priorityImportant) {
        synchronized (mSourceSyncRequestsQueue) {
            if (getBroadcastIdForSyncHandle(BassConstants.PENDING_SYNC_HANDLE) == broadcastId) {
                return true;
            }

            for (SourceSyncRequest sourceSyncRequest : mSourceSyncRequestsQueue) {
                if (BassUtils.getBroadcastId(sourceSyncRequest.getScanResult()) == broadcastId) {
                    return !priorityImportant || sourceSyncRequest.hasPriority();
                }
            }
        }

        return false;
    }

    /**
     * Add a Broadcast Source to the Broadcast Sink
     *
     * @param sink Broadcast Sink to which the Broadcast Source should be added
     * @param sourceMetadata Broadcast Source metadata to be added to the Broadcast Sink
     * @param isGroupOp set to true If Application wants to perform this operation for all
     *     coordinated set members, False otherwise
     */
    public void addSource(
            BluetoothDevice sink, BluetoothLeBroadcastMetadata sourceMetadata, boolean isGroupOp) {
        log(
                "addSource: "
                        + ("device: " + sink)
                        + (", sourceMetadata: " + sourceMetadata)
                        + (", isGroupOp: " + isGroupOp));

        List<BluetoothDevice> devices = getTargetDeviceList(sink, /* isGroupOp */ isGroupOp);
        // Don't coordinate it as a group if there's no group or there is one device only
        if (devices.size() < 2) {
            isGroupOp = false;
        }

        if (sourceMetadata == null) {
            log("addSource: Error bad parameter: sourceMetadata cannot be null");
            return;
        }

        if (isLocalBroadcast(sourceMetadata)) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leaudioBigDependsOnAudioState()) {
                if (leAudioService == null
                        || !(leAudioService.isPaused(sourceMetadata.getBroadcastId())
                                || leAudioService.isPlaying(sourceMetadata.getBroadcastId()))) {
                    Log.w(TAG, "addSource: Local source can't be add");

                    mCallbacks.notifySourceAddFailed(
                            sink,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);

                    return;
                }
            } else {
                if (leAudioService == null
                        || !leAudioService.isPlaying(sourceMetadata.getBroadcastId())) {
                    Log.w(TAG, "addSource: Local source can't be add");

                    mCallbacks.notifySourceAddFailed(
                            sink,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);

                    return;
                }
            }
        }

        // Remove pausedBroadcastId in case that broadcast was paused before.
        mPausedBroadcastIds.remove(sourceMetadata.getBroadcastId());
        logPausedBroadcastsAndSinks();

        for (BluetoothDevice device : devices) {
            BluetoothDevice sourceDevice = sourceMetadata.getSourceDevice();
            if (!isLocalBroadcast(sourceMetadata)
                    && (!getActiveSyncedSources()
                            .contains(
                                    getSyncHandleForBroadcastId(
                                            sourceMetadata.getBroadcastId())))) {
                log("Adding inactive source: " + sourceDevice);
                int broadcastId = sourceMetadata.getBroadcastId();
                if (broadcastId != BassConstants.INVALID_BROADCAST_ID) {
                    // Check if not added already
                    if (isAddedToSelectSourceRequest(broadcastId, /* priorityImportant */ true)) {
                        mPendingSourcesToAdd.add(
                                new AddSourceData(device, sourceMetadata, isGroupOp));
                        // If the source has been synced before, try to re-sync
                        // with the source by previously cached scan result.
                    } else if (getCachedBroadcast(broadcastId) != null) {
                        mPendingSourcesToAdd.add(
                                new AddSourceData(device, sourceMetadata, isGroupOp));
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    } else {
                        Log.w(TAG, "AddSource: broadcast not cached, broadcastId: " + broadcastId);
                        mCallbacks.notifySourceAddFailed(
                                sink, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                        return;
                    }
                } else {
                    Log.w(TAG, "AddSource: invalid broadcastId");
                    mCallbacks.notifySourceAddFailed(
                            sink, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                    return;
                }
                continue;
            }

            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            int statusCode =
                    validateParametersForSourceOperation(stateMachine, device, sourceMetadata);
            if (statusCode != BluetoothStatusCodes.SUCCESS) {
                mCallbacks.notifySourceAddFailed(device, sourceMetadata, statusCode);
                continue;
            }
            if (!stateMachine.isBassStateReady()) {
                Log.d(TAG, "addSource: BASS state not ready, retry later with device: " + device);
                synchronized (mPendingSourcesToAdd) {
                    mPendingSourcesToAdd.add(new AddSourceData(device, sourceMetadata, isGroupOp));
                }
                continue;
            }
            if (stateMachine.hasPendingSourceOperation()) {
                Log.w(
                        TAG,
                        "addSource: source operation already pending, device: "
                                + device
                                + ", broadcastId: "
                                + sourceMetadata.getBroadcastId());
                mCallbacks.notifySourceAddFailed(
                        device, sourceMetadata, BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                continue;
            }
            if (leaudioBroadcastResyncHelper()) {
                int sourceId = checkDuplicateSourceAdditionAndGetSourceId(device, sourceMetadata);
                if (sourceId != BassConstants.INVALID_SOURCE_ID) {
                    updateSourceToResumeBroadcast(device, sourceId, sourceMetadata);
                    continue;
                }
            }
            if (!hasRoomForBroadcastSourceAddition(device)) {
                log("addSource: device has no room");
                Integer sourceIdToRemove = getSourceIdToRemove(device);
                if (sourceIdToRemove != BassConstants.INVALID_SOURCE_ID) {
                    BluetoothLeBroadcastMetadata metaData =
                            stateMachine.getCurrentBroadcastMetadata(sourceIdToRemove);
                    if (metaData != null) {
                        removeSinkMetadata(device, metaData.getBroadcastId());

                        // Add host intentional pause if previous broadcast is different than
                        // current
                        if (sourceMetadata.getBroadcastId() != metaData.getBroadcastId()) {
                            stopBigMonitoring(metaData.getBroadcastId(), /* hostInitiated */ true);
                        }
                    }

                    sEventLogger.logd(
                            TAG,
                            "Switch Broadcast Source: "
                                    + ("device: " + device)
                                    + (", old SourceId: " + sourceIdToRemove)
                                    + (", new broadcastId: " + sourceMetadata.getBroadcastId())
                                    + (", new broadcastName: "
                                            + sourceMetadata.getBroadcastName()));

                    // new source will be added once the existing source got removed
                    if (isGroupOp) {
                        // mark group op for both remove and add source
                        // so setSourceGroupManaged will be updated accordingly in callbacks
                        enqueueSourceGroupOp(
                                device,
                                BassClientStateMachine.REMOVE_BCAST_SOURCE,
                                sourceIdToRemove);
                        enqueueSourceGroupOp(
                                device, BassClientStateMachine.ADD_BCAST_SOURCE, sourceMetadata);
                    }

                    /* Store metadata for sink device */
                    storeSinkMetadata(device, sourceMetadata.getBroadcastId(), sourceMetadata);

                    Message message =
                            stateMachine.obtainMessage(BassClientStateMachine.SWITCH_BCAST_SOURCE);
                    message.obj = sourceMetadata;
                    message.arg1 = sourceIdToRemove;
                    stateMachine.sendMessage(message);
                } else {
                    mCallbacks.notifySourceAddFailed(
                            device,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_REMOTE_NOT_ENOUGH_RESOURCES);
                }
                continue;
            }
            if (!leaudioBroadcastResyncHelper()) {
                int sourceId = checkDuplicateSourceAdditionAndGetSourceId(device, sourceMetadata);
                if (sourceId != BassConstants.INVALID_SOURCE_ID) {
                    log("addSource: not a valid broadcast source addition");
                    mCallbacks.notifySourceAddFailed(
                            device,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_DUPLICATE_ADDITION);
                    continue;
                }
            }

            // Even if there is a room for broadcast, it could happen that all broadcasts were
            // suspended via removing source. In that case, we have to found such broadcast and
            // remove it from metadata.
            checkIfBroadcastIsSuspendedBySourceRemovalAndClearData(device, stateMachine);

            /* Store metadata for sink device */
            storeSinkMetadata(device, sourceMetadata.getBroadcastId(), sourceMetadata);

            if (isGroupOp) {
                enqueueSourceGroupOp(
                        device, BassClientStateMachine.ADD_BCAST_SOURCE, sourceMetadata);
            }

            if (!isLocalBroadcast(sourceMetadata)) {
                checkAndSetGroupAllowedContextMask(device);
            }

            sEventLogger.logd(
                    TAG,
                    "Add Broadcast Source: "
                            + ("device: " + device)
                            + (", broadcastId: " + sourceMetadata.getBroadcastId())
                            + (", broadcastName: " + sourceMetadata.getBroadcastName())
                            + (", isGroupOp: " + isGroupOp));

            Message message = stateMachine.obtainMessage(BassClientStateMachine.ADD_BCAST_SOURCE);
            message.obj = sourceMetadata;
            stateMachine.sendMessage(message);

            byte[] code = sourceMetadata.getBroadcastCode();
            if (code != null && code.length != 0) {
                sEventLogger.logd(
                        TAG,
                        "Set Broadcast Code (Add Source context): "
                                + ("device: " + device)
                                + (", broadcastId: " + sourceMetadata.getBroadcastId())
                                + (", broadcastName: " + sourceMetadata.getBroadcastName()));

                message = stateMachine.obtainMessage(BassClientStateMachine.SET_BCAST_CODE);
                message.obj = sourceMetadata;
                message.arg1 = BassClientStateMachine.ARGTYPE_METADATA;
                stateMachine.sendMessage(message);
            }
        }
    }

    /**
     * Modify the Broadcast Source information on a Broadcast Sink
     *
     * @param sink representing the Broadcast Sink to which the Broadcast Source should be updated
     * @param sourceId source ID as delivered in onSourceAdded
     * @param updatedMetadata updated Broadcast Source metadata to be updated on the Broadcast Sink
     */
    public void modifySource(
            BluetoothDevice sink, int sourceId, BluetoothLeBroadcastMetadata updatedMetadata) {
        log(
                "modifySource: "
                        + ("device: " + sink)
                        + ("sourceId: " + sourceId)
                        + (", updatedMetadata: " + updatedMetadata));

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;

        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();

            if (updatedMetadata == null) {
                log("modifySource: Error bad parameters: updatedMetadata cannot be null");
                mCallbacks.notifySourceModifyFailed(
                        device, deviceSourceId, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                continue;
            }

            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            int statusCode =
                    validateParametersForSourceOperation(
                            stateMachine, device, updatedMetadata, deviceSourceId);
            if (statusCode != BluetoothStatusCodes.SUCCESS) {
                mCallbacks.notifySourceModifyFailed(device, deviceSourceId, statusCode);
                continue;
            }
            if (stateMachine.hasPendingSourceOperation()) {
                Log.w(
                        TAG,
                        "modifySource: source operation already pending, device: "
                                + device
                                + ", broadcastId: "
                                + updatedMetadata.getBroadcastId());
                mCallbacks.notifySourceModifyFailed(
                        device, deviceSourceId, BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                continue;
            }

            /* Update metadata for sink device */
            storeSinkMetadata(device, updatedMetadata.getBroadcastId(), updatedMetadata);

            sEventLogger.logd(
                    TAG,
                    "Modify Broadcast Source: "
                            + ("device: " + device)
                            + ("sourceId: " + deviceSourceId)
                            + (", updatedBroadcastId: " + updatedMetadata.getBroadcastId())
                            + (", updatedBroadcastName: " + updatedMetadata.getBroadcastName()));

            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
            message.arg1 = deviceSourceId;
            message.arg2 = BassConstants.INVALID_PA_SYNC_VALUE;
            message.obj = updatedMetadata;
            stateMachine.sendMessage(message);

            byte[] code = updatedMetadata.getBroadcastCode();
            if (code != null && code.length != 0) {
                sEventLogger.logd(
                        TAG,
                        "Set Broadcast Code (Modify Source context): "
                                + ("device: " + device)
                                + ("sourceId: " + deviceSourceId)
                                + (", updatedBroadcastId: " + updatedMetadata.getBroadcastId())
                                + (", updatedBroadcastName: "
                                        + updatedMetadata.getBroadcastName()));
                message = stateMachine.obtainMessage(BassClientStateMachine.SET_BCAST_CODE);
                message.obj = updatedMetadata;
                message.arg1 = BassClientStateMachine.ARGTYPE_METADATA;
                stateMachine.sendMessage(message);
            }
        }
    }

    /**
     * A public method for removing a Broadcast Source from a Broadcast Sink. It also supports group
     * removal if addSource was previously used with a group. Designed for external use, this method
     * always removes sources along with their cached values, even if they were suspended, as this
     * is intended by the user.
     *
     * @param sink representing the Broadcast Sink from which a Broadcast Source should be removed
     * @param sourceId source ID as delivered in onSourceAdded
     */
    public void removeSource(BluetoothDevice sink, int sourceId) {
        log("removeSource: device: " + sink + ", sourceId: " + sourceId);

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;
        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();

            mPausedBroadcastSinks.remove(device);

            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            int statusCode =
                    validateParametersForSourceOperation(stateMachine, device, deviceSourceId);
            if (statusCode != BluetoothStatusCodes.SUCCESS) {
                removeSinkMetadata(device);
                mCallbacks.notifySourceRemoveFailed(device, deviceSourceId, statusCode);
                continue;
            }

            BluetoothLeBroadcastMetadata metaData =
                    stateMachine.getCurrentBroadcastMetadata(deviceSourceId);
            if (metaData != null) {
                removeSinkMetadata(device, metaData.getBroadcastId());
            } else {
                removeSinkMetadata(device);
            }

            removeSourceInternal(device, deviceSourceId, stateMachine, metaData);
        }
    }

    /**
     * Removes the Broadcast Source from a single Broadcast Sink
     *
     * @param sink representing the Broadcast Sink from which a Broadcast Source should be removed
     * @param sourceId source ID as delivered in onSourceAdded
     */
    private void removeSourceInternal(BluetoothDevice sink, int sourceId) {
        log("removeSourceInternal prepare: device: " + sink + ", sourceId: " + sourceId);

        BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
        int statusCode = validateParametersForSourceOperation(stateMachine, sink, sourceId);
        if (statusCode != BluetoothStatusCodes.SUCCESS) {
            mCallbacks.notifySourceRemoveFailed(sink, sourceId, statusCode);
            return;
        }
        BluetoothLeBroadcastMetadata metaData = stateMachine.getCurrentBroadcastMetadata(sourceId);
        removeSourceInternal(sink, sourceId, stateMachine, metaData);
    }

    /**
     * Removes the Broadcast Source from a single Broadcast Sink
     *
     * @param sink representing the Broadcast Sink from which a Broadcast Source should be removed
     * @param sourceId source ID as delivered in onSourceAdded
     * @param stateMachine stateMachine for this sink
     * @param metaData current broadcast metadata for this sink
     */
    private void removeSourceInternal(
            BluetoothDevice sink,
            int sourceId,
            BassClientStateMachine stateMachine,
            BluetoothLeBroadcastMetadata metaData) {
        log("removeSourceInternal: device: " + sink + ", sourceId: " + sourceId);
        if (metaData != null) {
            stopBigMonitoring(metaData.getBroadcastId(), /* hostInitiated */ true);
        }

        if (stateMachine.isSyncedToTheSource(sourceId)) {
            sEventLogger.logd(
                    TAG,
                    "Remove Broadcast Source(Force lost PA sync): "
                            + ("device: " + sink)
                            + (", sourceId: " + sourceId)
                            + (", broadcastId: "
                                    + ((metaData == null)
                                            ? BassConstants.INVALID_BROADCAST_ID
                                            : metaData.getBroadcastId()))
                            + (", broadcastName: "
                                    + ((metaData == null) ? "" : metaData.getBroadcastName())));

            log("Force source to lost PA sync");
            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
            message.arg1 = sourceId;
            message.arg2 = BassConstants.PA_SYNC_DO_NOT_SYNC;
            /* Pending remove set. Remove source once not synchronized to PA */
            /* MetaData can be null if source is from remote's receive state */
            message.obj = metaData;
            stateMachine.sendMessage(message);
        } else {
            sEventLogger.logd(
                    TAG, "Remove Broadcast Source: device: " + sink + ", sourceId: " + sourceId);

            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.REMOVE_BCAST_SOURCE);
            message.arg1 = sourceId;
            stateMachine.sendMessage(message);
        }

        enqueueSourceGroupOp(
                sink, BassClientStateMachine.REMOVE_BCAST_SOURCE, Integer.valueOf(sourceId));
    }

    /**
     * Get information about all Broadcast Sources
     *
     * @param sink Broadcast Sink from which to get all Broadcast Sources
     * @return the list of Broadcast Receive State {@link BluetoothLeBroadcastReceiveState}
     */
    public List<BluetoothLeBroadcastReceiveState> getAllSources(BluetoothDevice sink) {
        log("getAllSources for " + sink);
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
            if (stateMachine == null) {
                log("stateMachine is null");
                return Collections.emptyList();
            }
            return stateMachine.getAllSources().stream()
                    .filter(rs -> !isEmptyBluetoothDevice(rs.getSourceDevice()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Get maximum number of sources that can be added to this Broadcast Sink
     *
     * @param sink Broadcast Sink device
     * @return maximum number of sources that can be added to this Broadcast Sink
     */
    int getMaximumSourceCapacity(BluetoothDevice sink) {
        log("getMaximumSourceCapacity: device = " + sink);
        BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
        if (stateMachine == null) {
            log("stateMachine is null");
            return 0;
        }
        return stateMachine.getMaximumSourceCapacity();
    }

    /**
     * Get metadata of source that stored on this Broadcast Sink
     *
     * @param sink Broadcast Sink device
     * @param sourceId Broadcast source id
     * @return metadata of source that stored on this Broadcast Sink
     */
    BluetoothLeBroadcastMetadata getSourceMetadata(BluetoothDevice sink, int sourceId) {
        if (!leaudioBroadcastApiGetLocalMetadata()) {
            return null;
        }

        log("getSourceMetadata: device = " + sink + " with source id = " + sourceId);
        BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
        if (stateMachine == null) {
            log("stateMachine is null");
            return null;
        }
        return stateMachine.getCurrentBroadcastMetadata(sourceId);
    }

    private boolean isLocalBroadcast(int broadcastId) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return false;
        }

        boolean wasFound =
                leAudioService.getAllBroadcastMetadata().stream()
                        .anyMatch(
                                meta -> {
                                    return meta.getBroadcastId() == broadcastId;
                                });
        log("isLocalBroadcast=" + wasFound);
        return wasFound;
    }

    boolean isLocalBroadcast(BluetoothLeBroadcastMetadata metaData) {
        if (metaData == null) {
            return false;
        }

        return isLocalBroadcast(metaData.getBroadcastId());
    }

    boolean isLocalBroadcast(BluetoothLeBroadcastReceiveState receiveState) {
        if (receiveState == null) {
            return false;
        }

        return isLocalBroadcast(receiveState.getBroadcastId());
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }

    private List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>>
            getReceiveStateDevicePairs(int broadcastId) {
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> list = new ArrayList<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                 * that all receivers should be considered.
                 */
                if ((broadcastId != BassConstants.INVALID_BROADCAST_ID)
                        && (receiveState.getBroadcastId() != broadcastId)) {
                    continue;
                }

                list.add(
                        new Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>(
                                receiveState, device));
            }
        }

        return list;
    }

    private void cancelPendingSourceOperations(int broadcastId) {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = getOrCreateStateMachine(device);
                if (sm != null && sm.hasPendingSourceOperation(broadcastId)) {
                    Message message =
                            sm.obtainMessage(
                                    BassClientStateMachine.CANCEL_PENDING_SOURCE_OPERATION);
                    message.arg1 = broadcastId;
                    sm.sendMessage(message);
                }
            }
        }
    }

    private void stopSourceReceivers(int broadcastId) {
        log("stopSourceReceivers broadcastId: " + broadcastId);

        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToRemove =
                getReceiveStateDevicePairs(broadcastId);

        for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToRemove) {
            removeSource(pair.second, pair.first.getSourceId());
        }

        if (!leaudioBroadcastResyncHelper() || broadcastId != BassConstants.INVALID_BROADCAST_ID) {
            /* There may be some pending add/modify source operations */
            cancelPendingSourceOperations(broadcastId);
        }
    }

    /**
     * Suspends source receivers for the given broadcast ID
     *
     * @param broadcastId The broadcast ID for which the receivers should be stopped or suspended
     */
    private void suspendSourceReceivers(int broadcastId) {
        log("stopSourceReceivers broadcastId: " + broadcastId);

        Map<BluetoothDevice, Integer> sourcesToRemove = new HashMap<>();
        HashSet<Integer> broadcastIdsToStopMonitoring = new HashSet<>();
        for (BluetoothDevice device : getConnectedDevices()) {
            if (!leaudioBroadcastResyncHelper()) {
                if (mPausedBroadcastSinks.contains(device)) {
                    // Skip this device if it has been paused
                    continue;
                }

                for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                    /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                     * that all receivers should be considered.
                     */
                    if ((broadcastId != BassConstants.INVALID_BROADCAST_ID)
                            && (receiveState.getBroadcastId() != broadcastId)) {
                        continue;
                    }

                    sEventLogger.logd(TAG, "Add broadcast sink to paused cache: " + device);
                    mPausedBroadcastSinks.add(device);

                    sourcesToRemove.put(device, receiveState.getSourceId());
                }
            } else {
                for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                    /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                     * that all receivers should be considered.
                     */
                    if ((broadcastId != BassConstants.INVALID_BROADCAST_ID)
                            && (receiveState.getBroadcastId() != broadcastId)) {
                        continue;
                    }

                    broadcastIdsToStopMonitoring.add(receiveState.getBroadcastId());

                    if (!mPausedBroadcastSinks.contains(device)
                            || isSinkUnintentionalPauseType(receiveState.getBroadcastId())) {
                        // Remove device if not paused yet
                        sourcesToRemove.put(device, receiveState.getSourceId());
                    }

                    sEventLogger.logd(TAG, "Add broadcast sink to paused cache: " + device);
                    mPausedBroadcastSinks.add(device);
                }
            }
        }

        for (int broadcastIdToStopMonitoring : broadcastIdsToStopMonitoring) {
            stopBigMonitoring(broadcastIdToStopMonitoring, /* hostInitiated */ true);
        }

        for (Map.Entry<BluetoothDevice, Integer> entry : sourcesToRemove.entrySet()) {
            removeSourceInternal(entry.getKey(), entry.getValue());
        }

        if (leaudioBroadcastResyncHelper()) {
            if (broadcastId != BassConstants.INVALID_BROADCAST_ID) {
                /* There may be some pending add/modify source operations */
                cancelPendingSourceOperations(broadcastId);
            }
        }
    }

    /** Return true if there is any non primary device receiving broadcast */
    private boolean isAudioSharingModeOn(Integer broadcastId) {
        HashSet<BluetoothDevice> devices = mLocalBroadcastReceivers.get(broadcastId);
        if (devices == null) {
            Log.w(TAG, "isAudioSharingModeOn: No receivers receiving broadcast: " + broadcastId);
            return false;
        }

        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.d(TAG, "isAudioSharingModeOn: No available LeAudioService");
            return false;
        }

        return devices.stream().anyMatch(d -> !leAudioService.isPrimaryDevice(d));
    }

    /** Handle disconnection of potential broadcast sinks */
    public void handleDeviceDisconnection(BluetoothDevice sink, boolean isIntentional) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.d(TAG, "BluetoothLeBroadcastReceiveState: No available LeAudioService");
            return;
        }

        Iterator<Map.Entry<Integer, HashSet<BluetoothDevice>>> iterator =
                mLocalBroadcastReceivers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, HashSet<BluetoothDevice>> entry = iterator.next();
            Integer broadcastId = entry.getKey();
            HashSet<BluetoothDevice> devices = entry.getValue();

            if (leaudioBigDependsOnAudioState()) {
                /* If somehow there is a non configured/playing broadcast, let's remove it */
                if (!(leAudioService.isPaused(broadcastId)
                        || leAudioService.isPlaying(broadcastId))) {
                    Log.w(TAG, "Non playing broadcast remove from receivers list");
                    iterator.remove();
                    continue;
                }
            } else {
                /* If somehow there is a non playing broadcast, let's remove it */
                if (!leAudioService.isPlaying(broadcastId)) {
                    Log.w(TAG, "Non playing broadcast remove from receivers list");
                    iterator.remove();
                    continue;
                }
            }

            if (isIntentional) {
                /* Check if disconnecting device participated in this broadcast reception */
                if (!devices.remove(sink)) {
                    continue;
                }

                removeSinkMetadata(sink);

                /* Check if there is any other primary device receiving this broadcast */
                if (devices.stream()
                        .anyMatch(
                                d ->
                                        ((getConnectionState(d) == STATE_CONNECTED)
                                                && leAudioService.isPrimaryDevice(d)))) {
                    continue;
                }

                Log.d(
                        TAG,
                        "handleIntendedDeviceDisconnection: No more potential broadcast "
                                + "(broadcast ID: "
                                + broadcastId
                                + ") receivers - stopping broadcast");
                iterator.remove();
                leAudioService.stopBroadcast(broadcastId);
            } else {
                /* Unintentional disconnection of primary device in private broadcast mode */
                if (!isAudioSharingModeOn(broadcastId)
                        && !devices.stream()
                                .anyMatch(
                                        d ->
                                                !d.equals(sink)
                                                        && (getConnectionState(d)
                                                                == STATE_CONNECTED))) {
                    iterator.remove();
                    leAudioService.stopBroadcast(broadcastId);
                    continue;
                }

                /* Unintentional disconnection of primary/secondary in broadcast sharing mode */
                if (devices.stream()
                        .anyMatch(
                                d ->
                                        !d.equals(sink)
                                                && (getConnectionState(d) == STATE_CONNECTED))) {
                    continue;
                }
                Log.d(
                        TAG,
                        "handleUnintendedDeviceDisconnection: No more potential broadcast "
                                + "(broadcast ID: "
                                + broadcastId
                                + ") receivers - stopping broadcast");
                mDialingOutTimeoutEvent = new DialingOutTimeoutEvent(broadcastId);
                mHandler.postDelayed(mDialingOutTimeoutEvent, DIALING_OUT_TIMEOUT_MS);
            }
        }
    }

    /* Handle device Bass state ready and check if assistant should resume broadcast */
    private void handleBassStateReady(BluetoothDevice sink) {
        //  Check its peer device still has active source
        Map<Integer, BluetoothLeBroadcastMetadata> entry = mBroadcastMetadataMap.get(sink);

        if (entry != null) {
            for (Map.Entry<Integer, BluetoothLeBroadcastMetadata> idMetadataIdPair :
                    entry.entrySet()) {
                BluetoothLeBroadcastMetadata metadata = idMetadataIdPair.getValue();
                if (metadata == null) {
                    Log.d(TAG, "handleBassStateReady: no metadata available");
                    continue;
                }
                for (BluetoothDevice groupDevice :
                        getTargetDeviceList(sink, /* isGroupOp */ true)) {
                    if (groupDevice.equals(sink)) {
                        continue;
                    }
                    // Check peer device
                    Optional<BluetoothLeBroadcastReceiveState> receiver =
                            getOrCreateStateMachine(groupDevice).getAllSources().stream()
                                    .filter(e -> e.getBroadcastId() == metadata.getBroadcastId())
                                    .findAny();
                    if (receiver.isPresent()
                            && !getAllSources(sink).stream()
                                    .anyMatch(
                                            rs ->
                                                    (rs.getBroadcastId()
                                                            == receiver.get().getBroadcastId()))) {
                        Log.d(TAG, "handleBassStateReady: restore the source for device, " + sink);
                        addSource(sink, metadata, /* isGroupOp */ false);
                        return;
                    }
                }
            }
        } else {
            Log.d(TAG, "handleBassStateReady: no entry for device: " + sink + ", available");
        }

        // Continue to check if there is pending source to add due to BASS not ready
        synchronized (mPendingSourcesToAdd) {
            Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
            while (iterator.hasNext()) {
                AddSourceData pendingSourcesToAdd = iterator.next();
                if (pendingSourcesToAdd.mSink.equals(sink)) {
                    Log.d(TAG, "handleBassStateReady: retry adding source with device, " + sink);
                    addSource(
                            pendingSourcesToAdd.mSink, pendingSourcesToAdd.mSourceMetadata, false);
                    iterator.remove();
                    return;
                }
            }
        }
    }

    /* Handle device Bass state setup failed */
    private void handleBassStateSetupFailed(BluetoothDevice sink) {
        // Check if there is pending source to add due to BASS not ready
        synchronized (mPendingSourcesToAdd) {
            Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
            while (iterator.hasNext()) {
                AddSourceData pendingSourcesToAdd = iterator.next();
                if (pendingSourcesToAdd.mSink.equals(sink)) {
                    mCallbacks.notifySourceAddFailed(
                            pendingSourcesToAdd.mSink,
                            pendingSourcesToAdd.mSourceMetadata,
                            BluetoothStatusCodes.ERROR_REMOTE_NOT_ENOUGH_RESOURCES);
                    iterator.remove();
                    return;
                }
            }
        }
    }

    private void logPausedBroadcastsAndSinks() {
        log(
                "mPausedBroadcastIds: "
                        + mPausedBroadcastIds
                        + ", mPausedBroadcastSinks: "
                        + mPausedBroadcastSinks);
    }

    private boolean isHostPauseType(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseType.HOST_INTENTIONAL));
    }

    private boolean isSinkUnintentionalPauseType(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseType.SINK_UNINTENTIONAL));
    }

    public void stopBigMonitoring() {
        if (!leaudioBroadcastResyncHelper()) {
            return;
        }
        log("stopBigMonitoring");
        mPausedBroadcastSinks.clear();

        Iterator<Integer> iterator = mPausedBroadcastIds.keySet().iterator();
        while (iterator.hasNext()) {
            int pausedBroadcastId = iterator.next();
            mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
            mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT);
            iterator.remove();
            synchronized (mSearchScanCallbackLock) {
                // when searching is stopped then stop active sync
                if (!isSearchInProgress()) {
                    cancelActiveSync(getSyncHandleForBroadcastId(pausedBroadcastId));
                }
            }
        }
        logPausedBroadcastsAndSinks();
    }

    private void checkAndStopBigMonitoring() {
        if (!leaudioBroadcastResyncHelper()) {
            return;
        }
        log("checkAndStopBigMonitoring");
        Iterator<Integer> iterator = mPausedBroadcastIds.keySet().iterator();
        while (iterator.hasNext()) {
            int pausedBroadcastId = iterator.next();
            if (!isAnyReceiverSyncedToBroadcast(pausedBroadcastId)) {
                mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
                mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT);

                if (isSinkUnintentionalPauseType(pausedBroadcastId)
                        || (isHostPauseType(pausedBroadcastId)
                                && mPausedBroadcastSinks.isEmpty())) {
                    iterator.remove();
                }
                synchronized (mSearchScanCallbackLock) {
                    // when searching is stopped then stop active sync
                    if (!isSearchInProgress()) {
                        cancelActiveSync(getSyncHandleForBroadcastId(pausedBroadcastId));
                    }
                }
                logPausedBroadcastsAndSinks();
            }
        }
    }

    private void stopBigMonitoring(int broadcastId, boolean hostInitiated) {
        if (!leaudioBroadcastResyncHelper()) {
            return;
        }
        log("stopBigMonitoring broadcastId: " + broadcastId + ", hostInitiated: " + hostInitiated);
        mTimeoutHandler.stop(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
        mTimeoutHandler.stop(broadcastId, MESSAGE_BROADCAST_MONITOR_TIMEOUT);
        if (hostInitiated) {
            mPausedBroadcastIds.put(broadcastId, PauseType.HOST_INTENTIONAL);
        } else {
            mPausedBroadcastIds.remove(broadcastId);
            mPausedBroadcastSinks.clear();
        }
        stopActiveSync(broadcastId);
        logPausedBroadcastsAndSinks();
    }

    private void stopActiveSync(int broadcastId) {
        synchronized (mSearchScanCallbackLock) {
            // when searching is stopped then stop active sync
            if (!isSearchInProgress()) {
                if (leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()) {
                    boolean waitingForPast = false;
                    synchronized (mSinksWaitingForPast) {
                        waitingForPast =
                                mSinksWaitingForPast.entrySet().stream()
                                        .anyMatch(entry -> entry.getValue().first == broadcastId);
                    }
                    if (!waitingForPast) {
                        cancelActiveSync(getSyncHandleForBroadcastId(broadcastId));
                    }
                } else {
                    cancelActiveSync(getSyncHandleForBroadcastId(broadcastId));
                }
            }
        }
    }

    /** Cache suspending sources when broadcast paused */
    public void cacheSuspendingSources(int broadcastId) {
        sEventLogger.logd(TAG, "Cache suspending sources: " + broadcastId);
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToCache =
                getReceiveStateDevicePairs(broadcastId);

        for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToCache) {
            mPausedBroadcastSinks.add(pair.second);
        }

        logPausedBroadcastsAndSinks();
    }

    /** Request receivers to suspend broadcast sources synchronization */
    @VisibleForTesting
    void suspendReceiversSourceSynchronization(int broadcastId) {
        sEventLogger.logd(TAG, "Suspend receivers source synchronization: " + broadcastId);
        suspendSourceReceivers(broadcastId);
    }

    /** Request all receivers to suspend broadcast sources synchronization */
    @VisibleForTesting
    void suspendAllReceiversSourceSynchronization() {
        sEventLogger.logd(TAG, "Suspend all receivers source synchronization");
        suspendSourceReceivers(BassConstants.INVALID_BROADCAST_ID);
    }

    /** Request receivers to stop broadcast sources synchronization and remove them */
    public void stopReceiversSourceSynchronization(int broadcastId) {
        sEventLogger.logd(TAG, "Stop receivers source synchronization: " + broadcastId);
        stopSourceReceivers(broadcastId);
    }

    /** Request receivers to resume broadcast source synchronization */
    public void resumeReceiversSourceSynchronization() {
        sEventLogger.logd(TAG, "Resume receivers source synchronization");

        Iterator<BluetoothDevice> iterator = mPausedBroadcastSinks.iterator();
        while (iterator.hasNext()) {
            BluetoothDevice sink = iterator.next();
            sEventLogger.logd(TAG, "Remove broadcast sink from paused cache: " + sink);
            Map<Integer, BluetoothLeBroadcastMetadata> entry =
                    mBroadcastMetadataMap.getOrDefault(sink, Collections.emptyMap());

            for (BluetoothLeBroadcastMetadata metadata : entry.values()) {

                if (leaudioBroadcastResyncHelper()) {
                    if (metadata == null) {
                        Log.w(
                                TAG,
                                "resumeReceiversSourceSynchronization: failed to get metadata to"
                                        + " resume sink: "
                                        + sink);
                        continue;
                    }

                    mPausedBroadcastIds.remove(metadata.getBroadcastId());

                    // For each device, find the source ID having this broadcast ID
                    BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
                    List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
                    Optional<BluetoothLeBroadcastReceiveState> receiveState =
                            sources.stream()
                                    .filter(e -> e.getBroadcastId() == metadata.getBroadcastId())
                                    .findAny();

                    if (leaudioBroadcastResyncHelper()
                            && receiveState.isPresent()
                            && (receiveState.get().getPaSyncState()
                                            == BluetoothLeBroadcastReceiveState
                                                    .PA_SYNC_STATE_SYNCINFO_REQUEST
                                    || receiveState.get().getPaSyncState()
                                            == BluetoothLeBroadcastReceiveState
                                                    .PA_SYNC_STATE_SYNCHRONIZED)) {
                        continue;
                    }

                    if (receiveState.isPresent()
                            && (!leaudioBroadcastResyncHelper()
                                    || isLocalBroadcast(metadata)
                                    || getActiveSyncedSources()
                                            .contains(
                                                    getSyncHandleForBroadcastId(
                                                            metadata.getBroadcastId())))) {
                        int sourceId = receiveState.get().getSourceId();
                        updateSourceToResumeBroadcast(sink, sourceId, metadata);
                    } else {
                        addSource(sink, metadata, /* isGroupOp */ false);
                    }
                } else {
                    if (metadata != null) {
                        mPausedBroadcastIds.remove(metadata.getBroadcastId());
                        addSource(sink, metadata, /* isGroupOp */ false);
                    } else {
                        Log.w(
                                TAG,
                                "resumeReceiversSourceSynchronization: failed to get metadata to"
                                        + " resume sink: "
                                        + sink);
                    }
                }
            }
            // remove the device from mPausedBroadcastSinks
            iterator.remove();
        }

        logPausedBroadcastsAndSinks();
    }

    private void updateSourceToResumeBroadcast(
            BluetoothDevice sink, int sourceId, BluetoothLeBroadcastMetadata metadata) {
        BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
        int statusCode =
                validateParametersForSourceOperation(stateMachine, sink, metadata, sourceId);
        if (statusCode != BluetoothStatusCodes.SUCCESS) {
            return;
        }
        if (stateMachine.hasPendingSourceOperation()) {
            Log.w(
                    TAG,
                    "updateSourceToResumeBroadcast: source operation already pending, device: "
                            + sink
                            + ", broadcastId: "
                            + metadata.getBroadcastId());
            return;
        }

        sEventLogger.logd(
                TAG,
                "Modify Broadcast Source (resume): "
                        + ("device: " + sink)
                        + (", sourceId: " + sourceId)
                        + (", updatedBroadcastId: " + metadata.getBroadcastId())
                        + (", updatedBroadcastName: " + metadata.getBroadcastName()));
        Message message = stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
        message.arg1 = sourceId;
        message.arg2 =
                DeviceConfig.getBoolean(
                                DeviceConfig.NAMESPACE_BLUETOOTH,
                                "persist.vendor.service.bt.defNoPAS",
                                false)
                        ? BassConstants.PA_SYNC_PAST_NOT_AVAILABLE
                        : BassConstants.PA_SYNC_PAST_AVAILABLE;
        message.obj = metadata;
        stateMachine.sendMessage(message);
    }

    /** Handle Unicast source stream status change */
    public void handleUnicastSourceStreamStatusChange(int status) {
        mUnicastSourceStreamStatus = Optional.of(status);

        if (status == STATUS_LOCAL_STREAM_REQUESTED) {
            if ((leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()
                            && hasPrimaryDeviceManagedExternalBroadcast())
                    || (!leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()
                            && areReceiversReceivingOnlyExternalBroadcast(getConnectedDevices()))) {
                cacheSuspendingSources(BassConstants.INVALID_BROADCAST_ID);
                List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToStop =
                        getReceiveStateDevicePairs(BassConstants.INVALID_BROADCAST_ID);
                for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToStop) {
                    stopBigMonitoring(pair.first.getBroadcastId(), /* hostInitiated */ true);
                }
            }
            if (!leaudioMonitorUnicastSourceWhenManagedByBroadcastDelegator()) {
                for (Map.Entry<Integer, PauseType> entry : mPausedBroadcastIds.entrySet()) {
                    Integer broadcastId = entry.getKey();
                    PauseType pauseType = entry.getValue();
                    if (pauseType != PauseType.HOST_INTENTIONAL) {
                        suspendReceiversSourceSynchronization(broadcastId);
                    }
                }
            }
        } else if (status == STATUS_LOCAL_STREAM_SUSPENDED) {
            /* Resume paused receivers if there are some */
            if (!mPausedBroadcastSinks.isEmpty()) {
                resumeReceiversSourceSynchronization();
            }
        } else if (status == STATUS_LOCAL_STREAM_STREAMING) {
            Log.d(TAG, "Ignore STREAMING source status");
        } else if (status == STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE) {
            suspendAllReceiversSourceSynchronization();
        }
    }

    /** Check if any sink receivers are receiving broadcast stream */
    public boolean isAnyReceiverActive(List<BluetoothDevice> devices) {
        for (BluetoothDevice device : devices) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                if (isReceiverActive(receiveState)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasPrimaryDeviceManagedExternalBroadcast() {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();

        if (leAudioService == null) {
            Log.e(TAG, "no LeAudioService");
            return false;
        }

        for (BluetoothDevice device : getConnectedDevices()) {
            if (!leAudioService.isPrimaryDevice(device)) {
                continue;
            }

            Map<Integer, BluetoothLeBroadcastMetadata> entry = mBroadcastMetadataMap.get(device);

            /* null means that this source was not added or modified by assistant */
            if (entry == null) {
                continue;
            }

            /* Assistant manages some external broadcast */
            if (entry.values().stream().anyMatch(e -> !isLocalBroadcast(e))) {
                return true;
            }
        }

        return false;
    }

    /** Check if any sink receivers are receiving broadcast stream */
    public boolean areReceiversReceivingOnlyExternalBroadcast(List<BluetoothDevice> devices) {
        boolean isReceivingExternalBroadcast = false;

        for (BluetoothDevice device : devices) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                for (int i = 0; i < receiveState.getNumSubgroups(); i++) {
                    if (isSyncedToBroadcastStream(receiveState.getBisSyncState().get(i))) {
                        if (isLocalBroadcast(receiveState)) {
                            return false;
                        } else {
                            isReceivingExternalBroadcast = true;
                        }
                    }
                }
            }
        }

        return isReceivingExternalBroadcast;
    }

    private boolean isAnyReceiverSyncedToBroadcast(int broadcastId) {
        for (BluetoothDevice device : getConnectedDevices()) {
            if (getAllSources(device).stream()
                    .anyMatch(receiveState -> (receiveState.getBroadcastId() == broadcastId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReceiverActive(BluetoothLeBroadcastReceiveState receiveState) {
        if (receiveState.getPaSyncState()
                == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED) {
            return true;
        } else {
            for (int i = 0; i < receiveState.getNumSubgroups(); i++) {
                if (isSyncedToBroadcastStream(receiveState.getBisSyncState().get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<Integer> getExternalBroadcastsActiveOnSinks() {
        HashSet<Integer> syncedBroadcasts = new HashSet<>();
        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                if (isLocalBroadcast(receiveState)) {
                    continue;
                }
                if (isReceiverActive(receiveState)) {
                    syncedBroadcasts.add(receiveState.getBroadcastId());
                    log("getExternalBroadcastsActiveOnSinks: " + receiveState);
                }
            }
        }
        return syncedBroadcasts;
    }

    private boolean isAllReceiversActive(int broadcastId) {
        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                if (receiveState.getBroadcastId() == broadcastId
                        && !isReceiverActive(receiveState)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Get sink devices synced to the broadcasts */
    public List<BluetoothDevice> getSyncedBroadcastSinks() {
        List<BluetoothDevice> activeSinks = new ArrayList<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            if (leaudioBigDependsOnAudioState()) {
                if (!getAllSources(device).isEmpty()) {
                    activeSinks.add(device);
                }
            } else {
                if (getAllSources(device).stream()
                        .anyMatch(
                                receiveState ->
                                        (receiveState.getBisSyncState().stream()
                                                .anyMatch(
                                                        BassClientService
                                                                ::isSyncedToBroadcastStream)))) {
                    activeSinks.add(device);
                }
            }
        }
        return activeSinks;
    }

    /** Get sink devices synced to the broadcasts by broadcast id */
    public List<BluetoothDevice> getSyncedBroadcastSinks(int broadcastId) {
        return getConnectedDevices().stream()
                .filter(
                        device ->
                                getAllSources(device).stream()
                                        .anyMatch(rs -> rs.getBroadcastId() == broadcastId))
                .collect(Collectors.toUnmodifiableList());
    }

    private static boolean isSyncedToBroadcastStream(Long syncState) {
        return syncState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_NOT_SYNC_TO_BIS
                && syncState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG;
    }

    private Set<Integer> getBroadcastIdsOfSyncedBroadcasters() {
        HashSet<Integer> broadcastIds = new HashSet<>();
        List<Integer> activeSyncedSrc = new ArrayList<>(getActiveSyncedSources());
        for (int syncHandle : activeSyncedSrc) {
            broadcastIds.add(getBroadcastIdForSyncHandle(syncHandle));
        }
        return broadcastIds;
    }

    private Set<Integer> getBroadcastIdsWaitingForPAST() {
        HashSet<Integer> broadcastIds = new HashSet<>();
        synchronized (mSinksWaitingForPast) {
            for (Map.Entry<BluetoothDevice, Pair<Integer, Integer>> entry :
                    mSinksWaitingForPast.entrySet()) {
                broadcastIds.add(entry.getValue().first);
            }
        }
        return broadcastIds;
    }

    private Set<Integer> getBroadcastIdsWaitingForAddSource() {
        HashSet<Integer> broadcastIds = new HashSet<>();
        synchronized (mPendingSourcesToAdd) {
            for (AddSourceData pendingSourcesToAdd : mPendingSourcesToAdd) {
                broadcastIds.add(pendingSourcesToAdd.mSourceMetadata.getBroadcastId());
            }
        }
        return broadcastIds;
    }

    private Set<Integer> getPausedBroadcastIdsBasedOnSinks() {
        HashSet<Integer> broadcastIds = new HashSet<>();
        for (BluetoothDevice pausedSink : mPausedBroadcastSinks) {
            Map<Integer, BluetoothLeBroadcastMetadata> entry =
                    mBroadcastMetadataMap.getOrDefault(pausedSink, Collections.emptyMap());
            broadcastIds.addAll(entry.keySet());
        }
        return broadcastIds;
    }

    private Set<Integer> getUnintentionallyPausedBroadcastIds() {
        HashSet<Integer> broadcastIds = new HashSet<>();
        for (int pausedBroadcastId : mPausedBroadcastIds.keySet()) {
            if (isSinkUnintentionalPauseType(pausedBroadcastId)) {
                broadcastIds.add(pausedBroadcastId);
            }
        }
        return broadcastIds;
    }

    /** Handle broadcast state changed */
    public void notifyBroadcastStateChanged(int state, int broadcastId) {
        switch (state) {
            case BROADCAST_STATE_STOPPED:
                if (mLocalBroadcastReceivers.remove(broadcastId) != null) {
                    sEventLogger.logd(TAG, "Broadcast ID: " + broadcastId + ", stopped");
                }
                break;
            case BROADCAST_STATE_CONFIGURING:
            case BROADCAST_STATE_PAUSED:
            case BROADCAST_STATE_ENABLING:
            case BROADCAST_STATE_DISABLING:
            case BROADCAST_STATE_STOPPING:
            case BROADCAST_STATE_STREAMING:
            default:
                break;
        }
    }

    /** Callback handler */
    static class Callbacks extends Handler {
        private static final int MSG_SEARCH_STARTED = 1;
        private static final int MSG_SEARCH_STARTED_FAILED = 2;
        private static final int MSG_SEARCH_STOPPED = 3;
        private static final int MSG_SEARCH_STOPPED_FAILED = 4;
        private static final int MSG_SOURCE_FOUND = 5;
        private static final int MSG_SOURCE_ADDED = 6;
        private static final int MSG_SOURCE_ADDED_FAILED = 7;
        private static final int MSG_SOURCE_MODIFIED = 8;
        private static final int MSG_SOURCE_MODIFIED_FAILED = 9;
        private static final int MSG_SOURCE_REMOVED = 10;
        private static final int MSG_SOURCE_REMOVED_FAILED = 11;
        private static final int MSG_RECEIVESTATE_CHANGED = 12;
        private static final int MSG_SOURCE_LOST = 13;
        private static final int MSG_BASS_STATE_READY = 14;
        private static final int MSG_BASS_STATE_SETUP_FAILED = 15;

        @GuardedBy("mCallbacksList")
        private final RemoteCallbackList<IBluetoothLeBroadcastAssistantCallback> mCallbacksList =
                new RemoteCallbackList<>();

        Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IBluetoothLeBroadcastAssistantCallback callback) {
            synchronized (mCallbacksList) {
                mCallbacksList.register(callback);
            }
        }

        public void unregister(IBluetoothLeBroadcastAssistantCallback callback) {
            synchronized (mCallbacksList) {
                mCallbacksList.unregister(callback);
            }
        }

        private static void checkForPendingGroupOpRequest(Message msg) {
            if (sService == null) {
                Log.e(TAG, "Service is null");
                return;
            }

            final int reason = msg.arg1;
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_SOURCE_ADDED:
                case MSG_SOURCE_ADDED_FAILED:
                    ObjParams param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    sService.checkForPendingGroupOpRequest(
                            sink, reason, BassClientStateMachine.ADD_BCAST_SOURCE, param.mObj2);
                    break;
                case MSG_SOURCE_REMOVED:
                case MSG_SOURCE_REMOVED_FAILED:
                    sink = (BluetoothDevice) msg.obj;
                    sService.checkForPendingGroupOpRequest(
                            sink,
                            reason,
                            BassClientStateMachine.REMOVE_BCAST_SOURCE,
                            Integer.valueOf(msg.arg2));
                    break;
                default:
                    break;
            }
        }

        private static boolean handleServiceInternalMessage(Message msg) {
            boolean isMsgHandled = false;
            if (sService == null) {
                Log.e(TAG, "Service is null");
                return isMsgHandled;
            }
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_BASS_STATE_READY:
                    sink = (BluetoothDevice) msg.obj;
                    sService.handleBassStateReady(sink);
                    isMsgHandled = true;
                    break;
                case MSG_BASS_STATE_SETUP_FAILED:
                    sink = (BluetoothDevice) msg.obj;
                    sService.handleBassStateSetupFailed(sink);
                    isMsgHandled = true;
                    break;
                default:
                    break;
            }
            return isMsgHandled;
        }

        @Override
        public void handleMessage(Message msg) {
            if (handleServiceInternalMessage(msg)) {
                log("Handled internal message: " + msg.what);
                return;
            }

            checkForPendingGroupOpRequest(msg);

            synchronized (mCallbacksList) {
                final int n = mCallbacksList.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    final IBluetoothLeBroadcastAssistantCallback callback =
                            mCallbacksList.getBroadcastItem(i);
                    try {
                        invokeCallback(callback, msg);
                    } catch (RemoteException e) {
                        // Ignore exception
                    }
                }
                mCallbacksList.finishBroadcast();
            }
        }

        private static class ObjParams {
            final Object mObj1;
            final Object mObj2;

            ObjParams(Object o1, Object o2) {
                mObj1 = o1;
                mObj2 = o2;
            }
        }

        private static void invokeCallback(
                IBluetoothLeBroadcastAssistantCallback callback, Message msg)
                throws RemoteException {
            final int reason = msg.arg1;
            final int sourceId = msg.arg2;
            ObjParams param;
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_SEARCH_STARTED:
                    callback.onSearchStarted(reason);
                    break;
                case MSG_SEARCH_STARTED_FAILED:
                    callback.onSearchStartFailed(reason);
                    break;
                case MSG_SEARCH_STOPPED:
                    callback.onSearchStopped(reason);
                    break;
                case MSG_SEARCH_STOPPED_FAILED:
                    callback.onSearchStopFailed(reason);
                    break;
                case MSG_SOURCE_FOUND:
                    callback.onSourceFound((BluetoothLeBroadcastMetadata) msg.obj);
                    break;
                case MSG_SOURCE_ADDED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    callback.onSourceAdded(sink, sourceId, reason);
                    break;
                case MSG_SOURCE_ADDED_FAILED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    BluetoothLeBroadcastMetadata metadata =
                            (BluetoothLeBroadcastMetadata) param.mObj2;
                    callback.onSourceAddFailed(sink, metadata, reason);
                    break;
                case MSG_SOURCE_MODIFIED:
                    callback.onSourceModified((BluetoothDevice) msg.obj, sourceId, reason);
                    break;
                case MSG_SOURCE_MODIFIED_FAILED:
                    callback.onSourceModifyFailed((BluetoothDevice) msg.obj, sourceId, reason);
                    break;
                case MSG_SOURCE_REMOVED:
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoved(sink, sourceId, reason);
                    break;
                case MSG_SOURCE_REMOVED_FAILED:
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoveFailed(sink, sourceId, reason);
                    break;
                case MSG_RECEIVESTATE_CHANGED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    BluetoothLeBroadcastReceiveState state =
                            (BluetoothLeBroadcastReceiveState) param.mObj2;
                    callback.onReceiveStateChanged(sink, sourceId, state);
                    break;
                case MSG_SOURCE_LOST:
                    callback.onSourceLost(sourceId);
                    break;
                default:
                    Log.e(TAG, "Invalid msg: " + msg.what);
                    break;
            }
        }

        void notifySearchStarted(int reason) {
            sEventLogger.logd(TAG, "notifySearchStarted: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED, reason, 0).sendToTarget();
        }

        void notifySearchStartFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStartFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED_FAILED, reason, 0).sendToTarget();
        }

        void notifySearchStopped(int reason) {
            sEventLogger.logd(TAG, "notifySearchStopped: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED, reason, 0).sendToTarget();
        }

        void notifySearchStopFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStopFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED_FAILED, reason, 0).sendToTarget();
        }

        void notifySourceFound(BluetoothLeBroadcastMetadata source) {
            sEventLogger.logd(
                    TAG,
                    "invokeCallback: MSG_SOURCE_FOUND"
                            + ", source: "
                            + source.getSourceDevice()
                            + ", broadcastId: "
                            + source.getBroadcastId()
                            + ", broadcastName: "
                            + source.getBroadcastName()
                            + ", isPublic: "
                            + source.isPublicBroadcast()
                            + ", isEncrypted: "
                            + source.isEncrypted());
            obtainMessage(MSG_SOURCE_FOUND, 0, 0, source).sendToTarget();
        }

        void notifySourceAdded(
                BluetoothDevice sink, BluetoothLeBroadcastReceiveState recvState, int reason) {
            sService.localNotifySourceAdded(sink, recvState);

            sEventLogger.logd(
                    TAG,
                    "notifySourceAdded: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + recvState.getSourceId()
                            + ", reason: "
                            + reason);

            ObjParams param = new ObjParams(sink, recvState);
            obtainMessage(MSG_SOURCE_ADDED, reason, recvState.getSourceId(), param).sendToTarget();
        }

        void notifySourceAddFailed(
                BluetoothDevice sink, BluetoothLeBroadcastMetadata source, int reason) {
            sService.checkAndResetGroupAllowedContextMask();
            sService.localNotifySourceAddFailed(sink, source);

            sEventLogger.loge(
                    TAG,
                    "notifySourceAddFailed: sink: "
                            + sink
                            + ", source: "
                            + source
                            + ", reason: "
                            + reason);
            ObjParams param = new ObjParams(sink, source);
            obtainMessage(MSG_SOURCE_ADDED_FAILED, reason, 0, param).sendToTarget();
        }

        void notifySourceModified(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logd(
                    TAG,
                    "notifySourceModified: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_MODIFIED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceModifyFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceModifyFailed: sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_MODIFIED_FAILED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoved(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logd(
                    TAG,
                    "notifySourceRemoved: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_REMOVED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoveFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceRemoveFailed: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_REMOVED_FAILED, reason, sourceId, sink).sendToTarget();
        }

        void notifyReceiveStateChanged(
                BluetoothDevice sink, int sourceId, BluetoothLeBroadcastReceiveState state) {
            ObjParams param = new ObjParams(sink, state);

            sService.localNotifyReceiveStateChanged(sink, state);

            StringBuilder subgroupState = new StringBuilder(" / SUB GROUPS: ");
            for (int i = 0; i < state.getNumSubgroups(); i++) {
                subgroupState
                        .append("IDX: ")
                        .append(i)
                        .append(", SYNC: ")
                        .append(state.getBisSyncState().get(i));
            }

            sEventLogger.logd(
                    TAG,
                    "notifyReceiveStateChanged: "
                            + "sink: "
                            + sink
                            + ", state: SRC ID: "
                            + state.getSourceId()
                            + " / ADDR TYPE: "
                            + state.getSourceAddressType()
                            + " / SRC DEV: "
                            + state.getSourceDevice()
                            + " / ADV SID: "
                            + state.getSourceAdvertisingSid()
                            + " / BID: "
                            + state.getBroadcastId()
                            + " / PA STATE: "
                            + state.getPaSyncState()
                            + " / BENC STATE: "
                            + state.getBigEncryptionState()
                            + " / BAD CODE: "
                            + Arrays.toString(state.getBadCode())
                            + subgroupState.toString());
            obtainMessage(MSG_RECEIVESTATE_CHANGED, 0, sourceId, param).sendToTarget();
        }

        void notifySourceLost(int broadcastId) {
            sEventLogger.logd(TAG, "notifySourceLost: broadcastId: " + broadcastId);
            obtainMessage(MSG_SOURCE_LOST, 0, broadcastId).sendToTarget();
        }

        void notifyBassStateReady(BluetoothDevice sink) {
            sEventLogger.logd(TAG, "notifyBassStateReady: sink: " + sink);
            obtainMessage(MSG_BASS_STATE_READY, sink).sendToTarget();
        }

        void notifyBassStateSetupFailed(BluetoothDevice sink) {
            sEventLogger.logd(TAG, "notifyBassStateSetupFailed: sink: " + sink);
            obtainMessage(MSG_BASS_STATE_SETUP_FAILED, sink).sendToTarget();
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);

        sb.append("Broadcast Assistant Service instance:\n");

        /* Dump first connected state machines */
        for (Map.Entry<BluetoothDevice, BassClientStateMachine> entry : mStateMachines.entrySet()) {
            BassClientStateMachine sm = entry.getValue();
            if (sm.getConnectionState() == STATE_CONNECTED) {
                sm.dump(sb);
                sb.append("\n\n");
            }
        }

        /* Dump at least all other than connected state machines */
        for (Map.Entry<BluetoothDevice, BassClientStateMachine> entry : mStateMachines.entrySet()) {
            BassClientStateMachine sm = entry.getValue();
            if (sm.getConnectionState() != STATE_CONNECTED) {
                sm.dump(sb);
            }
        }

        sb.append("\n\n");
        sEventLogger.dump(sb);
        sb.append("\n");
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothLeBroadcastAssistantBinder extends IBluetoothLeBroadcastAssistant.Stub
            implements IProfileServiceBinder {
        BassClientService mService;

        BluetoothLeBroadcastAssistantBinder(BassClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private BassClientService getServiceAndEnforceConnect(AttributionSource source) {
            // Cache mService because it can change while getService is called
            BassClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @RequiresPermission(allOf = {BLUETOOTH_SCAN, BLUETOOTH_PRIVILEGED})
        private BassClientService getServiceAndEnforceScan(AttributionSource source) {
            // Cache mService because it can change while getService is called
            BassClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkScanPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @Override
        public int getConnectionState(BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return STATE_DISCONNECTED;
            }
            return service.getConnectionState(sink);
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getConnectedDevices();
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return false;
            }
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return CONNECTION_POLICY_FORBIDDEN;
            }
            return service.getConnectionPolicy(device);
        }

        @Override
        public void registerCallback(
                IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.registerCallback(cb);
        }

        @Override
        public void unregisterCallback(
                IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.unregisterCallback(cb);
        }

        @Override
        public void startSearchingForSources(List<ScanFilter> filters, AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.startSearchingForSources(filters);
        }

        @Override
        public void stopSearchingForSources(AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.stopSearchingForSources();
        }

        @Override
        public boolean isSearchInProgress(AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return false;
            }
            return service.isSearchInProgress();
        }

        @Override
        public void addSource(
                BluetoothDevice sink,
                BluetoothLeBroadcastMetadata sourceMetadata,
                boolean isGroupOp,
                AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.addSource(sink, sourceMetadata, isGroupOp);
        }

        @Override
        public void modifySource(
                BluetoothDevice sink,
                int sourceId,
                BluetoothLeBroadcastMetadata updatedMetadata,
                AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.modifySource(sink, sourceId, updatedMetadata);
        }

        @Override
        public void removeSource(BluetoothDevice sink, int sourceId, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.removeSource(sink, sourceId);
        }

        @Override
        public List<BluetoothLeBroadcastReceiveState> getAllSources(
                BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getAllSources(sink);
        }

        @Override
        public int getMaximumSourceCapacity(BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return 0;
            }
            return service.getMaximumSourceCapacity(sink);
        }

        @Override
        public BluetoothLeBroadcastMetadata getSourceMetadata(
                BluetoothDevice sink, int sourceId, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return null;
            }
            return service.getSourceMetadata(sink, sourceId);
        }
    }
}
