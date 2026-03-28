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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static java.util.Objects.requireNonNull;

import android.app.NotificationManager;
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
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.auracast.AuracastUtils;
import com.android.bluetooth.auracast.BroadcastStreamInfo;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioConstants;
import com.android.bluetooth.le_audio.LeAudioStackEvent;
import com.android.bluetooth.le_audio.LeAudioUtils;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.profile.ConnectableProfile;
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
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Broadcast Assistant Scan Service */
public class BassClientService extends ConnectableProfile {
    static final String TAG = BassClientService.class.getSimpleName();

    private static final int THREAD_JOIN_TIMEOUT_MS = 1000;

    private static final int MAX_ACTIVE_SYNCED_SOURCES_NUM = 4;
    private static final int MAX_BIS_DISCOVERY_TRIES_NUM = 5;

    @VisibleForTesting static final int MESSAGE_BIG_MONITOR_TIMEOUT = 1;
    @VisibleForTesting static final int MESSAGE_OOR_MONITOR_TIMEOUT = 2;
    @VisibleForTesting static final int MESSAGE_SYNC_LOST_TIMEOUT = 3;
    @VisibleForTesting static final int MESSAGE_UPDATE_METADATA_TIMEOUT = 4;

    /* 1 minute timeout for primary device reconnection in Private Broadcast case */
    private static final int DIALING_OUT_TIMEOUT_MS = 60000;

    // 30 minutes timeout for monitoring BIG resynchronization
    private static final Duration sBigMonitorTimeout = Duration.ofMinutes(30);

    // 5 minutes timeout for monitoring OOR broadcaster
    private static final Duration sOorMonitorTimeout = Duration.ofMinutes(5);

    // 5 seconds timeout for sync Lost notification
    private static final Duration sSyncLostTimeout = Duration.ofSeconds(5);

    // 5 minutes timeout for sync metadata update, the best is to stay consistent with OOR
    private static final Duration sUpdateMetadataTimeout = sOorMonitorTimeout;

    // 5 minutes timeout for past response, the best is to stay consistent with OOR
    @VisibleForTesting static final Duration sPastResponseTimeout = sOorMonitorTimeout;

    // 2 seconds timeout for autonomous inactivation monitor
    @VisibleForTesting static final Duration sAutoInactiveMonitorTimeout = Duration.ofSeconds(2);

    // 15 seconds timeout for adding source by URI
    @VisibleForTesting static final Duration sAddSourceByUriTimeout = Duration.ofSeconds(15);

    private enum PauseReason {
        SUSPENDED_BY_HOST, // Broadcast suspended by host; monitoring is blocked.
        BIG_MONITORING, // BIG monitoring is activated.
        OOR_MONITORING, // OOR monitoring is activated.
        RESUMING // Broadcast during resume.
    }

    private enum ModifyCallReason {
        API,
        RESUME,
        SUSPEND,
        REMOVE
    }

    private enum SyncStatus {
        NOT_SYNCED,
        SOURCE_ADDED,
        PA_SYNCED,
        BIS_SYNCED
    }

    private static BassClientService sService;

    private final Map<BluetoothDevice, BassClientStateMachine> mStateMachines = new HashMap<>();
    private final Object mSearchScanCallbackLock = new Object();
    private final Map<Integer, ScanResult> mCachedBroadcasts = new HashMap<>();

    private final List<Integer> mActiveSyncedSources = new ArrayList<>();
    private final Map<Integer, IPeriodicAdvertisingCallback> mPeriodicAdvCallbacksMap =
            new HashMap<>();
    private final PriorityQueue<SourceSyncRequest> mSourceSyncRequestsQueue =
            new PriorityQueue<>(sSourceSyncRequestComparator);
    private final Map<Integer, Integer> mSyncFailureCounter = new HashMap<>();
    private final Map<Integer, Integer> mBisDiscoveryCounterMap = new HashMap<>();
    private final List<AddSourceData> mPendingSourcesToAdd = new ArrayList<>();

    private final List<PendingSourceToAddByUri> mPendingSourcesToAddByUri = new ArrayList<>();

    private final Map<BluetoothDevice, List<Pair<Integer, Object>>> mPendingGroupOp =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mGroupManagedSources =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mActiveSourceMap = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Map<Integer, BluetoothLeBroadcastMetadata>>
            mBroadcastMetadataMap = new ConcurrentHashMap<>();
    private final Set<BluetoothDevice> mPausedBroadcastSinks = ConcurrentHashMap.newKeySet();
    private final Set<BluetoothDevice> mSinksToRestoreFromPeer = ConcurrentHashMap.newKeySet();
    /* Receiver, sourceId, PastResponseTimeout */
    private final Map<BluetoothDevice, Map<Integer, PastResponseTimeout>> mPastResponseTimeouts =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Integer> mSinksWaitingForMetadata = new HashMap<>();
    private final Map<Integer, PauseReason> mPausedBroadcastIds = new HashMap<>();
    private final Map<Integer, HashSet<BluetoothDevice>> mLocalBroadcastReceivers =
            new ConcurrentHashMap<>();
    private final BassScanCallbackWrapper mBassScanCallback = new BassScanCallbackWrapper();

    private final ScanController mScanController;
    private final Handler mHandler;
    private final HandlerThread mStateMachinesThread;
    private final Looper mStateMachinesLooper;
    private final HandlerThread mCallbackHandlerThread;
    private final Callbacks mCallbacks;

    @VisibleForTesting
    final Set<BluetoothDevice> mPendingNfcJoiningDevices = ConcurrentHashMap.newKeySet();

    private DialingOutTimeoutEvent mDialingOutTimeoutEvent = null;
    private final Map<Integer, ReactivateGroupMonitor> mReactivateGroupMonitors =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, Boolean> mEncryptionStates = new ConcurrentHashMap<>();

    private record PendingSourceToAddByUri(
            BluetoothDevice sink,
            String name,
            List<Byte> code,
            int broadcastId,
            Runnable timeout) {}

