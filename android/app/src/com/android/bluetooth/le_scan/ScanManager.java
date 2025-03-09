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
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.le.ScanSettings.getScanModeString;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
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
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.bluetooth.Utils;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.BluetoothAdapterProxy;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.FilterParams;
import com.android.bluetooth.gatt.GattServiceConfig;
import com.android.bluetooth.util.SystemProperties;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Class that handles Bluetooth LE scan related operations. */
public class ScanManager {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + ScanManager.class.getSimpleName();

    public static final int SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW_MS = 512;
    public static final int SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL_MS = 10240;
    public static final int SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW_MS = 183;
    public static final int SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL_MS = 730;

    /** Scan params corresponding to regular scan setting */
    @VisibleForTesting static final int SCAN_MODE_LOW_POWER_WINDOW_MS = 140;

    @VisibleForTesting static final int SCAN_MODE_LOW_POWER_INTERVAL_MS = 1400;
    @VisibleForTesting static final int SCAN_MODE_BALANCED_WINDOW_MS = 183;
    @VisibleForTesting static final int SCAN_MODE_BALANCED_INTERVAL_MS = 730;
    @VisibleForTesting static final int SCAN_MODE_LOW_LATENCY_WINDOW_MS = 100;
    @VisibleForTesting static final int SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 100;

    // Result type defined in bt stack. Need to be accessed by ScanController.
    static final int SCAN_RESULT_TYPE_TRUNCATED = 1;
    static final int SCAN_RESULT_TYPE_FULL = 2;
    static final int SCAN_RESULT_TYPE_BOTH = 3;

    // Messages for handling BLE scan operations.
    @VisibleForTesting static final int MSG_START_BLE_SCAN = 0;
    static final int MSG_STOP_BLE_SCAN = 1;
    static final int MSG_FLUSH_BATCH_RESULTS = 2;
    static final int MSG_SCAN_TIMEOUT = 3;
    static final int MSG_SUSPEND_SCANS = 4;
    static final int MSG_RESUME_SCANS = 5;
    static final int MSG_IMPORTANCE_CHANGE = 6;
    static final int MSG_SCREEN_ON = 7;
    static final int MSG_SCREEN_OFF = 8;
    static final int MSG_REVERT_SCAN_MODE_UPGRADE = 9;
    static final int MSG_START_CONNECTING = 10;
    static final int MSG_STOP_CONNECTING = 11;
    private static final String ACTION_REFRESH_BATCHED_SCAN =
            "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN";

    private static final int FOREGROUND_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND_SERVICE;
    private static final boolean DEFAULT_UID_IS_FOREGROUND = true;
    private static final int SCAN_MODE_APP_IN_BACKGROUND = ScanSettings.SCAN_MODE_LOW_POWER;
    private static final int SCAN_MODE_FORCE_DOWNGRADED = ScanSettings.SCAN_MODE_LOW_POWER;
    private static final int SCAN_MODE_MAX_IN_CONCURRENCY = ScanSettings.SCAN_MODE_BALANCED;

    // Timeout for each controller operation.
    private static final int OPERATION_TIME_OUT_MILLIS = 500;
    private static final int MAX_IS_UID_FOREGROUND_MAP_SIZE = 500;

    @VisibleForTesting final ScanNative mScanNative;
    @VisibleForTesting final ClientHandler mHandler;

    private final Object mCurUsedTrackableAdvertisementsLock = new Object();
    private final Set<ScanClient> mRegularScanClients =
            Collections.newSetFromMap(new ConcurrentHashMap<ScanClient, Boolean>());
    private final Set<ScanClient> mBatchClients =
            Collections.newSetFromMap(new ConcurrentHashMap<ScanClient, Boolean>());
    private final Set<ScanClient> mSuspendedScanClients =
            Collections.newSetFromMap(new ConcurrentHashMap<ScanClient, Boolean>());
    private final SparseIntArray mPriorityMap = new SparseIntArray();
    private final SparseBooleanArray mIsUidForegroundMap = new SparseBooleanArray();
    private final AdapterService mAdapterService;
    private final ScanController mScanController;
    private final TimeProvider mTimeProvider;
    private final BluetoothAdapterProxy mBluetoothAdapterProxy;
    private final DisplayManager mDisplayManager;
    private final ActivityManager mActivityManager;
    private final LocationManager mLocationManager;
    private final BatchScanThrottler mBatchScanThrottler;

    @VisibleForTesting boolean mIsConnecting;
    @VisibleForTesting int mProfilesConnecting;

    private int mLastConfiguredScanSetting1m = Integer.MIN_VALUE;
    private int mLastConfiguredScanSettingCoded = Integer.MIN_VALUE;
    // Scan parameters for batch scan.
    private BatchScanParams mBatchScanParams;

    @GuardedBy("mCurUsedTrackableAdvertisementsLock")
    private int mCurUsedTrackableAdvertisements = 0;

    private boolean mScreenOn = false;
    private int mProfilesConnected, mProfilesDisconnecting;

    @VisibleForTesting
    record UidImportance(int uid, int importance) {}

