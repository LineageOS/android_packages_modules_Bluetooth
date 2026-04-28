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
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.le_scan.BatchScanUtil.ACTION_REFRESH_BATCHED_SCAN;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_FULL;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_TRUNCATED;
import static com.android.bluetooth.le_scan.ScanUtil.clearAutoBatchScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.getAggressiveClient;
import static com.android.bluetooth.le_scan.ScanUtil.isAutoBatchScanClientEnabled;
import static com.android.bluetooth.le_scan.ScanUtil.isBatchClient;
import static com.android.bluetooth.le_scan.ScanUtil.isExemptFromAutoBatchScanUpdate;
import static com.android.bluetooth.le_scan.ScanUtil.isExemptFromScanTimeout;
import static com.android.bluetooth.le_scan.ScanUtil.isOpportunisticScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.minScanMode;
import static com.android.bluetooth.le_scan.ScanUtil.priorityForScanMode;
import static com.android.bluetooth.le_scan.ScanUtil.requiresLocationOn;
import static com.android.bluetooth.le_scan.ScanUtil.requiresScreenOn;
import static com.android.bluetooth.le_scan.ScanUtil.scanModeToString;
import static com.android.bluetooth.le_scan.ScanUtil.setAutoBatchScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.setOpportunisticScanClient;
import static com.android.bluetooth.le_scan.ScanUtil.shouldUpdateScan;
import static com.android.bluetooth.le_scan.ScanUtil.upgradeScanModeByOneLevel;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.util.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayDeque;
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

/** Class that handles Bluetooth LE scan related operations. */
public class ScanManager {
    private static final String TAG = ScanUtil.TAG_PREFIX + ScanManager.class.getSimpleName();

    private static final int FOREGROUND_SERVICE_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND_SERVICE;
    private static final int FOREGROUND_UI_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND;
    private static final boolean DEFAULT_UID_IS_FOREGROUND = true;
    private static final boolean DEFAULT_UID_IS_FOREGROUND_UI = false;
    private static final int SCAN_MODE_FORCE_DOWNGRADED = ScanSettings.SCAN_MODE_LOW_POWER;
    private static final boolean DEFAULT_UID_HAS_PRIVILEGED_PERMISSION = false;

    // Timeout for each controller operation.
    private static final int MAX_UID_IMPORTANCE_MAP_SIZE = 500;

    private static final int ALL_PASS_FILTER_INDEX_REGULAR_SCAN = 1;
    private static final int ALL_PASS_FILTER_INDEX_BATCH_SCAN = 2;
    private static final int ALL_PASS_FILTER_SELECTION = 0;

    private static final int DISCARD_OLDEST_WHEN_BUFFER_FULL = 0;

    // The logic is AND for each filter field.
    private static final int LIST_LOGIC_TYPE = 0x1111111;
    private static final int FILTER_LOGIC_TYPE = 1;

    // Hardcoded min number of hardware adv monitor slots for MSFT-enabled controllers
    private static final int MIN_NUM_MSFT_MONITOR_SLOTS = 20;

    private final Set<ScanClient> mRegularScanClients = ConcurrentHashMap.newKeySet();
    private final Set<ScanClient> mBatchClients = ConcurrentHashMap.newKeySet();
    private final Set<ScanClient> mSuspendedScanClients = ConcurrentHashMap.newKeySet();
    private final SparseIntArray mUidImportanceMap = new SparseIntArray();
    private final SparseBooleanArray mIsUidPrivilegedPermissionMap = new SparseBooleanArray();

    // Filter indices that are available to user. It's sad we need to maintain filter index.
    private final Deque<Integer> mFilterIndexStack = new ArrayDeque<>();
    // Map of scannerId and Filter indices used by client.
    private final Map<Integer, Deque<Integer>> mClientFilterIndexMap = new HashMap<>();
    // Keep track of the clients that uses ALL_PASS filters.
    private final Set<Integer> mAllPassRegularClients = new HashSet<>();
    private final Set<Integer> mAllPassBatchClients = new HashSet<>();

    private final AtomicReference<BroadcastReceiver> mBatchAlarmReceiver = new AtomicReference<>();

    @VisibleForTesting final Map<ScanClient, Runnable> mScanTimeoutRunnables = new HashMap<>();

    private final Map<ScanClient, Runnable> mRevertScanModeUpgradeRunnables = new HashMap<>();

    private Runnable mClearConnectingStateRunnable;

    // List of merged MSFT patterns
    private final MsftAdvMonitorMergedFilterList mMsftAdvMonitorMergedFilterList =
            new MsftAdvMonitorMergedFilterList();

    private final AdapterService mAdapterService;
    private final ScanController mScanController;
    private final ScanNativeCallback mNativeCallback;
    private final ScanNativeInterface mNativeInterface;
    private final ScanRadioStats mScanRadioStats;
    private final TimeProvider mTimeProvider;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mBatchScanIntervalIntent;
    private final ActivityManager mActivityManager;
    private final LocationManager mLocationManager;
    @VisibleForTesting final ScanThrottler mScanThrottler;
    private final BatchScanThrottler mBatchScanThrottler;
    // Whether or not MSFT-based scanning hardware offload is available on this device
    private final boolean mIsMsftSupported;
    // Whether or not to use MSFT-based scan filtering
    private final boolean mUseMsftFiltering;