    @VisibleForTesting
    final BroadcastReceiver mEncryptionStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (BluetoothDevice.ACTION_ENCRYPTION_CHANGE.equals(action)) {
                        BluetoothDevice device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        if (device == null) {
                            return;
                        }
                        int transport =
                                intent.getIntExtra(
                                        BluetoothDevice.EXTRA_TRANSPORT,
                                        BluetoothDevice.TRANSPORT_AUTO);
                        if (transport != BluetoothDevice.TRANSPORT_LE) {
                            // BASS is a LE only profile
                            return;
                        }

                        boolean encrypted =
                                intent.getBooleanExtra(
                                        BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false);
                        int status =
                                intent.getIntExtra(
                                        BluetoothDevice.EXTRA_ENCRYPTION_STATUS,
                                        BluetoothStatusCodes.ERROR_UNKNOWN);

                        boolean encryptionState =
                                (encrypted && status == BluetoothStatusCodes.SUCCESS);
                        Log.d(
                                TAG,
                                "Received ACTION_ENCRYPTION_CHANGE for "
                                        + device
                                        + " state: "
                                        + encryptionState);
                        mEncryptionStates.put(device, encryptionState);
                        synchronized (mStateMachines) {
                            BassClientStateMachine sm = mStateMachines.get(device);
                            if (sm != null) {
                                sm.sendMessage(
                                        BassClientStateMachine.ENCRYPTION_STATE_CHANGED,
                                        encryptionState
                                                ? BassConstants.ENCRYPTED
                                                : BassConstants.NOT_ENCRYPTED);
                            }
                        }
                    }
                }
            };

    @VisibleForTesting
    final BroadcastReceiver mNfcJoinReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    if (!AuracastUtils.ACTION_CONNECT_STREAM.equals(action)) {
                        return;
                    }

                    String uriStr = intent.getStringExtra(AuracastUtils.EXTRA_METADATA);
                    if (uriStr == null) return;

                    // Directly parse the URI
                    BroadcastStreamInfo info = AuracastUtils.parseBroadcastURI(uriStr);

                    if (info == null) {
                        Log.e(TAG, "URI is missing Broadcast_Name. Cannot join.");
                        return;
                    }

                    String bName = info.getName();
                    int bId = info.getBroadcastId();
                    byte[] bCode = info.getCode();

                    final var leAudio = getAdapterService().getLeAudioService();
                    if (leAudio.isEmpty()) {
                        Log.e(TAG, "No available LeAudioService to determine primary device.");
                        return;
                    }

                    // Track pending devices and initiate the search/join
                    List<BluetoothDevice> connectedSinks = getConnectedDevices();
                    for (BluetoothDevice sink : connectedSinks) {
                        // Only trigger the group operation on the primary device
                        if (leAudio.get().isPrimaryDevice(sink)) {
                            addSourceByUri(sink, bName, bId, bCode);
                            mPendingNfcJoiningDevices.addAll(leAudio.get().getGroupDevices(sink));
                            break;
                        }
                    }

                    NotificationManager nm = context.getSystemService(NotificationManager.class);
                    nm.cancel(AuracastUtils.NOTIFICATION_ID);
                }
            };

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
    /* BluetoothDevice, broadcastId, SyncStatus */
    private final Map<BluetoothDevice, Map<Integer, SyncStatus>> mSyncStatusMap =
            new ConcurrentHashMap<>();
    private boolean mIsForegroundScan = false;
    // TODO Delete mIsBackgroundScan on leaudioBroadcastAlwaysUseBackgroundScanner flag cleanup
    private boolean mIsBackgroundScan = false;
    private boolean mIsAssistantActive = false;
    private boolean mIsAllowedContextOfActiveGroupModified = false;
    Optional<Integer> mUnicastSourceStreamStatus = Optional.empty();

    private final Map<Integer, LtvData.AudioActiveState> mAudioActiveStates =
            new ConcurrentHashMap<>();
    private volatile boolean mIsUnicastAutoResuming = false;

    private static final int LOG_NB_EVENTS = 100;
    private static final BluetoothEventLogger sEventLogger =
            new BluetoothEventLogger(LOG_NB_EVENTS, TAG + " event log");

    private class BassScanCallbackWrapper extends IScannerCallback.Stub {
        private static final int SCANNER_ID_NOT_INITIALIZED = -2;
        private static final int SCANNER_ID_INITIALIZING = -1;

        private final List<ScanFilter> mBaasUuidFilters = new ArrayList<>();
        private int mScannerId = SCANNER_ID_NOT_INITIALIZED;

        void registerAndStartScan(List<ScanFilter> filters) {
            synchronized (this) {
                if (mScannerId == SCANNER_ID_INITIALIZING) {
                    Log.d(TAG, "registerAndStartScan: Scanner is already initializing");
                    if (mIsForegroundScan) {
                        mCallbacks.notifySearchStartFailed(
                                BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    }
                    return;
                }
                if (filters != null) {
                    mBaasUuidFilters.addAll(filters);
                }

                if (!BassUtils.containUuid(mBaasUuidFilters, LeAudioConstants.BAAS_UUID)) {
                    byte[] serviceData = {0x00, 0x00, 0x00}; // Broadcast_ID
                    byte[] serviceDataMask = {0x00, 0x00, 0x00};

                    mBaasUuidFilters.add(
                            new ScanFilter.Builder()
                                    .setServiceData(
                                            LeAudioConstants.BAAS_UUID,
                                            serviceData,
                                            serviceDataMask)
                                    .build());
                }

                mScannerId = SCANNER_ID_INITIALIZING;
                var source = getAttributionSource();

                ScanSettings settings =
                        new ScanSettings.Builder()
                                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .setLegacy(false)
                                .build();
                runOnScanThread(
                        () ->
                                mScanController.registerAndStartScanInternal(
                                        this, source, settings, mBaasUuidFilters));
            }
        }

        void stopScanAndUnregister() {
            synchronized (this) {
                final var scannerIdToStop = mScannerId;
                runOnScanThread(
                        () -> {
                            mScanController.stopScan(scannerIdToStop);
                            mScanController.unregisterScanner(scannerIdToStop);
                        });
                mBaasUuidFilters.clear();
                mScannerId = SCANNER_ID_NOT_INITIALIZED;
            }
        }

        boolean isBroadcastAudioAnnouncementScanActive() {
            synchronized (this) {
                return mScannerId >= 0;
            }
        }

        boolean isBroadcastAudioAnnouncementScanInitializing() {
            synchronized (this) {
                return mScannerId == SCANNER_ID_INITIALIZING;
            }
        }

        @Override
        public void onScannerRegistered(int status, int scannerId) {
            Log.d(TAG, "onScannerRegistered: Status: " + status + ", id:" + scannerId);
            synchronized (this) {
                if (status != ScanCallback.NO_ERROR) {
                    Log.e(TAG, "onScannerRegistered: Scanner registration failed: " + status);
                    if (mIsForegroundScan) {
                        mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                    }
                    mScannerId = SCANNER_ID_NOT_INITIALIZED;
                    mIsForegroundScan = false;
                    mIsBackgroundScan = false;
                    return;
                }
                mScannerId = scannerId;

                // `ScanController#onScannerRegistered` starts the scan for us
                if (mIsForegroundScan) {
                    mCallbacks.notifySearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                }
            }
        }

        @Override
        public void onScanResult(ScanResult result) {
            Log.d(TAG, "onScanResult:" + result);
            synchronized (this) {
                if (mScannerId < 0) {
                    Log.d(TAG, "onScanResult: Ignoring result as scan stopped.");
                    return;
                }
            }

            Integer broadcastId = LeAudioUtils.getBroadcastId(result);
            if (broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
                Log.d(TAG, "onScanResult: Broadcast ID is invalid");
                return;
            }

            String broadcastName = BassUtils.getBroadcastName(result.getScanRecord());
            synchronized (mPendingSourcesToAddByUri) {
                ListIterator<PendingSourceToAddByUri> iterator =
                        mPendingSourcesToAddByUri.listIterator();
                while (iterator.hasNext()) {
                    PendingSourceToAddByUri pending = iterator.next();
                    if (pending.broadcastId() == LeAudioConstants.INVALID_BROADCAST_ID
                            && broadcastName != null
                            && broadcastName.equals(pending.name())) {
                        Log.i(TAG, "onScanResult: Matched pending name search: " + broadcastName);
                        iterator.set(
                                new PendingSourceToAddByUri(
                                        pending.sink(),
                                        pending.name(),
                                        pending.code(),
                                        broadcastId,
                                        pending.timeout()));
                    }
                }
            }

            Log.d(TAG, "Broadcast Source Found:" + result.getDevice());

            synchronized (mSearchScanCallbackLock) {
                if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                    if (!mIsForegroundScan && !shouldSync(broadcastId)) {
                        return;
                    }
                } else {
                    if (!mIsForegroundScan
                            && (!mIsBackgroundScan
                                    || (!isWaitingForMetadata(broadcastId)
                                            && !isOorMonitoringPauseReason(broadcastId)
                                            && !isPendingSourceToAddByUri(broadcastId)))) {
                        return;
                    }
                }

                if (!mCachedBroadcasts.containsKey(broadcastId)) {
                    Log.d(TAG, "selectBroadcastSource: broadcastId " + broadcastId);
                    mCachedBroadcasts.put(broadcastId, result);
                    addSelectSourceRequest(broadcastId, /* hasPriority */ false);
                } else {
                    if (mTimeoutHandler.isStarted(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT)) {
                        mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_SYNC_LOST_TIMEOUT, sSyncLostTimeout);
                    }
                    if (shouldSync(broadcastId)) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                }
            }
        }

        private boolean shouldSync(int broadcastId) {
            return isOorMonitoringPauseReason(broadcastId)
                    || (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                            && isWaitingForMetadata(broadcastId))
                    || isWaitingForPast(broadcastId)
                    || isAnnouncementMonitored(broadcastId)
                    || isPendingSourceToAddByUri(broadcastId);
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
            mIsBackgroundScan = false;
            mIsForegroundScan = false;
            informConnectedDeviceAboutScanOffloadStop();
        }
    }

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
                                    handleTimeoutMessage(msg, broadcastId);
                                }
                            });
        }

        private void handleTimeoutMessage(Message msg, int broadcastId) {
            switch (msg.what) {
                case MESSAGE_SYNC_LOST_TIMEOUT:
                    {
                        Log.d(TAG, "MESSAGE_SYNC_LOST_TIMEOUT");
                        // fall through
                    }
                case MESSAGE_OOR_MONITOR_TIMEOUT:
                    {
                        Log.d(TAG, "MESSAGE_OOR_MONITOR_TIMEOUT");
                        if (getActiveSyncedSources()
                                .contains(getSyncHandleForBroadcastId(broadcastId))) {
                            break;
                        }
                        // Clear from cache to make possible sync again
                        // (only during active searching)
                        synchronized (mSearchScanCallbackLock) {
                            if (isAnySearchInProgress()) {
                                mCachedBroadcasts.remove(broadcastId);
                            }
                        }
                        Log.d(TAG, "Notify broadcast source lost, broadcast id: " + broadcastId);
                        if (mIsForegroundScan) {
                            mCallbacks.notifySourceLost(broadcastId);
                        }
                        if (!isOorMonitoringPauseReason(broadcastId)) {
                            // In case of syncLost
                            break;
                        }
                        // fall through
                    }
                case MESSAGE_BIG_MONITOR_TIMEOUT:
                    {
                        Log.d(TAG, "MESSAGE_BIG_MONITOR_TIMEOUT");
                        stopSourceReceivers(broadcastId);
                        break;
                    }
                case MESSAGE_UPDATE_METADATA_TIMEOUT:
                    {
                        Log.d(TAG, "MESSAGE_UPDATE_METADATA_TIMEOUT");
                        synchronized (mSinksWaitingForMetadata) {
                            mSinksWaitingForMetadata
                                    .entrySet()
                                    .removeIf(entry -> entry.getValue() == broadcastId);
                            if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                                    && mSinksWaitingForMetadata.isEmpty()
                                    && mIsBackgroundScan) {
                                stopSearchingForSources(/* foreground= */ false);
                            }
                        }
                        stopBackgroundSearching();
                        break;
                    }
                default:
                    break;
            }
            Handler handler = getOrCreateHandler(broadcastId);
            if (!handler.hasMessagesOrCallbacks()) {
                mHandlers.remove(broadcastId);
            }
        }

        void start(int broadcastId, int msg, Duration duration) {
            Handler handler = getOrCreateHandler(broadcastId);
            Log.d(
                    TAG,
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
            if (!handler.hasMessagesOrCallbacks()) {
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
                if (!handler.hasMessagesOrCallbacks()) {
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
    }

    private class PastResponseTimeout implements Runnable {
        BluetoothDevice mSink;
        int mSourceId;
        int mBroadcastId;

        PastResponseTimeout(BluetoothDevice sink, int sourceId, int broadcastId) {
            mSink = sink;
            mSourceId = sourceId;
            mBroadcastId = broadcastId;
        }

        @Override
        public void run() {
            Log.w(TAG, "PastResponseTimeout: timeout expired for broadcast: " + mBroadcastId);
            synchronized (mPastResponseTimeouts) {
                Map<Integer, PastResponseTimeout> timeouts = mPastResponseTimeouts.get(mSink);
                if (timeouts != null) {
                    timeouts.remove(mSourceId);
                    stopBackgroundSearching();
                    if (timeouts.isEmpty()) {
                        mPastResponseTimeouts.remove(mSink);
                    }
                }
            }
        }
    }

    public boolean isEncrypted(BluetoothDevice device) {
        return mEncryptionStates.getOrDefault(device, false);
    }

    public BassClientService(AdapterService adapterService, ScanController scanController) {
        this(adapterService, scanController, null);
    }

    @VisibleForTesting
    BassClientService(AdapterService adapterService, ScanController scanController, Looper looper) {
        super(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT, adapterService);
        mScanController = requireNonNull(scanController);

        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
            mStateMachinesThread = new HandlerThread("BassClientService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
            mCallbackHandlerThread = new HandlerThread(TAG);
            mCallbackHandlerThread.start();
            mCallbacks = new Callbacks(mCallbackHandlerThread.getLooper());
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
            mCallbackHandlerThread = null;
            mCallbacks = new Callbacks(looper);
        }

        setBassClientService(this);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ENCRYPTION_CHANGE);
        if (Flags.leaudioBassReadCharacteristicsAfterEncryption())
            adapterService.registerReceiver(
                    mEncryptionStateReceiver, filter, BLUETOOTH_CONNECT, null);

        IntentFilter nfcFilter = new IntentFilter(AuracastUtils.ACTION_CONNECT_STREAM);
        if (Flags.leaudioAuracastCredentialExtension()) {
            registerReceiver(mNfcJoinReceiver, nfcFilter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false);
    }

    private record SourceSyncRequest(
            PeriodicAdvertisementResult paResult, boolean hasPriority, int syncFailureCounter) {
        int getRssi() {
            return paResult.getRssi();
        }

        @Override
        public String toString() {
            return "SourceSyncRequest{"
                    + "paResult="
                    + paResult
                    + ", hasPriority="
                    + hasPriority
                    + ", syncFailureCounter="
                    + syncFailureCounter
                    + '}';
        }
    }

    private static final Comparator<SourceSyncRequest> sSourceSyncRequestComparator =
            (ssr1, ssr2) -> {
                if (ssr1.hasPriority && !ssr2.hasPriority) {
                    return -1;
                } else if (!ssr1.hasPriority && ssr2.hasPriority) {
                    return 1;
                } else if (ssr1.syncFailureCounter != ssr2.syncFailureCounter) {
                    return Integer.compare(ssr1.syncFailureCounter, ssr2.syncFailureCounter);
                } else {
                    return Integer.compare(ssr2.getRssi(), ssr1.getRssi());
                }
            };

    private record AddSourceData(
            BluetoothDevice sink, BluetoothLeBroadcastMetadata sourceMetadata, boolean isGroupOp) {}

    private void runOnScanThread(Runnable action) {
        if (mScanController.isOnScanThread()) {
            action.run();
        } else {
            mScanController.doOnScanThread(action);
        }
    }

    void updatePeriodicAdvertisementResultMap(
            BluetoothDevice device,
            int syncHandle,
            int advSid,
            int advInterval,
            int bId,
            int rssi,
            PublicBroadcastData pbData,
            String broadcastName) {
        Log.d(
                TAG,
                "updatePeriodicAdvertisementResultMap:"
                        + (" device: " + device)
                        + (", syncHandle: " + syncHandle)
                        + (", advSid: " + advSid)
                        + (", advInterval: " + advInterval)
                        + (", broadcastId: " + bId)
                        + (", rssi: " + rssi)
                        + (", broadcastName: " + broadcastName)
                        + (", syncHandleToDeviceMap: " + mSyncHandleToDeviceMap)
                        + (", periodicAdvertisementResultMap: " + mPeriodicAdvertisementResultMap));
        Map<Integer, PeriodicAdvertisementResult> paResMap =
                mPeriodicAdvertisementResultMap.get(device);
        if (paResMap == null
                || (bId != LeAudioConstants.INVALID_BROADCAST_ID && !paResMap.containsKey(bId))) {
            Log.d(TAG, "PAResmap: add >>>");
            mSyncHandleToDeviceMap.put(syncHandle, device);
            updateSyncHandleForBroadcastId(syncHandle, bId);
            PeriodicAdvertisementResult paRes =
                    new PeriodicAdvertisementResult(
                            device,
                            syncHandle,
                            advSid,
                            advInterval,
                            bId,
                            rssi,
                            pbData,
                            broadcastName);
            if (paRes != null) {
                paRes.print();
                mPeriodicAdvertisementResultMap.putIfAbsent(device, new HashMap<>());
                mPeriodicAdvertisementResultMap.get(device).put(bId, paRes);
            }
        } else {
            Log.d(TAG, "PAResmap: update >>>");
            if (bId == LeAudioConstants.INVALID_BROADCAST_ID) {
                // Update when onSyncEstablished, try to retrieve valid broadcast id
                bId = getBroadcastIdForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);

                if (bId == LeAudioConstants.INVALID_BROADCAST_ID || !paResMap.containsKey(bId)) {
                    Log.e(TAG, "PAResmap: error! no valid broadcast id found>>>");
                    return;
                }

                int oldBroadcastId = getBroadcastIdForSyncHandle(syncHandle);
                if (oldBroadcastId != LeAudioConstants.INVALID_BROADCAST_ID
                        && oldBroadcastId != bId) {
                    Log.d(
                            TAG,
                            "updatePeriodicAdvertisementResultMap: SyncEstablished on the same"
                                    + (" syncHandle=" + syncHandle)
                                    + ", before syncLost. Notify broadcast source lost, broadcast"
                                    + (" id: " + oldBroadcastId));
                    if (mIsForegroundScan) {
                        mCallbacks.notifySourceLost(oldBroadcastId);
                    }
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
                mSyncHandleToDeviceMap.remove(BassConstants.PENDING_SYNC_HANDLE);
                mSyncHandleToDeviceMap.put(syncHandle, device);
                paRes.updateSyncHandle(syncHandle);
                if (paRes.getBroadcastId() != LeAudioConstants.INVALID_BROADCAST_ID) {
                    // broadcast successfully synced
                    // update the sync handle for the broadcast source
                    updateSyncHandleForBroadcastId(syncHandle, paRes.getBroadcastId());
                }
            }
            if (advInterval != BassConstants.INVALID_ADV_INTERVAL) {
                paRes.updateAdvInterval(advInterval);
            }
            if (bId != LeAudioConstants.INVALID_BROADCAST_ID) {
                paRes.updateBroadcastId(bId);
            }
            if (rssi != BluetoothLeBroadcastMetadata.RSSI_UNKNOWN) {
                paRes.updateRssi(rssi);
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
        Log.d(TAG, ">>mPeriodicAdvertisementResultMap" + mPeriodicAdvertisementResultMap);
    }

    PeriodicAdvertisementResult getPeriodicAdvertisementResult(
            BluetoothDevice device, int broadcastId) {
        if (broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
            Log.e(TAG, "getPeriodicAdvertisementResult: invalid broadcast id");
            return null;
        }

        if (mPeriodicAdvertisementResultMap.containsKey(device)) {
            return mPeriodicAdvertisementResultMap.get(device).get(broadcastId);
        }
        return null;
    }

    void clearNotifiedFlags() {
        Log.d(TAG, "clearNotifiedFlags");
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
        Log.d(TAG, "updateBase : mSyncHandleToBaseDataMap>>");
        mSyncHandleToBaseDataMap.put(syncHandleMap, base);
    }

    BaseData getBase(int syncHandleMap) {
        BaseData base = mSyncHandleToBaseDataMap.get(syncHandleMap);
        return base;
    }

    void removeActiveSyncedSource(Integer syncHandle) {
        Log.d(TAG, "removeActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle == null) {
            // remove all sources
            mActiveSyncedSources.clear();
        } else {
            mActiveSyncedSources.removeIf(e -> e.equals(syncHandle));
        }
        sEventLogger.logd(TAG, "Broadcast Source Unsynced: syncHandle= " + syncHandle);
    }

    void addActiveSyncedSource(Integer syncHandle) {
        Log.d(TAG, "addActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle != BassConstants.INVALID_SYNC_HANDLE) {
            if (!mActiveSyncedSources.contains(syncHandle)) {
                mActiveSyncedSources.add(syncHandle);
            }
        }
        sEventLogger.logd(TAG, "Broadcast Source Synced: syncHandle= " + syncHandle);
    }

    List<Integer> getActiveSyncedSources() {
        Log.d(TAG, "getActiveSyncedSources: sources num: " + mActiveSyncedSources.size());
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
        return new BassClientServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mUnicastSourceStreamStatus = Optional.empty();

        if (mDialingOutTimeoutEvent != null) {
            mHandler.removeCallbacks(mDialingOutTimeoutEvent);
            mDialingOutTimeoutEvent = null;
        }

        if (Flags.leaudioBassReadCharacteristicsAfterEncryption()) {
            getAdapterService().unregisterReceiver(mEncryptionStateReceiver);
        }

        if (Flags.leaudioAuracastCredentialExtension()) {
            try {
                unregisterReceiver(mNfcJoinReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "mNfcJoinReceiver not registered");
            }
        }
        mEncryptionStates.clear();
        mReactivateGroupMonitors.forEach((k, v) -> mHandler.removeCallbacks(v));
        mReactivateGroupMonitors.clear();
        mSyncStatusMap.clear();

        synchronized (mPastResponseTimeouts) {
            for (Map<Integer, PastResponseTimeout> timeouts : mPastResponseTimeouts.values()) {
                for (PastResponseTimeout timeout : timeouts.values()) {
                    mHandler.removeCallbacks(timeout);
                }
            }
            mPastResponseTimeouts.clear();
        }

        if (mIsAssistantActive) {
            getAdapterService()
                    .getLeAudioService()
                    .ifPresent(leAudio -> leAudio.activeBroadcastAssistantNotification(false));
            mIsAssistantActive = false;
        }

        if (mIsAllowedContextOfActiveGroupModified) {
            getAdapterService()
                    .getLeAudioService()
                    .ifPresent(
                            leAudio ->
                                    leAudio.setActiveGroupAllowedContextMask(
                                            BluetoothLeAudio.CONTEXTS_ALL,
                                            BluetoothLeAudio.CONTEXTS_ALL));
            mIsAllowedContextOfActiveGroupModified = false;
        }

        synchronized (mStateMachines) {
            for (BassClientStateMachine sm : mStateMachines.values()) {
                BassObjectsFactory.getInstance().destroyStateMachine(sm);
            }
            mStateMachines.clear();
        }

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }
        if (mCallbackHandlerThread != null) {
            try {
                mCallbackHandlerThread.quitSafely();
                mCallbackHandlerThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }

        mHandler.removeCallbacksAndMessages(null);
        mTimeoutHandler.stopAll();

        setBassClientService(null);
        synchronized (mSearchScanCallbackLock) {
            if (isAnySearchInProgress()) {
                mBassScanCallback.stopScanAndUnregister();
            }
            mIsForegroundScan = false;
            mIsBackgroundScan = false;
            clearAllSyncData();
        }

        mLocalBroadcastReceivers.clear();
        mPendingGroupOp.clear();
        mBroadcastMetadataMap.clear();
        mPausedBroadcastSinks.clear();
        mSinksToRestoreFromPeer.clear();

        synchronized (mPendingSourcesToAddByUri) {
            for (PendingSourceToAddByUri pending : mPendingSourcesToAddByUri) {
                mHandler.removeCallbacks(pending.timeout());
            }
            mPendingSourcesToAddByUri.clear();
        }
        mPendingNfcJoiningDevices.clear();
        mAudioActiveStates.clear();
        mIsUnicastAutoResuming = false;
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
        return LeAudioConstants.INVALID_BROADCAST_ID;
    }

    void updateSyncHandleForBroadcastId(int syncHandle, int broadcastId) {
        mSyncHandleToBroadcastIdMap.entrySet().removeIf(entry -> entry.getValue() == broadcastId);
        mSyncHandleToBroadcastIdMap.put(syncHandle, broadcastId);
        Log.d(TAG, "Updated mSyncHandleToBroadcastIdMap: " + mSyncHandleToBroadcastIdMap);
    }

    private static synchronized void setBassClientService(BassClientService instance) {
        Log.d(TAG, "setBassClientService(): set to: " + instance);
        sService = instance;
    }

    private void enqueueSourceGroupOp(BluetoothDevice sink, Integer msgId, Object obj) {
        Log.d(TAG, "enqueueSourceGroupOp device: " + sink + ", msgId: " + msgId);

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
        return switch (status) {
            case BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST,
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST,
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST,
                    BluetoothStatusCodes.REASON_SYSTEM_POLICY ->
                    true;
            default -> false;
        };
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
        Log.d(
                TAG,
                "checkForPendingGroupOpRequest:"
                        + (" device: " + sink)
                        + (" reason: " + reason)
                        + (" reqMsg: " + reqMsg));

        AtomicBoolean shouldUpdateAssistantActive = new AtomicBoolean(false);
        mPendingGroupOp.computeIfPresent(
                sink,
                (key, opsToModify) -> {
                    List<Pair<Integer, Object>> operations = new ArrayList<>(opsToModify);

                    switch (reqMsg) {
                        case BassClientStateMachine.ADD_BCAST_SOURCE -> {
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
                        }
                        case BassClientStateMachine.REMOVE_BCAST_SOURCE -> {
                            // Identify the operation by operation type and sourceId
                            removeMatchingOperation(operations, reqMsg, obj);
                            Integer sourceId = (Integer) obj;
                            setSourceGroupManaged(sink, sourceId, false);
                        }
                        default -> {}
                    }
                    return operations;
                });

        if (shouldUpdateAssistantActive.get()
                && !isAnyPendingAddSourceOperation()
                && mIsAssistantActive
                && mPausedBroadcastSinks.isEmpty()) {
            mIsAssistantActive = false;
            mUnicastSourceStreamStatus = Optional.empty();
            getAdapterService()
                    .getLeAudioService()
                    .ifPresent(leAudio -> leAudio.activeBroadcastAssistantNotification(false));
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
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            return false;
        }

        return (leAudio.get().getActiveGroupId() != LE_AUDIO_GROUP_ID_INVALID)
                && leAudio.get().getActiveDevices().contains(device);
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
                BassClientStateMachine sm = mStateMachines.get(device);
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
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            return;
        }

        /* Don't bother active group (external broadcaster scenario) with SOUND EFFECTS
         * and UNSPECIFIED context type
         */
        if (!mIsAllowedContextOfActiveGroupModified && isDevicePartOfActiveUnicastGroup(sink)) {
            leAudio.get()
                    .setActiveGroupAllowedContextMask(
                            BluetoothLeAudio.CONTEXTS_ALL
                                    & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                            | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS),
                            BluetoothLeAudio.CONTEXTS_ALL);
            mIsAllowedContextOfActiveGroupModified = true;
        }
    }

    private void checkAndResetGroupAllowedContextMask() {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            return;
        }

        /* Restore allowed context mask for Unicast */
        if (mIsAllowedContextOfActiveGroupModified
                && !hasAnyConnectedDeviceExternalBroadcastSource()
                && !isAnyConnectedDeviceSwitchingSource()) {
            leAudio.get()
                    .setActiveGroupAllowedContextMask(
                            BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
            mIsAllowedContextOfActiveGroupModified = false;
        }
    }

    /**
     * During a unicast stream, if the remote device synchronizes to a broadcast, it may release its
     * ASEs without proper stream closing. In such a case, Android disconnects such a device from
     * the Audio Framework, making it INACTIVE.
     *
     * <p>Below mechanism allows BASS to detect this situation. If it is determined that the Stream
     * was dropped due to the Broadcast Activities, BASS can re-activate the device to the Audio
     * Framework, which improves the user experience, e.g. the user can still use the device to pick
     * up a phone call or control the volume.
     *
     * <p>This mechanism monitors autonomous ASE releases together with add/modify source operations
     * or broadcast sync advancing within a 2-second window.
     */
    private class ReactivateGroupMonitor implements Runnable {
        private final int mGroupId;
        final boolean mInactivated;

        ReactivateGroupMonitor(int groupId, boolean inactivated) {
            mGroupId = groupId;
            mInactivated = inactivated;
        }

        @Override
        public void run() {
            mReactivateGroupMonitors.remove(mGroupId);
        }
    }

    private void startReactivateGroupMonitor(BluetoothDevice device, boolean inactivated) {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.d(TAG, "startReactivateGroupMonitor: LeAudioService is not available");
            return;
        }

        int groupId = leAudio.get().getGroupId(device);

        Log.v(
                TAG,
                "startReactivateGroupMonitor:"
                        + (" groupId: " + groupId)
                        + (", device: " + device)
                        + (", inactivated: " + inactivated));

        ReactivateGroupMonitor monitor = mReactivateGroupMonitors.remove(groupId);
        if (monitor != null) {
            mHandler.removeCallbacks(monitor);

            if (inactivated != monitor.mInactivated) {
                Log.d(TAG, "startReactivateGroupMonitor: group reactivation");
                leAudio.get().setActiveDevice(device);
                leAudio.get()
                        .setGroupAllowedContextMask(
                                groupId,
                                BluetoothLeAudio.CONTEXTS_ALL
                                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS),
                                BluetoothLeAudio.CONTEXTS_ALL);
                mIsAllowedContextOfActiveGroupModified = true;
                return;
            }
        }

        if (inactivated || (groupId == leAudio.get().getActiveGroupId())) {
            Log.v(TAG, "startReactivateGroupMonitor: enable monitor");
            ReactivateGroupMonitor newMonitor = new ReactivateGroupMonitor(groupId, inactivated);
            mReactivateGroupMonitors.put(groupId, newMonitor);
            mHandler.postDelayed(newMonitor, sAutoInactiveMonitorTimeout.toMillis());
        }
    }

    public void notifyLeAudioGroupAutonomousInactivated(BluetoothDevice leadDevice) {
        startReactivateGroupMonitor(leadDevice, /* inactivated */ true);
    }

    /**
     * Determines the {@link SyncStatus} based on the provided {@link
     * BluetoothLeBroadcastReceiveState}. The status is determined in the following order of
     * precedence: 1. {@link SyncStatus#BIS_SYNCED} if the receive state is synced to BIS. 2. {@link
     * SyncStatus#PA_SYNCED} if the PA sync state is synchronized. 3. {@link
     * SyncStatus#SOURCE_ADDED} if a source device is present. 4. Defaults to {@link
     * SyncStatus#NOT_SYNCED} otherwise.
     *
     * @param receiveState The {@link BluetoothLeBroadcastReceiveState} to evaluate.
     * @return The determined {@link SyncStatus}.
     */
    private static SyncStatus GetSyncStatusFromReceiveState(
            BluetoothLeBroadcastReceiveState receiveState) {
        SyncStatus syncStatus = SyncStatus.NOT_SYNCED;
        if (isReceiveStateSyncedToBis(receiveState)) {
            syncStatus = SyncStatus.BIS_SYNCED;
        } else if (receiveState.getPaSyncState()
                == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED) {
            syncStatus = SyncStatus.PA_SYNCED;
        } else if (!isEmptyBluetoothDevice(receiveState.getSourceDevice())) {
            syncStatus = SyncStatus.SOURCE_ADDED;
        }
        return syncStatus;
    }

    /**
     * Checks if the synchronization state for a given sink and its broadcast source has advanced.
     *
     * <p>This method tracks the synchronization progress for each sink-broadcast pair. It compares
     * the previously stored sync status with the new status to determine if there has been an
     * improvement (e.g., moving from PA synced to BIS synced).
     *
     * @param sink The sink (receiver) device.
     * @param receiveState The current receive state for the broadcast source.
     * @return {@code true} if the sync state has advanced (e.g., from PA_SYNCED to BIS_SYNCED),
     *     {@code false} otherwise. Also returns {@code false} if the state has not changed or has
     *     regressed.
     */
    @VisibleForTesting
    boolean isBroadcastSyncAdvancing(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState) {
        SyncStatus newSyncStatus = GetSyncStatusFromReceiveState(receiveState);

        if (newSyncStatus == SyncStatus.NOT_SYNCED) {
            HashSet<Integer> syncedBroadcastIds =
                    getAllSources(sink).stream()
                            .map(BluetoothLeBroadcastReceiveState::getBroadcastId)
                            .collect(Collectors.toCollection(HashSet::new));
            if (syncedBroadcastIds.isEmpty()) {
                mSyncStatusMap.remove(sink);
            } else {
                mSyncStatusMap.computeIfPresent(
                        sink,
                        (k, v) -> {
                            v.entrySet().removeIf(e -> !syncedBroadcastIds.contains(e.getKey()));
                            return v.isEmpty() ? null : v;
                        });
            }
            return false;
        }

        int broadcastId = receiveState.getBroadcastId();
        SyncStatus oldSyncStatus =
                mSyncStatusMap
                        .getOrDefault(sink, Collections.emptyMap())
                        .getOrDefault(broadcastId, SyncStatus.NOT_SYNCED);

        mSyncStatusMap
                .computeIfAbsent(sink, k -> new ConcurrentHashMap<>())
                .put(broadcastId, newSyncStatus);

        return (newSyncStatus.compareTo(oldSyncStatus) > 0);
    }

    void syncRequestForPast(BluetoothDevice sink, int broadcastId, int sourceId) {
        Log.d(
                TAG,
                "syncRequestForPast:"
                        + (" device: " + sink)
                        + (", broadcastId: " + broadcastId)
                        + (", sourceId: " + sourceId));

        synchronized (mPastResponseTimeouts) {
            PastResponseTimeout timeout = new PastResponseTimeout(sink, sourceId, broadcastId);
            if (mPastResponseTimeouts
                            .computeIfAbsent(sink, k -> new ConcurrentHashMap<>())
                            .putIfAbsent(sourceId, timeout)
                    == null) {
                mHandler.postDelayed(timeout, sPastResponseTimeout.toMillis());
                Log.d(TAG, "syncRequestForPast: timeout scheduled");
            }
        }
        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
    }

    private void syncRequestForMetadata(BluetoothDevice sink, int broadcastId) {
        Log.d(TAG, "syncRequestForMetadata: sink: " + sink + ", broadcastId: " + broadcastId);

        synchronized (mSinksWaitingForMetadata) {
            mSinksWaitingForMetadata.put(sink, broadcastId);
        }
        if (isLocalBroadcast(broadcastId)) {
            Log.d(TAG, "syncRequestForMetadata: local broadcast, updateMetadata");
            final var leAudio = getAdapterService().getLeAudioService();
            if (leAudio.isPresent()) {
                BluetoothLeBroadcastMetadata metadata =
                        leAudio.get().getBroadcastMetadata(broadcastId);
                if (metadata != null) {
                    updateMetadata(metadata);
                }
            }
        } else {
            mTimeoutHandler.start(
                    broadcastId, MESSAGE_UPDATE_METADATA_TIMEOUT, sUpdateMetadataTimeout);
            if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                addSelectSourceRequest(broadcastId, /* hasPriority */ true);
            } else {
                startSearchingForSources(Collections.emptyList(), /* foreground= */ false);
            }
        }
    }

    private void updateMetadata(BluetoothLeBroadcastMetadata metadata) {
        boolean isSinkWaitingForMetadataChanged = false;
        synchronized (mSinksWaitingForMetadata) {
            Iterator<Map.Entry<BluetoothDevice, Integer>> iterator =
                    mSinksWaitingForMetadata.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BluetoothDevice, Integer> entry = iterator.next();
                int broadcastId = entry.getValue();
                if (broadcastId != metadata.getBroadcastId()) {
                    continue;
                }
                BluetoothDevice sink = entry.getKey();
                Optional<BluetoothLeBroadcastReceiveState> rs = Optional.empty();
                BassClientStateMachine sm = mStateMachines.get(sink);
                if (sm != null) {
                    rs =
                            sm.getAllSources().stream()
                                    .filter(e -> e.getBroadcastId() == metadata.getBroadcastId())
                                    .findAny();
                }
                if (rs.isPresent()) {
                    Log.d(TAG, "updateMetadata: sink: " + sink + ", broadcastId: " + broadcastId);
                    storeSinkMetadata(sink, broadcastId, metadata);
                    Message message = sm.obtainMessage(BassClientStateMachine.UPDATE_METADATA);
                    message.arg1 = rs.get().getSourceId();
                    message.obj = metadata;
                    sm.sendMessage(message);
                }
                iterator.remove();
                mTimeoutHandler.stop(broadcastId, MESSAGE_UPDATE_METADATA_TIMEOUT);
                isSinkWaitingForMetadataChanged = true;
            }
            if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                    && mSinksWaitingForMetadata.isEmpty()
                    && mIsBackgroundScan) {
                stopSearchingForSources(/* foreground= */ false);
            }
        }
        if (isSinkWaitingForMetadataChanged) {
            stopBackgroundSearching();
        }
    }

    private BluetoothLeBroadcastMetadata getMetadataFromSinkWithBroadcastId(
            BluetoothDevice sink, int broadcastId) {
        Map<Integer, BluetoothLeBroadcastMetadata> entry =
                mBroadcastMetadataMap.getOrDefault(sink, Collections.emptyMap());

        return entry.get(broadcastId);
    }

    /**
     * This is used to configure which action the BIG Channel Map should consider channel
     * classification from sink device
     */
    public enum SetBigChannelMapClassificationAction {
        ADD(0x00),
        DELETE(0x01),
        CLEAR(0x02),
        NO_ACTION(0xFF);

        private final int mValue;

        SetBigChannelMapClassificationAction(int value) {
            mValue = value;
        }

        public int getValue() {
            return mValue;
        }

        public static String toString(int value) {
            for (SetBigChannelMapClassificationAction action : values()) {
                if (action.getValue() == value) {
                    return action.name();
                }
            }
            return "NO_ACTION";
        }
    }

    /**
     * Checks the PA (Periodic Advertising) sync status for BIG (Broadcast Isochronous Group)
     * channel map classification based on the provided {@link BluetoothLeBroadcastReceiveState}.
     *
     * @param sink The Bluetooth device of the sink.
     * @param broadcastId The Broadcast ID.
     * @param receiveState The current {@link BluetoothLeBroadcastReceiveState} of the broadcast.
     * @return An integer representing the action to be taken: {@link
     *     SetBigChannelMapClassificationAction#ADD} if transitioned to PA_SYNCED, {@link
     *     SetBigChannelMapClassificationAction#DELETE} if transitioned to NOT_SYNCED, or {@link
     *     SetBigChannelMapClassificationAction#NO_ACTION} if no change or other status.
     */
    public int checkPaSyncStatusForBigChannelMapClassification(
            BluetoothDevice sink, int broadcastId, BluetoothLeBroadcastReceiveState receiveState) {
        // Read the oldSyncStatus from the mSyncStatusMap for comparison with newSyncStatus.
        SyncStatus oldSyncStatus =
                mSyncStatusMap
                        .getOrDefault(sink, Collections.emptyMap())
                        .getOrDefault(broadcastId, SyncStatus.NOT_SYNCED);
        SyncStatus newSyncStatus = GetSyncStatusFromReceiveState(receiveState);

        int action = SetBigChannelMapClassificationAction.NO_ACTION.getValue();

        // status not changed, return NO_ACTION
        if (newSyncStatus.compareTo(oldSyncStatus) == 0) {
            return action;
        }

        if ((newSyncStatus == SyncStatus.PA_SYNCED)
                || (Flags.leaudioBroadcastSourceChannelMapClassificationImprovement()
                        && newSyncStatus == SyncStatus.BIS_SYNCED)) {
            /* PA state transitioned to PA_SYNCED or BIS_SYNCED and synced to own broadcast source
             * action determined: ADD */
            action = SetBigChannelMapClassificationAction.ADD.getValue();

        } else if (newSyncStatus == SyncStatus.NOT_SYNCED) {
            /* PA state transitioned to NOT_SYNCED and lost synced to own broadcast source
             * action determined: DELETE */
            action = SetBigChannelMapClassificationAction.DELETE.getValue();
        }

        Log.d(
                TAG,
                "PA SyncStatus transitioned from "
                        + oldSyncStatus
                        + " to "
                        + newSyncStatus
                        + " for "
                        + sink
                        + ", action: "
                        + SetBigChannelMapClassificationAction.toString(action));

        return action;
    }

    /**
     * Checks the PA Sync Status and triggers an update to the BIG Channel Map classification if the
     * status has changed for a local broadcast.
     *
     * @param sink The Bluetooth device sink.
     * @param broadcastId The ID of the broadcast.
     * @param receiveState The current {@link BluetoothLeBroadcastReceiveState}.
     */
    private void CheckAndTriggerUpdateChannelMapClassification(
            BluetoothDevice sink, int broadcastId, BluetoothLeBroadcastReceiveState receiveState) {
        if (!Flags.leaudioBroadcastSourceChannelMapClassification()) {
            return;
        }

        if (!getAdapterService().isLeBigSetChannelClassificationSupported()) {
            return;
        }

        if (isLocalBroadcast(broadcastId)) {
            int action =
                    checkPaSyncStatusForBigChannelMapClassification(
                            sink, broadcastId, receiveState);
            if (action != SetBigChannelMapClassificationAction.NO_ACTION.getValue()) {
                final var leAudio = getAdapterService().getLeAudioService();
                leAudio.ifPresent(l -> l.setBigChannelMapClassification(action, sink, broadcastId));
            }
        }
    }

    private void localNotifyReceiveStateChanged(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState) {
        int broadcastId = receiveState.getBroadcastId();
        // Check and trigger update of BIGChannelMapClassification based on SyncStatus change.
        CheckAndTriggerUpdateChannelMapClassification(sink, broadcastId, receiveState);
        boolean broadcastSyncIsAdvancing = isBroadcastSyncAdvancing(sink, receiveState);

        // Clear sink waiting for past if PA changed to other than SYNCINFO_REQUEST
        if (receiveState.getPaSyncState()
                != BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST) {
            synchronized (mPastResponseTimeouts) {
                Map<Integer, PastResponseTimeout> timeouts = mPastResponseTimeouts.get(sink);
                if (timeouts != null) {
                    PastResponseTimeout timeout = timeouts.remove(receiveState.getSourceId());
                    if (timeout != null) {
                        mHandler.removeCallbacks(timeout);
                    }
                    if (timeouts.isEmpty()) {
                        mPastResponseTimeouts.remove(sink);
                    }
                }
            }
        }

        handlePausedBroadcasts(sink, broadcastId, receiveState, broadcastSyncIsAdvancing);

        updateMetadataFromReceiveState(sink, broadcastId, receiveState);

        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            return;
        }

        if (broadcastSyncIsAdvancing) {
            startReactivateGroupMonitor(sink, /* inactivated */ false);
        }

        if (isPrimaryDeviceSyncedToExternalBroadcast()) {
            /* Assistant become active */
            if (!mIsAssistantActive) {
                mIsAssistantActive = true;
                leAudio.get().activeBroadcastAssistantNotification(true);
            }

            checkAndSetGroupAllowedContextMask(sink);
        } else {
            /* Assistant become inactive */
            if (mIsAssistantActive && mPausedBroadcastSinks.isEmpty()) {
                mIsAssistantActive = false;
                mUnicastSourceStreamStatus = Optional.empty();
                leAudio.get().activeBroadcastAssistantNotification(false);
            }

            /* Restore allowed context mask for unicast in case if last connected broadcast
             * delegator device which has external source removes this source
             */
            checkAndResetGroupAllowedContextMask();
        }
    }

    private void localNotifySourceAdded(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState, int reason) {
        if (reason == BluetoothStatusCodes.REASON_REMOTE_REQUEST) {
            syncRequestForMetadata(sink, receiveState.getBroadcastId());
        }

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
            mLocalBroadcastReceivers.put(broadcastId, new HashSet<>(Arrays.asList(sink)));
        }
        if (Flags.leaudioFallbackGroupSelection()) {
            updateDefaultBroadcastToUnicastFallbackGroup();
        }

        if (Flags.leaudioAuracastCredentialExtension()) {
            Log.i(TAG, "Source successfully added. Clearing wait state.");
            mPendingNfcJoiningDevices.clear();
        }
    }

    private void localNotifySourceAddFailed(
            BluetoothDevice sink, BluetoothLeBroadcastMetadata source) {
        removeSinkMetadata(sink, source.getBroadcastId());
        stopBackgroundSearching();

        if (!Flags.leaudioAuracastCredentialExtension()) {
            return;
        }

        if (!mPendingNfcJoiningDevices.remove(sink)) {
            Log.d(
                    TAG,
                    "Ignoring SOURCE_ADD_FAILED because the user did not initiate a join or it"
                            + " was already handled.");
            return;
        }

        Log.i(TAG, "Failure caught while waiting for join. Updating UI.");

        // Only show the error notification if ALL connected devices failed.
        // If the other earbud succeeded, the Set would already be empty.
        if (mPendingNfcJoiningDevices.isEmpty()) {
            showNfcJoiningFailureNotification(sink, source.getBroadcastName());
        }
    }

    private void showNfcJoiningFailureNotification(BluetoothDevice sink, String broadcastName) {
        String streamName =
                broadcastName != null
                        ? broadcastName
                        : getString(R.string.auracast_default_stream_name);

        RemoteDevices remoteDevices = getAdapterService().getRemoteDevices();
        String deviceName = remoteDevices.getAlias(sink);

        if (deviceName == null) {
            // If alias is null, try to get name
            deviceName = remoteDevices.getName(sink);
        }
        if (deviceName == null) {
            // If name is null, fallback
            deviceName = getString(R.string.auracast_default_device_name);
        }

        NotificationManager nm = getAdapterService().getSystemService(NotificationManager.class);
        String title = getString(R.string.auracast_notification_title, streamName);
        String text =
                getString(R.string.auracast_connection_failed_message, streamName, deviceName);
        AuracastUtils.showNotification(this, nm, title, text, null);
    }

    private void setSourceGroupManaged(BluetoothDevice sink, int sourceId, boolean isGroupOp) {
        Log.d(TAG, "setSourceGroupManaged device: " + sink);
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
        Log.d(TAG, "getGroupManagedDeviceSources device: " + sink + " sourceId: " + sourceId);
        Map<BluetoothDevice, Integer> map = new HashMap<>();

        if (mGroupManagedSources.containsKey(sink)
                && mGroupManagedSources.get(sink).contains(sourceId)) {
            BassClientStateMachine stateMachine = mStateMachines.get(sink);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + sink);
                return new Pair<>(null, null);
            }

            BluetoothLeBroadcastMetadata metadata =
                    stateMachine.getCurrentBroadcastMetadata(sourceId);
            if (metadata != null) {
                int broadcastId = metadata.getBroadcastId();

                for (BluetoothDevice device : getTargetDeviceList(sink, /* isGroupOp */ true)) {
                    BassClientStateMachine sm = mStateMachines.get(device);
                    if (sm == null) {
                        Log.w(
                                TAG,
                                "getGroupManagedDeviceSources: Failed to get state machine "
                                        + ("for device: " + device));
                        continue;
                    }
                    List<BluetoothLeBroadcastReceiveState> sources = sm.getAllSources();

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
                        "Couldn't find broadcast metadata for"
                                + (" device: " + sink)
                                + (" sourceId: " + sourceId));
            }
        }

        // Just put this single device if this source is not group managed
        map.put(sink, sourceId);
        return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(null, map);
    }

    private List<BluetoothDevice> getTargetDeviceList(BluetoothDevice device, boolean isGroupOp) {
        if (isGroupOp) {
            final var csipClient = getAdapterService().getCsipSetCoordinatorService();
            if (csipClient.isPresent()) {
                // Check for coordinated set of devices in the context of CAP
                List<BluetoothDevice> csipDevices =
                        csipClient.get().getGroupDevicesOrdered(device, BluetoothUuid.CAP);
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
                Log.d(TAG, "DuplicatedSourceAddition: for " + device + " metaData: " + metaData);
                break;
            }
        }
        return sourceId;
    }

    private boolean hasRoomForBroadcastSourceAddition(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;
        synchronized (mStateMachines) {
            stateMachine = mStateMachines.get(device);
        }
        if (stateMachine == null) {
            Log.d(TAG, "stateMachine is null");
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
        Log.d(TAG, "isRoomAvailable: " + isRoomAvailable);
        return isRoomAvailable;
    }

    private Integer getSourceIdToRemove(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;

        synchronized (mStateMachines) {
            stateMachine = mStateMachines.get(device);
        }
        if (stateMachine == null) {
            Log.d(TAG, "stateMachine is null");
            return BassConstants.INVALID_SOURCE_ID;
        }
        List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
        if (sources.isEmpty()) {
            Log.d(TAG, "sources is empty");
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

            Log.d(TAG, "Creating a new state machine for " + device);
            stateMachine =
                    BassObjectsFactory.getInstance()
                            .makeStateMachine(
                                    device,
                                    this,
                                    getAdapterService(),
                                    mScanController,
                                    mStateMachinesLooper);
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

            final var leAudio = getAdapterService().getLeAudioService();
            if (leAudio.isEmpty()) {
                Log.d(TAG, "DialingOutTimeoutEvent: No available LeAudioService");
                return;
            }

            sEventLogger.logd(TAG, "Broadcast timeout: " + mBroadcastId);
            mLocalBroadcastReceivers.remove(mBroadcastId);
            if (Flags.leaudioFallbackGroupSelection()) {
                updateDefaultBroadcastToUnicastFallbackGroup();
            }
            leAudio.get().stopBroadcast(mBroadcastId);
        }

        public boolean isScheduledForBroadcast(Integer broadcastId) {
            return mBroadcastId.equals(broadcastId);
        }
    }

    private static synchronized BassClientService getBassClientService() {
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
                Log.w(TAG, "removeStateMachine: No state machine for device: " + device);
                return;
            }
            Log.d(TAG, "removeStateMachine: removing state machine for device: " + device);
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
                BassClientStateMachine stateMachine = mStateMachines.get(device);
                if (stateMachine == null) {
                    Log.w(
                            TAG,
                            "informConnectedDeviceAboutScanOffloadStop: Can't get state "
                                    + ("machine for device: " + device));
                    continue;
                }
                stateMachine.sendMessage(BassClientStateMachine.STOP_SCAN_OFFLOAD);
            }
        }
    }

    private int validateParametersForSourceOperation(
            BassClientStateMachine stateMachine, BluetoothDevice device) {
        if ((stateMachine == null) || (getConnectionState(device) != STATE_CONNECTED)) {
            Log.d(TAG, "validateParameters: device is not connected, device: " + device);
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
            Log.d(TAG, "validateParameters: metadata is null for device: " + device);
            return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }

        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isPresent()) {
            boolean isOnlyHighQualityAvailable =
                    metadata.getAudioConfigQuality()
                            == BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH;
            if (isOnlyHighQualityAvailable
                    && !leAudio.get()
                            .isCapableToReceiveHighQualityBroadcastAudio(device)
                            .orElse(true)) {
                Log.e(TAG, "validateParameters: Sink doesn't support HIGH broadcast audio quality");
                return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
            }
        }

        byte[] code = metadata.getBroadcastCode();
        if ((code != null) && (code.length != 0)) {
            if ((code.length > 16) || (code.length < 4)) {
                Log.d(
                        TAG,
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
            Log.d(TAG, "validateParameters: no such sourceId for device: " + device);
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
            Log.d(TAG, "validateParameters: no such sourceId for device: " + device);
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
                    "connectionStateChanged: unexpected invocation."
                            + (" device: " + device)
                            + (" fromState: " + BluetoothProfile.getConnectionStateName(fromState))
                            + (" toState: " + BluetoothProfile.getConnectionStateName(toState)));
            return;
        }

        sEventLogger.logi(
                TAG,
                "connectionStateChanged:"
                        + (" device: " + device)
                        + (" fromState: " + BluetoothProfile.getConnectionStateName(fromState))
                        + (" toState: " + BluetoothProfile.getConnectionStateName(toState)));

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            mEncryptionStates.remove(device);
            mPendingGroupOp.remove(device);
            mPausedBroadcastSinks.remove(device);
            mSinksToRestoreFromPeer.remove(device);
            synchronized (mPastResponseTimeouts) {
                Map<Integer, PastResponseTimeout> timeouts = mPastResponseTimeouts.remove(device);
                if (timeouts != null) {
                    for (PastResponseTimeout timeout : timeouts.values()) {
                        mHandler.removeCallbacks(timeout);
                    }
                }
            }
            synchronized (mSinksWaitingForMetadata) {
                Integer broadcastId = mSinksWaitingForMetadata.remove(device);
                if (broadcastId != null) {
                    mTimeoutHandler.stop(broadcastId, MESSAGE_UPDATE_METADATA_TIMEOUT);
                }
                if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && mSinksWaitingForMetadata.isEmpty()
                        && mIsBackgroundScan) {
                    stopSearchingForSources(/* foreground= */ false);
                }
            }
            synchronized (mPendingSourcesToAddByUri) {
                Iterator<PendingSourceToAddByUri> iterator = mPendingSourcesToAddByUri.iterator();
                while (iterator.hasNext()) {
                    PendingSourceToAddByUri pending = iterator.next();
                    if (pending.sink().equals(device)) {
                        mHandler.removeCallbacks(pending.timeout());
                        iterator.remove();
                    }
                }
            }
            mPendingNfcJoiningDevices.remove(device);
            synchronized (mPendingSourcesToAdd) {
                mPendingSourcesToAdd.removeIf(
                        pendingSourcesToAdd -> pendingSourcesToAdd.sink.equals(device));
            }

            int bondState = getAdapterService().getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, "Unbonded " + device + ". Removing state machine");
                removeStateMachine(device);
            }

            checkAndStopBroadcastMonitoring();
            checkAndStopAnnouncementMonitoring();
            removeSinkMetadataFromGroupIfWholeUnsynced(device);
            mSyncStatusMap.remove(device);

            stopBackgroundSearching();

            if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                if (getConnectedDevices().isEmpty()
                        || (mPausedBroadcastSinks.isEmpty()
                                && mSinksWaitingForMetadata.isEmpty()
                                && mPendingSourcesToAdd.isEmpty()
                                && !isAnyConnectedDeviceSwitchingSource()
                                && !isAnyAnnouncementMonitored())) {
                    synchronized (mSearchScanCallbackLock) {
                        // when searching is stopped then clear all sync data
                        if (!isAnySearchInProgress()) {
                            clearAllSyncData();
                        }
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

    @Override
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);

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
    @Override
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        requireNonNull(device);

        if (!okToConnect(device)) {
            return false;
        }

        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
        if (!Util.arrayContains(featureUuids, BluetoothUuid.BASS)) {
            Log.e(
                    TAG,
                    "connect: Cannot connect to " + device + " : Remote does not have BASS UUID");
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
    @Override
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            Log.e(TAG, "disconnect: device is null");
            return false;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + device);
                return false;
            }

            stateMachine.sendMessage(BassClientStateMachine.DISCONNECT);
        }
        return true;
    }

    /**
     * Get connection state of remote device
     *
     * @param sink the remote device
     * @return connection state
     */
    @Override
    public int getConnectionState(BluetoothDevice sink) {
        synchronized (mStateMachines) {
            BassClientStateMachine sm = mStateMachines.get(sink);
            if (sm == null) {
                Log.d(TAG, "getConnectionState returns STATE_DISC");
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
        final var bondedDevices = getAdapterService().getBondedDevices();
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
                if (!Util.arrayContains(featureUuids, BluetoothUuid.BASS)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                BassClientStateMachine sm = mStateMachines.get(device);
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
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);

        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
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
    public void startSearchingForSources(List<ScanFilter> filters) {
        startSearchingForSources(filters, /* foreground= */ true);
    }

    private void startSearchingForSources(List<ScanFilter> filters, boolean foreground) {
        synchronized (mSearchScanCallbackLock) {
            if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                boolean firstForegroundStart = foreground && !mIsForegroundScan;
                mIsForegroundScan = mIsForegroundScan || foreground;

                Log.d(TAG, "startSearchingForSources with filters: " + filters);

                if (!Flags.leaudioBroadcastAlwaysClearNotifiedFlags()
                        && foreground
                        && !firstForegroundStart
                        && isAnySearchInProgress()) {
                    Log.e(TAG, "startSearchingForSources: already started");
                    mCallbacks.notifySearchStartFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }

                if ((Flags.leaudioBroadcastAlwaysClearNotifiedFlags() && foreground)
                        || (!Flags.leaudioBroadcastAlwaysClearNotifiedFlags()
                                && firstForegroundStart)) {
                    // Has to checked before any addSelectSourceRequest which start searching too
                    if (isAnySearchInProgress()) {
                        // Notify about search started by APP if it was started previously by stack
                        mCallbacks.notifySearchStarted(
                                BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                    }

                    // Clear failure counter before adding new broadcasts to sync
                    mSyncFailureCounter.clear();

                    // Collect broadcasts which should be sync and/or cache should remain.
                    Set<Integer> broadcastsToSync = getBroadcastsToSync();

                    // Add broadcasts to sync queue
                    for (int broadcastId : broadcastsToSync) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }

                    // When starting scan, clear the previously cached broadcast scan results,
                    // skip broadcast already added to sync
                    mCachedBroadcasts.keySet().removeIf(key -> !broadcastsToSync.contains(key));

                    printAllSyncData();

                    // Clear previous sources notify flag before scanning new result
                    // this is to make sure the active sources are notified even if already synced
                    clearNotifiedFlags();

                    if (Flags.leaudioBroadcastAlwaysClearNotifiedFlags()
                            && !firstForegroundStart
                            && isAnySearchInProgress()) {
                        Log.e(TAG, "startSearchingForSources: already started");
                        mCallbacks.notifySearchStartFailed(
                                BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                        return;
                    }
                }

                if (isAnySearchInitializing() || isAnySearchInProgress()) {
                    Log.d(TAG, "startSearchingForSources: already initializing or started");
                    return;
                }
            } else {
                Log.d(
                        TAG,
                        "startSearchingForSources with filters: "
                                + filters
                                + " in "
                                + (foreground ? "foreground" : "background"));

                boolean firstForegroundStart = false;
                if (foreground) {
                    if (!mIsForegroundScan) {
                        firstForegroundStart = true;
                    }
                    mIsForegroundScan = true;
                } else {
                    mIsBackgroundScan = true;
                }
                mSyncFailureCounter.clear();

                // Collect broadcasts which should be sync and/or cache should remain.
                // Broadcasts, which has to be synced, needs to have cache available.
                // Broadcasts which only cache should remain (i.e. because of potential resume)
                // has to be synced too to show it on the list before resume.
                LinkedHashSet<Integer> broadcastsToSync = new LinkedHashSet<>();

                // Sync to the broadcasts waiting for Metadata update
                broadcastsToSync.addAll(getBroadcastIdsWaitingForMetadata());

                // Keep already synced broadcasts
                broadcastsToSync.addAll(getBroadcastIdsOfSyncedBroadcasters());

                // Sync to the broadcasts already synced with sinks
                broadcastsToSync.addAll(getExternalBroadcastsActiveOnSinks());

                // Sync to the broadcasts waiting for PAST
                broadcastsToSync.addAll(getBroadcastIdsWaitingForPAST());

                // Sync to the broadcasts waiting for adding source (could be by resume too).
                broadcastsToSync.addAll(getBroadcastIdsWaitingForAddSource());

                // Sync to the broadcasts waiting for adding source by URI
                broadcastsToSync.addAll(getBroadcastIdsWaitingForAddSourceByUri());

                // Sync to the paused broadcasts
                broadcastsToSync.addAll(mPausedBroadcastIds.keySet());

                // Sync to monitored announcement
                broadcastsToSync.addAll(mAudioActiveStates.keySet());

                Log.d(TAG, "Broadcasts to sync on start: " + broadcastsToSync);

                // Add broadcasts to sync queue
                for (int broadcastId : broadcastsToSync) {
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                }

                // When starting scan, clear the previously cached broadcast scan results,
                // skip broadcast already added to sync
                mCachedBroadcasts.keySet().removeIf(key -> !broadcastsToSync.contains(key));

                printAllSyncData();

                // Clear previous sources notify flag before scanning new result
                // this is to make sure the active sources are notified even if already synced
                if (Flags.leaudioBroadcastAlwaysClearNotifiedFlags() || firstForegroundStart) {
                    clearNotifiedFlags();
                }

                if (isAnySearchInProgress()) {
                    // Notify about search started by APP if it was started previously by stack
                    if (firstForegroundStart) {
                        mCallbacks.notifySearchStarted(
                                BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                    } else {
                        Log.d(TAG, "startSearchingForSources: already started");
                        if (foreground) {
                            mCallbacks.notifySearchStartFailed(
                                    BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                        }
                    }
                    return;
                }
            }

            for (BluetoothDevice device : getConnectedDevices()) {
                synchronized (mStateMachines) {
                    BassClientStateMachine stateMachine = mStateMachines.get(device);
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

            mBassScanCallback.registerAndStartScan(filters);
            // Invoke search callbacks in onScannerRegistered
            sEventLogger.logi(
                    TAG,
                    "startSearchingForSources in " + (foreground ? "foreground" : "background"));
        }
    }

    /** Stops an ongoing search for nearby Broadcast Sources */
    public void stopSearchingForSources() {
        stopSearchingForSources(/* foreground= */ true);
    }

    private void stopBackgroundSearching() {
        if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            return;
        }

        stopSearchingForSources(/* foreground= */ false);
    }

    private void stopSearchingForSources(boolean foreground) {
        synchronized (mSearchScanCallbackLock) {
            if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                if (foreground) {
                    Log.d(TAG, "stopSearchingForSources");

                    if (!mIsForegroundScan) {
                        Log.e(TAG, "stopSearchingForSources: Scan not started yet");
                        mCallbacks.notifySearchStopFailed(
                                BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    } else {
                        // Notify about search stopped when called from APP even if still active
                        mCallbacks.notifySearchStopped(
                                BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                    }
                    mIsForegroundScan = false;
                } else if (mIsForegroundScan) {
                    return;
                }

                // Collect broadcasts which should stay synced after search stops
                Set<Integer> broadcastsToKeepSynced = getBroadcastsToKeepSynced();

                // Stop searching if synced to all needed broadcasts
                if (isAnySearchInProgress()
                        && getBroadcastIdsOfSyncedBroadcasters()
                                .containsAll(broadcastsToKeepSynced)) {
                    mBassScanCallback.stopScanAndUnregister();

                    informConnectedDeviceAboutScanOffloadStop();
                    sEventLogger.logi(
                            TAG,
                            "stopSearchingForSources in "
                                    + (foreground ? "foreground" : "background"));
                }

                if (!isAnySearchInProgress() && broadcastsToKeepSynced.isEmpty()) {
                    clearAllSyncData();
                    return;
                }

                // Remove all other broadcasts from sync queue if not in broadcastsToKeepSynced
                synchronized (mSourceSyncRequestsQueue) {
                    Iterator<SourceSyncRequest> iterator = mSourceSyncRequestsQueue.iterator();
                    while (iterator.hasNext()) {
                        SourceSyncRequest sourceSyncRequest = iterator.next();
                        Integer queuedBroadcastId = sourceSyncRequest.paResult.getBroadcastId();
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
                }

                // Unsync not needed broadcasts
                for (int syncHandleToRemove : syncHandlesToRemove) {
                    cancelActiveSync(syncHandleToRemove);
                }

                mSyncFailureCounter.clear();
                mTimeoutHandler.stopAll(MESSAGE_SYNC_LOST_TIMEOUT);

                printAllSyncData();

                // Need to handle select source request in case that pending sync was removed
                if (!mSourceSyncRequestsQueue.isEmpty()) {
                    handleSelectSourceRequest();
                }
            } else {
                Log.d(
                        TAG,
                        "stopSearchingForSources in " + (foreground ? "foreground" : "background"));

                if (foreground) {
                    mIsForegroundScan = false;
                } else {
                    mIsBackgroundScan = false;
                }

                if (mIsForegroundScan || mIsBackgroundScan) {
                    Log.d(
                            TAG,
                            "stopSearchingForSources: still used for "
                                    + (foreground ? "background" : "foreground"));
                    if (foreground) {
                        // Notify about search stopped when called from APP even if still active
                        mCallbacks.notifySearchStopped(
                                BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
                    }
                    return;
                }

                if (!isAnySearchInProgress()) {
                    Log.e(TAG, "stopSearchingForSources: Scan not started yet");
                    if (foreground) {
                        mCallbacks.notifySearchStopFailed(
                                BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    }
                    return;
                }
                mBassScanCallback.stopScanAndUnregister();

                printAllSyncData();

                // Collect broadcasts which should stay synced after search stops
                HashSet<Integer> broadcastsToKeepSynced = new HashSet<>();

                // Keep broadcasts waiting for PAST
                broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForPAST());

                // Keep broadcasts waiting for adding source (could be by resume too)
                broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForAddSource());

                // Keep broadcasts waiting for adding source by URI
                broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForAddSourceByUri());

                // Keep broadcast monitored or during resuming
                broadcastsToKeepSynced.addAll(getMonitoredOrResumingBroadcastIds());

                // Keep monitored announcement
                broadcastsToKeepSynced.addAll(mAudioActiveStates.keySet());

                Log.d(TAG, "Broadcasts to keep on stop: " + broadcastsToKeepSynced);

                // Remove all other broadcasts from sync queue if not in broadcastsToKeepSynced
                synchronized (mSourceSyncRequestsQueue) {
                    Iterator<SourceSyncRequest> iterator = mSourceSyncRequestsQueue.iterator();
                    while (iterator.hasNext()) {
                        SourceSyncRequest sourceSyncRequest = iterator.next();
                        Integer queuedBroadcastId = sourceSyncRequest.paResult.getBroadcastId();
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
                    // Add again, as monitored broadcasts were monitored in onScanResult during
                    // scanning, now need to be monitored in the sync loop
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                }

                // Unsync not needed broadcasts
                for (int syncHandleToRemove : syncHandlesToRemove) {
                    cancelActiveSync(syncHandleToRemove);
                }

                mSyncFailureCounter.clear();
                mTimeoutHandler.stopAll(MESSAGE_SYNC_LOST_TIMEOUT);

                printAllSyncData();

                informConnectedDeviceAboutScanOffloadStop();
                sEventLogger.logi(
                        TAG,
                        "stopSearchingForSources in " + (foreground ? "foreground" : "background"));
                if (foreground) {
                    mCallbacks.notifySearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
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
                        + ("\n mPastResponseTimeouts: " + mPastResponseTimeouts)
                        + ("\n mSinksWaitingForMetadata: " + mSinksWaitingForMetadata)
                        + ("\n mPausedBroadcastIds: " + mPausedBroadcastIds)
                        + ("\n mPausedBroadcastSinks: " + mPausedBroadcastSinks)
                        + ("\n mSinksToRestoreFromPeer: " + mSinksToRestoreFromPeer)
                        + ("\n mCachedBroadcasts: " + mCachedBroadcasts)
                        + ("\n mBroadcastMetadataMap: " + mBroadcastMetadataMap)
                        + ("\n mAudioActiveStates: " + mAudioActiveStates)
                        + ("\n mIsUnicastAutoResuming: " + mIsUnicastAutoResuming));
    }

    private void clearAllSyncData() {
        Log.d(TAG, "clearAllSyncData");
        synchronized (mSourceSyncRequestsQueue) {
            mTimeoutHandler.stopAll(MESSAGE_SYNC_LOST_TIMEOUT);
            mSourceSyncRequestsQueue.clear();
            mSyncFailureCounter.clear();

            if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                if (!mPeriodicAdvCallbacksMap.isEmpty()) {
                    cancelActiveSync(null);
                }
            } else {
                cancelActiveSync(null);
            }
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
            return isAnySearchInProgress() && mIsForegroundScan;
        }
    }

    /**
     * Return true if a search has been started by this application or stack
     *
     * @return true if a search has been started by this application or stack
     */
    @VisibleForTesting
    boolean isAnySearchInProgress() {
        synchronized (mSearchScanCallbackLock) {
            return mBassScanCallback.isBroadcastAudioAnnouncementScanActive();
        }
    }

    /**
     * Return true if a search is already initializing
     *
     * @return true if a search is already initializing
     */
    @VisibleForTesting
    boolean isAnySearchInitializing() {
        synchronized (mSearchScanCallbackLock) {
            return mBassScanCallback.isBroadcastAudioAnnouncementScanInitializing();
        }
    }

    /** Internal periodic Advertising manager callback */
    final class PACallback extends IPeriodicAdvertisingCallback.Stub {
        @Override
        public void onSyncEstablished(
                int syncHandle,
                BluetoothDevice device,
                int advertisingSid,
                int skip,
                int timeout,
                int status) {
            int broadcastId = getBroadcastIdForSyncHandle(BassConstants.PENDING_SYNC_HANDLE);
            Log.i(
                    TAG,
                    "onSyncEstablished status: "
                            + status
                            + ", syncHandle: "
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
                            + timeout);

            if (broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
                Log.w(TAG, "onSyncEstablished unexpected call, no pending synchronization");
                if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && mSourceSyncRequestsQueue.isEmpty()) {
                    stopBackgroundSearching();
                } else {
                    handleSelectSourceRequest();
                }
                return;
            }

            final int ERROR_CODE_SUCCESS = 0x00;
            if (status != ERROR_CODE_SUCCESS) {
                Log.d(TAG, "onSyncEstablished failed for broadcast id: " + broadcastId);
                boolean notifiedOfLost = false;
                synchronized (mPendingSourcesToAdd) {
                    Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
                    while (iterator.hasNext()) {
                        AddSourceData pendingSourcesToAdd = iterator.next();
                        if (pendingSourcesToAdd.sourceMetadata.getBroadcastId() == broadcastId) {
                            if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                                iterator.remove();
                            }
                            if (!notifiedOfLost) {
                                notifiedOfLost = true;
                                if (mIsForegroundScan) {
                                    mCallbacks.notifySourceLost(broadcastId);
                                }
                            }
                            mCallbacks.notifySourceAddFailed(
                                    pendingSourcesToAdd.sink,
                                    pendingSourcesToAdd.sourceMetadata,
                                    BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);
                            if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                                iterator.remove();
                            }
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

                if (isMonitoringOrResumingPauseReason(broadcastId)) {
                    if (!mTimeoutHandler.isStarted(broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT)) {
                        mPausedBroadcastIds.put(broadcastId, PauseReason.OOR_MONITORING);
                        logPausedBroadcastsAndSinks();
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT, sOorMonitorTimeout);
                    }
                    if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                            && !isAnySearchInProgress()) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                } else {
                    // Clear from cache to make possible sync again (only during active searching)
                    synchronized (mSearchScanCallbackLock) {
                        if (isAnySearchInProgress()) {
                            mCachedBroadcasts.remove(broadcastId);
                        }
                    }
                }

                if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && isWaitingForPast(broadcastId)) {
                    if (!isAnySearchInProgress()) {
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                }

                if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && mSourceSyncRequestsQueue.isEmpty()) {
                    stopBackgroundSearching();
                } else {
                    handleSelectSourceRequest();
                }
                return;
            }

            synchronized (mSourceSyncRequestsQueue) {
                // updates syncHandle, advSid
                // set other fields as invalid or null
                updatePeriodicAdvertisementResultMap(
                        device,
                        syncHandle,
                        advertisingSid,
                        BassConstants.INVALID_ADV_INTERVAL,
                        LeAudioConstants.INVALID_BROADCAST_ID,
                        BluetoothLeBroadcastMetadata.RSSI_UNKNOWN,
                        null,
                        null);
                addActiveSyncedSource(syncHandle);

                mTimeoutHandler.stop(broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT);
                // If OOR is set, but broadcast is already established, go back to BIG monitoring.
                // OOR could be set only from BIG monitoring, so its timer is still running.
                if (isOorMonitoringPauseReason(broadcastId)) {
                    mPausedBroadcastIds.put(broadcastId, PauseReason.BIG_MONITORING);
                    logPausedBroadcastsAndSinks();
                }

                // update valid sync handle in mPeriodicAdvCallbacksMap
                if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.PENDING_SYNC_HANDLE)) {
                    IPeriodicAdvertisingCallback paCb =
                            mPeriodicAdvCallbacksMap.get(BassConstants.PENDING_SYNC_HANDLE);
                    mPeriodicAdvCallbacksMap.put(syncHandle, paCb);
                    mPeriodicAdvCallbacksMap.remove(BassConstants.PENDING_SYNC_HANDLE);
                }

                mBisDiscoveryCounterMap.put(syncHandle, MAX_BIS_DISCOVERY_TRIES_NUM);

                if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && mSourceSyncRequestsQueue.isEmpty()) {
                    // It has to be checked and executed after addActiveSyncedSource but before
                    // mPastResponseTimeouts and mPendingSourcesToAdd clearing
                    stopBackgroundSearching();
                }
            }
            synchronized (mPastResponseTimeouts) {
                Iterator<Map.Entry<BluetoothDevice, Map<Integer, PastResponseTimeout>>> iterator =
                        mPastResponseTimeouts.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BluetoothDevice, Map<Integer, PastResponseTimeout>> entry =
                            iterator.next();
                    BluetoothDevice sinkDevice = entry.getKey();
                    Map<Integer, PastResponseTimeout> timeouts = entry.getValue();
                    timeouts.entrySet().stream()
                            .filter(e -> e.getValue().mBroadcastId == broadcastId)
                            .findFirst()
                            .ifPresent(
                                    timeoutEntry -> {
                                        int sourceId = timeoutEntry.getKey();
                                        initiatePaSyncTransferToSink(
                                                sinkDevice, syncHandle, sourceId);
                                        mHandler.removeCallbacks(timeoutEntry.getValue());
                                        timeouts.remove(sourceId);
                                        if (timeouts.isEmpty()) {
                                            iterator.remove();
                                        }
                                    });
                }
            }
            synchronized (mPendingSourcesToAdd) {
                List<AddSourceData> pendingSourcesToAdd = new ArrayList<>();
                Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
                while (iterator.hasNext()) {
                    AddSourceData pendingSourceToAdd = iterator.next();
                    if (pendingSourceToAdd.sourceMetadata.getBroadcastId() == broadcastId) {
                        boolean addSource = true;
                        if (pendingSourceToAdd.isGroupOp && !pendingSourcesToAdd.isEmpty()) {
                            List<BluetoothDevice> deviceGroup =
                                    getTargetDeviceList(
                                            pendingSourceToAdd.sink, /* isGroupOp */ true);
                            for (AddSourceData addSourceData : pendingSourcesToAdd) {
                                if (addSourceData.isGroupOp
                                        && deviceGroup.contains(addSourceData.sink)) {
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
                            addSourceData.sink,
                            addSourceData.sourceMetadata,
                            addSourceData.isGroupOp);
                }
            }
            handleSelectSourceRequest();
        }

        private void initiatePaSyncTransferToSink(
                BluetoothDevice sink, int syncHandle, int sourceId) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = mStateMachines.get(sink);
                if (sm == null) {
                    Log.w(
                            TAG,
                            "initiatePaSyncTransferToSink: failed to get state machine "
                                    + "for device: "
                                    + sink);
                    return;
                }

                Message message =
                        sm.obtainMessage(BassClientStateMachine.INITIATE_PA_SYNC_TRANSFER);
                message.arg1 = syncHandle;
                message.arg2 = sourceId;
                sm.sendMessage(message);
            }
        }

        @Override
        public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
            int syncHandle = report.getSyncHandle();
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            Log.d(
                    TAG,
                    "onPeriodicAdvertisingReport: syncHandle="
                            + syncHandle
                            + ", broadcastID="
                            + broadcastId);

            if (isAnnouncementMonitored(broadcastId)) {
                byte[] advData = report.getData().getServiceData(BassConstants.BASIC_AUDIO_UUID);
                if (advData != null) {
                    BaseData baseData = BaseData.parseBaseData(advData);
                    if (baseData != null) {
                        LtvData.AudioActiveState audioActiveState = LtvData.AudioActiveState.NONE;
                        for (BaseData.BaseSubgroup baseSubgroup : baseData.base().mSubgroups) {
                            LtvData.AudioActiveState aas =
                                    baseSubgroup.mLtvData.getAudioActiveState();
                            if (aas == LtvData.AudioActiveState.TRUE) {
                                audioActiveState = aas;
                                break;
                            } else if (aas == LtvData.AudioActiveState.FALSE) {
                                audioActiveState = aas;
                            }
                        }
                        handleAnnouncementMonitor(broadcastId, audioActiveState);
                    }
                }
            }

            // Parse the BIS indices from report's service data
            Integer bisCounter = mBisDiscoveryCounterMap.get(syncHandle);
            if (bisCounter != null && bisCounter != 0) {
                if (parseScanRecord(syncHandle, report.getData())) {
                    mBisDiscoveryCounterMap.put(syncHandle, 0);
                } else {
                    bisCounter--;
                    mBisDiscoveryCounterMap.put(syncHandle, bisCounter);
                    if (bisCounter == 0) {
                        BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
                        synchronized (mSinksWaitingForMetadata) {
                            mTimeoutHandler.stop(broadcastId, MESSAGE_UPDATE_METADATA_TIMEOUT);
                            mSinksWaitingForMetadata.remove(srcDevice);
                            if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                                    && mSinksWaitingForMetadata.isEmpty()
                                    && mIsBackgroundScan) {
                                stopSearchingForSources(/* foreground= */ false);
                            }
                        }
                        stopBackgroundSearching();
                        cancelActiveSync(syncHandle);
                    }
                }
            }

            BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
            if (srcDevice == null) {
                Log.d(TAG, "No device found.");
                return;
            }
            PeriodicAdvertisementResult result =
                    getPeriodicAdvertisementResult(
                            srcDevice, getBroadcastIdForSyncHandle(syncHandle));
            if (result == null) {
                Log.d(TAG, "No PA record found");
                return;
            }
            BaseData baseData = getBase(syncHandle);
            if (baseData == null) {
                Log.d(TAG, "No BaseData found");
                return;
            }
            PublicBroadcastData pbData = result.getPublicBroadcastData();
            if (pbData == null) {
                Log.d(TAG, "No public broadcast data found, wait for BIG");
                return;
            }
            if (!result.isNotified()
                    || !mSinksWaitingForMetadata.isEmpty()
                    || !mPendingSourcesToAddByUri.isEmpty()) {
                BluetoothLeBroadcastMetadata metaData =
                        getBroadcastMetadataFromBaseData(
                                baseData, srcDevice, syncHandle, pbData.isEncrypted());
                updateMetadata(metaData);
                processPendingAddSourceByUri(metaData);
                if (!result.isNotified()) {
                    result.setNotified(true);
                    Log.d(TAG, "Notify broadcast source found");
                    if (mIsForegroundScan) {
                        mCallbacks.notifySourceFound(metaData);
                    }
                }
            }
        }

        @Override
        public void onSyncLost(int syncHandle) {
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            Log.d(TAG, "OnSyncLost: syncHandle=" + syncHandle + ", broadcastID=" + broadcastId);
            clearAllDataForSyncHandle(syncHandle);
            if (broadcastId != LeAudioConstants.INVALID_BROADCAST_ID) {
                synchronized (mSourceSyncRequestsQueue) {
                    int failsCounter = mSyncFailureCounter.getOrDefault(broadcastId, 0) + 1;
                    mSyncFailureCounter.put(broadcastId, failsCounter);
                }
                mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
                if ((Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                                && getBroadcastsToKeepSynced().contains(broadcastId))
                        || (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                                && isMonitoringOrResumingPauseReason(broadcastId))) {
                    if (!mTimeoutHandler.isStarted(broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT)) {
                        mPausedBroadcastIds.put(broadcastId, PauseReason.OOR_MONITORING);
                        logPausedBroadcastsAndSinks();
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT, sOorMonitorTimeout);
                    }
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                } else {
                    mTimeoutHandler.start(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT, sSyncLostTimeout);
                }
            }
        }

        @Override
        public void onBigInfoAdvertisingReport(int syncHandle, boolean encrypted) {
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            Log.d(
                    TAG,
                    "onBIGInfoAdvertisingReport: syncHandle="
                            + syncHandle
                            + ", broadcastId="
                            + broadcastId
                            + ", encrypted="
                            + encrypted);
            BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
            if (srcDevice == null) {
                Log.d(TAG, "No device found.");
                return;
            }
            PeriodicAdvertisementResult result =
                    getPeriodicAdvertisementResult(srcDevice, broadcastId);
            if (result == null) {
                Log.d(TAG, "No PA record found");
                return;
            }
            BaseData baseData = getBase(syncHandle);
            if (baseData == null) {
                Log.d(TAG, "No BaseData found");
                return;
            }
            if (!result.isNotified()
                    || !mSinksWaitingForMetadata.isEmpty()
                    || !mPendingSourcesToAddByUri.isEmpty()) {
                BluetoothLeBroadcastMetadata metaData =
                        getBroadcastMetadataFromBaseData(
                                baseData, srcDevice, syncHandle, encrypted);
                updateMetadata(metaData);
                processPendingAddSourceByUri(metaData);
                if (!result.isNotified()) {
                    result.setNotified(true);
                    Log.d(TAG, "Notify broadcast source found");
                    if (mIsForegroundScan) {
                        mCallbacks.notifySourceFound(metaData);
                    }
                }
            }
            if (isBigMonitoringPauseReason(broadcastId)) {
                resumeReceiversSourceSynchronization(broadcastId);
            }
        }

        @Override
        public void onSyncTransferred(BluetoothDevice unused1, int unused2) {}
    }

    private void checkAndStopAnnouncementMonitoring() {
        Iterator<Integer> iterator = mAudioActiveStates.keySet().iterator();
        while (iterator.hasNext()) {
            int broadcastId = iterator.next();
            if (!isAnyReceiverSyncedToBroadcast(broadcastId)) {
                iterator.remove();
                Log.d(TAG, "Stop Announcement Monitoring: broadcastId: " + broadcastId);
            }
        }

        if (mAudioActiveStates.isEmpty()) {
            mIsUnicastAutoResuming = false;
            Log.d(TAG, "checkAndStopAnnouncementMonitoring: mIsUnicastAutoResuming=false");
        }
    }

    private boolean isAnnouncementMonitored(int broadcastId) {
        return mAudioActiveStates.containsKey(broadcastId);
    }

    private boolean isAnyAnnouncementMonitored() {
        return !mAudioActiveStates.isEmpty();
    }

    private void handleAnnouncementMonitor(
            int broadcastId, LtvData.AudioActiveState audioActiveState) {
        Log.d(
                TAG,
                "handleAnnouncementMonitor: "
                        + ("broadcastId: " + broadcastId)
                        + (", audioActiveState: " + audioActiveState)
                        + (", mAudioActiveStates: " + mAudioActiveStates)
                        + (", mIsUnicastAutoResuming: " + mIsUnicastAutoResuming));

        if (mAudioActiveStates.get(broadcastId) != audioActiveState) {
            mAudioActiveStates.put(broadcastId, audioActiveState);

            if (audioActiveState == LtvData.AudioActiveState.TRUE) {
                mUnicastSourceStreamStatus.ifPresent(
                        status -> {
                            if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_STREAMING) {
                                mIsUnicastAutoResuming = true;
                                Log.d(
                                        TAG,
                                        "handleAnnouncementMonitor: mIsUnicastAutoResuming=true");
                            }
                        });
                if (!mPausedBroadcastSinks.isEmpty()) {
                    Log.d(TAG, "handleAnnouncementMonitor: Resume broadcast: " + broadcastId);
                    resumeReceiversSourceSynchronization(broadcastId);
                }
            } else if (audioActiveState == LtvData.AudioActiveState.FALSE) {
                Set<Integer> activeBroadcastIds =
                        mAudioActiveStates.entrySet().stream()
                                .filter(e -> e.getValue() == LtvData.AudioActiveState.TRUE)
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toSet());

                if (activeBroadcastIds.isEmpty()) {
                    if (mIsUnicastAutoResuming) {
                        Log.d(TAG, "handleAnnouncementMonitor: Resume Unicast");
                        getAdapterService()
                                .getMcpService()
                                .ifPresent(mcpService -> mcpService.playRequest());
                    }
                } else if (isAnyReceiverSyncedToBroadcast(broadcastId)
                        && Collections.disjoint(
                                activeBroadcastIds, getExternalBroadcastsActiveOnSinks())) {
                    int firstActiveBroadcastId = activeBroadcastIds.stream().findFirst().get();
                    Log.d(
                            TAG,
                            "handleAnnouncementMonitor: Switching to broadcast: "
                                    + firstActiveBroadcastId);
                    cacheSuspendingSources(LeAudioConstants.INVALID_BROADCAST_ID);
                    resumeReceiversSourceSynchronization(firstActiveBroadcastId);
                }
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
                                pendingSourcesToAdd.sourceMetadata.getBroadcastId() == broadcastId);
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
        for (BaseData.BaseSubgroup baseSubgroup : baseData.base().mSubgroups) {
            BluetoothLeBroadcastSubgroup.Builder subGroup =
                    new BluetoothLeBroadcastSubgroup.Builder();
            for (BaseData.BaseBis bis : baseSubgroup.mBises) {
                BluetoothLeBroadcastChannel.Builder channel =
                        new BluetoothLeBroadcastChannel.Builder();
                channel.setChannelIndex(bis.mIndex);
                channel.setSelected(false);
                try {
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                    bis.mCodecSpecificConfiguration));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid metadata, adding empty data. Error: " + e);
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
                }
                subGroup.addChannel(channel.build());
            }
            subGroup.setCodecId(baseSubgroup.mCodecId);
            try {
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                baseSubgroup.mCodecSpecificConfiguration));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid config, adding empty one. Error: " + e);
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
            }

            try {
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(baseSubgroup.mMetadata));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid metadata, adding empty one. Error: " + e);
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(new byte[0]));
            }

            metaData.addSubgroup(subGroup.build());
        }
        metaData.setSourceDevice(device, device.getAddressType());
        metaData.setPresentationDelayMicros(baseData.base().mPresentationDelay);
        PeriodicAdvertisementResult result =
                getPeriodicAdvertisementResult(device, getBroadcastIdForSyncHandle(syncHandle));
        if (result != null) {
            int broadcastId = result.getBroadcastId();
            Log.d(TAG, "broadcast ID: " + broadcastId);
            metaData.setBroadcastId(broadcastId);
            metaData.setSourceAdvertisingSid(result.getAdvSid());
            metaData.setPaSyncInterval(result.getAdvInterval());
            int rssi = result.getRssi();
            if (rssi < -127 || rssi > 126) {
                metaData.setRssi(BluetoothLeBroadcastMetadata.RSSI_UNKNOWN);
            } else {
                metaData.setRssi(rssi);
            }

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
        }
        metaData.setEncrypted(encrypted);

        return metaData.build();
    }

    /**
     * @param syncHandle syncHandle to unsync source and clean up all data for it. Null is used to
     *     clean up all pending and established broadcast syncs.
     */
    private void cancelActiveSync(Integer syncHandle) {
        Log.d(TAG, "cancelActiveSync: syncHandle = " + syncHandle);
        if (syncHandle == null || syncHandle == BassConstants.PENDING_SYNC_HANDLE) {
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

    private boolean unsyncSource(int syncHandle) {
        Log.d(TAG, "unsyncSource: syncHandle: " + syncHandle);
        synchronized (mSourceSyncRequestsQueue) {
            if (mPeriodicAdvCallbacksMap.containsKey(syncHandle)) {
                var callback = mPeriodicAdvCallbacksMap.get(syncHandle);
                runOnScanThread(() -> mScanController.unregisterSync(callback));
            } else {
                Log.d(TAG, "calling unregisterSync, not found syncHandle: " + syncHandle);
            }
            clearAllDataForSyncHandle(syncHandle);
        }
        return true;
    }

    boolean parseBaseData(int syncHandle, byte[] serviceData) {
        Log.d(TAG, "parseBaseData" + Arrays.toString(serviceData));
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
        Log.d(
                TAG,
                "parseScanRecord: syncHandle="
                        + syncHandle
                        + ", broadcastID="
                        + broadcastId
                        + ", record="
                        + record);
        Map<ParcelUuid, byte[]> bmsAdvDataMap = record.getServiceData();
        if (bmsAdvDataMap != null) {
            for (Map.Entry<ParcelUuid, byte[]> entry : bmsAdvDataMap.entrySet()) {
                Log.d(
                        TAG,
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

    void addSelectSourceRequest(BluetoothLeBroadcastMetadata metadata, boolean hasPriority) {
        if (metadata == null) {
            Log.e(TAG, "addSelectSourceRequest: null metadata");
            return;
        }

        int broadcastId = metadata.getBroadcastId();
        if (getActiveSyncedSources().contains(getSyncHandleForBroadcastId(broadcastId))) {
            Log.d(TAG, "addSelectSourceRequest: Already synced");
            return;
        }

        if (isAddedToSelectSourceRequest(broadcastId, hasPriority)) {
            Log.d(TAG, "addSelectSourceRequest: Already added");
            return;
        }

        sEventLogger.logd(
                TAG,
                "Add Select Broadcast Source, metadata: "
                        + metadata
                        + ", hasPriority: "
                        + hasPriority);

        // Ensure a scanner is active for the sync attempt
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            startSearchingForSources(Collections.emptyList(), /* foreground= */ false);
        }

        PeriodicAdvertisementResult paResult =
                new PeriodicAdvertisementResult(
                        metadata.getSourceDevice(),
                        BassConstants.PENDING_SYNC_HANDLE,
                        metadata.getSourceAdvertisingSid(),
                        metadata.getPaSyncInterval(),
                        broadcastId,
                        metadata.getRssi(),
                        PublicBroadcastData.buildPublicBroadcastData(metadata),
                        metadata.getBroadcastName());

        mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
        synchronized (mSourceSyncRequestsQueue) {
            if (!mSyncFailureCounter.containsKey(broadcastId)) {
                mSyncFailureCounter.put(broadcastId, 0);
            }
            mSourceSyncRequestsQueue.add(
                    new SourceSyncRequest(
                            paResult, hasPriority, mSyncFailureCounter.get(broadcastId)));
        }

        handleSelectSourceRequest();
    }

    void addSelectSourceRequest(int broadcastId, boolean hasPriority) {
        if (getActiveSyncedSources().contains(getSyncHandleForBroadcastId(broadcastId))) {
            Log.d(TAG, "addSelectSourceRequest: Already synced");
            return;
        }

        if (isAddedToSelectSourceRequest(broadcastId, hasPriority)) {
            Log.d(TAG, "addSelectSourceRequest: Already added");
            return;
        }

        sEventLogger.logd(
                TAG,
                "Add Select Broadcast Source, broadcastId: "
                        + broadcastId
                        + ", hasPriority: "
                        + hasPriority);

        // Ensure a scanner is active for the sync attempt
        // Even if mSourceSyncRequestsQueue not be added scanner has to be enabled to monitor them
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            startSearchingForSources(Collections.emptyList(), /* foreground= */ false);
        }

        ScanResult scanResult = getCachedBroadcast(broadcastId);
        ScanRecord scanRecord = null;
        PeriodicAdvertisementResult paResult;
        if (scanResult == null) {
            Log.d(TAG, "addSelectSourceRequest: ScanResult empty");
        } else {
            scanRecord = scanResult.getScanRecord();
        }

        if (scanRecord != null) {
            paResult =
                    new PeriodicAdvertisementResult(
                            scanResult.getDevice(),
                            BassConstants.PENDING_SYNC_HANDLE,
                            scanResult.getAdvertisingSid(),
                            scanResult.getPeriodicAdvertisingInterval(),
                            broadcastId,
                            scanResult.getRssi(),
                            BassUtils.getPublicBroadcastData(scanRecord),
                            BassUtils.getBroadcastName(scanRecord));
        } else {
            Log.d(TAG, "addSelectSourceRequest: ScanRecord empty");
            BluetoothLeBroadcastMetadata metadata =
                    mBroadcastMetadataMap.values().stream()
                            .map(m -> m.get(broadcastId))
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
            if (metadata != null) {
                paResult =
                        new PeriodicAdvertisementResult(
                                metadata.getSourceDevice(),
                                BassConstants.PENDING_SYNC_HANDLE,
                                metadata.getSourceAdvertisingSid(),
                                metadata.getPaSyncInterval(),
                                broadcastId,
                                metadata.getRssi(),
                                PublicBroadcastData.buildPublicBroadcastData(metadata),
                                metadata.getBroadcastName());
            } else {
                Log.e(TAG, "addSelectSourceRequest: mBroadcastMetadataMap empty");
                return;
            }
        }

        mTimeoutHandler.stop(broadcastId, MESSAGE_SYNC_LOST_TIMEOUT);
        synchronized (mSourceSyncRequestsQueue) {
            if (!mSyncFailureCounter.containsKey(broadcastId)) {
                mSyncFailureCounter.put(broadcastId, 0);
            }
            mSourceSyncRequestsQueue.add(
                    new SourceSyncRequest(
                            paResult, hasPriority, mSyncFailureCounter.get(broadcastId)));
        }

        handleSelectSourceRequest();
    }

    private void handleSelectSourceRequest() {
        synchronized (mSourceSyncRequestsQueue) {
            if (mSourceSyncRequestsQueue.isEmpty()) {
                return;
            }

            if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.PENDING_SYNC_HANDLE)) {
                Log.d(TAG, "handleSelectSourceRequest: already pending sync");
                return;
            }

            final PeriodicAdvertisementResult paResult = mSourceSyncRequestsQueue.poll().paResult;
            final int broadcastId = paResult.getBroadcastId();

            sEventLogger.logd(TAG, "Select Broadcast Source, broadcastId: " + broadcastId);

            if (broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
                Log.e(TAG, "Invalid broadcast ID");
                handleSelectSourceRequest();
                return;
            }

            // Avoid duplicated sync request if the same broadcast BIG is synced
            final List<Integer> activeSyncedSrc = new ArrayList<>(getActiveSyncedSources());
            if (activeSyncedSrc.contains(getSyncHandleForBroadcastId(broadcastId))) {
                Log.d(TAG, "Skip duplicated sync request to broadcast id: " + broadcastId);
                handleSelectSourceRequest();
                return;
            }

            final IPeriodicAdvertisingCallback paCb = new PACallback();
            // put PENDING_SYNC_HANDLE and update it in onSyncEstablished
            mPeriodicAdvCallbacksMap.put(BassConstants.PENDING_SYNC_HANDLE, paCb);
            updatePeriodicAdvertisementResultMap(
                    paResult.getDevice(),
                    paResult.getSyncHandle(),
                    paResult.getAdvSid(),
                    paResult.getAdvInterval(),
                    paResult.getBroadcastId(),
                    paResult.getRssi(),
                    paResult.getPublicBroadcastData(),
                    paResult.getBroadcastName());

            // Check if there are resources for sync
            if (activeSyncedSrc.size() >= MAX_ACTIVE_SYNCED_SOURCES_NUM) {
                Log.d(TAG, "handleSelectSourceRequest: reached max allowed active source");
                Boolean canceledActiveSync = false;
                int broadcastIdToLostMonitoring = LeAudioConstants.INVALID_BROADCAST_ID;
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

            runOnScanThread(
                    () ->
                            mScanController.registerSync(
                                    paResult.getDevice(),
                                    paResult.getAdvSid(),
                                    0,
                                    BassConstants.PSYNC_TIMEOUT,
                                    paCb));
        }
    }

    private void storeSinkMetadata(
            BluetoothDevice device, int broadcastId, BluetoothLeBroadcastMetadata metadata) {
        if (device == null
                || broadcastId == LeAudioConstants.INVALID_BROADCAST_ID
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
        Log.v(
                TAG,
                ("Stored Sink Metadata (device: " + device)
                        + (", broadcastId: " + broadcastId)
                        + (", metadata: " + metadata + ")"));

        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()
                && Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            for (BluetoothLeBroadcastSubgroup subgroup : metadata.getSubgroups()) {
                LtvData ltvData = LtvData.parse(subgroup.getContentMetadata().getRawMetadata());
                Log.d(TAG, "LtvData in metadata: " + ltvData.toString());
                LtvData.AudioActiveState audioActiveState = ltvData.getAudioActiveState();
                Integer contexts = ltvData.getStreamingAudioContexts();
                if (audioActiveState != LtvData.AudioActiveState.NONE
                        && contexts != null
                        && (contexts & BluetoothLeAudio.CONTEXT_TYPE_INSTRUCTIONAL) != 0) {
                    Log.d(
                            TAG,
                            "Start Announcement Monitoring: broadcastId: "
                                    + broadcastId
                                    + ", audioActiveState: "
                                    + audioActiveState);
                    mAudioActiveStates.put(broadcastId, audioActiveState);
                    break;
                }
            }
        }
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

        Log.v(
                TAG,
                ("Removed Sink Metadata (device: " + device) + (", broadcastId: " + broadcastId));
    }

    private void removeSinkMetadata(BluetoothDevice device, int broadcastId) {
        if (device == null || broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
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
        if (device == null || broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
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
                    || (isSuspendedByHostPauseReason(broadcastId)
                            && !mPausedBroadcastSinks.isEmpty())) {
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

    private void removeSinkMetadataForRemovedBroadcasts(BluetoothDevice device) {
        Map<Integer, BluetoothLeBroadcastMetadata> entry = mBroadcastMetadataMap.get(device);
        if (entry == null) {
            return;
        }

        BassClientStateMachine sm = mStateMachines.get(device);
        if (sm == null) {
            removeSinkMetadata(device);
            return;
        }

        Set<Integer> currentBroadcastIds =
                getAllSources(device).stream()
                        .map(BluetoothLeBroadcastReceiveState::getBroadcastId)
                        .collect(Collectors.toUnmodifiableSet());

        entry.keySet().stream()
                .filter(
                        broadcastId ->
                                !currentBroadcastIds.contains(broadcastId)
                                        && (!sm.hasPendingSourceOperation(broadcastId))
                                        && (!sm.hasPendingSwitchingSourceOperation(broadcastId)))
                .collect(Collectors.toList()) // Collect to avoid ConcurrentModificationException
                .forEach(staleBroadcastId -> removeSinkMetadata(device, staleBroadcastId));
    }

    // TODO Delete it on leaudioBroadcastTreatEmptyRsExplicitly flag cleanup
    private void checkIfBroadcastIsSuspendedBySourceRemovalAndClearData(
            BluetoothDevice device, BassClientStateMachine stateMachine, int broadcastId) {
        if (!mPausedBroadcastSinks.contains(device)) {
            return;
        }
        Map<Integer, BluetoothLeBroadcastMetadata> entry = mBroadcastMetadataMap.get(device);
        if (entry == null) {
            return;
        }
        if (entry.keySet().size() >= stateMachine.getMaximumSourceCapacity()) {
            for (Integer cachedBroadcastId : entry.keySet()) {
                // Find broadcastId which is no synced and different from current
                if (!getAllSources(device).stream()
                                .anyMatch(rs -> (rs.getBroadcastId() == cachedBroadcastId))
                        && (broadcastId != cachedBroadcastId)) {
                    stopBroadcastMonitoring(cachedBroadcastId, /* hostInitiated */ false);
                    removeSinkMetadata(device, cachedBroadcastId);
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
                if (sourceSyncRequest.paResult.getBroadcastId() == broadcastId) {
                    return !priorityImportant || sourceSyncRequest.hasPriority;
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
        Log.d(
                TAG,
                "addSource: "
                        + ("device: " + sink)
                        + (", sourceMetadata: " + sourceMetadata)
                        + (", isGroupOp: " + isGroupOp));

        if (sourceMetadata == null) {
            Log.d(TAG, "addSource: Error bad parameter: sourceMetadata cannot be null");
            return;
        }

        int broadcastId = sourceMetadata.getBroadcastId();
        if (broadcastId == LeAudioConstants.INVALID_BROADCAST_ID) {
            Log.d(TAG, "addSource: Error bad parameter: invalid broadcastId");
            mCallbacks.notifySourceAddFailed(
                    sink, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
            return;
        }

        if (isLocalBroadcast(sourceMetadata)) {
            final var leAudio = getAdapterService().getLeAudioService();
            if (leAudio.isEmpty()
                    || !(leAudio.get().isPaused(broadcastId)
                            || leAudio.get().isPlaying(broadcastId))) {
                Log.w(TAG, "addSource: Local source can't be add");

                mCallbacks.notifySourceAddFailed(
                        sink,
                        sourceMetadata,
                        BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);

                return;
            }
        }

        List<BluetoothDevice> devices = getTargetDeviceList(sink, /* isGroupOp */ isGroupOp);
        // Don't coordinate it as a group if there's no group or there is one device only
        if (devices.size() < 2) {
            isGroupOp = false;
        }

        for (BluetoothDevice device : devices) {
            if (!isLocalBroadcast(sourceMetadata)) {
                checkAndSetGroupAllowedContextMask(device);
            }
            if (!isLocalBroadcast(sourceMetadata)
                    && (!getActiveSyncedSources()
                            .contains(getSyncHandleForBroadcastId(broadcastId)))) {
                Log.i(TAG, "Adding inactive broadcast: " + broadcastId);
                // Check if not added already
                if (isAddedToSelectSourceRequest(broadcastId, /* priorityImportant */ true)) {
                    mPendingSourcesToAdd.add(new AddSourceData(device, sourceMetadata, isGroupOp));
                    // If the source has been synced before, try to re-sync
                    // with the source by previously cached scan result.
                } else if (mCachedBroadcasts.containsKey(broadcastId)) {
                    mPendingSourcesToAdd.add(new AddSourceData(device, sourceMetadata, isGroupOp));
                    addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                } else {
                    Log.w(TAG, "AddSource: broadcast not cached, broadcastId: " + broadcastId);
                    mCallbacks.notifySourceAddFailed(
                            sink, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                    return;
                }
                continue;
            }

            BassClientStateMachine stateMachine = mStateMachines.get(device);
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
                                + broadcastId);
                if (!stateMachine.hasPendingSourceOperation(broadcastId)) {
                    mCallbacks.notifySourceAddFailed(
                            device,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST);
                }
                continue;
            }

            int sourceId = checkDuplicateSourceAdditionAndGetSourceId(device, sourceMetadata);
            if (sourceId != BassConstants.INVALID_SOURCE_ID) {
                // Update metadata in case that it was changed
                storeSinkMetadata(device, broadcastId, sourceMetadata);

                // sourceMetadata and pending operation were already checked a few lines above
                updateSourceToResumeBroadcast(device, sourceId, sourceMetadata);
                continue;
            }

            if (!hasRoomForBroadcastSourceAddition(device)) {
                Log.i(TAG, "addSource: device has no room");
                Integer sourceIdToRemove = getSourceIdToRemove(device);
                if (sourceIdToRemove != BassConstants.INVALID_SOURCE_ID) {
                    BluetoothLeBroadcastMetadata currentMetadata =
                            stateMachine.getCurrentBroadcastMetadata(sourceIdToRemove);
                    if (currentMetadata != null) {
                        removeSinkMetadata(device, currentMetadata.getBroadcastId());

                        // Add host intentional pause if previous broadcast is different than
                        // current
                        if (broadcastId != currentMetadata.getBroadcastId()) {
                            mPausedBroadcastSinks.remove(device);
                            mSinksToRestoreFromPeer.remove(device);
                            stopBroadcastMonitoring(
                                    currentMetadata.getBroadcastId(), /* hostInitiated */ true);
                        }
                    }

                    sEventLogger.logd(
                            TAG,
                            "Switch Broadcast Source: "
                                    + ("device: " + device)
                                    + (", old SourceId: " + sourceIdToRemove)
                                    + (", new broadcastId: " + broadcastId)
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
                    storeSinkMetadata(device, broadcastId, sourceMetadata);

                    startReactivateGroupMonitor(device, /* inactivated */ false);

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

            if (!Flags.leaudioBroadcastTreatEmptyRsExplicitly()) {
                // Even if there is a room for broadcast, it could happen that all broadcasts were
                // suspended via removing source. In that case, we have to found such broadcast and
                // remove it from metadata.
                checkIfBroadcastIsSuspendedBySourceRemovalAndClearData(
                        device, stateMachine, broadcastId);
            }

            /* Store metadata for sink device */
            storeSinkMetadata(device, broadcastId, sourceMetadata);

            if (isGroupOp) {
                enqueueSourceGroupOp(
                        device, BassClientStateMachine.ADD_BCAST_SOURCE, sourceMetadata);
            }

            sEventLogger.logi(
                    TAG,
                    "Add Broadcast Source: "
                            + ("device: " + device)
                            + (", broadcastId: " + broadcastId)
                            + (", broadcastName: " + sourceMetadata.getBroadcastName())
                            + (", isGroupOp: " + isGroupOp));

            startReactivateGroupMonitor(device, /* inactivated */ false);

            Message message = stateMachine.obtainMessage(BassClientStateMachine.ADD_BCAST_SOURCE);
            message.obj = sourceMetadata;
            stateMachine.sendMessage(message);
        }
    }

    /**
     * Add a Broadcast Source using the Broadcast Name and/or Broadcast ID (e.g., from a parsed
     * URI). It scans for the matching broadcast, retrieves the missing metadata, and completes the
     * addSource operation.
     */
    @VisibleForTesting
    void addSourceByUri(
            BluetoothDevice sink, String broadcastName, int broadcastId, byte[] broadcastCode) {
        if (broadcastName == null || broadcastName.isEmpty()) {
            Log.e(TAG, "addSourceByUri: broadcastName cannot be null or empty");
            return;
        }

        Log.d(
                TAG,
                "addSourceByUri: Searching for name = "
                        + broadcastName
                        + ", broadcastId = "
                        + broadcastId);

        java.util.List<Byte> codeList = null;
        if (broadcastCode != null) {
            codeList = new java.util.ArrayList<>(broadcastCode.length);
            for (byte b : broadcastCode) {
                codeList.add(b);
            }
        }

        Runnable timeout =
                () -> {
                    Log.w(TAG, "addSourceByUri: timeout expired for broadcast: " + broadcastName);
                    synchronized (mPendingSourcesToAddByUri) {
                        boolean removed = false;
                        Iterator<PendingSourceToAddByUri> iterator =
                                mPendingSourcesToAddByUri.iterator();
                        while (iterator.hasNext()) {
                            PendingSourceToAddByUri pending = iterator.next();
                            if (pending.sink().equals(sink)
                                    && pending.name().equals(broadcastName)) {
                                mHandler.removeCallbacks(pending.timeout());
                                iterator.remove();
                                removed = true;
                            }
                        }
                        if (removed) {
                            stopBackgroundSearching();
                            getAdapterService()
                                    .getLeAudioService()
                                    .ifPresent(
                                            leAudio -> {
                                                for (BluetoothDevice device :
                                                        leAudio.getGroupDevices(sink)) {
                                                    mPendingNfcJoiningDevices.remove(device);
                                                }
                                            });
                            showNfcJoiningFailureNotification(sink, broadcastName);
                        }
                    }
                };
        mHandler.postDelayed(timeout, sAddSourceByUriTimeout.toMillis());

        synchronized (mPendingSourcesToAddByUri) {
            mPendingSourcesToAddByUri.add(
                    new PendingSourceToAddByUri(
                            sink, broadcastName, codeList, broadcastId, timeout));
        }

        startSearchingForSources(Collections.emptyList(), /* foreground= */ false);
    }

    private void processPendingAddSourceByUri(BluetoothLeBroadcastMetadata metadata) {
        synchronized (mPendingSourcesToAddByUri) {
            Iterator<PendingSourceToAddByUri> iterator = mPendingSourcesToAddByUri.iterator();
            while (iterator.hasNext()) {
                PendingSourceToAddByUri pending = iterator.next();
                if (pending.broadcastId() == metadata.getBroadcastId()) {
                    BluetoothLeBroadcastMetadata finalMetadata = metadata;

                    if (metadata.isEncrypted()
                            && pending.code() != null
                            && !pending.code().isEmpty()) {
                        byte[] codeArray = new byte[pending.code().size()];
                        for (int i = 0; i < pending.code().size(); i++) {
                            codeArray[i] = pending.code().get(i);
                        }

                        finalMetadata =
                                new BluetoothLeBroadcastMetadata.Builder(metadata)
                                        .setBroadcastCode(codeArray)
                                        .build();
                    }

                    Log.d(
                            TAG,
                            "processPendingAddSourceByUri: Adding source = "
                                    + pending.name()
                                    + ", broadcastId = "
                                    + pending.broadcastId());
                    addSource(pending.sink(), finalMetadata, true);
                    mHandler.removeCallbacks(pending.timeout());
                    iterator.remove();
                }
            }
        }
    }

    private boolean isPendingSourceToAddByUri(int broadcastId) {
        synchronized (mPendingSourcesToAddByUri) {
            for (PendingSourceToAddByUri data : mPendingSourcesToAddByUri) {
                if (data.broadcastId() == broadcastId) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean isAnyChannelSelected(BluetoothLeBroadcastMetadata metadata) {
        if (metadata == null) {
            return false;
        }

        return metadata.getSubgroups().stream()
                .flatMap(subgroup -> subgroup.getChannels().stream())
                .anyMatch(BluetoothLeBroadcastChannel::isSelected);
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
        Log.d(
                TAG,
                "modifySource: "
                        + ("device: " + sink)
                        + (", sourceId: " + sourceId)
                        + (", updatedMetadata: " + updatedMetadata));

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;

        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();
            BassClientStateMachine stateMachine = mStateMachines.get(device);
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
                        device, deviceSourceId, BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST);
                continue;
            }

            /* Update metadata for sink device */
            storeSinkMetadata(device, updatedMetadata.getBroadcastId(), updatedMetadata);

            if (Flags.leaudioBroadcastStopBigMonitoringBasedOnBisSync()) {
                if (!isAnyChannelSelected(updatedMetadata)) {
                    stopBroadcastMonitoring(
                            updatedMetadata.getBroadcastId(), /* hostInitiated */ true);
                } else {
                    stopBroadcastMonitoring(
                            updatedMetadata.getBroadcastId(), /* hostInitiated */ false);
                }
            }

            sendModifySource(
                    stateMachine,
                    device,
                    deviceSourceId,
                    BassConstants.FLAG_SYNC_PA | BassConstants.FLAG_SYNC_BIS_CHANNEL_PREFERENCE,
                    updatedMetadata,
                    ModifyCallReason.API);
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
        Log.d(TAG, "removeSource: device: " + sink + ", sourceId: " + sourceId);

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;
        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();

            mPausedBroadcastSinks.remove(device);
            mSinksToRestoreFromPeer.remove(device);

            BassClientStateMachine stateMachine = mStateMachines.get(device);
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
     * @param stateMachine stateMachine for this sink
     * @param metaData current broadcast metadata for this sink
     */
    private void removeSourceInternal(
            BluetoothDevice sink,
            int sourceId,
            BassClientStateMachine stateMachine,
            BluetoothLeBroadcastMetadata metaData) {
        Log.d(TAG, "removeSourceInternal: device: " + sink + ", sourceId: " + sourceId);
        if (metaData != null) {
            stopBroadcastMonitoring(metaData.getBroadcastId(), /* hostInitiated */ true);
        }

        sEventLogger.logi(
                TAG, "Remove Broadcast Source: device: " + sink + ", sourceId: " + sourceId);

        Message message = stateMachine.obtainMessage(BassClientStateMachine.REMOVE_BCAST_SOURCE);
        message.arg1 = sourceId;
        stateMachine.sendMessage(message);

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
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = mStateMachines.get(sink);
            if (stateMachine == null) {
                Log.d(TAG, "stateMachine is null");
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
        Log.d(TAG, "getMaximumSourceCapacity: device = " + sink);
        BassClientStateMachine stateMachine = mStateMachines.get(sink);
        if (stateMachine == null) {
            Log.d(TAG, "stateMachine is null");
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
        Log.d(TAG, "getSourceMetadata: device = " + sink + " with source id = " + sourceId);
        BassClientStateMachine stateMachine = mStateMachines.get(sink);
        if (stateMachine == null) {
            Log.d(TAG, "stateMachine is null");
            return null;
        }
        return stateMachine.getCurrentBroadcastMetadata(sourceId);
    }

    private boolean isLocalBroadcast(int broadcastId) {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            return false;
        }

        final var wasFound = leAudio.get().getBroadcastMetadata(broadcastId) != null;

        Log.d(TAG, "isLocalBroadcast=" + wasFound);
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

    private List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>>
            getReceiveStateDevicePairs(int broadcastId) {
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> list = new ArrayList<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                 * that all receivers should be considered.
                 */
                if ((broadcastId != LeAudioConstants.INVALID_BROADCAST_ID)
                        && (receiveState.getBroadcastId() != broadcastId)) {
                    continue;
                }

                list.add(new Pair<>(receiveState, device));
            }
        }

        return list;
    }

    private void cancelPendingSourceOperations(int broadcastId) {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = mStateMachines.get(device);
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
        Log.d(TAG, "stopSourceReceivers broadcastId: " + broadcastId);

        for (BluetoothDevice sink : mPausedBroadcastSinks) {
            removeSinkMetadata(sink, broadcastId);
        }
        stopBroadcastMonitoring(broadcastId, /* hostInitiated */ false);

        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToRemove =
                getReceiveStateDevicePairs(broadcastId);

        for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToRemove) {
            removeSource(pair.second, pair.first.getSourceId());
        }

        if (broadcastId != LeAudioConstants.INVALID_BROADCAST_ID) {
            /* There may be some pending add/modify source operations */
            cancelPendingSourceOperations(broadcastId);
        }
    }

    private static BluetoothLeBroadcastMetadata getMetadataWithChannelUnselected(
            BluetoothLeBroadcastMetadata original) {
        if (original == null) {
            return null;
        }

        BluetoothLeBroadcastMetadata.Builder metaDataBuilder =
                new BluetoothLeBroadcastMetadata.Builder(original);
        metaDataBuilder.clearSubgroup();

        for (BluetoothLeBroadcastSubgroup subgroup : original.getSubgroups()) {
            BluetoothLeBroadcastSubgroup.Builder subGroupBuilder =
                    new BluetoothLeBroadcastSubgroup.Builder(subgroup);
            subGroupBuilder.clearChannel();

            for (BluetoothLeBroadcastChannel channel : subgroup.getChannels()) {
                BluetoothLeBroadcastChannel.Builder channelBuilder =
                        new BluetoothLeBroadcastChannel.Builder(channel).setSelected(false);
                subGroupBuilder.addChannel(channelBuilder.build());
            }
            metaDataBuilder.addSubgroup(subGroupBuilder.build());
        }

        return metaDataBuilder.build();
    }

    /** Return true if there is any non primary device receiving broadcast */
    private boolean isAudioSharingModeOn(Integer broadcastId) {
        HashSet<BluetoothDevice> devices = mLocalBroadcastReceivers.get(broadcastId);
        if (devices == null) {
            Log.w(TAG, "isAudioSharingModeOn: No receivers receiving broadcast: " + broadcastId);
            return false;
        }

        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.d(TAG, "isAudioSharingModeOn: No available LeAudioService");
            return false;
        }

        return devices.stream().anyMatch(device -> !leAudio.get().isPrimaryDevice(device));
    }

    /** Handle disconnection of potential broadcast sinks */
    public void handleDeviceDisconnection(BluetoothDevice sink, boolean isIntentional) {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.d(TAG, "BluetoothLeBroadcastReceiveState: No available LeAudioService");
            return;
        }

        Iterator<Map.Entry<Integer, HashSet<BluetoothDevice>>> iterator =
                mLocalBroadcastReceivers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, HashSet<BluetoothDevice>> entry = iterator.next();
            Integer broadcastId = entry.getKey();
            HashSet<BluetoothDevice> devices = entry.getValue();

            /* If somehow there is a non configured/playing broadcast, let's remove it */
            if (!(leAudio.get().isPaused(broadcastId) || leAudio.get().isPlaying(broadcastId))) {
                Log.w(TAG, "Non playing broadcast remove from receivers list");
                iterator.remove();
                continue;
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
                                                && leAudio.get().isPrimaryDevice(d)))) {
                    continue;
                }

                Log.d(
                        TAG,
                        "handleIntendedDeviceDisconnection: No more potential broadcast "
                                + "(broadcast ID: "
                                + broadcastId
                                + ") receivers - stopping broadcast");
                iterator.remove();
                leAudio.get().stopBroadcast(broadcastId);
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
                    leAudio.get().stopBroadcast(broadcastId);
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

    /**
     * Finds the first actionable broadcast ID for a given sink by checking for existing metadata to
     * restore or new peer metadata to add.
     *
     * @param sink The Bluetooth device sink to check.
     * @return An Optional containing the broadcast ID if an action is required, otherwise empty.
     */
    private Optional<Integer> findActionableBroadcastId(BluetoothDevice sink) {
        for (BluetoothDevice groupDevice : getTargetDeviceList(sink, /* isGroupOp */ true)) {
            if (groupDevice.equals(sink)) {
                continue;
            }

            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(groupDevice)) {
                int targetBroadcastId = receiveState.getBroadcastId();

                // Path 1: Found existing metadata to restore.
                if (getMetadataFromSinkWithBroadcastId(sink, targetBroadcastId) != null) {
                    Log.d(TAG, "findActionableBroadcastId: Found source to restore for " + sink);
                    return Optional.of(targetBroadcastId);
                }

                // Path 2: Found new metadata from a peer to add.
                BluetoothLeBroadcastMetadata sourceMetadata =
                        getMetadataFromSinkWithBroadcastId(groupDevice, targetBroadcastId);
                if (sourceMetadata != null) {
                    Log.d(
                            TAG,
                            "findActionableBroadcastId: Found new source to add from peer for "
                                    + sink);
                    BluetoothLeBroadcastMetadata newSourceMetadata =
                            getMetadataWithChannelUnselected(sourceMetadata);
                    storeSinkMetadata(sink, targetBroadcastId, newSourceMetadata);
                    return Optional.of(targetBroadcastId);
                }
            }
        }
        return Optional.empty();
    }

    /* Handle device Bass state ready and check if assistant should resume broadcast */
    private void handleBassStateReady(BluetoothDevice sink) {
        if (!Flags.leaudioBroadcastAddSourceFromPeerDevice()) {
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
                        BassClientStateMachine sm = mStateMachines.get(groupDevice);
                        if (groupDevice.equals(sink) || sm == null) {
                            continue;
                        }

                        // Check peer device
                        Optional<BluetoothLeBroadcastReceiveState> receiver =
                                sm.getAllSources().stream()
                                        .filter(
                                                e ->
                                                        e.getBroadcastId()
                                                                == metadata.getBroadcastId())
                                        .findAny();
                        if (receiver.isPresent()) {
                            Log.d(
                                    TAG,
                                    "handleBassStateReady: restore the source for device, " + sink);
                            mPausedBroadcastSinks.add(sink);
                            logPausedBroadcastsAndSinks();
                            // Not call resume if paused by host or monitored as it will be called
                            // later
                            if (!isSuspendedByHostPauseReason(metadata.getBroadcastId())
                                    && !isBigMonitoringPauseReason(metadata.getBroadcastId())
                                    && !isOorMonitoringPauseReason(metadata.getBroadcastId())) {
                                resumeReceiversSourceSynchronization(metadata.getBroadcastId());
                            }
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
                    if (pendingSourcesToAdd.sink.equals(sink)) {
                        Log.d(
                                TAG,
                                "handleBassStateReady: retry adding source with device, " + sink);
                        addSource(
                                pendingSourcesToAdd.sink,
                                pendingSourcesToAdd.sourceMetadata,
                                false);
                        iterator.remove();
                        return;
                    }
                }
            }
            return;
        }

        // Find the first source that needs action (either restore or add from peer).
        Optional<Integer> broadcastIdForAction = findActionableBroadcastId(sink);

        if (broadcastIdForAction.isPresent()) {
            int broadcastId = broadcastIdForAction.get();
            mPausedBroadcastSinks.add(sink);
            if (Flags.leaudioBroadcastStopBigMonitoringBasedOnBisSync()) {
                mSinksToRestoreFromPeer.add(sink);
            }
            logPausedBroadcastsAndSinks();

            // Not call resume if paused by host or monitored as it will be called later
            if (!isSuspendedByHostPauseReason(broadcastId)
                    && !isBigMonitoringPauseReason(broadcastId)
                    && !isOorMonitoringPauseReason(broadcastId)) {
                resumeReceiversSourceSynchronization(broadcastId);
            }
            return;
        }

        // Continue to check if there is pending source to add due to BASS not ready
        AddSourceData sourceToRetry = null;
        synchronized (mPendingSourcesToAdd) {
            Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
            while (iterator.hasNext()) {
                AddSourceData pendingSource = iterator.next();
                if (pendingSource.sink.equals(sink)) {
                    sourceToRetry = pendingSource;
                    iterator.remove();
                    break;
                }
            }
        }

        if (sourceToRetry != null) {
            Log.d(TAG, "handleBassStateReady: retry adding source with device, " + sink);
            addSource(sourceToRetry.sink, sourceToRetry.sourceMetadata, false);
        }
    }

    /* Handle device Bass state setup failed */
    private void handleBassStateSetupFailed(BluetoothDevice sink) {
        // Check if there is pending source to add due to BASS not ready
        synchronized (mPendingSourcesToAdd) {
            Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
            while (iterator.hasNext()) {
                AddSourceData pendingSourcesToAdd = iterator.next();
                if (pendingSourcesToAdd.sink.equals(sink)) {
                    if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                        iterator.remove();
                    }
                    mCallbacks.notifySourceAddFailed(
                            pendingSourcesToAdd.sink,
                            pendingSourcesToAdd.sourceMetadata,
                            BluetoothStatusCodes.ERROR_REMOTE_NOT_ENOUGH_RESOURCES);
                    if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
                        iterator.remove();
                    }
                    return;
                }
            }
        }
    }

    private void logPausedBroadcastsAndSinks() {
        Log.d(
                TAG,
                ("mPausedBroadcastIds: " + mPausedBroadcastIds)
                        + (", mPausedBroadcastSinks: " + mPausedBroadcastSinks)
                        + (", mSinksToRestoreFromPeer: " + mSinksToRestoreFromPeer));
    }

    private boolean isSuspendedByHostPauseReason(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseReason.SUSPENDED_BY_HOST));
    }

    private boolean isBigMonitoringPauseReason(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseReason.BIG_MONITORING));
    }

    private boolean isOorMonitoringPauseReason(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseReason.OOR_MONITORING));
    }

    private boolean isResumingPauseReason(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && mPausedBroadcastIds.get(broadcastId).equals(PauseReason.RESUMING));
    }

    private boolean isMonitoringOrResumingPauseReason(int broadcastId) {
        return (mPausedBroadcastIds.containsKey(broadcastId)
                && (mPausedBroadcastIds.get(broadcastId).equals(PauseReason.OOR_MONITORING)
                        || mPausedBroadcastIds.get(broadcastId).equals(PauseReason.BIG_MONITORING)
                        || mPausedBroadcastIds.get(broadcastId).equals(PauseReason.RESUMING)));
    }

    private boolean isWaitingForPast(int broadcastId) {
        synchronized (mPastResponseTimeouts) {
            return mPastResponseTimeouts.values().stream()
                    .flatMap(sinkTimeouts -> sinkTimeouts.values().stream())
                    .anyMatch(timeout -> timeout.mBroadcastId == broadcastId);
        }
    }

    private boolean isWaitingForMetadata(int broadcastId) {
        synchronized (mSinksWaitingForMetadata) {
            return mSinksWaitingForMetadata.values().stream()
                    .anyMatch(value -> value == broadcastId);
        }
    }

    private void checkAndStopBroadcastMonitoring() {
        Log.d(TAG, "checkAndStopBroadcastMonitoring");
        Iterator<Integer> iterator = mPausedBroadcastIds.keySet().iterator();
        while (iterator.hasNext()) {
            int pausedBroadcastId = iterator.next();
            if (!isAnyReceiverSyncedToBroadcast(pausedBroadcastId)) {
                mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
                mTimeoutHandler.stop(pausedBroadcastId, MESSAGE_OOR_MONITOR_TIMEOUT);

                if (isMonitoringOrResumingPauseReason(pausedBroadcastId)
                        || (isSuspendedByHostPauseReason(pausedBroadcastId)
                                && mPausedBroadcastSinks.isEmpty())) {
                    iterator.remove();
                }
                if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        || !Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
                    // No need to cancelActiveSync as stopBackgroundSearching is called after that
                    synchronized (mSearchScanCallbackLock) {
                        // when searching is stopped then stop active sync
                        if (!isAnySearchInProgress()) {
                            cancelActiveSync(getSyncHandleForBroadcastId(pausedBroadcastId));
                        }
                    }
                }
                logPausedBroadcastsAndSinks();
            }
        }
    }

    private void stopBroadcastMonitoring(int broadcastId, boolean hostInitiated) {
        Log.d(
                TAG,
                "stopBroadcastMonitoring broadcastId: "
                        + broadcastId
                        + ", hostInitiated: "
                        + hostInitiated);
        mTimeoutHandler.stop(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
        mTimeoutHandler.stop(broadcastId, MESSAGE_OOR_MONITOR_TIMEOUT);
        if (hostInitiated) {
            mPausedBroadcastIds.put(broadcastId, PauseReason.SUSPENDED_BY_HOST);
        } else {
            mPausedBroadcastIds.remove(broadcastId);
            mPausedBroadcastSinks.clear();
            mSinksToRestoreFromPeer.clear();
        }
        logPausedBroadcastsAndSinks();
        stopBackgroundSearching();
        stopActiveSync(broadcastId);
    }

    private void stopActiveSync(int broadcastId) {
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                && Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            // No need to use stopActiveSync as stopBackgroundSearching is always called before that
            return;
        }

        synchronized (mSearchScanCallbackLock) {
            // when searching is stopped then stop active sync
            if (!isAnySearchInProgress()
                    && !isWaitingForPast(broadcastId)
                    && !isWaitingForMetadata(broadcastId)) {
                cancelActiveSync(getSyncHandleForBroadcastId(broadcastId));
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
            stopBroadcastMonitoring(pair.first.getBroadcastId(), /* hostInitiated */ true);
        }
    }

    /** Request all receivers to suspend broadcast sources synchronization */
    @VisibleForTesting
    void suspendAllReceiversSourceSynchronization() {
        List<Pair<BluetoothDevice, Integer>> sourcesToModify = new ArrayList<>();
        HashSet<Integer> broadcastIdsToStopMonitoring = new HashSet<>();

        sEventLogger.logd(TAG, "Suspend all receivers source synchronization");

        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                broadcastIdsToStopMonitoring.add(receiveState.getBroadcastId());

                sourcesToModify.add(new Pair<>(device, receiveState.getSourceId()));

                sEventLogger.logd(TAG, "Add broadcast sink to paused cache: " + device);
                mPausedBroadcastSinks.add(device);
            }
        }

        for (int broadcastIdToStopMonitoring : broadcastIdsToStopMonitoring) {
            stopBroadcastMonitoring(broadcastIdToStopMonitoring, /* hostInitiated */ true);
        }

        /* Suspend all previously marked sources with modify source operation */
        for (Pair<BluetoothDevice, Integer> pair : sourcesToModify) {
            BluetoothDevice device = pair.first;

            BassClientStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.e(
                        TAG,
                        "suspendAllReceiversSourceSynchronization: invalid state machine for "
                                + "device: "
                                + pair.first);
                continue;
            }

            int sourceId = pair.second;
            BluetoothLeBroadcastMetadata metadata = sm.getCurrentBroadcastMetadata(sourceId);
            metadata = getMetadataWithChannelUnselected(metadata);
            sendModifySource(
                    sm,
                    device,
                    sourceId,
                    BassConstants.FLAG_SYNC_PA | BassConstants.FLAG_SYNC_BIS_CHANNEL_PREFERENCE,
                    metadata,
                    ModifyCallReason.SUSPEND);
        }
    }

    /** Request receivers to stop broadcast sources synchronization and remove them */
    public void stopReceiversSourceSynchronization(int broadcastId) {
        sEventLogger.logd(TAG, "Stop receivers source synchronization: " + broadcastId);
        stopSourceReceivers(broadcastId);
    }

    private static boolean doesReceiveStateNeedsResume(
            Optional<BluetoothLeBroadcastReceiveState> receiveState) {
        /* Only receive states which are synced to BIS doesn't need to be resumed. */
        if (receiveState != null
                && receiveState.isPresent()
                && isReceiveStateSyncedToBis(receiveState.get())) {
            return false;
        }

        return true;
    }

    /** Request receivers to resume all broadcast source synchronization */
    public void resumeReceiversSourceSynchronization() {
        resumeReceiversSourceSynchronization(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    private void resumeReceiversSourceSynchronization(int broadcastIdToResumeFlagged) {
        final int broadcastIdToResume =
                (!Flags.leaudioBroadcastAutoSwitchAnnouncement())
                        ? LeAudioConstants.INVALID_BROADCAST_ID
                        : broadcastIdToResumeFlagged;

        sEventLogger.logd(
                TAG,
                "Resume receivers source synchronization: broadcastIdToResume: "
                        + broadcastIdToResume);

        HashSet<BluetoothDevice> pausedSinksToRemove = new HashSet<>();
        for (BluetoothDevice sink : mPausedBroadcastSinks) {
            sEventLogger.logd(TAG, "Resume broadcast sink from paused cache: " + sink);
            Map<Integer, BluetoothLeBroadcastMetadata> entry =
                    mBroadcastMetadataMap.getOrDefault(sink, Collections.emptyMap());

            pausedSinksToRemove.add(sink);

            for (BluetoothLeBroadcastMetadata metadata : entry.values()) {
                if (metadata == null) {
                    Log.w(
                            TAG,
                            "resumeReceiversSourceSynchronization: failed to get metadata to"
                                    + " resume sink: "
                                    + sink);
                    continue;
                }

                int broadcastId = metadata.getBroadcastId();
                Log.d(
                        TAG,
                        "resumeReceiversSourceSynchronization: "
                                + ("sink: " + sink)
                                + (", broadcastId: " + broadcastId));

                if ((broadcastIdToResume != LeAudioConstants.INVALID_BROADCAST_ID)
                        && (broadcastId != broadcastIdToResume)) {
                    Log.d(
                            TAG,
                            "resumeReceiversSourceSynchronization: different broadcastIdToResume: "
                                    + broadcastIdToResume);
                    continue;
                }

                if (Flags.leaudioBroadcastStopBigMonitoringBasedOnBisSync()
                        && !isAnyChannelSelected(metadata)
                        && !mSinksToRestoreFromPeer.contains(sink)) {
                    Log.d(TAG, "resumeReceiversSourceSynchronization: paused by user");
                    continue;
                }

                // For each device, find the source ID having this broadcast ID
                BassClientStateMachine sm = mStateMachines.get(sink);
                if (sm == null) {
                    // Remove it only if no monitoring in case that other sink needs it
                    if (!isMonitoringOrResumingPauseReason(broadcastId)) {
                        mPausedBroadcastIds.remove(broadcastId);
                    }

                    Log.w(
                            TAG,
                            "resumeReceiversSourceSynchronization: Failed to get state machine"
                                    + " for device: "
                                    + sink);
                    continue;
                }

                List<BluetoothLeBroadcastReceiveState> sources = sm.getAllSources();
                Optional<BluetoothLeBroadcastReceiveState> receiveState =
                        sources.stream().filter(e -> e.getBroadcastId() == broadcastId).findAny();

                // Receiver synced, clear paused sink and broadcastId (if not already monitoring
                // or resuming)
                if (!doesReceiveStateNeedsResume(receiveState)) {
                    // Remove it only if no monitoring in case that other sink needs it
                    if (!isMonitoringOrResumingPauseReason(broadcastId)) {
                        mPausedBroadcastIds.remove(broadcastId);
                    }
                    Log.d(TAG, "resumeReceiversSourceSynchronization: already synced");
                    continue;
                }

                // Paused sink has to remain
                pausedSinksToRemove.remove(sink);

                // Set timer for BIG in case of syncLost
                if (!mTimeoutHandler.isStarted(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT)
                        && !isLocalBroadcast(metadata)) {
                    mTimeoutHandler.stop(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
                    mTimeoutHandler.start(
                            broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT, sBigMonitorTimeout);
                }

                // Past requested, wait for receiver sync by itself
                if (receiveState.isPresent()
                        && receiveState.get().getPaSyncState()
                                == BluetoothLeBroadcastReceiveState
                                        .PA_SYNC_STATE_SYNCINFO_REQUEST) {
                    // Set resuming only if no monitoring in case that other sink needs it
                    if (!isMonitoringOrResumingPauseReason(broadcastId)) {
                        mPausedBroadcastIds.put(broadcastId, PauseReason.RESUMING);
                    }
                    Log.d(TAG, "resumeReceiversSourceSynchronization: PAST requested");
                    continue;
                }

                // Broadcast already synced or local
                if (isLocalBroadcast(metadata)
                        || getActiveSyncedSources()
                                .contains(getSyncHandleForBroadcastId(broadcastId))) {
                    // Set resuming
                    mPausedBroadcastIds.put(broadcastId, PauseReason.RESUMING);
                    // Receiver has source so modify it
                    if (receiveState.isPresent()) {
                        Log.d(TAG, "resumeReceiversSourceSynchronization: modify source");
                        int sourceId = receiveState.get().getSourceId();
                        updateSourceToResumeBroadcast(sink, sourceId, metadata);
                        // Receive has no source so add it
                    } else {
                        Log.d(TAG, "resumeReceiversSourceSynchronization: add source");
                        addSource(sink, metadata, /* isGroupOp */ false);
                    }
                    // Broadcast not synced, set monitoring and sync to the broadcaster
                } else {
                    Log.d(TAG, "resumeReceiversSourceSynchronization: register sync");
                    mPausedBroadcastIds.put(broadcastId, PauseReason.BIG_MONITORING);
                    addSelectSourceRequest(metadata, /* hasPriority */ true);
                }
            }
        }
        mPausedBroadcastSinks.removeAll(pausedSinksToRemove);
        mSinksToRestoreFromPeer.removeAll(pausedSinksToRemove);

        logPausedBroadcastsAndSinks();
    }

    private void sendModifySource(
            BassClientStateMachine sm,
            BluetoothDevice device,
            int sourceId,
            int arg,
            BluetoothLeBroadcastMetadata metadata,
            ModifyCallReason modifyCallReason) {

        sEventLogger.logi(
                TAG,
                "Modify Broadcast Source: "
                        + ("reason: " + modifyCallReason)
                        + (", device: " + device)
                        + (", sourceId: " + sourceId)
                        + (", broadcastId: "
                                + (metadata != null
                                        ? metadata.getBroadcastId()
                                        : LeAudioConstants.INVALID_BROADCAST_ID))
                        + (", broadcastName: "
                                + (metadata != null ? metadata.getBroadcastName() : "")));

        startReactivateGroupMonitor(device, /* inactivated */ false);

        Message message = sm.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
        message.arg1 = sourceId;
        message.arg2 = arg;
        message.obj = metadata;
        sm.sendMessage(message);
    }

    private void updateSourceToResumeBroadcast(
            BluetoothDevice sink, int sourceId, BluetoothLeBroadcastMetadata metadata) {
        BassClientStateMachine stateMachine = mStateMachines.get(sink);
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

        // Use no preference BIS sync
        sendModifySource(
                stateMachine,
                sink,
                sourceId,
                BassConstants.FLAG_SYNC_PA,
                metadata,
                ModifyCallReason.RESUME);
    }

    private void updateMetadataFromReceiveState(
            BluetoothDevice sink, int broadcastId, BluetoothLeBroadcastReceiveState receiveState) {
        // If the stream is suspended by host, that means we already have set selected channels
        // to be resumed. Do not overwrite metadata - initial update was enough.
        if (!Flags.leaudioBroadcastStopBigMonitoringBasedOnBisSync()
                || mPausedBroadcastSinks.contains(sink)) {
            return;
        }

        // based on channels received in receiveState, update selected channels in metadata.
        BluetoothLeBroadcastMetadata currentMetadata =
                getMetadataFromSinkWithBroadcastId(sink, broadcastId);

        BluetoothLeBroadcastMetadata newMetadata =
                getSourceMetadata(sink, receiveState.getSourceId());
        if (currentMetadata == null) {
            Log.d(TAG, "updateMetadataFromReceiveState: no current metadata available");
        } else if (newMetadata == null) {
            Log.d(TAG, "updateMetadataFromReceiveState: no source metadata available");
        } else if (!Objects.equals(newMetadata, currentMetadata)) {
            storeSinkMetadata(sink, broadcastId, newMetadata);
        }
    }

    private void handlePausedBroadcasts(
            BluetoothDevice sink,
            int broadcastId,
            BluetoothLeBroadcastReceiveState receiveState,
            boolean broadcastSyncIsAdvancing) {
        Log.d(TAG, "handlePausedBroadcasts: sink: " + sink + ", broadcastId: " + broadcastId);
        // If sink unsynced then remove potentially waiting past and check if any broadcast
        // monitoring should be stopped for all broadcast Ids
        if (isEmptyBluetoothDevice(receiveState.getSourceDevice())) {
            Log.d(TAG, "handlePausedBroadcasts: empty RS");
            if (Flags.leaudioBroadcastTreatEmptyRsExplicitly()) {
                mPausedBroadcastSinks.remove(sink);
                mSinksToRestoreFromPeer.remove(sink);
                removeSinkMetadataForRemovedBroadcasts(sink);
                logPausedBroadcastsAndSinks();
            }
            synchronized (mSinksWaitingForMetadata) {
                Integer broadcastIdForMetadata = mSinksWaitingForMetadata.remove(sink);
                if (broadcastIdForMetadata != null) {
                    mTimeoutHandler.stop(broadcastIdForMetadata, MESSAGE_UPDATE_METADATA_TIMEOUT);
                }
                if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()
                        && mSinksWaitingForMetadata.isEmpty()
                        && mIsBackgroundScan) {
                    stopSearchingForSources(/* foreground= */ false);
                }
            }
            checkAndStopBroadcastMonitoring();
            checkAndStopAnnouncementMonitoring();
            stopBackgroundSearching();

            // If paused by host then stop active sync, it could be not stopped, if during previous
            // stop there was pending past or metadata request.
        } else if (isSuspendedByHostPauseReason(broadcastId)) {
            Log.d(TAG, "handlePausedBroadcasts: suspended by host");
            stopBackgroundSearching();
            stopActiveSync(broadcastId);
            // Clear paused broadcast sink if autonomously resumed by remote
            if (Flags.leaudioBroadcastStopBigMonitoringBasedOnBisSync()
                    && !isAnyChannelSelected(getMetadataFromSinkWithBroadcastId(sink, broadcastId))
                    && isReceiveStateSyncedToBis(receiveState)
                    && (!Flags.leaudioBroadcastCheckSyncAdvancementOnRemoteResume()
                            || broadcastSyncIsAdvancing)) {
                mPausedBroadcastSinks.remove(sink);
                mSinksToRestoreFromPeer.remove(sink);
                // If all sinks for this broadcast are actively synced (PA or BIG) and there is no
                // more sinks to resume then stop monitoring
                if (isAllReceiversActive(broadcastId) && mPausedBroadcastSinks.isEmpty()) {
                    stopBroadcastMonitoring(broadcastId, /* hostInitiated */ false);
                }
            }

            // If sink has broadcast synced && not paused by the host
        } else {
            // If sink actively synced (PA or BIG)
            if (isReceiverActive(receiveState)) {
                Log.d(TAG, "handlePausedBroadcasts: active");
                // Clear paused broadcast sink (not need to resume manually)
                mPausedBroadcastSinks.remove(sink);
                mSinksToRestoreFromPeer.remove(sink);

                // If all sinks for this broadcast are actively synced (PA or BIG) and there is no
                // more sinks to resume then stop monitoring
                if (isAllReceiversActive(broadcastId) && mPausedBroadcastSinks.isEmpty()) {
                    stopBroadcastMonitoring(broadcastId, /* hostInitiated */ false);
                }
                // If broadcast not local and sink not requested for PAST
            } else if ((!isLocalBroadcast(receiveState)
                    && receiveState.getPaSyncState()
                            != BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST)) {

                // If sink synchronization not advancing
                if (!broadcastSyncIsAdvancing) {
                    Log.d(TAG, "handlePausedBroadcasts: unsynced");
                    // Add sink to monitor and start BIG monitoring if not started yet to
                    // automatically resume it when possible
                    boolean newPausedSinkAdded = false;
                    if (!mPausedBroadcastSinks.contains(sink)) {
                        mPausedBroadcastSinks.add(sink);
                        newPausedSinkAdded = true;
                    }
                    if (!mPausedBroadcastIds.containsKey(broadcastId)) {
                        mPausedBroadcastIds.put(broadcastId, PauseReason.BIG_MONITORING);
                        mTimeoutHandler.stop(broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT);
                        mTimeoutHandler.start(
                                broadcastId, MESSAGE_BIG_MONITOR_TIMEOUT, sBigMonitorTimeout);
                        addSelectSourceRequest(broadcastId, /* hasPriority */ true);
                    }
                    logPausedBroadcastsAndSinks();
                    // If new paused sink, call resume in case that it is already in resume state
                    if (newPausedSinkAdded && isResumingPauseReason(broadcastId)) {
                        resumeReceiversSourceSynchronization(broadcastId);
                    }
                }
            }
        }
    }

    /** Handle Unicast source stream status change */
    public void handleUnicastSourceStreamStatusChange(int status) {
        mUnicastSourceStreamStatus = Optional.of(status);
        Log.d(TAG, "handleUnicastSourceStreamStatusChange: status: " + status);

        if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED) {
            if (isPrimaryDeviceSyncedToExternalBroadcast()) {
                cacheSuspendingSources(LeAudioConstants.INVALID_BROADCAST_ID);
            }
        } else if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED) {
            /* Resume paused receivers if there are some */
            if (!mPausedBroadcastSinks.isEmpty()) {
                mAudioActiveStates.entrySet().stream()
                        .filter(e -> e.getValue() == LtvData.AudioActiveState.TRUE)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .ifPresentOrElse(
                                broadcastId -> resumeReceiversSourceSynchronization(broadcastId),
                                () -> {
                                    if (mIsUnicastAutoResuming) {
                                        mIsUnicastAutoResuming = false;
                                        Log.d(
                                                TAG,
                                                "handleUnicastSourceStreamStatusChange:"
                                                        + " mIsUnicastAutoResuming=false");
                                    }
                                    resumeReceiversSourceSynchronization();
                                });
            }
        } else if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_STREAMING) {
            Log.d(TAG, "Ignore STREAMING source status");
        } else if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE) {
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

    public boolean isPrimaryDeviceSyncedToExternalBroadcast() {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.e(TAG, "no LeAudioService");
            return false;
        }

        for (BluetoothDevice device : getConnectedDevices()) {
            if (!leAudio.get().isPrimaryDevice(device)) {
                continue;
            }

            if (getAllSources(device).stream().anyMatch(rs -> !isLocalBroadcast(rs))) {
                return true;
            }
        }

        return false;
    }

    private static boolean isReceiveStateSyncedToBis(
            BluetoothLeBroadcastReceiveState receiveState) {
        for (int i = 0; i < receiveState.getNumSubgroups(); i++) {
            if (isSyncedToBroadcastStream(receiveState.getBisSyncState().get(i))) {
                return true;
            }
        }

        return false;
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
            return isReceiveStateSyncedToBis(receiveState);
        }
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
                    Log.d(TAG, "getExternalBroadcastsActiveOnSinks: " + receiveState);
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
            if (!getAllSources(device).isEmpty()) {
                activeSinks.add(device);
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
                .toList();
    }

    private static boolean isSyncedToBroadcastStream(Long syncState) {
        return syncState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_NOT_SYNC_TO_BIS
                && syncState != BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG;
    }

    private Set<Integer> getBroadcastIdsOfSyncedBroadcasters() {
        return getActiveSyncedSources().stream()
                .map(this::getBroadcastIdForSyncHandle)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<Integer> getBroadcastIdsWaitingForPAST() {
        synchronized (mPastResponseTimeouts) {
            return mPastResponseTimeouts.values().stream()
                    .flatMap(sinkTimeouts -> sinkTimeouts.values().stream())
                    .map(timeout -> timeout.mBroadcastId)
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    private Set<Integer> getBroadcastIdsWaitingForMetadata() {
        synchronized (mSinksWaitingForMetadata) {
            return mSinksWaitingForMetadata.values().stream()
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    private Set<Integer> getBroadcastIdsWaitingForAddSource() {
        synchronized (mPendingSourcesToAdd) {
            return mPendingSourcesToAdd.stream()
                    .map(pendingSource -> pendingSource.sourceMetadata.getBroadcastId())
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    private Set<Integer> getBroadcastIdsWaitingForAddSourceByUri() {
        synchronized (mPendingSourcesToAddByUri) {
            return mPendingSourcesToAddByUri.stream()
                    .map(PendingSourceToAddByUri::broadcastId)
                    .filter(id -> id != LeAudioConstants.INVALID_BROADCAST_ID)
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    private Set<Integer> getBroadcastIdsOfPendingSourceOperation() {
        HashSet<Integer> pendingBroadcastIds = new HashSet<>();
        synchronized (mStateMachines) {
            for (BassClientStateMachine sm : mStateMachines.values()) {
                int broadcastId = sm.getPendingOperationBroadcastId();
                if (broadcastId != LeAudioConstants.INVALID_BROADCAST_ID) {
                    pendingBroadcastIds.add(broadcastId);
                }
            }
        }
        return pendingBroadcastIds;
    }

    private Set<Integer> getMonitoredOrResumingBroadcastIds() {
        return mPausedBroadcastIds.keySet().stream()
                .filter(this::isMonitoringOrResumingPauseReason)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<Integer> getBroadcastsToSync() {
        // Collect broadcasts which should be sync and/or cache should remain.
        // Broadcasts, which has to be synced, needs to have cache available.
        // Broadcasts which only cache should remain (i.e. because of potential resume)
        // has to be synced too to show it on the list before resume.
        LinkedHashSet<Integer> broadcastsToSync = new LinkedHashSet<>();

        // Sync to the broadcasts waiting for Metadata update
        broadcastsToSync.addAll(getBroadcastIdsWaitingForMetadata());

        // Keep already synced broadcasts
        broadcastsToSync.addAll(getBroadcastIdsOfSyncedBroadcasters());

        // Sync to the broadcasts already synced with sinks
        broadcastsToSync.addAll(getExternalBroadcastsActiveOnSinks());

        // Sync to the broadcasts waiting for PAST
        broadcastsToSync.addAll(getBroadcastIdsWaitingForPAST());

        // Sync to the broadcasts waiting for adding source (could be by resume too).
        broadcastsToSync.addAll(getBroadcastIdsWaitingForAddSource());

        // Sync to the broadcasts waiting for adding source by URI
        broadcastsToSync.addAll(getBroadcastIdsWaitingForAddSourceByUri());

        // Sync to the broadcasts with pending source operation to guard switch
        // procedure
        broadcastsToSync.addAll(getBroadcastIdsOfPendingSourceOperation());

        // Sync to the paused broadcasts
        broadcastsToSync.addAll(mPausedBroadcastIds.keySet());

        // Sync to monitored announcement
        broadcastsToSync.addAll(mAudioActiveStates.keySet());

        Log.d(TAG, "Broadcasts to sync: " + broadcastsToSync);

        return broadcastsToSync;
    }

    private Set<Integer> getBroadcastsToKeepSynced() {
        // Collect broadcasts which should stay synced after search stops
        HashSet<Integer> broadcastsToKeepSynced = new HashSet<>();

        // Keep broadcasts waiting for Metadata update
        broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForMetadata());

        // Keep broadcasts waiting for PAST
        broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForPAST());

        // Keep broadcasts waiting for adding source (could be by resume too)
        broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForAddSource());

        // Keep broadcasts waiting for adding source by name
        broadcastsToKeepSynced.addAll(getBroadcastIdsWaitingForAddSourceByUri());

        // Keep broadcast with pending source operation to guard switch procedure
        broadcastsToKeepSynced.addAll(getBroadcastIdsOfPendingSourceOperation());

        // Keep broadcast monitored or during resuming
        broadcastsToKeepSynced.addAll(getMonitoredOrResumingBroadcastIds());

        // Keep monitored announcement
        broadcastsToKeepSynced.addAll(mAudioActiveStates.keySet());

        Log.d(TAG, "Broadcasts to keep synced: " + broadcastsToKeepSynced);

        return broadcastsToKeepSynced;
    }

    /** Handle broadcast state changed */
    public void notifyBroadcastStateChanged(int state, int broadcastId) {
        switch (state) {
            case LeAudioStackEvent.BROADCAST_STATE_STOPPED:
                if (mLocalBroadcastReceivers.remove(broadcastId) != null) {
                    sEventLogger.logd(TAG, "Broadcast ID: " + broadcastId + ", stopped");
                    if (Flags.leaudioFallbackGroupSelection()) {
                        updateDefaultBroadcastToUnicastFallbackGroup();
                    }
                }
                break;
            case LeAudioStackEvent.BROADCAST_STATE_CONFIGURING:
            case LeAudioStackEvent.BROADCAST_STATE_PAUSED:
            case LeAudioStackEvent.BROADCAST_STATE_ENABLING:
            case LeAudioStackEvent.BROADCAST_STATE_DISABLING:
            case LeAudioStackEvent.BROADCAST_STATE_STOPPING:
            case LeAudioStackEvent.BROADCAST_STATE_STREAMING:
            default:
                break;
        }
    }

    private void updateDefaultBroadcastToUnicastFallbackGroup() {
        final var leAudio = getAdapterService().getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.d(TAG, "updateDefaultBroadcastToUnicastFallbackGroup: No available LeAudioService");
            return;
        }

        leAudio.get()
                .selectDefaultBroadcastToUnicastFallbackGroup(
                        mLocalBroadcastReceivers.values().stream()
                                .flatMap(Set::stream)
                                .collect(Collectors.toSet()));
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
                case MSG_SOURCE_ADDED, MSG_SOURCE_ADDED_FAILED -> {
                    ObjParams param = (ObjParams) msg.obj;
                    sink = param.device;
                    sService.checkForPendingGroupOpRequest(
                            sink, reason, BassClientStateMachine.ADD_BCAST_SOURCE, param.obj2);
                }
                case MSG_SOURCE_REMOVED, MSG_SOURCE_REMOVED_FAILED -> {
                    sink = (BluetoothDevice) msg.obj;
                    sService.checkForPendingGroupOpRequest(
                            sink,
                            reason,
                            BassClientStateMachine.REMOVE_BCAST_SOURCE,
                            Integer.valueOf(msg.arg2));
                }
                default -> {}
            }
        }

        private static boolean handleServiceInternalMessage(Message msg) {
            if (sService == null) {
                Log.e(TAG, "Service is null");
                return false;
            }
            BluetoothDevice sink;

            return switch (msg.what) {
                case MSG_BASS_STATE_READY -> {
                    sink = (BluetoothDevice) msg.obj;
                    sService.handleBassStateReady(sink);
                    yield true;
                }
                case MSG_BASS_STATE_SETUP_FAILED -> {
                    sink = (BluetoothDevice) msg.obj;
                    sService.handleBassStateSetupFailed(sink);
                    yield true;
                }
                default -> false;
            };
        }

        @Override
        public void handleMessage(Message msg) {
            if (handleServiceInternalMessage(msg)) {
                Log.d(TAG, "Handled internal message: " + msg.what);
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

        private record ObjParams(BluetoothDevice device, Object obj2) {}

        private static void invokeCallback(
                IBluetoothLeBroadcastAssistantCallback callback, Message msg)
                throws RemoteException {
            final int reason = msg.arg1;
            final int sourceId = msg.arg2;
            ObjParams param;
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_SEARCH_STARTED -> callback.onSearchStarted(reason);
                case MSG_SEARCH_STARTED_FAILED -> callback.onSearchStartFailed(reason);
                case MSG_SEARCH_STOPPED -> callback.onSearchStopped(reason);
                case MSG_SEARCH_STOPPED_FAILED -> callback.onSearchStopFailed(reason);
                case MSG_SOURCE_FOUND ->
                        callback.onSourceFound((BluetoothLeBroadcastMetadata) msg.obj);
                case MSG_SOURCE_ADDED -> {
                    param = (ObjParams) msg.obj;
                    sink = param.device;
                    callback.onSourceAdded(sink, sourceId, reason);
                }
                case MSG_SOURCE_ADDED_FAILED -> {
                    param = (ObjParams) msg.obj;
                    sink = param.device;
                    BluetoothLeBroadcastMetadata metadata =
                            (BluetoothLeBroadcastMetadata) param.obj2;
                    callback.onSourceAddFailed(sink, metadata, reason);
                }
                case MSG_SOURCE_MODIFIED ->
                        callback.onSourceModified((BluetoothDevice) msg.obj, sourceId, reason);
                case MSG_SOURCE_MODIFIED_FAILED ->
                        callback.onSourceModifyFailed((BluetoothDevice) msg.obj, sourceId, reason);
                case MSG_SOURCE_REMOVED -> {
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoved(sink, sourceId, reason);
                }
                case MSG_SOURCE_REMOVED_FAILED -> {
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoveFailed(sink, sourceId, reason);
                }
                case MSG_RECEIVESTATE_CHANGED -> {
                    param = (ObjParams) msg.obj;
                    sink = param.device;
                    BluetoothLeBroadcastReceiveState state =
                            (BluetoothLeBroadcastReceiveState) param.obj2;
                    callback.onReceiveStateChanged(sink, sourceId, state);
                }
                case MSG_SOURCE_LOST -> callback.onSourceLost(sourceId);
                default -> Log.e(TAG, "Invalid msg: " + msg.what);
            }
        }

        void notifySearchStarted(int reason) {
            sEventLogger.logi(TAG, "notifySearchStarted: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED, reason, 0).sendToTarget();
        }

        void notifySearchStartFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStartFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED_FAILED, reason, 0).sendToTarget();
        }

        void notifySearchStopped(int reason) {
            sEventLogger.logi(TAG, "notifySearchStopped: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED, reason, 0).sendToTarget();
        }

        void notifySearchStopFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStopFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED_FAILED, reason, 0).sendToTarget();
        }

        void notifySourceFound(BluetoothLeBroadcastMetadata source) {
            sEventLogger.logi(
                    TAG,
                    "notifySourceFound: source: "
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
            sService.localNotifySourceAdded(sink, recvState, reason);

            sEventLogger.logi(
                    TAG,
                    "notifySourceAdded: reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", source: "
                            + recvState.getSourceId());

            ObjParams param = new ObjParams(sink, recvState);
            obtainMessage(MSG_SOURCE_ADDED, reason, recvState.getSourceId(), param).sendToTarget();
        }

        void notifySourceAddFailed(
                BluetoothDevice sink, BluetoothLeBroadcastMetadata source, int reason) {
            sService.checkAndResetGroupAllowedContextMask();
            sService.localNotifySourceAddFailed(sink, source);

            sEventLogger.loge(
                    TAG,
                    "notifySourceAddFailed: reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", source: "
                            + source);
            ObjParams param = new ObjParams(sink, source);
            obtainMessage(MSG_SOURCE_ADDED_FAILED, reason, 0, param).sendToTarget();
        }

        void notifySourceModified(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logd(
                    TAG,
                    "notifySourceModified: "
                            + "reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId);
            obtainMessage(MSG_SOURCE_MODIFIED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceModifyFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceModifyFailed: "
                            + "reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId);
            obtainMessage(MSG_SOURCE_MODIFIED_FAILED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoved(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logi(
                    TAG,
                    "notifySourceRemoved: "
                            + "reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId);
            obtainMessage(MSG_SOURCE_REMOVED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoveFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceRemoveFailed: "
                            + "reason: "
                            + reason
                            + ", sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId);
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

            sEventLogger.logi(
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
            sEventLogger.logi(TAG, "notifySourceLost: broadcastId: " + broadcastId);
            obtainMessage(MSG_SOURCE_LOST, 0, broadcastId).sendToTarget();
        }

        void notifyBassStateReady(BluetoothDevice sink) {
            sEventLogger.logi(TAG, "notifyBassStateReady: sink: " + sink);
            obtainMessage(MSG_BASS_STATE_READY, sink).sendToTarget();
        }

        void notifyBassStateSetupFailed(BluetoothDevice sink) {
            sEventLogger.logi(TAG, "notifyBassStateSetupFailed: sink: " + sink);
            obtainMessage(MSG_BASS_STATE_SETUP_FAILED, sink).sendToTarget();
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
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
}