    ScanManager(
            AdapterService adapterService,
            ScanController scanController,
            BluetoothAdapterProxy bluetoothAdapterProxy,
            Looper looper,
            TimeProvider timeProvider) {
        mScanController = scanController;
        mAdapterService = adapterService;
        mTimeProvider = timeProvider;
        mScanNative = new ScanNative(scanController);
        mDisplayManager = mAdapterService.getSystemService(DisplayManager.class);
        mActivityManager = mAdapterService.getSystemService(ActivityManager.class);
        mLocationManager = mAdapterService.getSystemService(LocationManager.class);
        mBluetoothAdapterProxy = bluetoothAdapterProxy;
        mIsConnecting = false;
        mPriorityMap.put(ScanSettings.SCAN_MODE_OPPORTUNISTIC, 0);
        mPriorityMap.put(ScanSettings.SCAN_MODE_SCREEN_OFF, 1);
        mPriorityMap.put(ScanSettings.SCAN_MODE_LOW_POWER, 2);
        mPriorityMap.put(ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED, 3);
        // BALANCED and AMBIENT_DISCOVERY now have the same settings and priority.
        mPriorityMap.put(ScanSettings.SCAN_MODE_BALANCED, 4);
        mPriorityMap.put(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY, 4);
        mPriorityMap.put(ScanSettings.SCAN_MODE_LOW_LATENCY, 5);
        mHandler = new ClientHandler(looper);
        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
        }
        mScreenOn = isScreenOn();
        AppScanStats.initScanRadioState();
        AppScanStats.setScreenState(mScreenOn, mTimeProvider);
        if (mActivityManager != null) {
            mActivityManager.addOnUidImportanceListener(
                    mUidImportanceListener, FOREGROUND_IMPORTANCE_CUTOFF);
        }
        IntentFilter locationIntentFilter = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
        locationIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mAdapterService.registerReceiver(mLocationReceiver, locationIntentFilter);
        mBatchScanThrottler = new BatchScanThrottler(timeProvider, mScreenOn);
    }

    void cleanup() {
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

        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }

        // Shut down the thread
        mHandler.removeCallbacksAndMessages(null);

        mScanNative.cleanup();

        try {
            mAdapterService.unregisterReceiver(mLocationReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "exception when invoking unregisterReceiver(mLocationReceiver)", e);
        }
    }

    void registerScanner(UUID uuid) {
        mScanNative.registerScanner(uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
    }

    void unregisterScanner(int scannerId) {
        mScanNative.unregisterScanner(scannerId);
    }

    /** Returns the regular scan queue. */
    Set<ScanClient> getRegularScanQueue() {
        return mRegularScanClients;
    }

    /** Returns the suspended scan queue. */
    Set<ScanClient> getSuspendedScanQueue() {
        return mSuspendedScanClients;
    }

    /** Returns batch scan queue. */
    Set<ScanClient> getBatchScanQueue() {
        return mBatchClients;
    }

    /** Returns a set of full batch scan clients. */
    Set<ScanClient> getFullBatchScanQueue() {
        // TODO: split full batch scan clients and truncated batch clients so we don't need to
        // construct this every time.
        return mBatchClients.stream()
                .filter(c -> c.mSettings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL)
                .collect(Collectors.toSet());
    }

    void startScan(ScanClient client) {
        Log.d(TAG, "startScan() " + client);
        sendMessage(MSG_START_BLE_SCAN, client);
    }

    void stopScan(int scannerId) {
        ScanClient client = mScanNative.getBatchScanClient(scannerId);
        if (client == null) {
            client = mScanNative.getRegularScanClient(scannerId);
        }
        if (client == null) {
            client = mScanNative.getSuspendedScanClient(scannerId);
        }
        sendMessage(MSG_STOP_BLE_SCAN, client);
    }

    void flushBatchScanResults(ScanClient client) {
        sendMessage(MSG_FLUSH_BATCH_RESULTS, client);
    }

    void callbackDone(int scannerId, int status) {
        mScanNative.callbackDone(scannerId, status);
    }

    void batchScanResultDelivered() {
        mBatchScanThrottler.resetBackoff();
    }

    private void sendMessage(int what, ScanClient client) {
        mHandler.obtainMessage(what, client).sendToTarget();
    }

    private boolean isFilteringSupported() {
        if (mBluetoothAdapterProxy == null) {
            Log.e(TAG, "mBluetoothAdapterProxy is null");
            return false;
        }
        return mBluetoothAdapterProxy.isOffloadedScanFilteringSupported();
    }

    boolean isAutoBatchScanClientEnabled(ScanClient client) {
        return mScanNative.isAutoBatchScanClientEnabled(client);
    }

    int getCurrentUsedTrackingAdvertisement() {
        synchronized (mCurUsedTrackableAdvertisementsLock) {
            return mCurUsedTrackableAdvertisements;
        }
    }

    void fetchAppForegroundState(ScanClient client) {
        PackageManager packageManager = mAdapterService.getPackageManager();
        if (mActivityManager == null || packageManager == null) {
            return;
        }
        String[] packages = packageManager.getPackagesForUid(client.mAppUid);
        if (packages == null || packages.length == 0) {
            return;
        }
        int importance = IMPORTANCE_CACHED;
        for (String packageName : packages) {
            importance = Math.min(importance, mActivityManager.getPackageImportance(packageName));
        }
        boolean isForeground = importance <= IMPORTANCE_FOREGROUND_SERVICE;
        mIsUidForegroundMap.put(client.mAppUid, isForeground);
        if (client.mStats != null) {
            client.mStats.setAppImportance(importance);
        }
    }

    // Handler class that handles BLE scan operations.
    @VisibleForTesting
    class ClientHandler extends Handler {

        ClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_BLE_SCAN:
                    handleStartScan((ScanClient) msg.obj);
                    break;
                case MSG_STOP_BLE_SCAN:
                    handleStopScan((ScanClient) msg.obj);
                    break;
                case MSG_FLUSH_BATCH_RESULTS:
                    handleFlushBatchResults((ScanClient) msg.obj);
                    break;
                case MSG_SCAN_TIMEOUT:
                    mScanNative.regularScanTimeout((ScanClient) msg.obj);
                    break;
                case MSG_SUSPEND_SCANS:
                    handleSuspendScans();
                    break;
                case MSG_RESUME_SCANS:
                    handleResumeScans();
                    break;
                case MSG_SCREEN_OFF:
                    handleScreenOff();
                    break;
                case MSG_SCREEN_ON:
                    handleScreenOn();
                    break;
                case MSG_REVERT_SCAN_MODE_UPGRADE:
                    handleRevertScanModeUpgrade((ScanClient) msg.obj);
                    break;
                case MSG_IMPORTANCE_CHANGE:
                    handleImportanceChange((UidImportance) msg.obj);
                    break;
                case MSG_START_CONNECTING:
                    handleConnectingState();
                    break;
                case MSG_STOP_CONNECTING:
                    handleClearConnectingState();
                    break;
                default:
                    // Shouldn't happen.
                    Log.e(TAG, "received an unknown message : " + msg.what);
            }
        }

        private void handleStartScan(ScanClient client) {
            Log.d(TAG, "handling starting scan");
            fetchAppForegroundState(client);

            if (!isScanSupported(client)) {
                Log.e(TAG, "Scan settings not supported");
                return;
            }

            if (mRegularScanClients.contains(client) || mBatchClients.contains(client)) {
                Log.e(TAG, "Scan already started for scanner id: " + client.mScannerId);
                return;
            }

            if (requiresScreenOn(client) && !mScreenOn) {
                Log.w(
                        TAG,
                        "Cannot start unfiltered scan in screen-off. This scan will be resumed "
                                + "later: "
                                + client.mScannerId);
                mSuspendedScanClients.add(client);
                if (client.mStats != null) {
                    client.mStats.recordScanSuspend(client.mScannerId);
                }
                return;
            }

            final boolean locationEnabled = mLocationManager.isLocationEnabled();
            if (requiresLocationOn(client) && !locationEnabled) {
                Log.i(
                        TAG,
                        "Cannot start unfiltered scan in location-off. This scan will be"
                                + " resumed when location is on: "
                                + client.mScannerId);
                mSuspendedScanClients.add(client);
                if (client.mStats != null) {
                    client.mStats.recordScanSuspend(client.mScannerId);
                }
                return;
            }

            if (!mScanNative.isExemptFromAutoBatchScanUpdate(client)) {
                if (mScreenOn) {
                    clearAutoBatchScanClient(client);
                } else {
                    setAutoBatchScanClient(client);
                }
            }

            // Begin scan operations.
            if (isBatchClient(client) || isAutoBatchScanClientEnabled(client)) {
                mBatchClients.add(client);
                mScanNative.startBatchScan(client);
            } else {
                updateScanModeBeforeStart(client);
                updateScanModeConcurrency(client);
                mRegularScanClients.add(client);
                mScanNative.startRegularScan(client);
                if (!mScanNative.isOpportunisticScanClient(client)) {
                    mScanNative.configureRegularScanParams();

                    if (!mScanNative.isExemptFromScanTimeout(client)) {
                        Message msg = obtainMessage(MSG_SCAN_TIMEOUT);
                        msg.obj = client;
                        // Only one timeout message should exist at any time
                        removeMessages(MSG_SCAN_TIMEOUT, client);
                        sendMessageDelayed(msg, mAdapterService.getScanTimeoutMillis());
                        Log.d(
                                TAG,
                                "apply scan timeout ("
                                        + mAdapterService.getScanTimeoutMillis()
                                        + ")"
                                        + "to scannerId "
                                        + client.mScannerId);
                    }
                }
            }
            client.mStarted = true;
        }

        private boolean requiresScreenOn(ScanClient client) {
            boolean isFiltered = isFilteredScan(client);
            return !mScanNative.isOpportunisticScanClient(client) && !isFiltered;
        }

        private static boolean requiresLocationOn(ScanClient client) {
            boolean isFiltered = isFilteredScan(client);
            return !client.mHasDisavowedLocation && !isFiltered;
        }

        private static boolean isFilteredScan(ScanClient client) {
            if ((client.mFilters == null) || client.mFilters.isEmpty()) {
                return false;
            }

            boolean atLeastOneValidFilter = false;
            for (ScanFilter filter : client.mFilters) {
                // A valid filter need at least one field not empty
                if (!filter.isAllFieldsEmpty()) {
                    atLeastOneValidFilter = true;
                    break;
                }
            }
            return atLeastOneValidFilter;
        }

        private void handleStopScan(ScanClient client) {
            if (client == null) {
                return;
            }
            Log.d(TAG, "handling stopping scan " + client);

            if (mSuspendedScanClients.contains(client)) {
                mSuspendedScanClients.remove(client);
            }
            removeMessages(MSG_REVERT_SCAN_MODE_UPGRADE, client);
            removeMessages(MSG_SCAN_TIMEOUT, client);
            if (mRegularScanClients.contains(client)) {
                mScanNative.stopRegularScan(client);

                if (!mScanNative.isOpportunisticScanClient(client)) {
                    mScanNative.configureRegularScanParams();
                }
            } else {
                if (isAutoBatchScanClientEnabled(client)) {
                    handleFlushBatchResults(client);
                }
                mScanNative.stopBatchScan(client);
            }
            if (client.mAppDied) {
                Log.d(TAG, "app died, unregister scanner - " + client.mScannerId);
                mScanController.unregisterScannerInternal(client.mScannerId);
            }
        }

        private void handleFlushBatchResults(ScanClient client) {
            Log.d(TAG, "handleFlushBatchResults() " + client);
            if (!mBatchClients.contains(client)) {
                Log.d(TAG, "There is no batch scan client to flush " + client);
                return;
            }
            mScanNative.flushBatchResults(client.mScannerId);
        }

        private static boolean isBatchClient(ScanClient client) {
            if (client == null || client.mSettings == null) {
                return false;
            }
            ScanSettings settings = client.mSettings;
            return settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    && settings.getReportDelayMillis() != 0;
        }

        private boolean isScanSupported(ScanClient client) {
            if (client == null || client.mSettings == null) {
                return true;
            }
            ScanSettings settings = client.mSettings;
            if (isFilteringSupported()) {
                return true;
            }
            return settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    && settings.getReportDelayMillis() == 0;
        }

        private void handleScreenOff() {
            AppScanStats.setScreenState(false, mTimeProvider);
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

        private void handleConnectingState() {
            if (mAdapterService.getScanDowngradeDurationMillis() == 0) {
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
                mScanNative.configureRegularScanParams();
            }
            removeMessages(MSG_STOP_CONNECTING);
            Message msg = obtainMessage(MSG_STOP_CONNECTING);
            sendMessageDelayed(msg, mAdapterService.getScanDowngradeDurationMillis());
        }

        private void handleClearConnectingState() {
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
                mScanNative.configureRegularScanParams();
            }
            removeMessages(MSG_STOP_CONNECTING);
            mIsConnecting = false;
        }

        private void handleSuspendScans() {
            for (ScanClient client : mRegularScanClients) {
                if ((requiresScreenOn(client) && !mScreenOn)
                        || (requiresLocationOn(client) && !mLocationManager.isLocationEnabled())) {
                    /*Suspend unfiltered scans*/
                    if (client.mStats != null) {
                        client.mStats.recordScanSuspend(client.mScannerId);
                    }
                    Log.d(TAG, "suspend scan " + client);
                    handleStopScan(client);
                    mSuspendedScanClients.add(client);
                }
            }
        }

        private void updateRegularScanToBatchScanClients() {
            boolean updatedScanParams = false;
            for (ScanClient client : mRegularScanClients) {
                if (!mScanNative.isExemptFromAutoBatchScanUpdate(client)) {
                    Log.d(TAG, "Updating regular scan to batch scan" + client);
                    handleStopScan(client);
                    setAutoBatchScanClient(client);
                    handleStartScan(client);
                    updatedScanParams = true;
                }
            }
            if (updatedScanParams) {
                mScanNative.configureRegularScanParams();
            }
        }

        private void updateBatchScanToRegularScanClients() {
            boolean updatedScanParams = false;
            for (ScanClient client : mBatchClients) {
                if (!mScanNative.isExemptFromAutoBatchScanUpdate(client)) {
                    Log.d(TAG, "Updating batch scan to regular scan" + client);
                    handleStopScan(client);
                    clearAutoBatchScanClient(client);
                    handleStartScan(client);
                    updatedScanParams = true;
                }
            }
            if (updatedScanParams) {
                mScanNative.configureRegularScanParams();
            }
        }

        private void setAutoBatchScanClient(ScanClient client) {
            if (isAutoBatchScanClientEnabled(client)) {
                return;
            }
            client.updateScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF);
            Log.d(
                    TAG,
                    "Scan mode update during setAutoBatchScanClient() to "
                            + getScanModeString(ScanSettings.SCAN_MODE_SCREEN_OFF));
            if (client.mStats != null) {
                client.mStats.setAutoBatchScan(client.mScannerId, true);
            }
        }

        private void clearAutoBatchScanClient(ScanClient client) {
            if (!isAutoBatchScanClientEnabled(client)) {
                return;
            }
            client.updateScanMode(client.mScanModeApp);
            Log.d(
                    TAG,
                    "Scan mode update during clearAutoBatchScanClient() to "
                            + getScanModeString(client.mScanModeApp));
            if (client.mStats != null) {
                client.mStats.setAutoBatchScan(client.mScannerId, false);
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
                mScanNative.configureRegularScanParams();
            }
        }

        private boolean updateScanModeScreenOff(ScanClient client) {
            if (mScanNative.isOpportunisticScanClient(client)) {
                return false;
            }
            int updatedScanMode = client.mScanModeApp;
            if (!isAppForeground(client) || mScanNative.isForceDowngradedScanClient(client)) {
                updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF;
            } else {
                // The following codes are effectively only for services
                // Apps are either already or will be soon handled by handleImportanceChange().
                switch (client.mScanModeApp) {
                    case ScanSettings.SCAN_MODE_LOW_POWER:
                        updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF;
                        break;
                    case ScanSettings.SCAN_MODE_BALANCED:
                    case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                        updatedScanMode = ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED;
                        break;
                    case ScanSettings.SCAN_MODE_LOW_LATENCY:
                        updatedScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
                        break;
                    case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                    default:
                        return false;
                }
            }
            Log.d(
                    TAG,
                    "Scan mode update during screen off from "
                            + getScanModeString(client.mScanModeApp)
                            + " to "
                            + getScanModeString(updatedScanMode));
            return client.updateScanMode(updatedScanMode);
        }

        /**
         * Services and Apps are assumed to be in the foreground by default unless it changes to the
         * background triggering onUidImportance().
         */
        private boolean isAppForeground(ScanClient client) {
            return mIsUidForegroundMap.get(client.mAppUid, DEFAULT_UID_IS_FOREGROUND);
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
            if (client.mStarted || mAdapterService.getScanUpgradeDurationMillis() == 0) {
                return false;
            }
            if (client.mStats == null || client.mStats.hasRecentScan()) {
                return false;
            }
            if (!isAppForeground(client) || isBatchClient(client)) {
                return false;
            }

            if (upgradeScanModeByOneLevel(client)) {
                Message msg = obtainMessage(MSG_REVERT_SCAN_MODE_UPGRADE);
                msg.obj = client;
                Log.d(
                        TAG,
                        "scanMode is upgraded to "
                                + getScanModeString(client.mSettings.getScanMode())
                                + " for "
                                + client);
                sendMessageDelayed(msg, mAdapterService.getScanUpgradeDurationMillis());
                return true;
            }
            return false;
        }

        private static boolean upgradeScanModeByOneLevel(ScanClient client) {
            switch (client.mScanModeApp) {
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    return client.updateScanMode(ScanSettings.SCAN_MODE_BALANCED);
                case ScanSettings.SCAN_MODE_BALANCED:
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                    return client.updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
                case ScanSettings.SCAN_MODE_OPPORTUNISTIC:
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                default:
                    return false;
            }
        }

        private void handleRevertScanModeUpgrade(ScanClient client) {
            if (mPriorityMap.get(client.mSettings.getScanMode())
                    <= mPriorityMap.get(client.mScanModeApp)) {
                return;
            }
            if (client.updateScanMode(client.mScanModeApp)) {
                Log.d(
                        TAG,
                        "scanMode upgrade is reverted to "
                                + getScanModeString(client.mScanModeApp)
                                + " for "
                                + client);
                mScanNative.configureRegularScanParams();
            }
        }

        private void handleImportanceChange(UidImportance imp) {
            if (imp == null) {
                return;
            }
            int uid = imp.uid;
            int importance = imp.importance;
            boolean updatedScanParams = false;
            boolean isForeground = importance <= IMPORTANCE_FOREGROUND_SERVICE;

            if (mIsUidForegroundMap.size() < MAX_IS_UID_FOREGROUND_MAP_SIZE) {
                mIsUidForegroundMap.put(uid, isForeground);
            }

            for (ScanClient client : mRegularScanClients) {
                if (client.mAppUid != uid || mScanNative.isOpportunisticScanClient(client)) {
                    continue;
                }
                if (client.mStats != null) {
                    client.mStats.setAppImportance(importance);
                }
                if (isForeground) {
                    int scanMode = client.mScanModeApp;
                    int maxScanMode =
                            mScanNative.isForceDowngradedScanClient(client)
                                    ? SCAN_MODE_FORCE_DOWNGRADED
                                    : scanMode;
                    if (client.updateScanMode(getMinScanMode(scanMode, maxScanMode))) {
                        updatedScanParams = true;
                    }
                } else {
                    int scanMode = client.mSettings.getScanMode();
                    int maxScanMode =
                            mScreenOn
                                    ? SCAN_MODE_APP_IN_BACKGROUND
                                    : ScanSettings.SCAN_MODE_SCREEN_OFF;
                    if (client.updateScanMode(getMinScanMode(scanMode, maxScanMode))) {
                        updatedScanParams = true;
                    }
                }
                Log.d(
                        TAG,
                        ("uid " + uid)
                                + (" isForeground " + isForeground)
                                + (" scanMode "
                                        + getScanModeString(client.mSettings.getScanMode())));
            }

            if (updatedScanParams) {
                mScanNative.configureRegularScanParams();
            }
        }

        private boolean updateScanModeScreenOn(ScanClient client) {
            if (mScanNative.isOpportunisticScanClient(client)) {
                return false;
            }
            int scanMode =
                    isAppForeground(client) ? client.mScanModeApp : SCAN_MODE_APP_IN_BACKGROUND;
            int maxScanMode =
                    mScanNative.isForceDowngradedScanClient(client)
                            ? SCAN_MODE_FORCE_DOWNGRADED
                            : scanMode;
            Log.d(
                    TAG,
                    "Scan mode update during screen on from "
                            + getScanModeString(client.mScanModeApp)
                            + " to "
                            + getScanModeString(getMinScanMode(scanMode, maxScanMode)));
            return client.updateScanMode(getMinScanMode(scanMode, maxScanMode));
        }

        private boolean downgradeScanModeFromMaxDuty(ScanClient client) {
            if ((client.mStats == null) || mAdapterService.getScanDowngradeDurationMillis() == 0) {
                return false;
            }
            int updatedScanMode =
                    getMinScanMode(client.mSettings.getScanMode(), SCAN_MODE_MAX_IN_CONCURRENCY);
            if (client.updateScanMode(updatedScanMode)) {
                client.mStats.setScanDowngrade(client.mScannerId, true);
                Log.d(
                        TAG,
                        "downgradeScanModeFromMaxDuty() to "
                                + getScanModeString(updatedScanMode)
                                + " for "
                                + client);
                return true;
            }
            return false;
        }

        private boolean revertDowngradeScanModeFromMaxDuty(ScanClient client) {
            if (!mScanNative.isDowngradedScanClient(client)) {
                return false;
            }
            if (client.mStats != null) {
                client.mStats.setScanDowngrade(client.mScannerId, false);
            }
            Log.d(TAG, "revertDowngradeScanModeFromMaxDuty() for " + client);
            if (mScreenOn) {
                return updateScanModeScreenOn(client);
            } else {
                return updateScanModeScreenOff(client);
            }
        }

        private void handleScreenOn() {
            AppScanStats.setScreenState(true, mTimeProvider);
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

        private void handleResumeScans() {
            Iterator<ScanClient> iterator = mSuspendedScanClients.iterator();
            while (iterator.hasNext()) {
                ScanClient client = iterator.next();
                if ((!requiresScreenOn(client) || mScreenOn)
                        && (!requiresLocationOn(client) || mLocationManager.isLocationEnabled())) {
                    if (client.mStats != null) {
                        client.mStats.recordScanResume(client.mScannerId);
                    }
                    Log.d(TAG, "resume scan " + client);
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
                mScanNative.configureRegularScanParams();
            }
        }

        private void handleProfileConnectionStateChanged(int profile, int fromState, int toState) {
            boolean updatedConnectingState =
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
    }

    /** Parameters for batch scans. */
    static class BatchScanParams {
        @VisibleForTesting int mScanMode;
        private int mFullScanScannerId;
        private int mTruncatedScanScannerId;

        BatchScanParams() {
            mScanMode = -1;
            mFullScanScannerId = -1;
            mTruncatedScanScannerId = -1;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BatchScanParams other)) {
                return false;
            }
            return mScanMode == other.mScanMode
                    && mFullScanScannerId == other.mFullScanScannerId
                    && mTruncatedScanScannerId == other.mTruncatedScanScannerId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mScanMode, mFullScanScannerId, mTruncatedScanScannerId);
        }
    }

    @VisibleForTesting
    class ScanNative {

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
        private static final String MSFT_HCI_EXT_ENABLED = "bluetooth.core.le.use_msft_hci_ext";
        // Hardcoded min number of hardware adv monitor slots for MSFT-enabled controllers
        private static final int MIN_NUM_MSFT_MONITOR_SLOTS = 20;

        // Filter indices that are available to user. It's sad we need to maintain filter index.
        private final Deque<Integer> mFilterIndexStack;
        // Map of scannerId and Filter indices used by client.
        private final Map<Integer, Deque<Integer>> mClientFilterIndexMap;
        // Keep track of the clients that uses ALL_PASS filters.
        private final Set<Integer> mAllPassRegularClients = new HashSet<>();
        private final Set<Integer> mAllPassBatchClients = new HashSet<>();

        private final AtomicReference<BroadcastReceiver> mBatchAlarmReceiver =
                new AtomicReference<>();

        private final AlarmManager mAlarmManager;
        private final PendingIntent mBatchScanIntervalIntent;
        private final ScanNativeInterface mNativeInterface;

        // Whether or not MSFT-based scanning hardware offload is available on this device
        private final boolean mIsMsftSupported;
        // Whether or not MSFT-based scanning is currently enabled in the controller
        private boolean scanEnabledMsft = false;
        // List of merged MSFT patterns
        private final MsftAdvMonitorMergedPatternList mMsftAdvMonitorMergedPatternList =
                new MsftAdvMonitorMergedPatternList();

        private ScanNative(ScanController scanController) {
            mNativeInterface = ScanObjectsFactory.getInstance().getScanNativeInterface();
            mNativeInterface.init(scanController);
            mFilterIndexStack = new ArrayDeque<Integer>();
            mClientFilterIndexMap = new HashMap<Integer, Deque<Integer>>();

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
                            Log.d(TAG, "awakened up at time " + mTimeProvider.elapsedRealtime());
                            String action = intent.getAction();

                            if (action.equals(ACTION_REFRESH_BATCHED_SCAN)) {
                                if (mBatchClients.isEmpty()) {
                                    return;
                                }
                                // Note this actually flushes all pending batch data.
                                if (mBatchClients.iterator().hasNext()) {
                                    flushBatchScanResults(mBatchClients.iterator().next());
                                }
                            }
                        }
                    });
            mAdapterService.registerReceiver(mBatchAlarmReceiver.get(), filter);

            mIsMsftSupported =
                    Flags.leScanMsftSupport()
                            && SystemProperties.getBoolean(MSFT_HCI_EXT_ENABLED, false)
                            && mNativeInterface.gattClientIsMsftSupported();
        }

        private void callbackDone(int scannerId, int status) {
            Log.d(TAG, "callback done for scannerId - " + scannerId + " status - " + status);
            if (status == 0) {
                mNativeInterface.callbackDone();
            }
            // TODO: add a callback for scan failure.
        }

        private void resetCountDownLatch() {
            mNativeInterface.resetCountDownLatch();
        }

        private boolean waitForCallback() {
            return mNativeInterface.waitForCallback(OPERATION_TIME_OUT_MILLIS);
        }

        void configureRegularScanParams() {
            Log.d(TAG, "configureRegularScanParams() - queue=" + mRegularScanClients.size());
            int newScanSetting1m = Integer.MIN_VALUE;
            int newScanSettingCoded = Integer.MIN_VALUE;
            ScanClient client1m = getAggressiveClient(mRegularScanClients, true, false);
            ScanClient clientCoded = getAggressiveClient(mRegularScanClients, false, false);
            if (client1m != null) {
                newScanSetting1m = client1m.mSettings.getScanMode();
            }
            if (clientCoded != null) {
                newScanSettingCoded = clientCoded.mSettings.getScanMode();
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
                mNativeInterface.gattClientScan(false);
                if (!AppScanStats.recordScanRadioStop(mTimeProvider)) {
                    Log.w(TAG, "There is no scan radio to stop");
                }
                Log.d(
                        TAG,
                        "Start gattClientScanNative with"
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
                        client1m == null ? 0 : client1m.mScannerId,
                        scanInterval1m,
                        scanWindow1m,
                        clientCoded == null ? 0 : clientCoded.mScannerId,
                        scanIntervalCoded,
                        scanWindowCoded,
                        scanPhyMask);
                mNativeInterface.gattClientScan(true);
                recordScanRadioStart(client1m, clientCoded, newScanSetting1m, newScanSettingCoded);
            } else {
                Log.d(TAG, "configureRegularScanParams() - queue empty, scan stopped");
            }
            mLastConfiguredScanSetting1m = newScanSetting1m;
            mLastConfiguredScanSettingCoded = newScanSettingCoded;
        }

        private ScanClient getAggressiveClient(
                Set<ScanClient> cList, boolean use1mPhy, boolean isBatch) {
            ScanClient result = null;
            int currentScanModePriority = Integer.MIN_VALUE;
            for (ScanClient client : cList) {
                // Batch is only done on the 1M PHY and the client PHY setting is ignored
                if (!isBatch && !isPhyConfigured(client, use1mPhy)) {
                    continue;
                }
                int priority = mPriorityMap.get(client.mSettings.getScanMode());
                if (priority > currentScanModePriority) {
                    result = client;
                    currentScanModePriority = priority;
                }
            }
            return result;
        }

        private static boolean isPhyConfigured(ScanClient client, boolean use1mPhy) {
            if (!Flags.phyToNative()) {
                // When the flag is off the PHY setting is ignored and all clients scan on 1m
                return use1mPhy;
            }
            if (client.mSettings.getPhy() == ScanSettings.PHY_LE_ALL_SUPPORTED) {
                return true;
            }
            return use1mPhy
                    ? client.mSettings.getPhy() == BluetoothDevice.PHY_LE_1M
                    : client.mSettings.getPhy() == BluetoothDevice.PHY_LE_CODED;
        }

        private static boolean shouldUpdateScan(int newScanSetting, int oldScanSetting) {
            return newScanSetting != Integer.MIN_VALUE
                    && newScanSetting != ScanSettings.SCAN_MODE_OPPORTUNISTIC
                    && newScanSetting != oldScanSetting;
        }

        private int getScanWindow(@Nullable ScanClient client) {
            return client == null ? 0 : Utils.millsToUnit(getScanWindowMillis(client.mSettings));
        }

        private int getScanInterval(@Nullable ScanClient client) {
            // convert scanWindow and scanInterval from ms to LE scan units(0.625ms)
            return client == null ? 0 : Utils.millsToUnit(getScanIntervalMillis(client.mSettings));
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
                        mPriorityMap.get(setting1m) >= mPriorityMap.get(settingCoded)
                                ? client1m
                                : clientCoded;
            }
            if (chosenClient != null
                    && chosenClient.mStats != null
                    && !AppScanStats.recordScanRadioStart(
                            chosenClient.mScanModeApp,
                            chosenClient.mScannerId,
                            chosenClient.mStats,
                            getScanWindowMillis(chosenClient.mSettings),
                            getScanIntervalMillis(chosenClient.mSettings),
                            mTimeProvider)) {
                Log.w(TAG, "Scan radio already started");
            }
        }

        void startRegularScan(ScanClient client) {
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
                    && client.mSettings != null
                    && client.mSettings.getScanMode() != ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
                Log.d(TAG, "start gattClientScanNative from startRegularScan()");
                mNativeInterface.gattClientScan(true);
                if (!Flags.bleScanAdvMetricsRedesign()) {
                    if (client.mStats != null
                            && !AppScanStats.recordScanRadioStart(
                                    client.mSettings.getScanMode(),
                                    client.mScannerId,
                                    client.mStats,
                                    getScanWindowMillis(client.mSettings),
                                    getScanIntervalMillis(client.mSettings),
                                    mTimeProvider)) {
                        Log.w(TAG, "Scan radio already started");
                    }
                }
            }
        }

        private int numRegularScanClients() {
            int num = 0;
            for (ScanClient client : mRegularScanClients) {
                if (client.mSettings.getScanMode() != ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
                    num++;
                }
            }
            return num;
        }

        void startBatchScan(ScanClient client) {
            if (mFilterIndexStack.isEmpty() && isFilteringSupported()) {
                initFilterIndexStack();
            }
            configureScanFilters(client);
            if (!isOpportunisticScanClient(client)) {
                // Reset batch scan. May need to stop the existing batch scan and update scan
                // params.
                resetBatchScan(client);
            }
        }

        private static boolean isExemptFromScanTimeout(ScanClient client) {
            return isOpportunisticScanClient(client) || isFirstMatchScanClient(client);
        }

        private static boolean isExemptFromAutoBatchScanUpdate(ScanClient client) {
            return isOpportunisticScanClient(client) || !isAllMatchesAutoBatchScanClient(client);
        }

        private static boolean isAutoBatchScanClientEnabled(ScanClient client) {
            return client.mStats != null && client.mStats.isAutoBatchScan(client.mScannerId);
        }

        private static boolean isAllMatchesAutoBatchScanClient(ScanClient client) {
            return client.mSettings.getCallbackType()
                    == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH;
        }

        private static boolean isOpportunisticScanClient(ScanClient client) {
            return client.mSettings.getScanMode() == ScanSettings.SCAN_MODE_OPPORTUNISTIC;
        }

        private static boolean isTimeoutScanClient(ScanClient client) {
            return (client.mStats != null) && client.mStats.isScanTimeout(client.mScannerId);
        }

        private static boolean isDowngradedScanClient(ScanClient client) {
            return (client.mStats != null) && client.mStats.isScanDowngraded(client.mScannerId);
        }

        private static boolean isForceDowngradedScanClient(ScanClient client) {
            return isTimeoutScanClient(client) || isDowngradedScanClient(client);
        }

        private static boolean isFirstMatchScanClient(ScanClient client) {
            return (client.mSettings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                    != 0;
        }

        private void resetBatchScan(ScanClient client) {
            int scannerId = client.mScannerId;
            BatchScanParams batchScanParams = getBatchScanParams();
            // Stop batch if batch scan params changed and previous params is not null.
            if (mBatchScanParams != null && (!mBatchScanParams.equals(batchScanParams))) {
                Log.d(TAG, "stopping BLe Batch");
                resetCountDownLatch();
                mNativeInterface.gattClientStopBatchScan(scannerId);
                waitForCallback();
                // Clear pending results as it's illegal to config storage if there are still
                // pending results.
                flushBatchResults(scannerId);
            }
            // Start batch if batchScanParams changed and current params is not null.
            if (batchScanParams != null && (!batchScanParams.equals(mBatchScanParams))) {
                int notifyThreshold = 95;
                Log.d(TAG, "Starting BLE batch scan");
                int resultType = getResultType(batchScanParams);
                int fullScanPercent = getFullScanStoragePercent(resultType);
                resetCountDownLatch();
                Log.d(TAG, "configuring batch scan storage, appIf " + client.mScannerId);
                mNativeInterface.gattClientConfigBatchScanStorage(
                        client.mScannerId, fullScanPercent, 100 - fullScanPercent, notifyThreshold);
                waitForCallback();
                resetCountDownLatch();
                int scanInterval =
                        Utils.millsToUnit(getBatchScanIntervalMillis(batchScanParams.mScanMode));
                int scanWindow =
                        Utils.millsToUnit(getBatchScanWindowMillis(batchScanParams.mScanMode));
                mNativeInterface.gattClientStartBatchScan(
                        scannerId,
                        resultType,
                        scanInterval,
                        scanWindow,
                        0,
                        DISCARD_OLDEST_WHEN_BUFFER_FULL);
                waitForCallback();
            }
            mBatchScanParams = batchScanParams;
            setBatchAlarm();
        }

        private static int getFullScanStoragePercent(int resultType) {
            switch (resultType) {
                case SCAN_RESULT_TYPE_FULL:
                    return 100;
                case SCAN_RESULT_TYPE_TRUNCATED:
                    return 0;
                case SCAN_RESULT_TYPE_BOTH:
                    return 50;
                default:
                    return 50;
            }
        }

        private BatchScanParams getBatchScanParams() {
            if (mBatchClients.isEmpty()) {
                return null;
            }
            BatchScanParams params = new BatchScanParams();
            ScanClient winner = getAggressiveClient(mBatchClients, true, true);
            if (winner != null) {
                params.mScanMode = winner.mSettings.getScanMode();
            }
            // TODO: split full batch scan results and truncated batch scan results to different
            // collections.
            for (ScanClient client : mBatchClients) {
                if (client.mSettings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL) {
                    params.mFullScanScannerId = client.mScannerId;
                } else {
                    params.mTruncatedScanScannerId = client.mScannerId;
                }
            }
            return params;
        }

        // Batched scan doesn't require high duty cycle scan because scan result is reported
        // infrequently anyway. To avoid redefining parameter sets, map to the low duty cycle
        // parameter set as follows.
        private int getBatchScanWindowMillis(int scanMode) {
            ContentResolver resolver = mAdapterService.getContentResolver();
            switch (scanMode) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                            SCAN_MODE_BALANCED_WINDOW_MS);
                case ScanSettings.SCAN_MODE_SCREEN_OFF:
                    return mAdapterService.getScreenOffLowPowerWindowMillis();
                default:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                            SCAN_MODE_LOW_POWER_WINDOW_MS);
            }
        }

        private int getBatchScanIntervalMillis(int scanMode) {
            ContentResolver resolver = mAdapterService.getContentResolver();
            switch (scanMode) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                            SCAN_MODE_BALANCED_INTERVAL_MS);
                case ScanSettings.SCAN_MODE_SCREEN_OFF:
                    return mAdapterService.getScreenOffLowPowerIntervalMillis();
                default:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                            SCAN_MODE_LOW_POWER_INTERVAL_MS);
            }
        }

        // Set the batch alarm to be triggered within a short window after batch interval. This
        // allows system to optimize wake up time while still allows a degree of precise control.
        private void setBatchAlarm() {
            // Cancel any pending alarm just in case.
            mAlarmManager.cancel(mBatchScanIntervalIntent);
            if (mBatchClients.isEmpty()) {
                return;
            }
            long batchTriggerIntervalMillis =
                    Flags.batchScanOptimization()
                            ? mBatchScanThrottler.getBatchTriggerIntervalMillis(mBatchClients)
                            : getBatchTriggerIntervalMillis();
            // Allows the alarm to be triggered within
            // [batchTriggerIntervalMillis, 1.1 * batchTriggerIntervalMillis]
            long windowLengthMillis = batchTriggerIntervalMillis / 10;
            long windowStartMillis = mTimeProvider.elapsedRealtime() + batchTriggerIntervalMillis;
            mAlarmManager.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    windowStartMillis,
                    windowLengthMillis,
                    mBatchScanIntervalIntent);
        }

        void stopRegularScan(ScanClient client) {
            // Remove scan filters and recycle filter indices.
            if (client == null) {
                return;
            }
            int deliveryMode = getDeliveryMode(client);
            if (deliveryMode == DELIVERY_MODE_ON_FOUND_LOST) {
                // Decrement the count of trackable advertisements in use
                int entriesToFreePerFilter = getNumOfTrackingAdvertisements(client.mSettings);
                for (int i = 0; i < client.mFilters.size(); i++) {
                    if (!manageAllocationOfTrackingAdvertisement(entriesToFreePerFilter, false)) {
                        Log.e(
                                TAG,
                                "Error freeing for onfound/onlost filter resources "
                                        + entriesToFreePerFilter);
                        try {
                            mScanController.onScanManagerErrorCallback(
                                    client.mScannerId, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                        } catch (RemoteException e) {
                            Log.e(TAG, "failed on onScanManagerCallback at freeing", e);
                        }
                    }
                }
            }
            mRegularScanClients.remove(client);
            if (numRegularScanClients() == 0) {
                Log.d(TAG, "stop gattClientScanNative");
                mNativeInterface.gattClientScan(false);
                if (!AppScanStats.recordScanRadioStop(mTimeProvider)) {
                    Log.w(TAG, "There is no scan radio to stop");
                }
            }

            if (!mIsMsftSupported) {
                removeScanFilters(client.mScannerId);
            } else {
                removeFiltersMsft(client);
            }
        }

        void regularScanTimeout(ScanClient client) {
            if (!isExemptFromScanTimeout(client)
                    && (client.mStats == null || client.mStats.isScanningTooLong())) {
                Log.d(TAG, "regularScanTimeout - client scan time was too long");
                if (client.mFilters == null || client.mFilters.isEmpty()) {
                    Log.w(
                            TAG,
                            "Moving unfiltered scan client to opportunistic scan (scannerId "
                                    + client.mScannerId
                                    + ")");
                    setOpportunisticScanClient(client);
                    removeScanFilters(client.mScannerId);

                } else {
                    Log.w(
                            TAG,
                            "Moving filtered scan client to downgraded scan (scannerId "
                                    + client.mScannerId
                                    + ")");
                    int scanMode = client.mSettings.getScanMode();
                    int maxScanMode = SCAN_MODE_FORCE_DOWNGRADED;
                    client.updateScanMode(getMinScanMode(scanMode, maxScanMode));
                }
                if (client.mStats != null) {
                    client.mStats.setScanTimeout(client.mScannerId);
                    client.mStats.recordScanTimeoutCountMetrics(
                            client.mScannerId, mAdapterService.getScanTimeoutMillis());
                }
            }

            // The scan should continue for background scans
            configureRegularScanParams();
            if (numRegularScanClients() == 0) {
                Log.d(TAG, "stop gattClientScanNative");
                mNativeInterface.gattClientScan(false);
                if (!AppScanStats.recordScanRadioStop(mTimeProvider)) {
                    Log.w(TAG, "There is no scan radio to stop");
                }
            }
        }

        void setOpportunisticScanClient(ScanClient client) {
            // TODO: Add constructor to ScanSettings.Builder
            // that can copy values from an existing ScanSettings object
            ScanSettings.Builder builder = new ScanSettings.Builder();
            ScanSettings settings = client.mSettings;
            builder.setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC);
            builder.setCallbackType(settings.getCallbackType());
            builder.setScanResultType(settings.getScanResultType());
            builder.setReportDelay(settings.getReportDelayMillis());
            builder.setNumOfMatches(settings.getNumOfMatches());
            client.mSettings = builder.build();
        }

        // Find the regular scan client information.
        ScanClient getRegularScanClient(int scannerId) {
            for (ScanClient client : mRegularScanClients) {
                if (client.mScannerId == scannerId) {
                    return client;
                }
            }
            return null;
        }

        ScanClient getSuspendedScanClient(int scannerId) {
            for (ScanClient client : mSuspendedScanClients) {
                if (client.mScannerId == scannerId) {
                    return client;
                }
            }
            return null;
        }

        void stopBatchScan(ScanClient client) {
            mBatchClients.remove(client);
            removeScanFilters(client.mScannerId);
            if (!isOpportunisticScanClient(client)) {
                resetBatchScan(client);
            }
        }

        void flushBatchResults(int scannerId) {
            Log.d(TAG, "flushPendingBatchResults - scannerId = " + scannerId);
            if (mBatchScanParams.mFullScanScannerId != -1) {
                resetCountDownLatch();
                mNativeInterface.gattClientReadScanReports(
                        mBatchScanParams.mFullScanScannerId, SCAN_RESULT_TYPE_FULL);
                waitForCallback();
            }
            if (mBatchScanParams.mTruncatedScanScannerId != -1) {
                resetCountDownLatch();
                mNativeInterface.gattClientReadScanReports(
                        mBatchScanParams.mTruncatedScanScannerId, SCAN_RESULT_TYPE_TRUNCATED);
                waitForCallback();
            }
            setBatchAlarm();
        }

        void cleanup() {
            mAlarmManager.cancel(mBatchScanIntervalIntent);
            // Protect against multiple calls of cleanup.
            BroadcastReceiver receiver = mBatchAlarmReceiver.getAndSet(null);
            if (receiver != null) {
                mAdapterService.unregisterReceiver(receiver);
            }
            mNativeInterface.cleanup();
        }

        private long getBatchTriggerIntervalMillis() {
            long intervalMillis = Long.MAX_VALUE;
            for (ScanClient client : mBatchClients) {
                if (client.mSettings != null && client.mSettings.getReportDelayMillis() > 0) {
                    intervalMillis =
                            Math.min(intervalMillis, client.mSettings.getReportDelayMillis());
                }
            }
            return intervalMillis;
        }

        // Add scan filters. The logic is:
        // If no offload filter can/needs to be set, set ALL_PASS filter.
        // Otherwise offload all filters to hardware and enable all filters.
        private void configureScanFilters(ScanClient client) {
            int scannerId = client.mScannerId;
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
            mNativeInterface.gattClientScanFilterEnable(scannerId, true);
            waitForCallback();

            if (shouldUseAllPassFilter(client)) {
                int filterIndex =
                        (deliveryMode == DELIVERY_MODE_BATCH)
                                ? ALL_PASS_FILTER_INDEX_BATCH_SCAN
                                : ALL_PASS_FILTER_INDEX_REGULAR_SCAN;
                resetCountDownLatch();
                // Don't allow Onfound/onlost with all pass
                configureFilterParameter(
                        scannerId, client, ALL_PASS_FILTER_SELECTION, filterIndex, 0);
                waitForCallback();
            } else {
                Deque<Integer> clientFilterIndices = new ArrayDeque<Integer>();
                for (ScanFilter filter : client.mFilters) {
                    ScanFilterQueue queue = new ScanFilterQueue();
                    queue.addScanFilter(filter);
                    int featureSelection = queue.getFeatureSelection();
                    int filterIndex = mFilterIndexStack.pop();

                    resetCountDownLatch();
                    mNativeInterface.gattClientScanFilterAdd(
                            scannerId, queue.toArray(), filterIndex);
                    waitForCallback();

                    resetCountDownLatch();
                    if (deliveryMode == DELIVERY_MODE_ON_FOUND_LOST) {
                        trackEntries = getNumOfTrackingAdvertisements(client.mSettings);
                        if (!manageAllocationOfTrackingAdvertisement(trackEntries, true)) {
                            Log.e(
                                    TAG,
                                    "No hardware resources for onfound/onlost filter "
                                            + trackEntries);
                            if (client.mStats != null) {
                                client.mStats.recordTrackingHwFilterNotAvailableCountMetrics(
                                        client.mScannerId,
                                        mAdapterService.getTotalNumOfTrackableAdvertisements());
                            }
                            try {
                                mScanController.onScanManagerErrorCallback(
                                        scannerId, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                            } catch (RemoteException e) {
                                Log.e(TAG, "failed on onScanManagerCallback", e);
                            }
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
                mAllPassBatchClients.add(client.mScannerId);
                return mAllPassBatchClients.size() == 1;
            } else {
                mAllPassRegularClients.add(client.mScannerId);
                return mAllPassRegularClients.size() == 1;
            }
        }

        private void removeScanFilters(int scannerId) {
            Deque<Integer> filterIndices = mClientFilterIndexMap.remove(scannerId);
            if (filterIndices != null) {
                mFilterIndexStack.addAll(filterIndices);
                for (Integer filterIndex : filterIndices) {
                    resetCountDownLatch();
                    mNativeInterface.gattClientScanFilterParamDelete(scannerId, filterIndex);
                    waitForCallback();
                }
            }
            // Remove if ALL_PASS filters are used.
            removeFilterIfExists(
                    mAllPassRegularClients, scannerId, ALL_PASS_FILTER_INDEX_REGULAR_SCAN);
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
                mNativeInterface.gattClientScanFilterParamDelete(scannerId, filterIndex);
                waitForCallback();
            }
        }

        private ScanClient getBatchScanClient(int scannerId) {
            for (ScanClient client : mBatchClients) {
                if (client.mScannerId == scannerId) {
                    return client;
                }
            }
            return null;
        }

        /** Return batch scan result type value defined in bt stack. */
        private static int getResultType(BatchScanParams params) {
            if (params.mFullScanScannerId != -1 && params.mTruncatedScanScannerId != -1) {
                return SCAN_RESULT_TYPE_BOTH;
            }
            if (params.mTruncatedScanScannerId != -1) {
                return SCAN_RESULT_TYPE_TRUNCATED;
            }
            if (params.mFullScanScannerId != -1) {
                return SCAN_RESULT_TYPE_FULL;
            }
            return -1;
        }

        // Check if ALL_PASS filter should be used for the client.
        private boolean shouldUseAllPassFilter(ScanClient client) {
            if (client == null) {
                return true;
            }
            if (client.mFilters == null || client.mFilters.isEmpty()) {
                return true;
            }
            if (client.mFilters.size() > mFilterIndexStack.size()) {
                if (client.mStats != null) {
                    client.mStats.recordHwFilterNotAvailableCountMetrics(
                            client.mScannerId,
                            mAdapterService.getNumOfOffloadedScanFilterSupported());
                }
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
            ScanSettings settings = client.mSettings;
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
            mNativeInterface.gattClientScanFilterParamAdd(filtValue);
        }

        // Get delivery mode based on scan settings.
        private static int getDeliveryMode(ScanClient client) {
            if (client == null) {
                return DELIVERY_MODE_IMMEDIATE;
            }
            ScanSettings settings = client.mSettings;
            if (settings == null) {
                return DELIVERY_MODE_IMMEDIATE;
            }
            if ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                    || (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
                return DELIVERY_MODE_ON_FOUND_LOST;
            }
            if (isAllMatchesAutoBatchScanClient(client)) {
                return isAutoBatchScanClientEnabled(client)
                        ? DELIVERY_MODE_BATCH
                        : DELIVERY_MODE_IMMEDIATE;
            }
            return settings.getReportDelayMillis() == 0
                    ? DELIVERY_MODE_IMMEDIATE
                    : DELIVERY_MODE_BATCH;
        }

        private int getScanWindowMillis(ScanSettings settings) {
            ContentResolver resolver = mAdapterService.getContentResolver();
            if (settings == null) {
                return Settings.Global.getInt(
                        resolver,
                        Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                        SCAN_MODE_LOW_POWER_WINDOW_MS);
            }

            switch (settings.getScanMode()) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_LATENCY_WINDOW_MS,
                            SCAN_MODE_LOW_LATENCY_WINDOW_MS);
                case ScanSettings.SCAN_MODE_BALANCED:
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                            SCAN_MODE_BALANCED_WINDOW_MS);
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                            SCAN_MODE_LOW_POWER_WINDOW_MS);
                case ScanSettings.SCAN_MODE_SCREEN_OFF:
                    return mAdapterService.getScreenOffLowPowerWindowMillis();
                case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED:
                    return mAdapterService.getScreenOffBalancedWindowMillis();
                default:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                            SCAN_MODE_LOW_POWER_WINDOW_MS);
            }
        }

        private int getScanIntervalMillis(ScanSettings settings) {
            ContentResolver resolver = mAdapterService.getContentResolver();
            if (settings == null) {
                return Settings.Global.getInt(
                        resolver,
                        Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                        SCAN_MODE_LOW_POWER_INTERVAL_MS);
            }
            switch (settings.getScanMode()) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_LATENCY_INTERVAL_MS,
                            SCAN_MODE_LOW_LATENCY_INTERVAL_MS);
                case ScanSettings.SCAN_MODE_BALANCED:
                case ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                            SCAN_MODE_BALANCED_INTERVAL_MS);
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                            SCAN_MODE_LOW_POWER_INTERVAL_MS);
                case ScanSettings.SCAN_MODE_SCREEN_OFF:
                    return mAdapterService.getScreenOffLowPowerIntervalMillis();
                case ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED:
                    return mAdapterService.getScreenOffBalancedIntervalMillis();
                default:
                    return Settings.Global.getInt(
                            resolver,
                            Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                            SCAN_MODE_LOW_POWER_INTERVAL_MS);
            }
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
            int val = 0;
            int maxTotalTrackableAdvertisements =
                    mAdapterService.getTotalNumOfTrackableAdvertisements();
            // controller based onfound onlost resources are scarce commodity; the
            // assignment of filters to num of beacons to track is configurable based
            // on hw capabilities. Apps give an intent and allocation of onfound
            // resources or failure there of is done based on availability - FCFS model
            switch (settings.getNumOfMatches()) {
                case ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT:
                    val = 1;
                    break;
                case ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT:
                    val = 2;
                    break;
                case ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT:
                    val = maxTotalTrackableAdvertisements / 2;
                    if (Flags.changeDefaultTrackableAdvNumber()) {
                        val = maxTotalTrackableAdvertisements / 4;
                    }
                    break;
                default:
                    val = 1;
                    Log.d(
                            TAG,
                            "Invalid setting for getNumOfMatches() " + settings.getNumOfMatches());
            }
            return val;
        }

        private boolean manageAllocationOfTrackingAdvertisement(
                int numOfTrackableAdvertisement, boolean allocate) {
            int maxTotalTrackableAdvertisements =
                    mAdapterService.getTotalNumOfTrackableAdvertisements();
            synchronized (mCurUsedTrackableAdvertisementsLock) {
                int availableEntries =
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

        private void registerScanner(long appUuidLsb, long appUuidMsb) {
            mNativeInterface.registerScanner(appUuidLsb, appUuidMsb);
        }

        private void unregisterScanner(int scannerId) {
            mNativeInterface.unregisterScanner(scannerId);
        }

        private void addFiltersMsft(ScanClient client) {
            // Do not add any filters set by opportunistic scan clients
            if (isOpportunisticScanClient(client)) {
                return;
            }

            if (client == null
                    || client.mFilters == null
                    || client.mFilters.isEmpty()
                    || client.mFilters.size() > mFilterIndexStack.size()) {
                // Use all-pass filter
                updateScanMsft();
                return;
            }

            Deque<Integer> clientFilterIndices = new ArrayDeque<>();
            for (ScanFilter filter : client.mFilters) {
                MsftAdvMonitor monitor = new MsftAdvMonitor(filter);

                if (monitor.getAddress().bd_addr != null) {
                    int filterIndex = mFilterIndexStack.pop();

                    resetCountDownLatch();
                    mNativeInterface.gattClientMsftAdvMonitorAdd(
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
                            "No MSFT pattern or address was translated from client filter: "
                                    + filter);
                    continue;
                }

                // Some chipsets don't support multiple monitors with the same pattern. Skip
                // creating a new monitor if the pattern has already been registered
                int filterIndex = mFilterIndexStack.pop();
                int existingFilterIndex =
                        mMsftAdvMonitorMergedPatternList.add(filterIndex, monitor.getPatterns());
                if (filterIndex == existingFilterIndex) {
                    resetCountDownLatch();
                    mNativeInterface.gattClientMsftAdvMonitorAdd(
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
            mClientFilterIndexMap.put(client.mScannerId, clientFilterIndices);

            updateScanMsft();
        }

        private void removeFiltersMsft(ScanClient client) {
            Deque<Integer> clientFilterIndices = mClientFilterIndexMap.remove(client.mScannerId);
            if (clientFilterIndices != null) {
                for (int filterIndex : clientFilterIndices) {
                    if (mMsftAdvMonitorMergedPatternList.remove(filterIndex)) {
                        resetCountDownLatch();
                        mNativeInterface.gattClientMsftAdvMonitorRemove(filterIndex);
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
                                            c.mSettings != null
                                                    && c.mSettings.getScanMode()
                                                            != ScanSettings.SCAN_MODE_OPPORTUNISTIC
                                                    && !this.mClientFilterIndexMap.containsKey(
                                                            c.mScannerId));
            if (scanEnabledMsft != shouldEnableScanMsft) {
                resetCountDownLatch();
                mNativeInterface.gattClientMsftAdvMonitorEnable(shouldEnableScanMsft);
                waitForCallback();
                scanEnabledMsft = shouldEnableScanMsft;

                // Restart scanning, since enabling/disabling may have changed
                // the filter policy
                Log.d(TAG, "Restarting MSFT scan");
                mNativeInterface.gattClientScan(false);
                if (numRegularScanClients() > 0) {
                    mNativeInterface.gattClientScan(true);
                }
            }
        }
    }

    @VisibleForTesting
    BatchScanParams getBatchScanParams() {
        return mBatchScanParams;
    }

    private boolean isScreenOn() {
        Display[] displays = mDisplayManager.getDisplays();

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

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (isScreenOn()) {
                        sendMessage(MSG_SCREEN_ON, null);
                    } else {
                        sendMessage(MSG_SCREEN_OFF, null);
                    }
                }
            };

    private final ActivityManager.OnUidImportanceListener mUidImportanceListener =
            new ActivityManager.OnUidImportanceListener() {
                @Override
                public void onUidImportance(final int uid, final int importance) {
                    if (mScanController.getScannerMap().getAppScanStatsByUid(uid) != null) {
                        Message message = new Message();
                        message.what = MSG_IMPORTANCE_CHANGE;
                        message.obj = new UidImportance(uid, importance);
                        mHandler.sendMessage(message);
                    }
                }
            };

    private final BroadcastReceiver mLocationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                        final boolean locationEnabled = mLocationManager.isLocationEnabled();
                        if (locationEnabled) {
                            sendMessage(MSG_RESUME_SCANS, null);
                        } else {
                            sendMessage(MSG_SUSPEND_SCANS, null);
                        }
                    }
                }
            };

    private boolean updateCountersAndCheckForConnectingState(int state, int prevState) {
        switch (prevState) {
            case STATE_CONNECTING:
                if (mProfilesConnecting > 0) {
                    mProfilesConnecting--;
                } else {
                    Log.e(TAG, "mProfilesConnecting " + mProfilesConnecting);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
                break;
            case STATE_CONNECTED:
                if (mProfilesConnected > 0) {
                    mProfilesConnected--;
                } else {
                    Log.e(TAG, "mProfilesConnected " + mProfilesConnected);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
                break;
            case STATE_DISCONNECTING:
                if (mProfilesDisconnecting > 0) {
                    mProfilesDisconnecting--;
                } else {
                    Log.e(TAG, "mProfilesDisconnecting " + mProfilesDisconnecting);
                    throw new IllegalStateException(
                            "Invalid state transition, " + prevState + " -> " + state);
                }
                break;
        }
        switch (state) {
            case STATE_CONNECTING:
                mProfilesConnecting++;
                break;
            case STATE_CONNECTED:
                mProfilesConnected++;
                break;
            case STATE_DISCONNECTING:
                mProfilesDisconnecting++;
                break;
            case STATE_DISCONNECTED:
                break;
            default:
        }
        Log.d(
                TAG,
                ("mProfilesConnecting " + mProfilesConnecting)
                        + (", mProfilesConnected " + mProfilesConnected)
                        + (", mProfilesDisconnecting " + mProfilesDisconnecting));
        return (mProfilesConnecting > 0);
    }

    private int getMinScanMode(int oldScanMode, int newScanMode) {
        return mPriorityMap.get(oldScanMode) <= mPriorityMap.get(newScanMode)
                ? oldScanMode
                : newScanMode;
    }

    /**
     * Handle bluetooth profile connection state changes (for A2DP, HFP, HFP Client, A2DP Sink and
     * LE Audio).
     */
    public void handleBluetoothProfileConnectionStateChanged(
            int profile, int fromState, int toState) {
        mHandler.post(
                () -> mHandler.handleProfileConnectionStateChanged(profile, fromState, toState));
    }
}