    @VisibleForTesting final Handler mHandler;
    private volatile boolean mIsAvailable = true;

    @VisibleForTesting boolean mIsConnecting;
    @VisibleForTesting int mProfilesConnecting;

    private int mLastConfiguredScanSetting1m = Integer.MIN_VALUE;
    private int mLastConfiguredScanSettingCoded = Integer.MIN_VALUE;
    // Scan parameters for batch scan.
    private BatchScanParams mBatchScanParams;

    // TODO(b/397863857) Rename to `mCurUsedTrackableAdvertisements` on flag cleanup
    private int mCurUsedTrackableAdvertisementsScanThread = 0;

    private boolean mScreenOn = false;
    private int mProfilesConnected;
    private int mProfilesDisconnecting;
    // Whether or not MSFT-based scanning is currently enabled in the controller
    private boolean mScanEnabledMsft = false;

    @VisibleForTesting
    record UidImportance(int uid, int importance) {}

    ScanManager(
            AdapterService service,
            ScanController scanController,
            ScanNativeInterface nativeInterface,
            ScanRadioStats scanRadioStats,
            Looper looper,
            TimeProvider timeProvider) {
        this(
                service,
                scanController,
                new ScanNativeCallback(service, scanController),
                nativeInterface,
                scanRadioStats,
                looper,
                timeProvider);
    }

