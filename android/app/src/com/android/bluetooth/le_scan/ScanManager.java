/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.le.ScanSettings.getScanModeString;

import static com.android.bluetooth.le_scan.ScanUtil.ACTION_REFRESH_BATCHED_SCAN;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_BOTH;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_FULL;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_TRUNCATED;
import static com.android.bluetooth.le_scan.ScanUtil.clearAutoBatchScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.isAllMatchesAutoBatchScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.isAutoBatchScanClientEnabled;
import static com.android.bluetooth.le_scan.ScanUtil.isBatchClient;
import static com.android.bluetooth.le_scan.ScanUtil.isDowngradedScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.isExemptFromAutoBatchScanUpdate;
import static com.android.bluetooth.le_scan.ScanUtil.isExemptFromScanTimeout;
import static com.android.bluetooth.le_scan.ScanUtil.isForceDowngradedScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.isPhyConfigured;
import static com.android.bluetooth.le_scan.ScanUtil.minScanMode;
import static com.android.bluetooth.le_scan.ScanUtil.priorityForScanMode;
import static com.android.bluetooth.le_scan.ScanUtil.requiresLocationOn;
import static com.android.bluetooth.le_scan.ScanUtil.requiresScreenOn;
import static com.android.bluetooth.le_scan.ScanUtil.setAutoBatchScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.setOpportunisticScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.shouldUpdateScan;
import static com.android.bluetooth.le_scan.ScanUtil.upgradeScanModeByOneLevel;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.bluetooth.Utils;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Class that handles Bluetooth LE scan related operations. */
class ScanManager {
    private static final String TAG = ScanManager.class.getSimpleName();

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. To be deleted [START]
    // Messages for handling BLE scan operations.
    @VisibleForTesting static final int MSG_START_BLE_SCAN = 0;
    @VisibleForTesting static final int MSG_STOP_BLE_SCAN = 1;
    private static final int MSG_FLUSH_BATCH_RESULTS = 2;
    @VisibleForTesting static final int MSG_SCAN_TIMEOUT = 3;
    @VisibleForTesting static final int MSG_SUSPEND_SCANS = 4;
    @VisibleForTesting static final int MSG_RESUME_SCANS = 5;
    @VisibleForTesting static final int MSG_IMPORTANCE_CHANGE = 6;
    @VisibleForTesting static final int MSG_SCREEN_ON = 7;
    @VisibleForTesting static final int MSG_SCREEN_OFF = 8;
    private static final int MSG_REVERT_SCAN_MODE_UPGRADE = 9;
    @VisibleForTesting static final int MSG_START_CONNECTING = 10;
    @VisibleForTesting static final int MSG_STOP_CONNECTING = 11;
    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. To be deleted [END]

    private static final int FOREGROUND_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND_SERVICE;
    private static final boolean DEFAULT_UID_IS_FOREGROUND = true;
    private static final int SCAN_MODE_APP_IN_BACKGROUND = ScanSettings.SCAN_MODE_LOW_POWER;
    private static final int SCAN_MODE_FORCE_DOWNGRADED = ScanSettings.SCAN_MODE_LOW_POWER;
    private static final int SCAN_MODE_MAX_IN_CONCURRENCY = ScanSettings.SCAN_MODE_BALANCED;

    // Timeout for each controller operation.
    private static final int OPERATION_TIME_OUT_MILLIS = 500;
    private static final int MAX_IS_UID_FOREGROUND_MAP_SIZE = 500;

    // Delivery mode defined in bt stack.
    private static final int DELIVERY_MODE_IMMEDIATE = 0;
    private static final int DELIVERY_MODE_ON_FOUND_LOST = 1;
    private static final int DELIVERY_MODE_BATCH = 2;

    private static final int ONFOUND_SIGHTINGS_AGGRESSIVE = 1;
    private static final int ONFOUND_SIGHTINGS_STICKY = 4;

    private static final int ALL_PASS_FILTER_INDEX_REGULAR_SCAN = 1;
    private static final int ALL_PASS_FILTER_INDEX_BATCH_SCAN = 2;
    private static final int ALL_PASS_FILTER_SELECTION = 0;

    private static final int DISCARD_OLDEST_WHEN_BUFFER_FULL = 0;

    /** Onfound/onlost for scan settings */
    private static final int MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR = (1);

    private static final int MATCH_MODE_STICKY_TIMEOUT_FACTOR = (3);
    private static final int ONLOST_FACTOR = 2;
    private static final int ONLOST_ONFOUND_BASE_TIMEOUT_MS = 500;

    // The logic is AND for each filter field.
    private static final int LIST_LOGIC_TYPE = 0x1111111;
    private static final int FILTER_LOGIC_TYPE = 1;

    // MSFT-based hardware scan offload sysprop
    @VisibleForTesting
    static final String MSFT_HCI_EXT_ENABLED = "bluetooth.core.le.use_msft_hci_ext";

    // Hardcoded min number of hardware adv monitor slots for MSFT-enabled controllers
    private static final int MIN_NUM_MSFT_MONITOR_SLOTS = 20;

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. Delete on flag cleanup
    @VisibleForTesting @Nullable final ClientHandler mClientHandler;

    private final Set<ScanClient> mRegularScanClients = ConcurrentHashMap.newKeySet();
    private final Set<ScanClient> mBatchClients = ConcurrentHashMap.newKeySet();
    private final Set<ScanClient> mSuspendedScanClients = ConcurrentHashMap.newKeySet();
    private final SparseBooleanArray mIsUidForegroundMap = new SparseBooleanArray();

    // Filter indices that are available to user. It's sad we need to maintain filter index.
    private final Deque<Integer> mFilterIndexStack = new ArrayDeque<>();
    // Map of scannerId and Filter indices used by client.
    private final Map<Integer, Deque<Integer>> mClientFilterIndexMap = new HashMap<>();
    // Keep track of the clients that uses ALL_PASS filters.
    private final Set<Integer> mAllPassRegularClients = new HashSet<>();
    private final Set<Integer> mAllPassBatchClients = new HashSet<>();

    private final AtomicReference<BroadcastReceiver> mBatchAlarmReceiver = new AtomicReference<>();

    // TODO(b/397863857) Used when `Flags.scanControllerThread()`. Remove @Nullable on flag cleanup
    @VisibleForTesting @Nullable
    final Map<ScanClient, Runnable> mScanTimeoutRunnables = new HashMap<>();

    // TODO(b/397863857) Used when `Flags.scanControllerThread()`. Remove @Nullable on flag cleanup
    @Nullable
    private final Map<ScanClient, Runnable> mRevertScanModeUpgradeRunnables = new HashMap<>();

    // TODO(b/397863857) Used when `Flags.scanControllerThread()`. Remove @Nullable on flag cleanup
    @Nullable private Runnable mClearConnectingStateRunnable;

    // List of merged MSFT patterns
    private final MsftAdvMonitorMergedPatternList mMsftAdvMonitorMergedPatternList =
            new MsftAdvMonitorMergedPatternList();

    private final AdapterService mAdapterService;
    private final BluetoothAdapter mAdapter;
    private final ScanController mScanController;
    private final ScanNativeInterface mNativeInterface;
    private final TimeProvider mTimeProvider;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mBatchScanIntervalIntent;
    private final DisplayManager mDisplayManager;
    private final ActivityManager mActivityManager;
    private final LocationManager mLocationManager;
    private final BatchScanThrottler mBatchScanThrottler;
    // Whether or not MSFT-based scanning hardware offload is available on this device
    private final boolean mIsMsftSupported;

    // TODO(b/397863857) Used when `Flags.scanControllerThread()`. Remove @Nullable on flag cleanup
    @VisibleForTesting @Nullable final Handler mHandler;
    private volatile boolean mIsAvailable = true;

    @VisibleForTesting boolean mIsConnecting;
    @VisibleForTesting int mProfilesConnecting;

    private int mLastConfiguredScanSetting1m = Integer.MIN_VALUE;
    private int mLastConfiguredScanSettingCoded = Integer.MIN_VALUE;
    // Scan parameters for batch scan.
    private BatchScanParams mBatchScanParams;

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. Delete on flag cleanup
    private final Object mCurUsedTrackableAdvertisementsLock = new Object();

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. Delete on flag cleanup
    @GuardedBy("mCurUsedTrackableAdvertisementsLock")
    private int mCurUsedTrackableAdvertisements = 0;

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is true
    // TODO(b/397863857) Rename to `mCurUsedTrackableAdvertisements` on flag cleanup
    private int mCurUsedTrackableAdvertisementsScanThread = 0;

    private boolean mScreenOn = false;
    private int mProfilesConnected;
    private int mProfilesDisconnecting;
    // Whether or not MSFT-based scanning is currently enabled in the controller
    private boolean mScanEnabledMsft = false;

    record BatchScanParams(int scanMode, int fullScanScannerId, int truncatedScanScannerId) {}

    @VisibleForTesting
    record UidImportance(int uid, int importance) {}

    ScanManager(
            AdapterService service,
            ScanController scanController,
            ScanNativeInterface nativeInterface,
            Looper looper,
            TimeProvider timeProvider) {
        mAdapterService = requireNonNull(service);
        mAdapter = mAdapterService.getSystemService(BluetoothManager.class).getAdapter();
        mScanController = scanController;
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new ScanNativeInterface(mScanController));
        mNativeInterface.init();
        mTimeProvider = timeProvider;
        mAlarmManager = mAdapterService.getSystemService(AlarmManager.class);
        Intent batchIntent = new Intent(ACTION_REFRESH_BATCHED_SCAN, null);
        mBatchScanIntervalIntent =
                PendingIntent.getBroadcast(
                        mAdapterService, 0, batchIntent, PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(ACTION_REFRESH_BATCHED_SCAN);
        mBatchAlarmReceiver.set(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        var elapsed = mTimeProvider.elapsedRealtime();
                        var time = Utils.formatElapsedRealtime(elapsed);
                        Log.d(TAG, "Awakened up at=" + time + " (" + elapsed + "ms)");
                        var isActionRefreshBatchedScan =
                                intent.getAction().equals(ACTION_REFRESH_BATCHED_SCAN);
                        mScanController.doOnScanThread(
                                () -> {
                                    if (isActionRefreshBatchedScan && !mBatchClients.isEmpty()) {
                                        // Note this actually flushes all pending batch data.
                                        flushBatchScanResults(mBatchClients.iterator().next());
                                    }
                                });
                    }
                });
        mAdapterService.registerReceiver(mBatchAlarmReceiver.get(), filter);
        mIsMsftSupported =
                Flags.leScanMsftSupport()
                        && SystemProperties.getBoolean(MSFT_HCI_EXT_ENABLED, false)
                        && mNativeInterface.isMsftSupported();
        mDisplayManager = requireNonNull(mAdapterService.getSystemService(DisplayManager.class));
        mActivityManager = mAdapterService.getSystemService(ActivityManager.class);
        mLocationManager = mAdapterService.getSystemService(LocationManager.class);
        mIsConnecting = false;
        if (Flags.scanControllerThread()) {
            mHandler = new Handler(looper);
            mClientHandler = null;
        } else {
            mHandler = null;
            mClientHandler = new ClientHandler(looper);
        }
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mScreenOn = isScreenOn();
        AppScanStats.setScreenState(mScreenOn);
        mScanController.getScanRadioStats().initScanRadioState();
        mScanController.getScanRadioStats().setScreenState(mScreenOn);
        if (mActivityManager != null) {
            mActivityManager.addOnUidImportanceListener(
                    mUidImportanceListener, FOREGROUND_IMPORTANCE_CUTOFF);
        }
        IntentFilter locationIntentFilter = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
        locationIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mAdapterService.registerReceiver(mLocationReceiver, locationIntentFilter);
        mBatchScanThrottler = new BatchScanThrottler(timeProvider, mScreenOn);

        Log.d(TAG, "IsMsftSupported? " + mIsMsftSupported);
    }

    void cleanup() {
        Log.i(TAG, "cleanup()");
        mIsAvailable = false;
        mRegularScanClients.clear();
        mBatchClients.clear();
        mSuspendedScanClients.clear();

        if (mActivityManager != null) {
            try {
                mActivityManager.removeOnUidImportanceListener(mUidImportanceListener);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "exception when invoking removeOnUidImportanceListener", e);
            }
        }

        mDisplayManager.unregisterDisplayListener(mDisplayListener);

        if (!Flags.scanControllerThread()) {
            // Shut down the thread
            mClientHandler.removeCallbacksAndMessages(null);
        }

        mAlarmManager.cancel(mBatchScanIntervalIntent);
        // Protect against multiple calls of cleanup.
        BroadcastReceiver receiver = mBatchAlarmReceiver.getAndSet(null);
        if (receiver != null) {
            mAdapterService.unregisterReceiver(receiver);
        }
        mNativeInterface.cleanup();

        try {
            mAdapterService.unregisterReceiver(mLocationReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "exception when invoking unregisterReceiver(mLocationReceiver)", e);
        }
    }

    Map<Integer, ScanSettings> getSettingsMap() {
        return Stream.of(mRegularScanClients, mBatchClients, mSuspendedScanClients)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(ScanClient::getScannerId, ScanClient::getSettings));
    }

    @VisibleForTesting
    BatchScanParams getBatchScanParams() {
        return mBatchScanParams;
    }

    Set<ScanClient> getRegularScanQueue() {
        return mRegularScanClients;
    }

    Set<ScanClient> getSuspendedScanQueue() {
        return mSuspendedScanClients;
    }

    Set<ScanClient> getBatchScanQueue() {
        return mBatchClients;
    }

    void registerScanner(UUID uuid) {
        mNativeInterface.registerScanner(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
    }

    void unregisterScanner(int scannerId) {
        mNativeInterface.unregisterScanner(scannerId);
    }

    Set<ScanClient> getFullBatchScanQueue() {
        // TODO: split full batch scan clients and truncated batch clients so we don't need to
        // construct this every time.
        return mBatchClients.stream()
                .filter(
                        c ->
                                c.getSettings().getScanResultType()
                                        == ScanSettings.SCAN_RESULT_TYPE_FULL)
                .collect(Collectors.toSet());
    }

    void startScan(ScanClient client) {
        Log.d(TAG, "startScan() " + client);
        if (Flags.scanControllerThread()) {
            handleStartScan(client);
        } else {
            sendMessage(MSG_START_BLE_SCAN, client);
        }
    }

    void stopScan(int scannerId) {
        ScanSettings scanSettings = new ScanSettings.Builder().build();
        ScanClient tmpClient = new ScanClient(scannerId, scanSettings, null, 0);
        if (Flags.scanControllerThread()) {
            handleStopScan(tmpClient);
        } else {
            sendMessage(MSG_STOP_BLE_SCAN, tmpClient);
        }
    }

    void flushBatchScanResults(ScanClient client) {
        Log.d(TAG, "flushBatchScanResults for client: " + client);
        if (Flags.scanControllerThread()) {
            handleFlushBatchResults(client);
        } else {
            sendMessage(MSG_FLUSH_BATCH_RESULTS, client);
        }
    }

    void callbackDone(int scannerId, int status) {
        Log.d(TAG, "callback done for scannerId - " + scannerId + " status - " + status);
        if (status == 0) {
            mNativeInterface.callbackDone();
        }
        // TODO: add a callback for scan failure.
    }

    void batchScanResultDelivered() {
        mBatchScanThrottler.resetBackoff();
    }

    private void sendMessage(int what, ScanClient client) {
        if (Flags.scanControllerThread()) {
            throw new IllegalStateException(
                    "sendMessage using `mClientHandler` should not be called on scan thread");
        }
        final var message = mClientHandler.messageToString(what);
        Log.d(TAG, "Sending message " + message + " for client: " + client);
        mClientHandler.obtainMessage(what, client).sendToTarget();
    }

    private boolean isFilteringSupported() {
        return mAdapter.isOffloadedFilteringSupported();
    }

    int getCurrentUsedTrackingAdvertisement() {
        if (!Flags.scanControllerThread()) {
            synchronized (mCurUsedTrackableAdvertisementsLock) {
                return mCurUsedTrackableAdvertisements;
            }
        }

        return mCurUsedTrackableAdvertisementsScanThread;
    }

    void fetchAppForegroundState(ScanClient client) {
        PackageManager packageManager = mAdapterService.getPackageManager();
        if (mActivityManager == null || packageManager == null) {
            return;
        }
        String[] packages = packageManager.getPackagesForUid(client.getAppUid());
        if (packages == null || packages.length == 0) {
            return;
        }
        int importance = IMPORTANCE_CACHED;
        for (String packageName : packages) {
            importance = Math.min(importance, mActivityManager.getPackageImportance(packageName));
        }
        boolean isForeground = importance <= IMPORTANCE_FOREGROUND_SERVICE;
        mIsUidForegroundMap.put(client.getAppUid(), isForeground);
        final int finalImportance = importance;
        client.getAppScanStats().ifPresent(stats -> stats.setAppImportance(finalImportance));
    }

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. Delete on flag cleanup
    // Handler class that handles BLE scan operations.
    @VisibleForTesting
    class ClientHandler extends Handler {

        ClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_BLE_SCAN -> handleStartScanClientHandlerImpl((ScanClient) msg.obj);
                case MSG_STOP_BLE_SCAN -> handleStopScanClientHandlerImpl((ScanClient) msg.obj);
                case MSG_FLUSH_BATCH_RESULTS ->
                        handleFlushBatchResultsClientHandlerImpl((ScanClient) msg.obj);
                case MSG_SCAN_TIMEOUT -> regularScanTimeoutClientHandlerImpl((ScanClient) msg.obj);
                case MSG_SUSPEND_SCANS -> handleSuspendScansClientHandlerImpl();
                case MSG_RESUME_SCANS -> handleResumeScansClientHandlerImpl();
                case MSG_SCREEN_OFF -> handleScreenOffClientHandlerImpl();
                case MSG_SCREEN_ON -> handleScreenOnClientHandlerImpl();
                case MSG_REVERT_SCAN_MODE_UPGRADE ->
                        handleRevertScanModeUpgradeClientHandlerImpl((ScanClient) msg.obj);
                case MSG_IMPORTANCE_CHANGE ->
                        handleImportanceChangeClientHandlerImpl((UidImportance) msg.obj);
                case MSG_START_CONNECTING -> handleConnectingStateClientHandlerImpl();
                case MSG_STOP_CONNECTING -> handleClearConnectingStateClientHandlerImpl();
                // Shouldn't happen.
                default -> Log.e(TAG, "received an unknown message : " + msg.what);
            }
        }

        void handleStartScanClientHandlerImpl(ScanClient client) {
            handleStartScan(client);
        }

        void handleStopScanClientHandlerImpl(ScanClient client) {
            handleStopScan(client);
        }

        void handleFlushBatchResultsClientHandlerImpl(ScanClient client) {
            handleFlushBatchResults(client);
        }

        void regularScanTimeoutClientHandlerImpl(ScanClient client) {
            regularScanTimeout(client);
        }

        void handleSuspendScansClientHandlerImpl() {
            handleSuspendScans();
        }

        void handleResumeScansClientHandlerImpl() {
            handleResumeScans();
        }

        void handleScreenOffClientHandlerImpl() {
            handleScreenOff();
        }

        void handleScreenOnClientHandlerImpl() {
            handleScreenOn();
        }

        void handleRevertScanModeUpgradeClientHandlerImpl(ScanClient client) {
            handleRevertScanModeUpgrade(client);
        }

        void handleImportanceChangeClientHandlerImpl(UidImportance uidImportance) {
            handleImportanceChange(uidImportance);
        }

        void handleConnectingStateClientHandlerImpl() {
            handleConnectingState();
        }

        void handleClearConnectingStateClientHandlerImpl() {
            handleClearConnectingState();
        }

        private static String messageToString(int msg) {
            return switch (msg) {
                case MSG_START_BLE_SCAN -> "MSG_START_BLE_SCAN";
                case MSG_STOP_BLE_SCAN -> "MSG_STOP_BLE_SCAN";
                case MSG_FLUSH_BATCH_RESULTS -> "MSG_FLUSH_BATCH_RESULTS";
                case MSG_SCAN_TIMEOUT -> "MSG_SCAN_TIMEOUT";
                case MSG_SUSPEND_SCANS -> "MSG_SUSPEND_SCANS";
                case MSG_RESUME_SCANS -> "MSG_RESUME_SCANS";
                case MSG_IMPORTANCE_CHANGE -> "MSG_IMPORTANCE_CHANGE";
                case MSG_SCREEN_ON -> "MSG_SCREEN_ON";
                case MSG_SCREEN_OFF -> "MSG_SCREEN_OFF";
                case MSG_REVERT_SCAN_MODE_UPGRADE -> "MSG_REVERT_SCAN_MODE_UPGRADE";
                case MSG_START_CONNECTING -> "MSG_START_CONNECTING";
                case MSG_STOP_CONNECTING -> "MSG_STOP_CONNECTING";
                default -> "UNKNOWN(" + msg + ")";
            };
        }
    }

    private void handleStartScan(ScanClient client) {
        Log.d(TAG, "Handling start scan");
        fetchAppForegroundState(client);

        if (!isScanSupported(client)) {
            Log.e(TAG, "Scan settings not supported");
            return;
        }

        if (mRegularScanClients.contains(client) || mBatchClients.contains(client)) {
            Log.e(TAG, "Scan already started for " + client);
            return;
        }

        if (Flags.adapterSuspendMgmt()
                && Flags.stopLeScanSystemSuspend()
                && mScanController.isSystemSuspended()) {
            Log.w(
                    TAG,
                    "Cannot start LE scan in system-suspend."
                            + (" This scan will be resumed later for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats()
                    .ifPresent(stats -> stats.recordScanSuspend(client.getScannerId()));
            return;
        }

        if (requiresScreenOn(client) && !mScreenOn) {
            Log.w(
                    TAG,
                    "Cannot start unfiltered scan in screen-off."
                            + (" This scan will be resumed later for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats()
                    .ifPresent(stats -> stats.recordScanSuspend(client.getScannerId()));
            return;
        }

        final boolean locationEnabled = mLocationManager.isLocationEnabled();
        if (requiresLocationOn(client) && !locationEnabled) {
            Log.i(
                    TAG,
                    "Cannot start unfiltered scan in location-off."
                            + (" This scan will be resumed when location is on for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats()
                    .ifPresent(stats -> stats.recordScanSuspend(client.getScannerId()));
            return;
        }

        if (!isExemptFromAutoBatchScanUpdate(client)) {
            if (mScreenOn) {
                clearAutoBatchScanClient(client);
            } else {
                setAutoBatchScanClient(client);
            }
        }

        // Begin scan operations.
        if (isBatchClient(client) || isAutoBatchScanClientEnabled(client)) {
            mBatchClients.add(client);
            startBatchScan(client);
        } else {
            updateScanModeBeforeStart(client);
            updateScanModeConcurrency(client);
            mRegularScanClients.add(client);
            startRegularScan(client);
            if (!isOpportunisticScanClient(client)) {
                configureRegularScanParams();
                configureTimeout(client);
            }
        }
        client.setStarted(true);
    }

    private void configureTimeout(ScanClient client) {
        if (isExemptFromScanTimeout(client)) {
            return;
        }
        if (Flags.scanControllerThread()) {
            // Ensure only one timeout runnable exists per client
            Runnable oldRunnable = mScanTimeoutRunnables.remove(client);
            if (oldRunnable != null) {
                mHandler.removeCallbacks(oldRunnable);
            }
            final Runnable timeoutRunnable =
                    () -> {
                        if (!mIsAvailable) return;
                        mScanTimeoutRunnables.remove(client);
                        regularScanTimeout(client);
                    };
            mScanTimeoutRunnables.put(client, timeoutRunnable);
            mHandler.postDelayed(timeoutRunnable, mAdapterService.getScanTimeout().toMillis());
        } else {
            Message msg = mClientHandler.obtainMessage(MSG_SCAN_TIMEOUT);
            msg.obj = client;
            // Only one timeout message should exist at any time
            mClientHandler.removeMessages(MSG_SCAN_TIMEOUT, client);
            mClientHandler.sendMessageDelayed(msg, mAdapterService.getScanTimeout().toMillis());
        }
        Log.d(TAG, "Apply scan timeout (" + mAdapterService.getScanTimeout() + ") to " + client);
    }

    private void handleStopScan(ScanClient tmpClient) {
        int scannerIdToStop = tmpClient.getScannerId();
        ScanClient client = getBatchScanClient(scannerIdToStop);
        if (client == null) {
            client = getRegularScanClient(scannerIdToStop);
        }
        if (client == null) {
            client = getSuspendedScanClient(scannerIdToStop);
        }
        if (client == null) {
            Log.d(
                    TAG,
                    "Handling stopping scan, no client found for scannerId - " + scannerIdToStop);
            return;
        }
        Log.d(TAG, "Handling stopping scan for " + client);
        final var appDied = client.getAppDied();
        final var scannerId = client.getScannerId();

        if (mSuspendedScanClients.contains(client)) {
            mSuspendedScanClients.remove(client);
        }
        if (Flags.scanControllerThread()) {
            Runnable timeoutRunnable = mScanTimeoutRunnables.remove(client);
            if (timeoutRunnable != null) {
                mHandler.removeCallbacks(timeoutRunnable);
            }
            Runnable revertRunnable = mRevertScanModeUpgradeRunnables.remove(client);
            if (revertRunnable != null) {
                mHandler.removeCallbacks(revertRunnable);
            }
        } else {
            mClientHandler.removeMessages(MSG_REVERT_SCAN_MODE_UPGRADE, client);
            mClientHandler.removeMessages(MSG_SCAN_TIMEOUT, client);
        }
        if (mRegularScanClients.contains(client)) {
            stopRegularScan(client);

            if (!isOpportunisticScanClient(client)) {
                configureRegularScanParams();
            }
        } else {
            if (isAutoBatchScanClientEnabled(client)) {
                handleFlushBatchResults(client);
            }
            stopBatchScan(client);
        }
        if (appDied) {
            Log.d(TAG, "App died, unregister scanner - " + scannerId);
            mScanController.unregisterScanner(scannerId);
        }
    }

    private void handleFlushBatchResults(ScanClient client) {
        if (!mBatchClients.contains(client)) {
            Log.d(TAG, "There is no batch scan client to flush for " + client);
            return;
        }
        flushBatchResults(client);
    }

    private boolean isScanSupported(ScanClient client) {
        if (client == null) {
            return true;
        }
        if (isFilteringSupported()) {
            return true;
        }
        if (mIsMsftSupported && !isBatchClient(client)) {
            return true;
        }
        return client.getSettings().getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                && client.getSettings().getReportDelayMillis() == 0;
    }

    @VisibleForTesting
    void handleScreenOff() {
        AppScanStats.setScreenState(false);
        mScanController.getScanRadioStats().setScreenState(false);
        if (!mScreenOn) {
            return;
        }
        mScreenOn = false;
        Log.d(TAG, "handleScreenOff()");
        mBatchScanThrottler.onScreenOn(false);
        handleSuspendScans();
        updateRegularScanClientsScreenOff();
        updateRegularScanToBatchScanClients();
    }

    @VisibleForTesting
    void handleConnectingState() {
        if (mAdapterService.getScanDowngradeDuration().equals(Duration.ZERO)) {
            return;
        }
        boolean updatedScanParams = false;
        mIsConnecting = true;
        Log.d(TAG, "handleConnectingState()");
        for (ScanClient client : mRegularScanClients) {
            if (downgradeScanModeFromMaxDuty(client)) {
                updatedScanParams = true;
                Log.d(TAG, "scanMode is downgraded by connecting for " + client);
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
        if (Flags.scanControllerThread()) {
            // Cancel any previously scheduled runnable to ensure only one is pending.
            if (mClearConnectingStateRunnable != null) {
                mHandler.removeCallbacks(mClearConnectingStateRunnable);
            }
            mClearConnectingStateRunnable =
                    () -> {
                        if (!mIsAvailable) return;
                        handleClearConnectingState();
                    };
            mHandler.postDelayed(
                    mClearConnectingStateRunnable,
                    mAdapterService.getScanDowngradeDuration().toMillis());
        } else {
            mClientHandler.removeMessages(MSG_STOP_CONNECTING);
            Message msg = mClientHandler.obtainMessage(MSG_STOP_CONNECTING);
            mClientHandler.sendMessageDelayed(
                    msg, mAdapterService.getScanDowngradeDuration().toMillis());
        }
    }

    @VisibleForTesting
    void handleClearConnectingState() {
        if (!mIsConnecting) {
            Log.e(TAG, "handleClearConnectingState() - not connecting state");
            return;
        }
        Log.d(TAG, "handleClearConnectingState()");
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (revertDowngradeScanModeFromMaxDuty(client)) {
                updatedScanParams = true;
                Log.d(TAG, "downgraded scanMode is reverted for " + client);
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
        if (Flags.scanControllerThread()) {
            if (mClearConnectingStateRunnable != null) {
                mHandler.removeCallbacks(mClearConnectingStateRunnable);
                mClearConnectingStateRunnable = null;
            }
        } else {
            mClientHandler.removeMessages(MSG_STOP_CONNECTING);
        }
        mIsConnecting = false;
    }

    @VisibleForTesting
    void handleSuspendScans() {
        for (ScanClient client : mRegularScanClients) {
            if ((requiresScreenOn(client) && !mScreenOn)
                    || (requiresLocationOn(client) && !mLocationManager.isLocationEnabled())) {
                // Suspend unfiltered scans
                client.getAppScanStats()
                        .ifPresent(stats -> stats.recordScanSuspend(client.getScannerId()));
                Log.d(TAG, "suspend scan " + client);
                handleStopScan(client);
                mSuspendedScanClients.add(client);
            }
        }
    }

    private void updateRegularScanToBatchScanClients() {
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (!isExemptFromAutoBatchScanUpdate(client)) {
                Log.d(TAG, "Updating regular scan to batch scan" + client);
                handleStopScan(client);
                setAutoBatchScanClient(client);
                handleStartScan(client);
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    private void updateBatchScanToRegularScanClients() {
        boolean updatedScanParams = false;
        for (ScanClient client : mBatchClients) {
            if (!isExemptFromAutoBatchScanUpdate(client)) {
                Log.d(TAG, "Updating batch scan to regular scan" + client);
                handleStopScan(client);
                clearAutoBatchScanClient(client);
                handleStartScan(client);
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    private void updateRegularScanClientsScreenOff() {
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (updateScanModeScreenOff(client)) {
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    private boolean updateScanModeScreenOff(ScanClient client) {
        if (isOpportunisticScanClient(client)) {
            return false;
        }
        int updatedScanMode = client.getScanModeApp();
        final var scanModeString = getScanModeString(updatedScanMode);
        if (!isAppForeground(client) || isForceDowngradedScanClient(client)) {
            updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF;
        } else {
            // The following codes are effectively only for services
            // Apps are either already or will be soon handled by handleImportanceChange().
            switch (updatedScanMode) {
                case ScanSettings.SCAN_MODE_LOW_POWER ->
                        updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF;
                case ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                        updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED;
                case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                        updatedScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
                default -> {
                    return false;
                }
            }
        }
        Log.d(
                TAG,
                "Scan mode update during screen off from "
                        + scanModeString
                        + " to "
                        + getScanModeString(updatedScanMode));
        return client.updateScanMode(updatedScanMode);
    }

    /**
     * Services and Apps are assumed to be in the foreground by default unless it changes to the
     * background triggering onUidImportance().
     */
    private boolean isAppForeground(ScanClient client) {
        return mIsUidForegroundMap.get(client.getAppUid(), DEFAULT_UID_IS_FOREGROUND);
    }

    private boolean updateScanModeBeforeStart(ScanClient client) {
        if (upgradeScanModeBeforeStart(client)) {
            return true;
        }
        if (mScreenOn) {
            return updateScanModeScreenOn(client);
        } else {
            return updateScanModeScreenOff(client);
        }
    }

    private boolean updateScanModeConcurrency(ScanClient client) {
        if (mIsConnecting) {
            return downgradeScanModeFromMaxDuty(client);
        }
        return false;
    }

    private boolean upgradeScanModeBeforeStart(ScanClient client) {
        if (client.getStarted() || mAdapterService.getScanUpgradeDuration().equals(Duration.ZERO)) {
            return false;
        }
        if (client.getAppScanStats().isEmpty() || client.getAppScanStats().get().hasRecentScan()) {
            return false;
        }
        if (!isAppForeground(client) || isBatchClient(client)) {
            return false;
        }
        if (Flags.upgradeLeScanOnlyScreenOn() && !mScreenOn) {
            return false;
        }

        if (upgradeScanModeByOneLevel(client)) {
            if (Flags.scanControllerThread()) {
                final Runnable revertRunnable =
                        () -> {
                            if (!mIsAvailable) return;
                            mRevertScanModeUpgradeRunnables.remove(client);
                            handleRevertScanModeUpgrade(client);
                        };
                mRevertScanModeUpgradeRunnables.put(client, revertRunnable);
                mHandler.postDelayed(
                        revertRunnable, mAdapterService.getScanUpgradeDuration().toMillis());
            } else {
                Message msg = mClientHandler.obtainMessage(MSG_REVERT_SCAN_MODE_UPGRADE);
                msg.obj = client;
                mClientHandler.sendMessageDelayed(
                        msg, mAdapterService.getScanUpgradeDuration().toMillis());
            }
            final var scanModeString = getScanModeString(client.getSettings().getScanMode());
            Log.d(TAG, "Scan mode is upgraded to " + scanModeString + " for " + client);
            return true;
        }
        return false;
    }

    private void handleRevertScanModeUpgrade(ScanClient client) {
        final var scanModeApp = client.getScanModeApp();
        if (priorityForScanMode(client.getSettings().getScanMode())
                <= priorityForScanMode(scanModeApp)) {
            return;
        }
        if (client.updateScanMode(scanModeApp)) {
            Log.d(
                    TAG,
                    "scanMode upgrade is reverted to "
                            + getScanModeString(scanModeApp)
                            + " for "
                            + client);
            configureRegularScanParams();
        }
    }

    @VisibleForTesting
    void handleImportanceChange(UidImportance imp) {
        if (imp == null) {
            return;
        }
        final int uid = imp.uid;
        final int importance = imp.importance;
        final boolean isForeground = importance <= IMPORTANCE_FOREGROUND_SERVICE;

        if (mIsUidForegroundMap.size() < MAX_IS_UID_FOREGROUND_MAP_SIZE) {
            mIsUidForegroundMap.put(uid, isForeground);
        }

        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (client.getAppUid() != uid || isOpportunisticScanClient(client)) {
                continue;
            }
            client.getAppScanStats().ifPresent(stats -> stats.setAppImportance(importance));
            final var scanSettings = client.getSettings();
            if (isForeground) {
                final int scanMode = client.getScanModeApp();
                final int maxScanMode =
                        isForceDowngradedScanClient(client) ? SCAN_MODE_FORCE_DOWNGRADED : scanMode;
                if (client.updateScanMode(minScanMode(scanMode, maxScanMode))) {
                    updatedScanParams = true;
                }
            } else {
                final int scanMode = scanSettings.getScanMode();
                final int maxScanMode =
                        mScreenOn ? SCAN_MODE_APP_IN_BACKGROUND : ScanSettings.SCAN_MODE_SCREEN_OFF;
                if (client.updateScanMode(minScanMode(scanMode, maxScanMode))) {
                    updatedScanParams = true;
                }
            }
            Log.d(
                    TAG,
                    ("uid " + uid)
                            + (" isForeground " + isForeground)
                            + (" scanMode " + getScanModeString(scanSettings.getScanMode())));
        }

        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    private boolean updateScanModeScreenOn(ScanClient client) {
        if (isOpportunisticScanClient(client)) {
            return false;
        }
        final var scanModeApp = client.getScanModeApp();
        final int scanMode = isAppForeground(client) ? scanModeApp : SCAN_MODE_APP_IN_BACKGROUND;
        final int maxScanMode =
                isForceDowngradedScanClient(client) ? SCAN_MODE_FORCE_DOWNGRADED : scanMode;
        Log.d(
                TAG,
                "Scan mode update during screen on from "
                        + getScanModeString(scanModeApp)
                        + " to "
                        + getScanModeString(minScanMode(scanMode, maxScanMode)));
        return client.updateScanMode(minScanMode(scanMode, maxScanMode));
    }

    private boolean downgradeScanModeFromMaxDuty(ScanClient client) {
        if (client.getAppScanStats().isEmpty()
                || mAdapterService.getScanDowngradeDuration().equals(Duration.ZERO)) {
            return false;
        }
        final int updatedScanMode =
                minScanMode(client.getSettings().getScanMode(), SCAN_MODE_MAX_IN_CONCURRENCY);
        if (client.updateScanMode(updatedScanMode)) {
            client.getAppScanStats().get().setScanDowngrade(client.getScannerId(), true);
            Log.d(
                    TAG,
                    ("downgradeScanModeFromMaxDuty() to " + getScanModeString(updatedScanMode))
                            + (" for " + client));
            return true;
        }
        return false;
    }

    private boolean revertDowngradeScanModeFromMaxDuty(ScanClient client) {
        if (!isDowngradedScanClient(client)) {
            return false;
        }
        client.getAppScanStats()
                .ifPresent(stats -> stats.setScanDowngrade(client.getScannerId(), false));
        Log.d(TAG, "revertDowngradeScanModeFromMaxDuty() for " + client);
        if (mScreenOn) {
            return updateScanModeScreenOn(client);
        } else {
            return updateScanModeScreenOff(client);
        }
    }

    @VisibleForTesting
    void handleScreenOn() {
        AppScanStats.setScreenState(true);
        mScanController.getScanRadioStats().setScreenState(true);
        if (mScreenOn) {
            return;
        }
        mScreenOn = true;
        Log.d(TAG, "handleScreenOn()");
        mBatchScanThrottler.onScreenOn(true);
        updateBatchScanToRegularScanClients();
        handleResumeScans();
        updateRegularScanClientsScreenOn();
    }

    @VisibleForTesting
    void handleResumeScans() {
        Iterator<ScanClient> iterator = mSuspendedScanClients.iterator();
        while (iterator.hasNext()) {
            ScanClient client = iterator.next();
            if ((!requiresScreenOn(client) || mScreenOn)
                    && (!requiresLocationOn(client) || mLocationManager.isLocationEnabled())) {
                client.getAppScanStats()
                        .ifPresent(stats -> stats.recordScanResume(client.getScannerId()));
                Log.d(TAG, "Resume scan for " + client);
                handleStartScan(client);
                iterator.remove();
            }
        }
    }

    private void updateRegularScanClientsScreenOn() {
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (updateScanModeScreenOn(client)) {
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    // TODO(b/397863857) Inline within `void handleProfileConnectionStateChanged` on cleanup
    private void handleProfileConnectionStateChanged(int profile, int fromState, int toState) {
        final boolean updatedConnectingState =
                updateCountersAndCheckForConnectingState(toState, fromState);
        Log.d(
                TAG,
                "PROFILE_CONNECTION_STATE_CHANGE:"
                        + (" profile=" + BluetoothProfile.getProfileName(profile))
                        + (" prevState=" + fromState)
                        + (" state=" + toState)
                        + (" updatedConnectingState = " + updatedConnectingState));
        if (updatedConnectingState) {
            if (!mIsConnecting) {
                handleConnectingState();
            }
        } else {
            if (mIsConnecting) {
                handleClearConnectingState();
            }
        }
    }

    private void resetCountDownLatch() {
        mNativeInterface.resetCountDownLatch();
    }

    private boolean waitForCallback() {
        return mNativeInterface.waitForCallback(OPERATION_TIME_OUT_MILLIS);
    }

    private void configureRegularScanParams() {
        Log.d(TAG, "configureRegularScanParams() - queue=" + mRegularScanClients.size());
        int newScanSetting1m = Integer.MIN_VALUE;
        int newScanSettingCoded = Integer.MIN_VALUE;
        ScanClient client1m = getAggressiveClient(mRegularScanClients, true, false);
        ScanClient clientCoded = getAggressiveClient(mRegularScanClients, false, false);
        if (client1m != null) {
            newScanSetting1m = client1m.getSettings().getScanMode();
        }
        if (clientCoded != null) {
            newScanSettingCoded = clientCoded.getSettings().getScanMode();
        }

        int curPhyMask =
                getScanPhyMask(
                        mLastConfiguredScanSetting1m != Integer.MIN_VALUE,
                        mLastConfiguredScanSettingCoded != Integer.MIN_VALUE);
        int scanPhyMask = getScanPhyMask(client1m != null, clientCoded != null);

        // Only update scan parameters if at least one of the following is true:
        // 1. The 1M PHY mode has changed and is a valid value
        // 2. The coded PHY mode has changed and is a valid value
        // 3. The PHYs to scan on have changed and the new setting is valid (not 0)
        if (shouldUpdateScan(newScanSetting1m, mLastConfiguredScanSetting1m)
                || shouldUpdateScan(newScanSettingCoded, mLastConfiguredScanSettingCoded)
                || (scanPhyMask != 0 && curPhyMask != scanPhyMask)) {
            int scanWindow1m = getScanWindow(client1m);
            int scanInterval1m = getScanInterval(client1m);
            int scanWindowCoded = getScanWindow(clientCoded);
            int scanIntervalCoded = getScanInterval(clientCoded);
            mNativeInterface.scan(false);
            if (!mScanController.getScanRadioStats().recordScanRadioStop()) {
                Log.w(TAG, "There is no scan radio to stop");
            }
            Log.d(
                    TAG,
                    "Start scanNative with"
                            + " old 1M scanMode "
                            + mLastConfiguredScanSetting1m
                            + " new 1M scanMode "
                            + newScanSetting1m
                            + " ( in scan unit: "
                            + scanInterval1m
                            + " / "
                            + scanWindow1m
                            + ", "
                            + " old coded scanMode "
                            + mLastConfiguredScanSettingCoded
                            + " new coded scanMode "
                            + newScanSettingCoded
                            + " ( in scan unit: "
                            + scanIntervalCoded
                            + " / "
                            + scanWindowCoded
                            + ", "
                            + "scanPhyMask: "
                            + scanPhyMask
                            + " ) "
                            + client1m
                            + " / "
                            + clientCoded);
            mNativeInterface.gattSetScanParameters(
                    client1m == null ? 0 : client1m.getScannerId(),
                    scanInterval1m,
                    scanWindow1m,
                    clientCoded == null ? 0 : clientCoded.getScannerId(),
                    scanIntervalCoded,
                    scanWindowCoded,
                    scanPhyMask);
            mNativeInterface.scan(true);
            recordScanRadioStart(client1m, clientCoded, newScanSetting1m, newScanSettingCoded);
        } else {
            Log.d(TAG, "configureRegularScanParams() - queue empty, scan stopped");
        }
        mLastConfiguredScanSetting1m = newScanSetting1m;
        mLastConfiguredScanSettingCoded = newScanSettingCoded;
    }

    private static ScanClient getAggressiveClient(
            Set<ScanClient> cList, boolean use1mPhy, boolean isBatch) {
        ScanClient result = null;
        int currentScanModePriority = Integer.MIN_VALUE;
        for (ScanClient client : cList) {
            // Batch is only done on the 1M PHY and the client PHY setting is ignored
            if (!isBatch && !isPhyConfigured(client, use1mPhy)) {
                continue;
            }
            if (isOpportunisticScanClient(client)) {
                continue;
            }
            final int priority = priorityForScanMode(client.getSettings().getScanMode());
            if (priority > currentScanModePriority) {
                result = client;
                currentScanModePriority = priority;
            }
        }
        return result;
    }

    private int getScanWindow(@Nullable ScanClient client) {
        return client == null ? 0 : Utils.millsToUnit(getScanWindowMillis(client.getSettings()));
    }

    private int getScanInterval(@Nullable ScanClient client) {
        // convert scanWindow and scanInterval from ms to LE scan units(0.625ms)
        return client == null ? 0 : Utils.millsToUnit(getScanIntervalMillis(client.getSettings()));
    }

    private void recordScanRadioStart(
            @Nullable ScanClient client1m,
            @Nullable ScanClient clientCoded,
            int setting1m,
            int settingCoded) {
        ScanClient chosenClient;
        if (client1m == null) {
            chosenClient = clientCoded;
        } else if (clientCoded == null) {
            chosenClient = client1m;
        } else {
            chosenClient =
                    priorityForScanMode(setting1m) >= priorityForScanMode(settingCoded)
                            ? client1m
                            : clientCoded;
        }
        if (chosenClient != null
                && chosenClient.getAppScanStats().isPresent()
                && !mScanController
                        .getScanRadioStats()
                        .recordScanRadioStart(
                                chosenClient.getScanModeApp(),
                                chosenClient.getScannerId(),
                                chosenClient.getAppScanStats().get(),
                                getScanWindowMillis(chosenClient.getSettings()),
                                getScanIntervalMillis(chosenClient.getSettings()))) {
            Log.w(TAG, "Scan radio already started");
        }
    }

    private void startRegularScan(ScanClient client) {
        if ((isFilteringSupported() || mIsMsftSupported)
                && mFilterIndexStack.isEmpty()
                && mClientFilterIndexMap.isEmpty()) {
            initFilterIndexStack();
        }
        if (isFilteringSupported()) {
            configureScanFilters(client);
        } else if (mIsMsftSupported) {
            addFiltersMsft(client);
        }

        // Start scan native only for the first client.
        if (numRegularScanClients() == 1
                && client.getSettings().getScanMode() != ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
            Log.d(TAG, "start scanNative from startRegularScan()");
            mNativeInterface.scan(true);
        }
    }

    private int numRegularScanClients() {
        int num = 0;
        for (ScanClient client : mRegularScanClients) {
            if (client.getSettings().getScanMode() != ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
                num++;
            }
        }
        return num;
    }

    private void startBatchScan(ScanClient client) {
        if (mFilterIndexStack.isEmpty()
                && isFilteringSupported()
                && mClientFilterIndexMap.isEmpty()) {
            initFilterIndexStack();
        }
        configureScanFilters(client);
        if (!isOpportunisticScanClient(client)) {
            // Reset batch scan. May need to stop the existing batch scan and update scan
            // params.
            resetBatchScan(client);
        }
    }

    private void resetBatchScan(ScanClient client) {
        int scannerId = client.getScannerId();
        BatchScanParams batchScanParams = fetchBatchScanParams();
        // Stop batch if batch scan params changed and previous params is not null.
        if (mBatchScanParams != null && (!mBatchScanParams.equals(batchScanParams))) {
            Log.d(TAG, "Stopping BLE Batch");
            resetCountDownLatch();
            mNativeInterface.stopBatchScan(scannerId);
            waitForCallback();
            // Clear pending results as it's illegal to config storage if there are still
            // pending results.
            flushBatchResults(client);
        }
        // Start batch if batchScanParams changed and current params is not null.
        if (batchScanParams != null && (!batchScanParams.equals(mBatchScanParams))) {
            int notifyThreshold = 95;
            Log.d(TAG, "Starting BLE batch scan");
            int resultType = getResultType(batchScanParams);
            int fullScanPercent = getFullScanStoragePercent(resultType);
            resetCountDownLatch();
            Log.d(TAG, "Configuring batch scan storage for " + client);
            mNativeInterface.configBatchScanStorage(
                    client.getScannerId(), fullScanPercent, 100 - fullScanPercent, notifyThreshold);
            waitForCallback();
            resetCountDownLatch();
            int scanInterval =
                    Utils.millsToUnit(getBatchScanIntervalMillis(batchScanParams.scanMode));
            int scanWindow = Utils.millsToUnit(getBatchScanWindowMillis(batchScanParams.scanMode));
            mNativeInterface.startBatchScan(
                    scannerId,
                    resultType,
                    scanInterval,
                    scanWindow,
                    0,
                    DISCARD_OLDEST_WHEN_BUFFER_FULL);
            waitForCallback();
        }
        mBatchScanParams = batchScanParams;
        setBatchAlarm(client);
    }

    private static int getFullScanStoragePercent(int resultType) {
        return switch (resultType) {
            case SCAN_RESULT_TYPE_FULL -> 100;
            case SCAN_RESULT_TYPE_TRUNCATED -> 0;
            case SCAN_RESULT_TYPE_BOTH -> 50;
            default -> 50;
        };
    }

    private BatchScanParams fetchBatchScanParams() {
        if (mBatchClients.isEmpty()) {
            return null;
        }

        int scanMode = -1;
        int fullScanScannerId = -1;
        int truncatedScanScannerId = -1;

        ScanClient winner = getAggressiveClient(mBatchClients, true, true);
        if (winner != null) {
            scanMode = winner.getSettings().getScanMode();
        }

        // TODO: split full batch scan results and truncated batch scan results to different
        // collections.
        for (ScanClient client : mBatchClients) {
            if (client.getSettings().getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL) {
                fullScanScannerId = client.getScannerId();
            } else {
                truncatedScanScannerId = client.getScannerId();
            }
        }

        return new BatchScanParams(scanMode, fullScanScannerId, truncatedScanScannerId);
    }

    // Batched scan doesn't require high duty cycle scan because scan result is reported
    // infrequently anyway. To avoid redefining parameter sets, map to the low duty cycle
    // parameter set as follows.
    private int getBatchScanWindowMillis(int scanMode) {
        ContentResolver resolver = mAdapterService.getContentResolver();
        final var windowMs =
                switch (scanMode) {
                    case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                            Settings.Global.getInt(
                                    resolver,
                                    Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                                    SCAN_MODE_BALANCED_WINDOW_MS);
                    case ScanSettings.SCAN_MODE_SCREEN_OFF ->
                            (int) mAdapterService.getScreenOffLowPowerWindow().toMillis();
                    default ->
                            Settings.Global.getInt(
                                    resolver,
                                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                                    SCAN_MODE_LOW_POWER_WINDOW_MS);
                };
        Log.d(TAG, "Scan window is " + windowMs + "ms for mode " + getScanModeString(scanMode));
        return windowMs;
    }

    private int getBatchScanIntervalMillis(int scanMode) {
        ContentResolver resolver = mAdapterService.getContentResolver();
        final var internalMs =
                switch (scanMode) {
                    case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                            Settings.Global.getInt(
                                    resolver,
                                    Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                                    SCAN_MODE_BALANCED_INTERVAL_MS);
                    case ScanSettings.SCAN_MODE_SCREEN_OFF ->
                            (int) mAdapterService.getScreenOffLowPowerInterval().toMillis();
                    default ->
                            Settings.Global.getInt(
                                    resolver,
                                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                                    SCAN_MODE_LOW_POWER_INTERVAL_MS);
                };
        Log.d(TAG, "Scan interval is " + internalMs + "ms for mode " + getScanModeString(scanMode));
        return internalMs;
    }

    // Set the batch alarm to be triggered within a short window after batch interval. This
    // allows system to optimize wake up time while still allows a degree of precise control.
    private void setBatchAlarm(ScanClient client) {
        Log.d(TAG, "setBatchAlarm(): Caller: " + client + ". Canceling pending batch scan alarm");
        mAlarmManager.cancel(mBatchScanIntervalIntent);

        if (mBatchClients.isEmpty()) {
            Log.d(TAG, "setBatchAlarm(): No batch clients; Skipping alarm setup");
            return;
        }
        final long batchTriggerIntervalMillis =
                mBatchScanThrottler.getBatchTriggerIntervalMillis(mBatchClients);
        // Allows the alarm to be triggered within
        // [batchTriggerIntervalMillis, 1.1 * batchTriggerIntervalMillis]
        final long windowLengthMillis = batchTriggerIntervalMillis / 10;
        final long windowStartMs = mTimeProvider.elapsedRealtime() + batchTriggerIntervalMillis;
        final var windowStartReadable = Utils.formatElapsedRealtime(windowStartMs);
        Log.d(TAG, "setBatchAlarm(): for:" + windowStartReadable + " (" + windowStartMs + "ms)");
        client.getAppScanStats().ifPresent(AppScanStats::recordBatchAlarmScheduled);
        mAlarmManager.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                windowStartMs,
                windowLengthMillis,
                mBatchScanIntervalIntent);
    }

    private void stopRegularScan(ScanClient client) {
        // Remove scan filters and recycle filter indices.
        if (client == null) {
            return;
        }
        int deliveryMode = getDeliveryMode(client);
        if (deliveryMode == DELIVERY_MODE_ON_FOUND_LOST) {
            // Decrement the count of trackable advertisements in use
            int entriesToFreePerFilter = getNumOfTrackingAdvertisements(client.getSettings());
            for (int i = 0; i < client.getFilters().size(); i++) {
                if (!manageAllocationOfTrackingAdvertisement(entriesToFreePerFilter, false)) {
                    Log.e(
                            TAG,
                            "Error freeing for onfound/onlost filter resources "
                                    + entriesToFreePerFilter);
                    mScanController.onScanManagerErrorCallback(
                            client.getScannerId(), ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                }
            }
        }
        mRegularScanClients.remove(client);
        if (numRegularScanClients() == 0) {
            Log.d(TAG, "stop scanNative");
            mNativeInterface.scan(false);
            if (!mScanController.getScanRadioStats().recordScanRadioStop()) {
                Log.w(TAG, "There is no scan radio to stop");
            }
        }

        if (!isFilteringSupported() && mIsMsftSupported) {
            removeFiltersMsft(client);
        } else {
            removeScanFilters(client.getScannerId());
        }
    }

    private void regularScanTimeout(ScanClient client) {
        if (!isExemptFromScanTimeout(client)
                && (client.getAppScanStats().isEmpty()
                        || client.getAppScanStats().get().isScanningTooLong())) {
            Log.d(TAG, "RegularScanTimeout(): Client scan time was too long");
            if (client.getFilters().isEmpty()) {
                Log.w(TAG, "Moving unfiltered scan to opportunistic scan for " + client);
                setOpportunisticScanClient(client);
                removeScanFilters(client.getScannerId());
            } else {
                Log.w(TAG, "Moving filtered scan to downgraded scan for " + client);
                int scanMode = client.getSettings().getScanMode();
                int maxScanMode = SCAN_MODE_FORCE_DOWNGRADED;
                client.updateScanMode(minScanMode(scanMode, maxScanMode));
            }
            client.getAppScanStats()
                    .ifPresent(
                            stats -> {
                                stats.setScanTimeout(client.getScannerId());
                                stats.recordScanTimeoutCountMetrics(
                                        client.getScannerId(),
                                        mAdapterService.getScanTimeout().toMillis());
                            });
        }

        // The scan should continue for background scans
        configureRegularScanParams();
        if (numRegularScanClients() == 0) {
            Log.d(TAG, "stop scanNative");
            mNativeInterface.scan(false);
            if (!mScanController.getScanRadioStats().recordScanRadioStop()) {
                Log.w(TAG, "There is no scan radio to stop");
            }
        }
    }

    // Find the regular scan client information.
    private ScanClient getRegularScanClient(int scannerId) {
        for (ScanClient client : mRegularScanClients) {
            if (client.getScannerId() == scannerId) {
                return client;
            }
        }
        return null;
    }

    private ScanClient getSuspendedScanClient(int scannerId) {
        for (ScanClient client : mSuspendedScanClients) {
            if (client.getScannerId() == scannerId) {
                return client;
            }
        }
        return null;
    }

    private void stopBatchScan(ScanClient client) {
        mBatchClients.remove(client);
        removeScanFilters(client.getScannerId());
        if (!isOpportunisticScanClient(client)) {
            resetBatchScan(client);
        }
    }

    private void flushBatchResults(ScanClient client) {
        if (mBatchScanParams.fullScanScannerId != -1) {
            resetCountDownLatch();
            mNativeInterface.readScanReports(
                    mBatchScanParams.fullScanScannerId, SCAN_RESULT_TYPE_FULL);
            waitForCallback();
        }
        if (mBatchScanParams.truncatedScanScannerId != -1) {
            resetCountDownLatch();
            mNativeInterface.readScanReports(
                    mBatchScanParams.truncatedScanScannerId, SCAN_RESULT_TYPE_TRUNCATED);
            waitForCallback();
        }
        setBatchAlarm(client);
    }

    // Add scan filters. The logic is:
    // If no offload filter can/needs to be set, set ALL_PASS filter.
    // Otherwise offload all filters to hardware and enable all filters.
    private void configureScanFilters(ScanClient client) {
        int scannerId = client.getScannerId();
        int deliveryMode = getDeliveryMode(client);
        int trackEntries = 0;

        // Do not add any filters set by opportunistic scan clients
        if (isOpportunisticScanClient(client)) {
            return;
        }

        if (!shouldAddAllPassFilterToController(client, deliveryMode)) {
            return;
        }

        resetCountDownLatch();
        mNativeInterface.scanFilterEnable(scannerId, true);
        waitForCallback();

        if (shouldUseAllPassFilter(client)) {
            int filterIndex =
                    (deliveryMode == DELIVERY_MODE_BATCH)
                            ? ALL_PASS_FILTER_INDEX_BATCH_SCAN
                            : ALL_PASS_FILTER_INDEX_REGULAR_SCAN;
            resetCountDownLatch();
            // Don't allow Onfound/onlost with all pass
            configureFilterParameter(scannerId, client, ALL_PASS_FILTER_SELECTION, filterIndex, 0);
            waitForCallback();
        } else {
            Deque<Integer> clientFilterIndices = new ArrayDeque<>();
            for (ScanFilter filter : client.getFilters()) {
                ScanFilterQueue queue = new ScanFilterQueue();
                queue.addScanFilter(filter);
                int featureSelection = queue.getFeatureSelection();
                int filterIndex = mFilterIndexStack.pop();

                resetCountDownLatch();
                mNativeInterface.scanFilterAdd(scannerId, queue.toArray(), filterIndex);
                waitForCallback();

                resetCountDownLatch();
                if (deliveryMode == DELIVERY_MODE_ON_FOUND_LOST) {
                    trackEntries = getNumOfTrackingAdvertisements(client.getSettings());
                    if (!manageAllocationOfTrackingAdvertisement(trackEntries, true)) {
                        Log.e(
                                TAG,
                                "No hardware resources for onfound/onlost filter " + trackEntries);
                        var mumOfOffloadedScanFilterSupported =
                                mAdapterService.getNumOfOffloadedScanFilterSupported();
                        client.getAppScanStats()
                                .ifPresent(
                                        stats ->
                                                stats.recordHwFilterNotAvailableCountMetrics(
                                                        scannerId,
                                                        mumOfOffloadedScanFilterSupported));
                        mScanController.onScanManagerErrorCallback(
                                scannerId, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                    }
                }
                configureFilterParameter(
                        scannerId, client, featureSelection, filterIndex, trackEntries);
                waitForCallback();
                clientFilterIndices.add(filterIndex);
            }
            mClientFilterIndexMap.put(scannerId, clientFilterIndices);
        }
    }

    // Check whether the filter should be added to controller.
    // Note only on ALL_PASS filter should be added.
    private boolean shouldAddAllPassFilterToController(ScanClient client, int deliveryMode) {
        // Not an ALL_PASS client, need to add filter.
        if (!shouldUseAllPassFilter(client)) {
            return true;
        }

        if (deliveryMode == DELIVERY_MODE_BATCH) {
            mAllPassBatchClients.add(client.getScannerId());
            return mAllPassBatchClients.size() == 1;
        } else {
            mAllPassRegularClients.add(client.getScannerId());
            return mAllPassRegularClients.size() == 1;
        }
    }

    private void removeScanFilters(int scannerId) {
        Deque<Integer> filterIndices = mClientFilterIndexMap.remove(scannerId);
        if (filterIndices != null) {
            mFilterIndexStack.addAll(filterIndices);
            for (Integer filterIndex : filterIndices) {
                resetCountDownLatch();
                mNativeInterface.scanFilterParamDelete(scannerId, filterIndex);
                waitForCallback();
            }
        }
        // Remove if ALL_PASS filters are used.
        removeFilterIfExists(mAllPassRegularClients, scannerId, ALL_PASS_FILTER_INDEX_REGULAR_SCAN);
        removeFilterIfExists(mAllPassBatchClients, scannerId, ALL_PASS_FILTER_INDEX_BATCH_SCAN);
    }

    private void removeFilterIfExists(Set<Integer> clients, int scannerId, int filterIndex) {
        if (!clients.contains(scannerId)) {
            return;
        }
        clients.remove(scannerId);
        // Remove ALL_PASS filter iff no app is using it.
        if (clients.isEmpty()) {
            resetCountDownLatch();
            mNativeInterface.scanFilterParamDelete(scannerId, filterIndex);
            waitForCallback();
        }
    }

    private ScanClient getBatchScanClient(int scannerId) {
        for (ScanClient client : mBatchClients) {
            if (client.getScannerId() == scannerId) {
                return client;
            }
        }
        return null;
    }

    /** Return batch scan result type value defined in bt stack. */
    private static int getResultType(BatchScanParams params) {
        if (params.fullScanScannerId != -1 && params.truncatedScanScannerId != -1) {
            return SCAN_RESULT_TYPE_BOTH;
        }
        if (params.truncatedScanScannerId != -1) {
            return SCAN_RESULT_TYPE_TRUNCATED;
        }
        if (params.fullScanScannerId != -1) {
            return SCAN_RESULT_TYPE_FULL;
        }
        return -1;
    }

    // Check if ALL_PASS filter should be used for the client.
    private boolean shouldUseAllPassFilter(ScanClient client) {
        if (client == null) {
            return true;
        }
        if (client.getFilters().isEmpty()) {
            return true;
        }
        if (client.getFilters().size() > mFilterIndexStack.size()) {
            client.getAppScanStats()
                    .ifPresent(
                            stats ->
                                    stats.recordHwFilterNotAvailableCountMetrics(
                                            client.getScannerId(),
                                            mAdapterService
                                                    .getNumOfOffloadedScanFilterSupported()));
            return true;
        }
        return false;
    }

    private void initFilterIndexStack() {
        int maxFiltersSupported = mAdapterService.getNumOfOffloadedScanFilterSupported();
        if (!isFilteringSupported() && mIsMsftSupported) {
            // Hardcoded minimum number of hardware adv monitor slots, because this value
            // cannot be queried from the controller for MSFT enabled devices
            maxFiltersSupported = MIN_NUM_MSFT_MONITOR_SLOTS;
        }
        // Start from index 4 as:
        // index 0 is reserved for ALL_PASS filter in Settings app.
        // index 1 is reserved for ALL_PASS filter for regular scan apps.
        // index 2 is reserved for ALL_PASS filter for batch scan apps.
        // index 3 is reserved for BAP/CAP Announcements
        for (int i = 4; i < maxFiltersSupported; ++i) {
            mFilterIndexStack.add(i);
        }
    }

    // Configure filter parameters.
    private void configureFilterParameter(
            int scannerId,
            ScanClient client,
            int featureSelection,
            int filterIndex,
            int numOfTrackingEntries) {
        int deliveryMode = getDeliveryMode(client);
        int rssiThreshold = Byte.MIN_VALUE;
        ScanSettings settings = client.getSettings();
        if (Flags.rssiScanFilter()) {
            rssiThreshold = settings.getRssiThreshold();
        }
        int onFoundTimeout = getOnFoundOnLostTimeoutMillis(settings, true);
        int onFoundCount = getOnFoundOnLostSightings(settings);
        int onLostTimeout = 10000;
        Log.d(
                TAG,
                "configureFilterParameter "
                        + onFoundTimeout
                        + " "
                        + onLostTimeout
                        + " "
                        + onFoundCount
                        + " "
                        + numOfTrackingEntries);
        FilterParams filtValue =
                new FilterParams(
                        scannerId,
                        filterIndex,
                        featureSelection,
                        LIST_LOGIC_TYPE,
                        FILTER_LOGIC_TYPE,
                        rssiThreshold,
                        rssiThreshold,
                        deliveryMode,
                        onFoundTimeout,
                        onLostTimeout,
                        onFoundCount,
                        numOfTrackingEntries);
        mNativeInterface.scanFilterParamAdd(filtValue);
    }

    // Get delivery mode based on scan settings.
    private static int getDeliveryMode(ScanClient client) {
        if (client == null) {
            Log.d(TAG, "getDeliveryMode(): Client is null, defaulting to DELIVERY_MODE_IMMEDIATE");
            return DELIVERY_MODE_IMMEDIATE;
        }
        final var settings = client.getSettings();
        if ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                || (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            Log.d(
                    TAG,
                    "getDeliveryMode(): Callback type is CALLBACK_TYPE_FIRST_MATCH OR"
                            + " CALLBACK_TYPE_MATCH_LOST, using DELIVERY_MODE_ON_FOUND_LOST");
            return DELIVERY_MODE_ON_FOUND_LOST;
        }
        if (isAllMatchesAutoBatchScanClient(client)) {
            final boolean isEnabled = isAutoBatchScanClientEnabled(client);
            final int mode = isEnabled ? DELIVERY_MODE_BATCH : DELIVERY_MODE_IMMEDIATE;
            Log.d(
                    TAG,
                    "getDeliveryMode(): Client is auto-batch (enabled="
                            + isEnabled
                            + "), using delivery mode "
                            + (isEnabled ? "DELIVERY_MODE_BATCH" : "DELIVERY_MODE_IMMEDIATE"));
            return mode;
        }
        final long delay = settings.getReportDelayMillis();
        final int mode = delay == 0 ? DELIVERY_MODE_IMMEDIATE : DELIVERY_MODE_BATCH;
        Log.d(
                TAG,
                "getDeliveryMode(): Using report delay ("
                        + delay
                        + "ms) to set delivery mode to "
                        + ((delay == 0) ? "DELIVERY_MODE_IMMEDIATE" : "DELIVERY_MODE_BATCH"));
        return mode;
    }

    private int getScanWindowMillis(ScanSettings settings) {
        ContentResolver resolver = mAdapterService.getContentResolver();
        if (settings == null) {
            return Settings.Global.getInt(
                    resolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    SCAN_MODE_LOW_POWER_WINDOW_MS);
        }

        return switch (settings.getScanMode()) {
            case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_LATENCY_WINDOW_MS,
                            SCAN_MODE_LOW_LATENCY_WINDOW_MS);
            case ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                            SCAN_MODE_BALANCED_WINDOW_MS);
            case ScanSettings.SCAN_MODE_LOW_POWER ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                            SCAN_MODE_LOW_POWER_WINDOW_MS);
            case ScanSettings.SCAN_MODE_SCREEN_OFF ->
                    (int) mAdapterService.getScreenOffLowPowerWindow().toMillis();
            case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED ->
                    (int) mAdapterService.getScreenOffBalancedWindow().toMillis();
            default ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                            SCAN_MODE_LOW_POWER_WINDOW_MS);
        };
    }

    private int getScanIntervalMillis(ScanSettings settings) {
        ContentResolver resolver = mAdapterService.getContentResolver();
        if (settings == null) {
            return Settings.Global.getInt(
                    resolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    SCAN_MODE_LOW_POWER_INTERVAL_MS);
        }
        return switch (settings.getScanMode()) {
            case ScanSettings.SCAN_MODE_LOW_LATENCY ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_LATENCY_INTERVAL_MS,
                            SCAN_MODE_LOW_LATENCY_INTERVAL_MS);
            case ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                            SCAN_MODE_BALANCED_INTERVAL_MS);
            case ScanSettings.SCAN_MODE_LOW_POWER ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                            SCAN_MODE_LOW_POWER_INTERVAL_MS);
            case ScanSettings.SCAN_MODE_SCREEN_OFF ->
                    (int) mAdapterService.getScreenOffLowPowerInterval().toMillis();
            case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED ->
                    (int) mAdapterService.getScreenOffBalancedInterval().toMillis();
            default ->
                    Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                            SCAN_MODE_LOW_POWER_INTERVAL_MS);
        };
    }

    private static int getScanPhyMask(boolean usePhy1m, boolean usePhyCoded) {
        int phy = 0;
        if (usePhy1m) {
            phy |= BluetoothDevice.PHY_LE_1M_MASK;
        }
        if (usePhyCoded) {
            phy |= BluetoothDevice.PHY_LE_CODED_MASK;
        }
        return phy;
    }

    private static int getOnFoundOnLostTimeoutMillis(ScanSettings settings, boolean onFound) {
        int factor;
        int timeout = ONLOST_ONFOUND_BASE_TIMEOUT_MS;

        if (settings.getMatchMode() == ScanSettings.MATCH_MODE_AGGRESSIVE) {
            factor = MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR;
        } else {
            factor = MATCH_MODE_STICKY_TIMEOUT_FACTOR;
        }
        if (!onFound) {
            factor = factor * ONLOST_FACTOR;
        }
        return (timeout * factor);
    }

    private static int getOnFoundOnLostSightings(ScanSettings settings) {
        if (settings == null) {
            return ONFOUND_SIGHTINGS_AGGRESSIVE;
        }
        if (settings.getMatchMode() == ScanSettings.MATCH_MODE_AGGRESSIVE) {
            return ONFOUND_SIGHTINGS_AGGRESSIVE;
        } else {
            return ONFOUND_SIGHTINGS_STICKY;
        }
    }

    @VisibleForTesting
    int getNumOfTrackingAdvertisements(ScanSettings settings) {
        if (settings == null) {
            return 0;
        }
        int maxTotalTrackableAdvertisements =
                mAdapterService.getTotalNumOfTrackableAdvertisements();
        // controller based onfound onlost resources are scarce commodity; the
        // assignment of filters to num of beacons to track is configurable based
        // on hw capabilities. Apps give an intent and allocation of onfound
        // resources or failure there of is done based on availability - FCFS model
        return switch (settings.getNumOfMatches()) {
            case ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT -> 1;
            case ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT -> 2;
            case ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT -> {
                yield maxTotalTrackableAdvertisements / 4;
            }
            default -> {
                Log.d(TAG, "Invalid setting for getNumOfMatches() " + settings.getNumOfMatches());
                yield 1;
            }
        };
    }

    private boolean manageAllocationOfTrackingAdvertisement(
            int numOfTrackableAdvertisement, boolean allocate) {
        final int maxTotalTrackableAdvertisements =
                mAdapterService.getTotalNumOfTrackableAdvertisements();
        if (Flags.scanControllerThread()) {
            final int availableEntries =
                    maxTotalTrackableAdvertisements - mCurUsedTrackableAdvertisementsScanThread;
            if (allocate) {
                if (availableEntries >= numOfTrackableAdvertisement) {
                    mCurUsedTrackableAdvertisementsScanThread += numOfTrackableAdvertisement;
                    return true;
                }
                return false;
            } else {
                if (numOfTrackableAdvertisement > mCurUsedTrackableAdvertisementsScanThread) {
                    return false;
                }
                mCurUsedTrackableAdvertisementsScanThread -= numOfTrackableAdvertisement;
                return true;
            }
        } else {
            synchronized (mCurUsedTrackableAdvertisementsLock) {
                final int availableEntries =
                        maxTotalTrackableAdvertisements - mCurUsedTrackableAdvertisements;
                if (allocate) {
                    if (availableEntries >= numOfTrackableAdvertisement) {
                        mCurUsedTrackableAdvertisements += numOfTrackableAdvertisement;
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    if (numOfTrackableAdvertisement > mCurUsedTrackableAdvertisements) {
                        return false;
                    } else {
                        mCurUsedTrackableAdvertisements -= numOfTrackableAdvertisement;
                        return true;
                    }
                }
            }
        }
    }

    private void addFiltersMsft(ScanClient client) {
        // Do not add any filters set by opportunistic scan clients
        if (isOpportunisticScanClient(client)) {
            return;
        }

        if (client == null
                || client.getFilters().isEmpty()
                || client.getFilters().size() > mFilterIndexStack.size()) {
            // Use all-pass filter
            updateScanMsft();
            return;
        }

        Deque<Integer> clientFilterIndices = new ArrayDeque<>();
        for (ScanFilter filter : client.getFilters()) {
            MsftAdvMonitor monitor = new MsftAdvMonitor(filter);

            if (monitor.getAddress().bd_addr != null) {
                int filterIndex = mFilterIndexStack.pop();

                resetCountDownLatch();
                mNativeInterface.msftAdvMonitorAdd(
                        monitor.getMonitor(),
                        monitor.getPatterns(),
                        monitor.getAddress(),
                        filterIndex);
                waitForCallback();

                clientFilterIndices.add(filterIndex);
            }

            if (monitor.getPatterns().length == 0) {
                Log.d(
                        TAG,
                        "No MSFT pattern or address was translated from client filter: " + filter);
                continue;
            }

            // Some chipsets don't support multiple monitors with the same pattern. Skip
            // creating a new monitor if the pattern has already been registered
            int filterIndex = mFilterIndexStack.pop();
            int existingFilterIndex =
                    mMsftAdvMonitorMergedPatternList.add(filterIndex, monitor.getPatterns());
            if (filterIndex == existingFilterIndex) {
                resetCountDownLatch();
                mNativeInterface.msftAdvMonitorAdd(
                        monitor.getMonitor(),
                        monitor.getPatterns(),
                        monitor.getAddress(),
                        filterIndex);
                waitForCallback();
            } else {
                mFilterIndexStack.add(filterIndex);
            }

            clientFilterIndices.add(existingFilterIndex);
        }
        mClientFilterIndexMap.put(client.getScannerId(), clientFilterIndices);

        updateScanMsft();
    }

    private void removeFiltersMsft(ScanClient client) {
        Deque<Integer> clientFilterIndices = mClientFilterIndexMap.remove(client.getScannerId());
        if (clientFilterIndices != null) {
            for (int filterIndex : clientFilterIndices) {
                if (mMsftAdvMonitorMergedPatternList.remove(filterIndex)) {
                    resetCountDownLatch();
                    mNativeInterface.msftAdvMonitorRemove(filterIndex);
                    waitForCallback();
                    mFilterIndexStack.add(filterIndex);
                }
            }
        }

        updateScanMsft();
    }

    private void updateScanMsft() {
        boolean shouldEnableScanMsft =
                !mRegularScanClients.stream()
                        .anyMatch(
                                c ->
                                        c.getSettings().getScanMode()
                                                        != ScanSettings.SCAN_MODE_OPPORTUNISTIC
                                                && !this.mClientFilterIndexMap.containsKey(
                                                        c.getScannerId()));
        if (mScanEnabledMsft != shouldEnableScanMsft) {
            resetCountDownLatch();
            mNativeInterface.msftAdvMonitorEnable(shouldEnableScanMsft);
            waitForCallback();
            mScanEnabledMsft = shouldEnableScanMsft;

            // Restart scanning, since enabling/disabling may have changed
            // the filter policy
            Log.d(TAG, "Restarting MSFT scan");
            mNativeInterface.scan(false);
            if (numRegularScanClients() > 0) {
                mNativeInterface.scan(true);
            }
        }
    }

    private boolean isScreenOn() {
        final var displays = mDisplayManager.getDisplays();
        if (displays == null) {
            return false;
        }

        for (Display display : displays) {
            if (display.getState() == Display.STATE_ON) {
                return true;
            }
        }

        return false;
    }

    void onDisplayChanged(boolean screenOn) {
        if (Flags.scanControllerThread()) {
            mScanController.doOnScanThread(
                    screenOn
                            ? ScanManager.this::handleScreenOn
                            : ScanManager.this::handleScreenOff);
        } else {
            sendMessage(screenOn ? MSG_SCREEN_ON : MSG_SCREEN_OFF, null);
        }
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    onDisplayChanged(displayId);
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    onDisplayChanged(displayId);
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (Flags.adapterSuspendMgmt() && Flags.stopLeScanSystemSuspend()) {
                        Log.d(TAG, "Listen to display changes from adapter suspend manager");
                        return;
                    }
                    final var screenOn = isScreenOn();
                    if (Flags.scanControllerThread()) {
                        mScanController.doOnScanThread(
                                screenOn
                                        ? ScanManager.this::handleScreenOn
                                        : ScanManager.this::handleScreenOff);
                    } else {
                        sendMessage(screenOn ? MSG_SCREEN_ON : MSG_SCREEN_OFF, null);
                    }
                }
            };

    private final ActivityManager.OnUidImportanceListener mUidImportanceListener =
            new ActivityManager.OnUidImportanceListener() {
                @Override
                public void onUidImportance(final int uid, final int importance) {
                    if (mScanController.getScannerMap().getAppScanStatsByUid(uid) != null) {
                        final var uidImportance = new UidImportance(uid, importance);
                        if (Flags.scanControllerThread()) {
                            mScanController.doOnScanThread(
                                    () -> handleImportanceChange(uidImportance));
                        } else {
                            Message message = new Message();
                            message.what = MSG_IMPORTANCE_CHANGE;
                            message.obj = uidImportance;
                            mClientHandler.sendMessage(message);
                        }
                    }
                }
            };

    private final BroadcastReceiver mLocationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                        final var locationEnabled = mLocationManager.isLocationEnabled();
                        if (Flags.scanControllerThread()) {
                            mScanController.doOnScanThread(
                                    locationEnabled
                                            ? ScanManager.this::handleResumeScans
                                            : ScanManager.this::handleSuspendScans);
                        } else {
                            sendMessage(
                                    locationEnabled ? MSG_RESUME_SCANS : MSG_SUSPEND_SCANS, null);
                        }
                    }
                }
            };

    private boolean updateCountersAndCheckForConnectingState(int state, int prevState) {
        switch (prevState) {
            case STATE_CONNECTING -> {
                if (mProfilesConnecting > 0) {
                    mProfilesConnecting--;
                } else {
                    Log.e(TAG, "mProfilesConnecting " + mProfilesConnecting);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
            }
            case STATE_CONNECTED -> {
                if (mProfilesConnected > 0) {
                    mProfilesConnected--;
                } else {
                    Log.e(TAG, "mProfilesConnected " + mProfilesConnected);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
            }
            case STATE_DISCONNECTING -> {
                if (mProfilesDisconnecting > 0) {
                    mProfilesDisconnecting--;
                } else {
                    Log.e(TAG, "mProfilesDisconnecting " + mProfilesDisconnecting);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
            }
            default -> {} // Nothing to do
        }
        switch (state) {
            case STATE_CONNECTING -> mProfilesConnecting++;
            case STATE_CONNECTED -> mProfilesConnected++;
            case STATE_DISCONNECTING -> mProfilesDisconnecting++;
            default -> {} // Nothing to do
        }
        Log.d(
                TAG,
                ("mProfilesConnecting " + mProfilesConnecting)
                        + (", mProfilesConnected " + mProfilesConnected)
                        + (", mProfilesDisconnecting " + mProfilesDisconnecting));
        return (mProfilesConnecting > 0);
    }

    /**
     * Handle bluetooth profile connection state changes (for A2DP, HFP, HFP Client, A2DP Sink and
     * LE Audio).
     */
    void handleBluetoothProfileConnectionStateChanged(int profile, int fromState, int toState) {
        if (Flags.scanControllerThread()) {
            handleProfileConnectionStateChanged(profile, fromState, toState);
        } else {
            mClientHandler.post(
                    () -> handleProfileConnectionStateChanged(profile, fromState, toState));
        }
    }
}