    @VisibleForTesting
    ScanManager(
            AdapterService service,
            ScanController scanController,
            ScanNativeCallback nativeCallback,
            ScanNativeInterface nativeInterface,
            ScanRadioStats scanRadioStats,
            Looper looper,
            TimeProvider timeProvider) {
        mAdapterService = requireNonNull(service);
        mScanController = scanController;
        mNativeCallback = requireNonNull(nativeCallback);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new ScanNativeInterface(mNativeCallback));
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
        mIsMsftSupported = mNativeInterface.isMsftSupported();
        // Prefer APCF filtering over MSFT if both are available
        mUseMsftFiltering = !isFilteringSupported() && mIsMsftSupported;
        mActivityManager = mAdapterService.getSystemService(ActivityManager.class);
        mLocationManager = mAdapterService.getSystemService(LocationManager.class);
        mIsConnecting = false;
        mHandler = new Handler(looper);
        mScreenOn = mAdapterService.getDisplayListener().isScreenOn();
        mScanRadioStats = scanRadioStats;
        mScanController.doOnScanThread(
                () -> {
                    AppScanStats.setScreenState(mScreenOn);
                    mScanRadioStats.setScreenState(mScreenOn);
                });
        if (mActivityManager != null) {
            mActivityManager.addOnUidImportanceListener(
                    mForegroundServiceImportanceListener, FOREGROUND_SERVICE_IMPORTANCE_CUTOFF);
        }
        IntentFilter locationIntentFilter = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
        locationIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mAdapterService.registerReceiver(mLocationReceiver, locationIntentFilter);
        mScanThrottler = new ScanThrottler(this, mAdapterService, mHandler);
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            if (mActivityManager != null) {
                mActivityManager.addOnUidImportanceListener(
                        mForegroundUiImportanceListener, FOREGROUND_UI_IMPORTANCE_CUTOFF);
            }
        }
        mBatchScanThrottler = new BatchScanThrottler(timeProvider, mScreenOn);

        Log.d(TAG, "MSFT: isSupported=" + mIsMsftSupported + ", useFiltering=" + mUseMsftFiltering);
    }

    void cleanup() {
        Log.i(TAG, "cleanup()");
        mIsAvailable = false;
        mRegularScanClients.clear();
        mBatchClients.clear();
        mSuspendedScanClients.clear();

        if (mActivityManager != null) {
            try {
                mActivityManager.removeOnUidImportanceListener(
                        mForegroundServiceImportanceListener);
                if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
                    mActivityManager.removeOnUidImportanceListener(mForegroundUiImportanceListener);
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "exception when invoking removeOnUidImportanceListener", e);
            }
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
        mNativeInterface.registerScanner(uuid);
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
        Log.d(TAG, "startScan(" + client + ")");
        fetchAppForegroundState(client);
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            fetchUidPermission(client);
        }

        if (!isScanSupported(client)) {
            Log.e(TAG, "Scan settings not supported");
            return;
        }

        if (mRegularScanClients.contains(client) || mBatchClients.contains(client)) {
            Log.e(TAG, "Scan already started for " + client);
            return;
        }

        if (mScanController.isSystemSuspended()) {
            Log.w(
                    TAG,
                    "Cannot start LE scan in system-suspend."
                            + (" This scan will be resumed later for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats().recordScanSuspend(client.getScannerId());
            return;
        }

        if (requiresScreenOn(client) && !mScreenOn) {
            Log.w(
                    TAG,
                    "Cannot start unfiltered scan in screen-off."
                            + (" This scan will be resumed later for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats().recordScanSuspend(client.getScannerId());
            return;
        }

        final boolean locationEnabled = mLocationManager.isLocationEnabled();
        if (requiresLocationOn(client) && !locationEnabled) {
            Log.i(
                    TAG,
                    "Cannot start unfiltered scan in location-off."
                            + (" This scan will be resumed when location is on for " + client));
            mSuspendedScanClients.add(client);
            client.getAppScanStats().recordScanSuspend(client.getScannerId());
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
            if (mScanThrottler.isScanAllowanceThrottlingEnabled()
                    && mScanThrottler.applyAllowanceThrottling(client, client.getScanModeApp())) {
                Log.d(
                        TAG,
                        "Throttled batch scan mode for $client "
                            + "scanMode=${scanModeToString(client.getSettings().getScanMode())}");
            }
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

    void stopScan(int scannerId) {
        ScanClient client = ScanUtil.findById(mBatchClients, scannerId);
        if (client == null) {
            client = ScanUtil.findById(mRegularScanClients, scannerId);
        }
        if (client == null) {
            client = ScanUtil.findById(mSuspendedScanClients, scannerId);
        }
        if (client == null) {
            Log.d(TAG, "handleStopScan(): No client found for scannerId=" + scannerId);
            return;
        }
        handleStopScan(client);
    }

    void flushBatchScanResults(ScanClient client) {
        Log.d(TAG, "flushBatchScanResults(" + client + ")");
        handleFlushBatchResults(client);
    }

    void batchScanResultDelivered() {
        mBatchScanThrottler.resetBackoff();
    }

    private boolean isFilteringSupported() {
        return ScanUtil.isOffloadedFilteringSupported(mAdapterService);
    }

    int getCurrentUsedTrackingAdvertisement() {
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
        mUidImportanceMap.put(client.getAppUid(), importance);
        client.getAppScanStats().setAppImportance(importance);
    }

    private void fetchUidPermission(ScanClient client) {
        PackageManager packageManager = mAdapterService.getPackageManager();
        if (packageManager == null) {
            return;
        }
        String[] packages = packageManager.getPackagesForUid(client.getAppUid());
        if (packages == null || packages.length == 0) {
            return;
        }
        boolean hasPrivilegedPermission = false;
        for (String packageName : packages) {
            hasPrivilegedPermission =
                    Util.checkPrivilegedPermission(
                            mAdapterService, packageName, client.getAppUid());
        }
        mIsUidPrivilegedPermissionMap.put(client.getAppUid(), hasPrivilegedPermission);
    }

    private void configureTimeout(ScanClient client) {
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            // Skip scan time out when allowance based throttling is enabled
            return;
        }
        if (isExemptFromScanTimeout(client)) {
            return;
        }
        // Ensure only one timeout runnable exists per client
        Runnable oldRunnable = mScanTimeoutRunnables.remove(client);
        if (oldRunnable != null) {
            mHandler.removeCallbacks(oldRunnable);
        }
        final Runnable timeoutRunnable =
                () -> {
                    if (!mIsAvailable) return;
                    mScanTimeoutRunnables.remove(client);
                    handleRegularScanTimeout(client);
                };
        mScanTimeoutRunnables.put(client, timeoutRunnable);
        mHandler.postDelayed(timeoutRunnable, mAdapterService.getScanTimeout().toMillis());
        Log.d(TAG, "Apply scan timeout (" + mAdapterService.getScanTimeout() + ") to " + client);
    }

    private void handleStopScan(ScanClient client) {
        var header = "handleStopScan(): ";
        Log.d(TAG, header + "For " + client);
        final var appDied = client.getAppDied();
        final var scannerId = client.getScannerId();

        if (mSuspendedScanClients.contains(client)) {
            mSuspendedScanClients.remove(client);
        }
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            mScanThrottler.removeRecordUsageRunnable(client);
        } else {
            Runnable timeoutRunnable = mScanTimeoutRunnables.remove(client);
            if (timeoutRunnable != null) {
                mHandler.removeCallbacks(timeoutRunnable);
            }
        }
        Runnable revertRunnable = mRevertScanModeUpgradeRunnables.remove(client);
        if (revertRunnable != null) {
            mHandler.removeCallbacks(revertRunnable);
        }
        if (mRegularScanClients.contains(client)) {
            stopRegularScan(client);

            if (!isOpportunisticScanClient(client)) {
                configureRegularScanParams();
            }
        } else if (mBatchClients.contains(client)) {
            if (isAutoBatchScanClientEnabled(client)) {
                handleFlushBatchResults(client);
            }
            stopBatchScan(client);
        }
        if (appDied) {
            Log.d(TAG, header + "App died, unregister scannerId=" + scannerId);
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
        if (mUseMsftFiltering && !isBatchClient(client)) {
            return true;
        }
        return client.getSettings().getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                && client.getSettings().getReportDelayMillis() == 0;
    }

    @VisibleForTesting
    void handleScreenOff() {
        AppScanStats.setScreenState(false);
        mScanRadioStats.setScreenState(false);
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
            if (mScanThrottler.downgradeScanModeFromMaxDuty(client)) {
                updatedScanParams = true;
                Log.d(TAG, "scanMode is downgraded by connecting for " + client);
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
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
    }

    @VisibleForTesting
    void handleClearConnectingState() {
        if (!mIsConnecting) {
            Log.e(TAG, "handleClearConnectingState(): Not connecting state");
            return;
        }
        Log.d(TAG, "handleClearConnectingState()");
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (mScanThrottler.revertDowngradeScanModeFromMaxDuty(client, mScreenOn)) {
                updatedScanParams = true;
                Log.d(TAG, "downgraded scanMode is reverted for " + client);
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
        if (mClearConnectingStateRunnable != null) {
            mHandler.removeCallbacks(mClearConnectingStateRunnable);
            mClearConnectingStateRunnable = null;
        }
        mIsConnecting = false;
    }

    @VisibleForTesting
    void handleSuspendScans() {
        var isLocationEnabled = mLocationManager.isLocationEnabled();
        for (ScanClient client : mRegularScanClients) {
            var screenRequirementUnmet = requiresScreenOn(client) && !mScreenOn;
            var locationRequirementUnmet = requiresLocationOn(client) && !isLocationEnabled;
            if (screenRequirementUnmet || locationRequirementUnmet) {
                client.getAppScanStats().recordScanSuspend(client.getScannerId());
                Log.d(TAG, "Suspending scan for " + client);
                handleStopScan(client);
                mSuspendedScanClients.add(client);
            }
        }
    }

    private void updateRegularScanToBatchScanClients() {
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (!isExemptFromAutoBatchScanUpdate(client)) {
                Log.d(TAG, "Updating regular scan to batch scan for " + client);
                handleStopScan(client);
                setAutoBatchScanClient(client);
                startScan(client);
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
                Log.d(TAG, "Updating batch scan to regular scan for " + client);
                handleStopScan(client);
                clearAutoBatchScanClient(client);
                startScan(client);
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    private void updateRegularScanClientsScreenOff() {
        // use the scan_allowance_throttling_enabled flag to gate the screen off delayed
        // throttling
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            if (!mRegularScanClients.isEmpty()) {
                mScanThrottler.throttleAllScanModeScreenOffDelayed(
                        mRegularScanClients,
                        isUpdated -> {
                            if (isUpdated) {
                                configureRegularScanParams();
                            }
                        });
            }
            return;
        }
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (mScanThrottler.throttleScanModeScreenOff(client)) {
                updatedScanParams = true;
            }
        }
        if (updatedScanParams) {
            configureRegularScanParams();
        }
    }

    /**
     * Services and Apps are assumed to be in the foreground by default unless it changes to the
     * background triggering onUidImportance().
     */
    boolean isAppForeground(ScanClient client) {
        if (mUidImportanceMap.indexOfKey(client.getAppUid()) < 0) {
            return DEFAULT_UID_IS_FOREGROUND;
        }
        return mUidImportanceMap.get(client.getAppUid()) <= IMPORTANCE_FOREGROUND_SERVICE;
    }

    boolean isAppForegroundUi(ScanClient client) {
        if (mUidImportanceMap.indexOfKey(client.getAppUid()) < 0) {
            return DEFAULT_UID_IS_FOREGROUND_UI;
        }
        return mUidImportanceMap.get(client.getAppUid()) <= IMPORTANCE_FOREGROUND;
    }

    boolean hasPrivilegedPermission(ScanClient client) {
        return mIsUidPrivilegedPermissionMap.get(
                client.getAppUid(), DEFAULT_UID_HAS_PRIVILEGED_PERMISSION);
    }

    private boolean updateScanModeBeforeStart(ScanClient client) {
        if (upgradeScanModeBeforeStart(client)) {
            return true;
        }
        if (mScreenOn) {
            return mScanThrottler.throttleScanModeScreenOn(client);
        } else {
            return mScanThrottler.throttleScanModeScreenOff(client);
        }
    }

    private boolean updateScanModeConcurrency(ScanClient client) {
        if (mIsConnecting) {
            return mScanThrottler.downgradeScanModeFromMaxDuty(client);
        }
        return false;
    }

    private boolean upgradeScanModeBeforeStart(ScanClient client) {
        if (client.getStarted() || mAdapterService.getScanUpgradeDuration().equals(Duration.ZERO)) {
            return false;
        }
        if (client.getAppScanStats() == null || client.getAppScanStats().hasRecentScan()) {
            return false;
        }
        if (!isAppForeground(client) || isBatchClient(client)) {
            return false;
        }
        if (Flags.upgradeLeScanOnlyScreenOn() && !mScreenOn) {
            return false;
        }

        if (upgradeScanModeByOneLevel(client)) {
            final Runnable revertRunnable =
                    () -> {
                        if (!mIsAvailable) return;
                        mRevertScanModeUpgradeRunnables.remove(client);
                        handleRevertScanModeUpgrade(client);
                    };
            mRevertScanModeUpgradeRunnables.put(client, revertRunnable);
            mHandler.postDelayed(
                    revertRunnable, mAdapterService.getScanUpgradeDuration().toMillis());
            final var scanModeString = scanModeToString(client.getSettings().getScanMode());
            Log.d(TAG, "upgradeScanModeBeforeStart(): for " + client + " to=" + scanModeString);
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
            var header = "handleRevertScanModeUpgrade(): ";
            Log.d(TAG, header + "for " + client + " to=" + scanModeToString(scanModeApp));
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

        // There can be multuple importance changed listeners registered to listen multiple cut
        // point like IMPORTANCE_FOREGROUND_SERVICES and IMPORTANCE_FOREGROUND. Skip if
        // importance is already up to date.
        if (mUidImportanceMap.get(uid) == importance) {
            return;
        }

        if (mUidImportanceMap.size() < MAX_UID_IMPORTANCE_MAP_SIZE) {
            mUidImportanceMap.put(uid, importance);
        }

        Set<ScanClient> uidScanClients =
                mRegularScanClients.stream()
                        .filter(
                                client ->
                                        client.getAppUid() == uid
                                                && !isOpportunisticScanClient(client))
                        .collect(Collectors.toSet());

        if (uidScanClients.isEmpty()) return;

        boolean updatedScanParams = false;
        for (ScanClient client : uidScanClients) {
            client.getAppScanStats().setAppImportance(importance);
            if (isForeground) {
                if (mScanThrottler.throttleScanModeForegroundUid(client, uid, mScreenOn)) {
                    updatedScanParams = true;
                }
            } else {
                // use the scan_allowance_throttling_enabled flag to gate the background uid
                // delayed
                // throttling. Background uid immediate throttling should be disabled when flag is
                // on
                if (!mScanThrottler.isScanAllowanceThrottlingEnabled()
                        && mScanThrottler.throttleScanModeBackgroundUid(client, uid, mScreenOn)) {
                    updatedScanParams = true;
                }
            }
        }

        if (updatedScanParams) {
            configureRegularScanParams();
        }

        // use the scan_allowance_throttling_enabled flag to gate the background uid delayed
        // throttling
        if (mScanThrottler.isScanAllowanceThrottlingEnabled() && !isForeground) {
            mScanThrottler.throttleAllScanModeBackgroundUidDelayed(
                    uid,
                    mScreenOn,
                    uidScanClients,
                    isUpdated -> {
                        if (isUpdated) {
                            configureRegularScanParams();
                        }
                    });
        }
    }

    @VisibleForTesting
    void handleScreenOn() {
        AppScanStats.setScreenState(true);
        mScanRadioStats.setScreenState(true);
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
                client.getAppScanStats().recordScanResume(client.getScannerId());
                Log.d(TAG, "Resume scan for " + client);
                startScan(client);
                iterator.remove();
            }
        }
    }

    private void updateRegularScanClientsScreenOn() {
        boolean updatedScanParams = false;
        for (ScanClient client : mRegularScanClients) {
            if (mScanThrottler.throttleScanModeScreenOn(client)) {
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

    public void onScanAllowanceChanged() {
        boolean changed = false;
        // TODO(b/478349128): support batch scan clients
        for (ScanClient client : mRegularScanClients) {
            changed |= mScanThrottler.throttleScanMode(client, client.getScanModeApp(), mScreenOn);
        }
        if (changed) {
            configureRegularScanParams();
        }
        for (ScanClient client : mBatchClients) {
            if (mScanThrottler.applyAllowanceThrottling(client, client.getScanModeApp())) {
                resetBatchScan(client);
            }
        }
    }

    private void configureRegularScanParams() {
        var header = "configureRegularScanParams(): ";
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
                ScanUtil.scanPhyMask(
                        mLastConfiguredScanSetting1m != Integer.MIN_VALUE,
                        mLastConfiguredScanSettingCoded != Integer.MIN_VALUE);
        int scanPhyMask = ScanUtil.scanPhyMask(client1m != null, clientCoded != null);

        // Only update scan parameters if at least one of the following is true:
        // 1. The 1M PHY mode has changed and is a valid value
        var has1mPhyChanged = shouldUpdateScan(newScanSetting1m, mLastConfiguredScanSetting1m);
        // 2. The coded PHY mode has changed and is a valid value
        var hasCodedPhyChanged =
                shouldUpdateScan(newScanSettingCoded, mLastConfiguredScanSettingCoded);
        // 3. The PHYs to scan on have changed and the new setting is valid (not 0)
        var hasPhyMaskChanged = scanPhyMask != 0 && curPhyMask != scanPhyMask;
        Log.d(
                TAG,
                (header + "queueSize=" + mRegularScanClients.size())
                        + (" has1mPhyChanged=" + has1mPhyChanged)
                        + (" hasCodedPhyChanged=" + hasCodedPhyChanged)
                        + (" hasPhyMaskChanged=" + hasPhyMaskChanged));
        if (has1mPhyChanged || hasCodedPhyChanged || hasPhyMaskChanged) {
            int scanWindow1m = ScanUtil.scanWindow(mAdapterService, client1m);
            int scanInterval1m = ScanUtil.scanInterval(mAdapterService, client1m);
            int scanWindowCoded = ScanUtil.scanWindow(mAdapterService, clientCoded);
            int scanIntervalCoded = ScanUtil.scanInterval(mAdapterService, clientCoded);
            mNativeInterface.scan(false, "configureRegularScanParams");
            mScanRadioStats.recordScanRadioStop("configureRegularScanParams");
            Log.d(
                    TAG,
                    (header + "Set Scan Parameters Native")
                            + (" old 1M scanMode=" + mLastConfiguredScanSetting1m)
                            + (", new 1M scanMode=" + newScanSetting1m)
                            + (" (in scan unit=" + scanInterval1m + " / " + scanWindow1m + ")")
                            + (", old coded scanMode=" + mLastConfiguredScanSettingCoded)
                            + (", new coded scanMode=" + newScanSettingCoded)
                            + (" (in scan unit=" + scanIntervalCoded + " / " + scanWindowCoded)
                            + (", " + "scanPhyMask=" + scanPhyMask + " )")
                            + (", " + client1m + " / " + clientCoded));
            mNativeInterface.setScanParameters(
                    client1m == null ? 0 : client1m.getScannerId(),
                    scanInterval1m,
                    scanWindow1m,
                    clientCoded == null ? 0 : clientCoded.getScannerId(),
                    scanIntervalCoded,
                    scanWindowCoded,
                    scanPhyMask);
            mNativeInterface.scan(true, "configureRegularScanParams");
            recordScanRadioStart(client1m, clientCoded, newScanSetting1m, newScanSettingCoded);
        } else {
            Log.d(TAG, header + "Queue empty, scan stopped");
        }
        mLastConfiguredScanSetting1m = newScanSetting1m;
        mLastConfiguredScanSettingCoded = newScanSettingCoded;
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
        if (chosenClient != null && chosenClient.getAppScanStats() != null) {
            var chosenClientSettings = chosenClient.getSettings();
            mScanRadioStats.recordScanRadioStart(
                    chosenClient.getScanModeApp(),
                    chosenClient.getScannerId(),
                    chosenClient.getAppScanStats(),
                    ScanUtil.windowMillis(mAdapterService, chosenClientSettings),
                    ScanUtil.intervalMillis(mAdapterService, chosenClientSettings));
        }
    }

    private void startRegularScan(ScanClient client) {
        if ((isFilteringSupported() || mUseMsftFiltering)
                && mFilterIndexStack.isEmpty()
                && mClientFilterIndexMap.isEmpty()) {
            initFilterIndexStack();
        }
        if (isFilteringSupported()) {
            configureScanFilters(client);
        } else if (mUseMsftFiltering) {
            addFiltersMsft(client);
        }

        // Start scan native only for the first client.
        if (numRegularScanClients() == 1
                && client.getSettings().getScanMode() != ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
            mNativeInterface.scan(true, "startRegularScan");
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
            // Reset batch scan. May need to stop the existing batch scan and update scan params
            resetBatchScan(client);
        }
    }

    private void resetBatchScan(ScanClient client) {
        var header = "resetBatchScan(" + client + "): ";
        int scannerId = client.getScannerId();
        BatchScanParams batchScanParams = fetchBatchScanParams();
        // Stop batch if batch scan params changed and previous params is not null.
        if (mBatchScanParams != null && (!mBatchScanParams.equals(batchScanParams))) {
            Log.d(TAG, header + "Stopping BLE Batch");
            mNativeInterface.stopBatchScan(scannerId);
            // Clear pending results as it's illegal to config storage if there are still
            // pending results.
            flushBatchResults(client);
        }
        // Start batch if batchScanParams changed and current params is not null.
        if (batchScanParams != null && (!batchScanParams.equals(mBatchScanParams))) {
            int notifyThreshold = 95;
            int resultType = BatchScanUtil.resultType(batchScanParams);
            int fullScanPercent = BatchScanUtil.fullScanStoragePercent(resultType);
            Log.d(TAG, header + "Configuring batch scan storage");
            mNativeInterface.configBatchScanStorage(
                    client.getScannerId(), fullScanPercent, 100 - fullScanPercent, notifyThreshold);
            var scanMode = batchScanParams.getScanMode();
            int scanInterval =
                    Utils.millsToUnit(BatchScanUtil.intervalMillis(mAdapterService, scanMode));
            int scanWindow =
                    Utils.millsToUnit(BatchScanUtil.windowMillis(mAdapterService, scanMode));
            Log.d(
                    TAG,
                    header
                            + ("Start BLE batch scan with scanIntervalMs=" + scanInterval)
                            + (", windowIntervalMs" + scanWindow));
            mNativeInterface.startBatchScan(
                    scannerId,
                    resultType,
                    scanInterval,
                    scanWindow,
                    0,
                    DISCARD_OLDEST_WHEN_BUFFER_FULL);
        }
        mBatchScanParams = batchScanParams;
        setBatchAlarm(client);
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

    // Set the batch alarm to be triggered within a short window after batch interval. This
    // allows system to optimize wake up time while still allows a degree of precise control.
    private void setBatchAlarm(ScanClient client) {
        var header = "setBatchAlarm(" + client + "): ";
        Log.d(TAG, header + "Canceling pending batch scan alarm");
        mAlarmManager.cancel(mBatchScanIntervalIntent);

        if (mBatchClients.isEmpty()) {
            Log.d(TAG, header + "No batch clients; Skipping alarm setup");
            return;
        }
        final long batchTriggerIntervalMillis =
                mBatchScanThrottler.getBatchTriggerIntervalMillis(mBatchClients);
        // Allows the alarm to be triggered within
        // [batchTriggerIntervalMillis, 1.1 * batchTriggerIntervalMillis]
        final long windowLengthMillis = batchTriggerIntervalMillis / 10;
        final long windowStartMs = mTimeProvider.elapsedRealtime() + batchTriggerIntervalMillis;
        final var windowStartReadable = Utils.formatElapsedRealtime(windowStartMs);
        Log.d(TAG, header + "For=" + windowStartReadable + " (" + windowStartMs + "ms)");
        client.getAppScanStats().recordBatchAlarmScheduled();
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
        int deliveryMode = ScanUtil.deliveryMode(client);
        if (deliveryMode == ScanUtil.DELIVERY_MODE_ON_FOUND_LOST) {
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
            mNativeInterface.scan(false, "stopRegularScan");
            mScanRadioStats.recordScanRadioStop("stopRegularScan");
        }

        if (mUseMsftFiltering) {
            removeFiltersMsft(client);
        } else {
            removeScanFilters(client.getScannerId());
        }
    }

    private void handleRegularScanTimeout(ScanClient client) {
        if (mScanThrottler.isScanAllowanceThrottlingEnabled()) {
            // skip scan timeout when allowance based throttling is enabled
            return;
        }
        var header = "handleRegularScanTimeout(" + client + "): ";
        var appScanStats = client.getAppScanStats();
        var isScanningTooLong = appScanStats == null || appScanStats.isScanningTooLong();
        if (!isExemptFromScanTimeout(client) && isScanningTooLong) {
            Log.d(TAG, header + "Scan time was too long");
            if (!client.isFiltered()) {
                Log.w(TAG, header + "Moving unfiltered scan to opportunistic scan");
                setOpportunisticScanClient(client);
                removeScanFilters(client.getScannerId());
            } else {
                Log.w(TAG, header + "Moving filtered scan to downgraded scan");
                int scanMode = client.getSettings().getScanMode();
                int maxScanMode = SCAN_MODE_FORCE_DOWNGRADED;
                client.updateScanMode(minScanMode(scanMode, maxScanMode));
            }
            client.getAppScanStats().setScanTimeout(client.getScannerId());
            client.getAppScanStats()
                    .recordScanTimeoutCountMetrics(
                            client.getScannerId(), mAdapterService.getScanTimeout().toMillis());
        }

        // The scan should continue for background scans
        configureRegularScanParams();
        if (numRegularScanClients() == 0) {
            mNativeInterface.scan(false, "handleRegularScanTimeout");
            mScanRadioStats.recordScanRadioStop("handleRegularScanTimeout");
        }
    }

    private void stopBatchScan(ScanClient client) {
        mBatchClients.remove(client);
        removeScanFilters(client.getScannerId());
        if (!isOpportunisticScanClient(client)) {
            resetBatchScan(client);
        }
    }

    private void flushBatchResults(ScanClient client) {
        int fullScanScannerId = mBatchScanParams.getFullScanScannerId();
        if (fullScanScannerId != -1) {
            mNativeInterface.readScanReports(fullScanScannerId, SCAN_RESULT_TYPE_FULL);
        }
        int truncatedScanScannerId = mBatchScanParams.getTruncatedScanScannerId();
        if (truncatedScanScannerId != -1) {
            mNativeInterface.readScanReports(truncatedScanScannerId, SCAN_RESULT_TYPE_TRUNCATED);
        }
        setBatchAlarm(client);
    }

    // Add scan filters. The logic is:
    // If no offload filter can/needs to be set, set ALL_PASS filter.
    // Otherwise offload all filters to hardware and enable all filters.
    private void configureScanFilters(ScanClient client) {
        int scannerId = client.getScannerId();
        int deliveryMode = ScanUtil.deliveryMode(client);
        int trackEntries = 0;

        // Do not add any filters set by opportunistic scan clients
        if (isOpportunisticScanClient(client)) {
            return;
        }

        if (!shouldAddAllPassFilterToController(client, deliveryMode)) {
            return;
        }

        mNativeInterface.scanFilterEnable(scannerId, true);

        if (shouldUseAllPassFilter(client)) {
            int filterIndex =
                    (deliveryMode == ScanUtil.DELIVERY_MODE_BATCH)
                            ? ALL_PASS_FILTER_INDEX_BATCH_SCAN
                            : ALL_PASS_FILTER_INDEX_REGULAR_SCAN;
            // Don't allow Onfound/onlost with all pass
            configureFilterParameter(scannerId, client, ALL_PASS_FILTER_SELECTION, filterIndex, 0);
        } else {
            Deque<Integer> clientFilterIndices = new ArrayDeque<>();
            for (ScanFilter filter : client.getFilters()) {
                ScanFilterQueue queue = new ScanFilterQueue();
                queue.addScanFilter(filter);
                int featureSelection = queue.getFeatureSelection();
                int filterIndex = mFilterIndexStack.pop();

                mNativeInterface.scanFilterAdd(scannerId, queue.toArray(), filterIndex);

                if (deliveryMode == ScanUtil.DELIVERY_MODE_ON_FOUND_LOST) {
                    trackEntries = getNumOfTrackingAdvertisements(client.getSettings());
                    if (!manageAllocationOfTrackingAdvertisement(trackEntries, true)) {
                        Log.e(
                                TAG,
                                "No hardware resources for onfound/onlost filter " + trackEntries);
                        var mumOfOffloadedScanFilterSupported =
                                mAdapterService.getNumOfOffloadedScanFilterSupported();
                        client.getAppScanStats()
                                .recordHwFilterNotAvailableCountMetrics(
                                        scannerId, mumOfOffloadedScanFilterSupported);
                        mScanController.onScanManagerErrorCallback(
                                scannerId, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                    }
                }
                configureFilterParameter(
                        scannerId, client, featureSelection, filterIndex, trackEntries);
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

        if (deliveryMode == ScanUtil.DELIVERY_MODE_BATCH) {
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
                mNativeInterface.scanFilterParamDelete(scannerId, filterIndex);
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
            mNativeInterface.scanFilterParamDelete(scannerId, filterIndex);
        }
    }

    // Check if ALL_PASS filter should be used for the client.
    private boolean shouldUseAllPassFilter(ScanClient client) {
        if (client == null) {
            return true;
        }
        if (!client.isFiltered()) {
            return true;
        }
        if (client.getFilters().size() > mFilterIndexStack.size()) {
            client.getAppScanStats()
                    .recordHwFilterNotAvailableCountMetrics(
                            client.getScannerId(),
                            mAdapterService.getNumOfOffloadedScanFilterSupported());
            return true;
        }
        return false;
    }

    private void initFilterIndexStack() {
        int maxFiltersSupported = mAdapterService.getNumOfOffloadedScanFilterSupported();
        if (mUseMsftFiltering) {
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
        int deliveryMode = ScanUtil.deliveryMode(client);
        ScanSettings settings = client.getSettings();
        int rssiThreshold = settings.getRssiThreshold();
        int onFoundTimeout = ScanUtil.onFoundOnLostTimeoutMillis(settings, true);
        int onFoundCount = ScanUtil.onFoundOnLostSightings(settings);
        int onLostTimeout = 10000;
        Log.d(
                TAG,
                "configureFilterParameter(): "
                        + ("onFoundTimeout=" + onFoundTimeout)
                        + (" onLostTimeout=" + onLostTimeout)
                        + (" onFoundCount=" + onFoundCount)
                        + (" numOfTrackingEntries=" + numOfTrackingEntries));
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
    }

    private void addFiltersMsft(ScanClient client) {
        // Do not add any filters set by opportunistic scan clients
        if (isOpportunisticScanClient(client)) {
            return;
        }

        if (client == null
                || !client.isFiltered()
                || client.getFilters().size() > mFilterIndexStack.size()) {
            // Use all-pass filter
            updateScanMsft();
            return;
        }

        Deque<Integer> clientFilterIndices = new ArrayDeque<>();
        for (ScanFilter filter : client.getFilters()) {
            MsftAdvMonitor monitor = new MsftAdvMonitor(filter);

            if (monitor.getMonitor().condition_type == MsftAdvMonitor.MSFT_CONDITION_TYPE_INVALID) {
                Log.e(TAG, "No MSFT monitor was translated from client filter: " + filter);
                continue;
            }

            int filterIndex = mFilterIndexStack.pop();
            int existingFilterIndex = filterIndex;
            // Some chipsets don't support multiple monitors with the same pattern. Skip
            // creating a new monitor if the pattern has already been registered
            if (monitor.getMonitor().condition_type == MsftAdvMonitor.MSFT_CONDITION_TYPE_ADDRESS) {
                existingFilterIndex =
                        mMsftAdvMonitorMergedFilterList.addAddress(
                                filterIndex, monitor.getAddress());
            } else if (monitor.getMonitor().condition_type
                    == MsftAdvMonitor.MSFT_CONDITION_TYPE_UUID) {
                existingFilterIndex =
                        mMsftAdvMonitorMergedFilterList.addUuid(filterIndex, monitor.getUuid());
            } else {
                existingFilterIndex =
                        mMsftAdvMonitorMergedFilterList.addPattern(
                                filterIndex, monitor.getPatterns());
            }

            if (filterIndex == existingFilterIndex) {
                mNativeInterface.msftAdvMonitorAdd(
                        monitor.getMonitor(),
                        monitor.getPatterns(),
                        monitor.getUuid(),
                        monitor.getAddress(),
                        filterIndex);
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
                if (mMsftAdvMonitorMergedFilterList.remove(filterIndex)) {
                    final int monitorHandle =
                            mScanController.msftMonitorHandleFromFilterIndex(filterIndex);
                    if (monitorHandle >= 0) {
                        mNativeInterface.msftAdvMonitorRemove(filterIndex, monitorHandle);
                    }
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
            mNativeInterface.msftAdvMonitorEnable(shouldEnableScanMsft);
            mScanEnabledMsft = shouldEnableScanMsft;
            // Restart LE scan in callback to apply filter policy change.
        }
    }

    void restartScan(String caller) {
        Log.d(TAG, "restartScan(" + caller + ")");
        mNativeInterface.scan(false, caller);
        if (numRegularScanClients() > 0) {
            mNativeInterface.scan(true, caller);
        }
    }

    void onDisplayChanged(boolean screenOn) {
        if (screenOn) handleScreenOn();
        else handleScreenOff();
    }

    private final ActivityManager.OnUidImportanceListener mForegroundServiceImportanceListener =
            getUidImportanceListener();
    private final ActivityManager.OnUidImportanceListener mForegroundUiImportanceListener =
            getUidImportanceListener();

    private ActivityManager.OnUidImportanceListener getUidImportanceListener() {
        return new ActivityManager.OnUidImportanceListener() {
            @Override
            public void onUidImportance(final int uid, final int importance) {
                mScanController.doOnScanThread(
                        () -> {
                            if (mScanController.getScannerMap().getAppScanStatsByUid(uid) != null) {
                                handleImportanceChange(new UidImportance(uid, importance));
                            }
                        });
            }
        };
    }

    private final BroadcastReceiver mLocationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                        final var locationEnabled = mLocationManager.isLocationEnabled();
                        mScanController.doOnScanThread(
                                locationEnabled
                                        ? ScanManager.this::handleResumeScans
                                        : ScanManager.this::handleSuspendScans);
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
        handleProfileConnectionStateChanged(profile, fromState, toState);
    }
}
