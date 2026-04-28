/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST;

import static com.android.bluetooth.Util.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.le_scan.BatchScanUtil.permittedResults;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_TRUNCATED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Intent;
import android.net.MacAddress;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.util.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ScanController {
    private static final String TAG = ScanUtil.TAG_PREFIX + ScanController.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    // onFoundLost related constants
    @VisibleForTesting static final int ADVT_STATE_ONFOUND = 0;
    private static final int ADVT_STATE_ONLOST = 1;

    private static final int ET_SCANNABLE_MASK = 0x02;
    private static final int ET_SCAN_RESPONSE_MASK = 0x08;
    private static final int ET_LEGACY_MASK = 0x10;

    private final PendingIntent.CancelListener mScanIntentCancelListener =
            intent -> {
                Log.d(TAG, "onCanceled(): Scanning PendingIntent canceled");
                doOnScanThread(() -> stopScan(intent));
            };

    private final Map<Integer, Integer> mFilterIndexToMsftAdvMonitorMap = new HashMap<>();

    private final Object mTestModeLock = new Object();

    private final AdapterService mAdapterService;
    private final AppOpsManager mAppOps;
    private final CompanionDeviceManager mCompanionManager;
    private final ScanBinder mBinder;
    private final ScannerMap mScannerMap;
    private final ScanRadioStats mScanRadioStats;
    private final String mExposureNotificationPackage;
    private final Predicate<ScanResult> mLocationDenylistPredicate;

    private final HandlerThread mHandlerThread;
    private final Looper mLooper;
    private final Handler mHandler;
    private final ScanManager mScanManager;
    private final ScanSuspendManager mScanSuspendManager;
    private final PeriodicScanManager mPeriodicScanManager;

    private volatile boolean mIsAvailable = true;
    private volatile boolean mTestModeEnabled = false;
    private volatile boolean mIsMsftAdvMonitorEnabled = false;
    private Handler mTestModeHandler;

    public ScanController(
            AdapterService adapterService,
            ScanNativeInterface scanNativeInterface,
            PeriodicScanNativeInterface periodicScanNativeInterface,
            BatteryStatsManager batteryStatsManager,
            CompanionDeviceManager companionDeviceManager) {
        this(
                adapterService,
                null,
                scanNativeInterface,
                null,
                periodicScanNativeInterface,
                batteryStatsManager,
                companionDeviceManager,
                null,
                TimeProvider.getSystemClock());
    }

    @VisibleForTesting
    ScanController(
            AdapterService adapterService,
            ScanManager scanManager,
            ScanNativeInterface scanNativeInterface,
            PeriodicScanManager periodicScanManager,
            PeriodicScanNativeInterface periodicScanNativeInterface,
            BatteryStatsManager batteryStatsManager,
            CompanionDeviceManager companionDeviceManager,
            Looper looper,
            TimeProvider timeProvider) {
        Log.i(TAG, "Created");
        mAdapterService = requireNonNull(adapterService);
        mAppOps = mAdapterService.getSystemService(AppOpsManager.class);
        mCompanionManager = companionDeviceManager;
        mBinder = new ScanBinder(mAdapterService, this, mTestModeEnabled);
        mScannerMap = new ScannerMap(mAdapterService, batteryStatsManager);
        mScanRadioStats = new ScanRadioStats(timeProvider);
        mExposureNotificationPackage =
                mAdapterService.getString(R.string.exposure_notification_package);
        mLocationDenylistPredicate =
                (scanResult) -> {
                    final MacAddress parsedAddress =
                            MacAddress.fromString(scanResult.getDevice().getAddress());
                    if (mAdapterService
                            .getLocationDenylistMac()
                            .test(parsedAddress.toByteArray())) {
                        Log.v(TAG, "Skipping device matching denylist: " + scanResult.getDevice());
                        return true;
                    }
                    final ScanRecord scanRecord = scanResult.getScanRecord();
                    if (scanRecord.matchesAnyField(
                            mAdapterService.getLocationDenylistAdvertisingData())) {
                        Log.v(TAG, "Skipping data matching denylist: " + scanRecord);
                        return true;
                    }
                    return false;
                };
        mHandlerThread = new HandlerThread("BluetoothScanManager");
        mHandlerThread.start();
        mLooper = requireNonNullElseGet(looper, mHandlerThread::getLooper);
        mHandler = new Handler(mLooper);
        mScanManager =
                requireNonNullElseGet(
                        scanManager,
                        () ->
                                new ScanManager(
                                        mAdapterService,
                                        this,
                                        scanNativeInterface,
                                        mScanRadioStats,
                                        mLooper,
                                        timeProvider));
        mScanSuspendManager = new ScanSuspendManager(mScanManager);
        mPeriodicScanManager =
                requireNonNullElseGet(
                        periodicScanManager,
                        () ->
                                new PeriodicScanManager(
                                        mAdapterService, this, periodicScanNativeInterface));
    }

    public void cleanup() {
        Log.i(TAG, "cleanup()");
        mIsAvailable = false;
        mHandler.removeCallbacksAndMessages(null);
        forceRunSyncOnScanThread(
                () -> {
                    mBinder.cleanup();
                    mScannerMap.clear();
                    mScanManager.cleanup();
                    mPeriodicScanManager.cleanup();
                    mHandlerThread.quitSafely();
                });
    }

    /** Notify Scan manager of bluetooth profile connection state changes */
    public void notifyProfileConnectionStateChange(int profile, int fromState, int toState) {
        enforceScanThread();
        mScanManager.handleBluetoothProfileConnectionStateChanged(profile, fromState, toState);
    }

    public IBinder getBinder() {
        return mBinder;
    }

    ScannerMap getScannerMap() {
        enforceScanThread();
        return mScannerMap;
    }

    /** onDisplayChanged notifies ScanManager when the screen status changes. */
    public void onDisplayChanged(boolean screenOn) {
        enforceScanThread();
        Log.d(TAG, "onDisplayChanged(): Screen on=" + screenOn);
        mScanManager.onDisplayChanged(screenOn);
    }

    /** onSystemSuspendChanged notifies ScanSuspendManager when the system suspends and resumes. */
    public void onSystemSuspendChanged(boolean suspended) {
        enforceScanThread();
        Log.d(TAG, "onSystemSuspendChanged(): Suspended=" + suspended);
        mScanSuspendManager.onSystemSuspendChanged(suspended);
    }

    boolean isSystemSuspended() {
        return mScanSuspendManager.isSystemSuspended();
    }

    public void setTestModeEnabled(boolean enableTestMode) {
        synchronized (mTestModeLock) {
            if (mTestModeHandler == null) {
                mTestModeHandler =
                        new Handler(mLooper) {
                            public void handleMessage(Message msg) {
                                synchronized (mTestModeLock) {
                                    if (!mTestModeEnabled) {
                                        return;
                                    }
                                    ScanTestUtil.runTestCycle(ScanController.this);
                                    sendEmptyMessageDelayed(0, DateUtils.SECOND_IN_MILLIS);
                                }
                            }
                        };
            }
            if (enableTestMode == mTestModeEnabled) {
                return;
            }
            mTestModeEnabled = enableTestMode;
            mTestModeHandler.removeMessages(0);
            mTestModeHandler.sendEmptyMessageDelayed(
                    0, enableTestMode ? DateUtils.SECOND_IN_MILLIS : 0);
        }
    }

    /** Callback method for a scan result. */
    void onScanResult(
            int eventType,
            int addressType,
            String address,
            int primaryPhy,
            int secondaryPhy,
            int advertisingSid,
            int txPower,
            int rssi,
            int periodicAdvInt,
            byte[] advData,
            String originalAddress) {
        // When in testing mode, ignore all real-world events
        if (mTestModeEnabled) return;

        enforceScanThread();

        mScanRadioStats.recordScanRadioResultCount();
        onScanResultInternal(
                eventType,
                addressType,
                address,
                primaryPhy,
                secondaryPhy,
                advertisingSid,
                txPower,
                rssi,
                periodicAdvInt,
                advData,
                originalAddress);
    }

    void onScanResultInternal(
            int eventType,
            int addressType,
            String address,
            int primaryPhy,
            int secondaryPhy,
            int advertisingSid,
            int txPower,
            int rssi,
            int periodicAdvInt,
            byte[] advData,
            String originalAddress) {
        Log.v(
                TAG,
                "onScanResult(): "
                        + ("eventType=0x" + Integer.toHexString(eventType))
                        + (", addressType=" + addressType)
                        + (", address=" + BluetoothUtils.toAnonymizedAddress(address))
                        + (", primaryPhy=" + primaryPhy)
                        + (", secondaryPhy=" + secondaryPhy)
                        + (", advertisingSid=0x" + Integer.toHexString(advertisingSid))
                        + (", txPower=" + txPower)
                        + (", rssi=" + rssi)
                        + (", periodicAdvInt=0x" + Integer.toHexString(periodicAdvInt))
                        + (", originalAddress=" + originalAddress));

        // Retain the original behavior of returning bluetoothAddress when identityAddress is null
        String identityAddress = mAdapterService.getBrEdrAddress(address);

        if (!address.equals(identityAddress)) {
            Log.v(
                    TAG,
                    ("Found identityAddress of " + BluetoothUtils.toAnonymizedAddress(address))
                            + (", replace originalAddress as "
                                    + BluetoothUtils.toAnonymizedAddress(identityAddress)));
            originalAddress = identityAddress;
        }

        byte[] legacyAdvData = Arrays.copyOfRange(advData, 0, 62);
        var device = mAdapterService.getRemoteDevice(address, addressType);
        // Aggregate skipped clients to reduce log spam
        var scanTypeMismatch = new ArrayList<ScanClient>();
        var legacyScanNonLegacyResult = new ArrayList<ScanClient>();
        var locationDenyList = new ArrayList<ScanClient>();
        var noPermission = new ArrayList<ScanClient>();
        var noFilterMatched = new ArrayList<ScanClient>();
        var notAllMatches = new ArrayList<ScanClient>();
        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            var app = mScannerMap.getById(client.getScannerId());
            if (app == null) {
                Log.v(TAG, "App not found for " + client + "; Skip");
                continue;
            }

            final ScanSettings settings = client.getSettings();
            final byte[] scanRecordData;
            boolean isScanResponse = (eventType & ET_SCAN_RESPONSE_MASK) != 0;
            boolean requiresScanResponse =
                    (eventType & ET_SCANNABLE_MASK) != 0
                            && !isScanResponse
                            && !mIsMsftAdvMonitorEnabled;

            if ((settings.getScanType() == ScanSettings.SCAN_TYPE_ACTIVE && requiresScanResponse)
                    || (settings.getScanType() == ScanSettings.SCAN_TYPE_PASSIVE
                            && isScanResponse)) {
                scanTypeMismatch.add(client);
                continue;
            }

            // This is for compatibility with applications that assume fixed size scan data.
            if (settings.getLegacy()) {
                if ((eventType & ET_LEGACY_MASK) == 0) {
                    // If this is legacy scan, but nonlegacy result - skip.
                    legacyScanNonLegacyResult.add(client);
                    continue;
                } else {
                    // Some apps are used to fixed-size advertise data.
                    scanRecordData = legacyAdvData;
                }
            } else {
                scanRecordData = advData;
            }

            ScanRecord scanRecord = ScanRecord.parseFromBytes(scanRecordData);
            ScanResult result =
                    new ScanResult(
                            device,
                            eventType,
                            primaryPhy,
                            secondaryPhy,
                            advertisingSid,
                            txPower,
                            rssi,
                            periodicAdvInt,
                            scanRecord,
                            SystemClock.elapsedRealtimeNanos());

            if (client.getHasDisavowedLocation()) {
                if (mLocationDenylistPredicate.test(result)) {
                    locationDenyList.add(client);
                    continue;
                }
            }

            var hasPermission = ScanUtil.hasScanResultPermission(mAdapterService, client);
            if (!hasPermission) {
                for (String associatedDevice : client.getAssociatedDevices()) {
                    if (associatedDevice.equalsIgnoreCase(address)) {
                        hasPermission = true;
                        break;
                    }
                }
            }
            if (!hasPermission && client.isEligibleForSanitizedExposureNotification()) {
                ScanResult sanitized = ScanUtil.getSanitizedExposureNotification(scanRecord, rssi);
                if (sanitized != null) {
                    hasPermission = true;
                    result = sanitized;
                }
            }
            if (!hasPermission) {
                noPermission.add(client);
                continue;
            }
            if (!matchesFilters(client, result, originalAddress)) {
                noFilterMatched.add(client);
                continue;
            }

            final int callbackType = settings.getCallbackType();
            if (!(callbackType == CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)) {
                notAllMatches.add(client);
                continue;
            }

            try {
                app.getAppScanStats().addResults(client.getScannerId(), 1, false /* isBatch */);
                if (app.getCallback() != null) {
                    app.getCallback().onScanResult(result);
                } else {
                    Log.v(TAG, "Callback null for " + client + "; Send results by pendingIntent");
                    List<ScanResult> results = new ArrayList<>(Arrays.asList(result));
                    sendResultsByPendingIntent(
                            app.getPendingIntent(), results, CALLBACK_TYPE_ALL_MATCHES);
                }
            } catch (RemoteException | PendingIntent.CanceledException e) {
                Log.e(TAG, "onScanResult(): Exception: " + e);
                handleDeadScanClient(client);
            }
        }
        if (!scanTypeMismatch.isEmpty()) {
            Log.v(TAG, "Scan type mismatch for " + scanTypeMismatch + "; Skip");
        }
        if (!legacyScanNonLegacyResult.isEmpty()) {
            Log.v(
                    TAG,
                    "Legacy scan, non legacy result for " + legacyScanNonLegacyResult + "; Skip");
        }
        if (!locationDenyList.isEmpty()) {
            Log.i(TAG, "Location deny list for " + locationDenyList + "; Skip");
        }
        if (!noPermission.isEmpty()) {
            Log.v(TAG, "No permission for " + noPermission + "; Skip");
        }
        if (!noFilterMatched.isEmpty()) {
            Log.v(TAG, "No filter match for " + noFilterMatched + "; Skip");
        }
        if (!notAllMatches.isEmpty()) {
            Log.v(TAG, "Not CALLBACK_TYPE_ALL_MATCHES for " + notAllMatches + "; Skip");
        }
    }

    private void sendResultByPendingIntent(
            PendingIntent pendingIntent, ScanResult result, int callbackType, ScanClient client) {
        List<ScanResult> results = new ArrayList<>(Arrays.asList(result));
        try {
            sendResultsByPendingIntent(pendingIntent, results, callbackType);
        } catch (PendingIntent.CanceledException e) {
            final long token = Binder.clearCallingIdentity();
            try {
                handleDeadScanClient(client);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private void sendResultsByPendingIntent(
            PendingIntent pendingIntent, List<ScanResult> results, int callbackType)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, new ArrayList<>(results));
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType);
        pendingIntent.send(mAdapterService, 0, extrasIntent);
    }

    private void sendErrorByPendingIntent(PendingIntent pendingIntent, int errorCode)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, errorCode);
        pendingIntent.send(mAdapterService, 0, extrasIntent);
    }

    /** Callback method for scanner registration. */
    void onScannerRegistered(int status, int scannerId, UUID uuid) {
        enforceScanThread();
        var header = "onScannerRegistered(): ";
        Log.d(
                TAG,
                (header + "UUID=" + uuid + ", scannerId=" + scannerId)
                        + (", status=" + ScanUtil.statusToString(status)));

        var app = mScannerMap.getByUuid(uuid);
        if (app == null) {
            Log.e(TAG, header + "App not found");
            return;
        }
        if (app.getCallback() != null) {
            callbackToApp(() -> app.getCallback().onScannerRegistered(status, scannerId));
        }
        if (status != ScanCallback.NO_ERROR) {
            unregisterScanner(scannerId);
            return;
        }
        app.setScannerId(scannerId);
        // If app is callback based, setup a death recipient and start scan.
        // Otherwise, if PendingIntent based, start the scan directly.
        if (app.getCallback() != null) {
            var message = "Unregister " + scannerId + " for " + app;
            Runnable onDeathAction = () -> doOnScanThread(() -> handleDeadScanClient(scannerId));
            app.linkToDeath(new ActionOnDeathRecipient(TAG, message, onDeathAction));
            if (app.isInternal()) {
                startScanInternal(app);
            } else {
                startScan(app);
            }
        } else {
            dispatchPendingIntentStartScan(app);
        }
    }

    // Check if a scan record matches a specific filters.
    @VisibleForTesting
    static boolean matchesFilters(ScanClient client, ScanResult scanResult) {
        return matchesFilters(client, scanResult, null);
    }

    // Check if a scan record matches a specific filters or original address
    @VisibleForTesting
    static boolean matchesFilters(
            ScanClient client, ScanResult scanResult, String originalAddress) {
        ScanSettings settings = client.getSettings();
        if (scanResult.getRssi() < settings.getRssiThreshold()) {
            return false;
        }
        if (!client.isFiltered()) {
            // TODO: Do we really wanna return true here?
            return true;
        }
        for (ScanFilter filter : client.getFilters()) {
            // Need to check the filter matches, and the original address without changing the API
            if (filter.matches(scanResult)) {
                return true;
            }
            if (originalAddress != null
                    && originalAddress.equalsIgnoreCase(filter.getDeviceAddress())
                    && filter.matchesWithoutAddress(scanResult)) {
                return true;
            }
        }
        return false;
    }

    private void handleDeadScanClient(int scannerId) {
        var client = ScanUtil.findById(mScanManager.getRegularScanQueue(), scannerId);
        if (client == null) {
            client = ScanUtil.findById(mScanManager.getBatchScanQueue(), scannerId);
        }
        if (client != null) {
            handleDeadScanClient(client);
        }
    }

    private void handleDeadScanClient(ScanClient client) {
        if (client.getAppDied()) {
            Log.w(TAG, "Already dead " + client);
            return;
        }
        client.setAppDied(true);
        client.getAppScanStats().setAppDead(true);
        stopScan(client.getScannerId());
    }

    /** Callback method for batch scan reports */
    void onBatchScanReports(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData) {
        // When in testing mode, ignore all real-world events
        if (mTestModeEnabled) return;

        enforceScanThread();
        mScanRadioStats.recordBatchScanRadioResultCount(numRecords);
        onBatchScanReportsInternal(status, scannerId, reportType, numRecords, recordData);
    }

    @VisibleForTesting
    void onBatchScanReportsInternal(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData) {
        Set<ScanResult> results =
                BatchScanUtil.parseResults(mAdapterService, numRecords, reportType, recordData);
        if (reportType == SCAN_RESULT_TYPE_TRUNCATED) {
            // We only support single client for truncated mode.
            var header = "onBatchScanReportsInternal(): ";
            var app = mScannerMap.getById(scannerId);
            if (app == null) {
                Log.e(TAG, header + "App not found for scannerId=" + scannerId);
                return;
            }

            var client = ScanUtil.findById(mScanManager.getBatchScanQueue(), scannerId);
            if (client == null) {
                return;
            }

            List<ScanResult> permittedResults = permittedResults(mAdapterService, client, results);
            if (client.getHasDisavowedLocation()) {
                permittedResults.removeIf(mLocationDenylistPredicate);
            }
            if (permittedResults.isEmpty()) {
                return;
            }

            if (app.getCallback() != null) {
                callbackToApp(() -> app.getCallback().onBatchScanResults(permittedResults));
                mScanManager.batchScanResultDelivered();
            } else {
                // PendingIntent based
                try {
                    sendResultsByPendingIntent(
                            app.getPendingIntent(), permittedResults, CALLBACK_TYPE_ALL_MATCHES);
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, header + "Error sending result via PendingIntent: " + e);
                    handleDeadScanClient(client);
                }
            }
        } else {
            for (ScanClient client : mScanManager.getFullBatchScanQueue()) {
                // Deliver results for each client.
                deliverBatchScan(client, results);
            }
        }
    }

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults) {
        var app = mScannerMap.getById(client.getScannerId());
        if (app == null) {
            Log.e(TAG, "deliverBatchScan(): App not found for scannerId=" + client.getScannerId());
            return;
        }

        List<ScanResult> permittedResults = permittedResults(mAdapterService, client, allResults);
        if (!client.isFiltered()) {
            sendBatchScanResults(app, client, permittedResults);
            return;
        }
        // Reconstruct the scan results.
        List<ScanResult> results = new ArrayList<>();
        for (ScanResult scanResult : permittedResults) {
            if (matchesFilters(client, scanResult)) {
                results.add(scanResult);
            }
        }
        sendBatchScanResults(app, client, results);
    }

    private void sendBatchScanResults(ScannerApp app, ScanClient client, List<ScanResult> results) {
        if (results.isEmpty()) {
            return;
        }
        try {
            app.getAppScanStats()
                    .addResults(client.getScannerId(), results.size(), true /* isBatch */);
            if (app.getCallback() != null) {
                if (ScanUtil.isAutoBatchScanClientEnabled(client)) {
                    Log.d(TAG, "sendBatchScanResults() to onScanResult() for " + client);
                    for (ScanResult result : results) {
                        app.getCallback().onScanResult(result);
                    }
                } else {
                    Log.d(TAG, "sendBatchScanResults() to onBatchScanResults() for " + client);
                    app.getCallback().onBatchScanResults(results);
                }
            } else {
                sendResultsByPendingIntent(
                        app.getPendingIntent(), results, CALLBACK_TYPE_ALL_MATCHES);
            }
        } catch (RemoteException | PendingIntent.CanceledException e) {
            Log.e(TAG, "sendBatchScanResults(): Exception: " + e);
            handleDeadScanClient(client);
        }
        mScanManager.batchScanResultDelivered();
    }

    void onBatchScanThresholdCrossed(int scannerId) {
        enforceScanThread();
        Log.d(TAG, "onBatchScanThresholdCrossed(scannerId=" + scannerId + ")");
        flushPendingBatchResults(scannerId);
    }

    void onTrackAdvFoundLost(AdvtFilterOnFoundOnLostInfo trackingInfo) {
        enforceScanThread();
        int scannerId = trackingInfo.getScannerId();
        var address = trackingInfo.getAddress();
        Log.d(
                TAG,
                ("onTrackAdvFoundLost(): scannerId=" + scannerId + ", address=" + address)
                        + (", addressType=" + trackingInfo.getAddressType())
                        + (", adv_state=" + trackingInfo.getAdvState()));

        var app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, "onTrackAdvFoundLost(): App not found for scannerId=" + scannerId);
            return;
        }

        var device = mAdapterService.getRemoteDevice(address, trackingInfo.getAddressType());
        int advertiserState = trackingInfo.getAdvState();
        ScanResult result =
                new ScanResult(
                        device,
                        ScanRecord.parseFromBytes(trackingInfo.getResult()),
                        trackingInfo.getRssiValue(),
                        SystemClock.elapsedRealtimeNanos());

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.getScannerId() == scannerId) {
                ScanSettings settings = client.getSettings();
                if ((advertiserState == ADVT_STATE_ONFOUND)
                        && ((settings.getCallbackType() & CALLBACK_TYPE_FIRST_MATCH) != 0)) {
                    if (app.getCallback() != null) {
                        callbackToApp(() -> app.getCallback().onFoundOrLost(true, result));
                    } else {
                        sendResultByPendingIntent(
                                app.getPendingIntent(), result, CALLBACK_TYPE_FIRST_MATCH, client);
                    }
                } else if ((advertiserState == ADVT_STATE_ONLOST)
                        && ((settings.getCallbackType() & CALLBACK_TYPE_MATCH_LOST) != 0)) {
                    if (app.getCallback() != null) {
                        callbackToApp(() -> app.getCallback().onFoundOrLost(false, result));
                    } else {
                        sendResultByPendingIntent(
                                app.getPendingIntent(), result, CALLBACK_TYPE_MATCH_LOST, client);
                    }
                } else {
                    Log.d(
                            TAG,
                            "Not reporting onlost/onfound -"
                                    + (" advertiserState=" + advertiserState)
                                    + (", scannerId=" + client.getScannerId())
                                    + (", callbackType=" + settings.getCallbackType()));
                }
            }
        }
    }

    /** Callback method for configuration of scan parameters. */
    void onScanParamSetupCompleted(int status, int scannerId) {
        enforceScanThread();
        Log.d(TAG, "onScanParamSetupCompleted(): scannerId=" + scannerId + ", status=" + status);
        var app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, "onScanParamSetupCompleted(): App not found for scannerId=" + scannerId);
        } else if (app.getCallback() == null) {
            Log.e(TAG, "onScanParamSetupCompleted(): App callback null for " + app);
        }
    }

    // callback from ScanManager for dispatch of errors apps.
    void onScanManagerErrorCallback(int scannerId, int errorCode) {
        enforceScanThread();
        var header = "onScanManagerErrorCallback(): ";
        var app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, header + "App not found for scannerId=" + scannerId);
            return;
        }
        if (app.getCallback() != null) {
            callbackToApp(() -> app.getCallback().onScanManagerErrorCallback(errorCode));
        } else {
            try {
                sendErrorByPendingIntent(app.getPendingIntent(), errorCode);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, header + "Error sending error code via PendingIntent: " + e);
                handleDeadScanClient(scannerId);
            }
        }
    }

    int msftMonitorHandleFromFilterIndex(int filterIndex) {
        enforceScanThread();
        if (!mFilterIndexToMsftAdvMonitorMap.containsKey(filterIndex)) {
            Log.e(TAG, "Monitor with filterIndex'" + filterIndex + "' does not exist");
            return -1;
        }
        return mFilterIndexToMsftAdvMonitorMap.get(filterIndex);
    }

    void onMsftAdvMonitorAdd(int filterIndex, int monitorHandle, int status) {
        enforceScanThread();
        if (status != 0) {
            Log.e(
                    TAG,
                    "Error adding advertisement monitor with filter index '" + filterIndex + "'");
            return;
        }
        if (mFilterIndexToMsftAdvMonitorMap.containsKey(filterIndex)) {
            Log.e(TAG, "Monitor with filterIndex'" + filterIndex + "' already added");
            return;
        }
        mFilterIndexToMsftAdvMonitorMap.put(filterIndex, monitorHandle);
    }

    void onMsftAdvMonitorRemove(int filterIndex, int status) {
        enforceScanThread();
        if (status != 0) {
            Log.e(
                    TAG,
                    "Error removing advertisement monitor with filter index '" + filterIndex + "'");
        }
        if (!mFilterIndexToMsftAdvMonitorMap.containsKey(filterIndex)) {
            Log.e(TAG, "Monitor with filterIndex'" + filterIndex + "' does not exist");
            return;
        }
        mFilterIndexToMsftAdvMonitorMap.remove(filterIndex);
    }

    void onMsftAdvMonitorEnable(boolean enable, int status) {
        enforceScanThread();
        if (status != 0) {
            Log.e(TAG, "Error enabling advertisement monitor");
        } else {
            mIsMsftAdvMonitorEnabled = enable;
            mScanManager.restartScan("onMsftAdvMonitorEnable");
        }
    }

    void registerAndStartScan(
            @NonNull IScannerCallback callback,
            @Nullable WorkSource workSource,
            @NonNull AttributionSource source,
            boolean hasPrivilegedPermission,
            @NonNull ScanSettings settings,
            @NonNull List<ScanFilter> filters) {
        enforceScanThread();
        var appScanStats = mScannerMap.getAppScanStatsByUid(source.getUid());
        if (appScanStats != null
                && appScanStats.isScanningTooFrequently()
                && !hasPrivilegedPermission) {
            Log.e(TAG, "registerAndStartScan(): " + appScanStats + " is scanning too frequently");
            try {
                callback.onScannerRegistered(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "registerAndStartScan(): Exception: " + e);
            }
            return;
        }
        registerAndStartScan(
                callback, workSource, source, settings, filters, /* isInternal */ false);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void registerAndStartScanInternal(
            @NonNull IScannerCallback callback,
            @NonNull AttributionSource source,
            @NonNull ScanSettings settings,
            @NonNull List<ScanFilter> filters) {
        enforceScanThread();
        registerAndStartScan(
                callback, /* workSource */ null, source, settings, filters, /* isInternal */ true);
    }

    private void registerAndStartScan(
            @NonNull IScannerCallback callback,
            @Nullable WorkSource workSource,
            @NonNull AttributionSource source,
            @NonNull ScanSettings settings,
            @NonNull List<ScanFilter> filters,
            boolean isInternal) {
        Log.d(
                TAG,
                ("registerAndStartScan(): source=" + source)
                        + (", settings=" + ScanUtil.toStringShort(settings))
                        + (", filters=" + filters + ", isInternal=" + isInternal));
        var app =
                mScannerMap.addWithCallback(
                        source, workSource, callback, settings, filters, isInternal);
        mScanManager.registerScanner(app.getUuid());
    }

    public void unregisterScanner(int scannerId) {
        enforceScanThread();
        Log.d(TAG, "unregisterScanner(scannerId=" + scannerId + ")");
        mScannerMap.remove(scannerId);
        mScanManager.unregisterScanner(scannerId);
    }

    private List<String> getAssociatedDevices(String callingPackage) {
        if (mCompanionManager == null) {
            return Collections.emptyList();
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return mCompanionManager.getAllAssociations().stream()
                    .filter(
                            info ->
                                    info.getPackageName().equals(callingPackage)
                                            && !info.isSelfManaged()
                                            && info.getDeviceMacAddress() != null)
                    .map(info -> info.getDeviceMacAddress().toString())
                    .collect(Collectors.toList());
        } catch (SecurityException se) {
            // Not an app with associated devices
        } catch (Exception e) {
            Log.e(TAG, "Cannot check device associations for " + callingPackage, e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return Collections.emptyList();
    }

    private void startScan(ScannerApp app) {
        Log.d(TAG, "startScan(app=" + app + " with scannerId=" + app.getScannerId() + ")");
        String callingPackage = app.getSource().getPackageName();
        var settings = BatchScanUtil.enforceReportDelayFloor(app.getSettings());
        mAppOps.checkPackage(app.getUid(), callingPackage);
        var hasDisavowedLocation =
                Util.hasDisavowedLocationForScan(
                        mAdapterService, app.getSource(), mTestModeEnabled);
        var isQApp = checkCallerTargetSdk(mAdapterService, app.getSource(), Build.VERSION_CODES.Q);
        var userHandle = Binder.getCallingUserHandle();
        var hasLocationPermission = false; // Unacted upon if `hasDisavowedLocation` is true
        if (!hasDisavowedLocation) {
            if (isQApp) {
                hasLocationPermission =
                        Util.checkCallerHasFineLocation(
                                mAdapterService, app.getSource(), userHandle);
            } else {
                hasLocationPermission =
                        Util.checkCallerHasCoarseOrFineLocation(
                                mAdapterService, app.getSource(), userHandle);
            }
        }
        var client =
                new ScanClient(
                        app,
                        settings,
                        userHandle,
                        callingPackage.equals(mExposureNotificationPackage),
                        hasDisavowedLocation,
                        hasLocationPermission, // Unacted upon if `hasDisavowedLocation` is true
                        Util.checkCallerHasNetworkSettingsPermission(mAdapterService),
                        Util.checkCallerHasNetworkSetupWizardPermission(mAdapterService),
                        Util.checkCallerHasScanWithoutLocationPermission(mAdapterService),
                        getAssociatedDevices(callingPackage));
        dispatchStartScan(app, client);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    private void startScanInternal(ScannerApp app) {
        // This ScanClient will be billed to the Bluetooth app due to its internal usage
        var client =
                new ScanClient(
                        app,
                        Binder.getCallingUid(),
                        Binder.getCallingUserHandle(),
                        Util.checkCallerHasNetworkSettingsPermission(mAdapterService),
                        Util.checkCallerHasNetworkSetupWizardPermission(mAdapterService),
                        Util.checkCallerHasScanWithoutLocationPermission(mAdapterService));
        dispatchStartScan(app, client);
    }

    private void dispatchStartScan(ScannerApp app, ScanClient client) {
        mScanManager.fetchAppForegroundState(client);
        client.getAppScanStats()
                .recordScanStart(
                        client.getSettings(),
                        client.getFilters(),
                        client.isFiltered(),
                        app.getCallback() != null,
                        client.getScannerId(),
                        app.getAttributionTag());
        mScanManager.startScan(client);
    }

    void registerPiAndStartScan(
            @NonNull PendingIntent pendingIntent,
            @NonNull ScanSettings settings,
            @NonNull List<ScanFilter> filters,
            @NonNull AttributionSource source) {
        enforceScanThread();
        var header = "registerPiAndStartScan(): ";
        settings = BatchScanUtil.enforceReportDelayFloor(settings);
        var callingPackage = source.getPackageName();
        Log.d(TAG, header + "source=" + source);

        // Don't start scan if the Pi scan already in mScannerMap.
        if (mScannerMap.getByPendingIntent(pendingIntent) != null) {
            Log.d(TAG, header + "Ignoring since the same PI scan is already in ScannerMap");
            return;
        }

        var app = mScannerMap.addWithPendingIntent(source, pendingIntent, settings, filters);
        mAppOps.checkPackage(source.getUid(), callingPackage);
        app.setEligibleForSanitizedExposureNotification(
                callingPackage.equals(mExposureNotificationPackage));
        app.setHasDisavowedLocation(
                Util.hasDisavowedLocationForScan(mAdapterService, source, mTestModeEnabled));
        if (!app.getHasDisavowedLocation()) {
            try {
                if (checkCallerTargetSdk(mAdapterService, source, Build.VERSION_CODES.Q)) {
                    app.setHasLocationPermission(
                            Util.checkCallerHasFineLocation(
                                    mAdapterService, source, app.getUserHandle()));
                } else {
                    app.setHasLocationPermission(
                            Util.checkCallerHasCoarseOrFineLocation(
                                    mAdapterService, source, app.getUserHandle()));
                }
            } catch (SecurityException se) {
                // No need to throw here. Just mark as not granted.
                app.setHasLocationPermission(false);
            }
        }
        app.setHasNetworkSettingsPermission(
                Util.checkCallerHasNetworkSettingsPermission(mAdapterService));
        app.setHasNetworkSetupWizardPermission(
                Util.checkCallerHasNetworkSetupWizardPermission(mAdapterService));
        app.setHasScanWithoutLocationPermission(
                Util.checkCallerHasScanWithoutLocationPermission(mAdapterService));
        app.setAssociatedDevices(getAssociatedDevices(callingPackage));

        mScanManager.registerScanner(app.getUuid());
        // If this fails, we should stop the scan immediately.
        if (!pendingIntent.addCancelListener(Runnable::run, mScanIntentCancelListener)) {
            Log.d(TAG, header + "Stopping scan as the PI scan is already cancelled");
            stopScan(pendingIntent);
        }
    }

    private void dispatchPendingIntentStartScan(ScannerApp app) {
        var client = new ScanClient(app);
        mScanManager.fetchAppForegroundState(client);
        client.getAppScanStats()
                .recordScanStart(
                        app.getSettings(),
                        app.getFilters(),
                        client.isFiltered(),
                        false,
                        app.getScannerId(),
                        app.getAttributionTag());
        mScanManager.startScan(client);
    }

    void flushPendingBatchResults(int scannerId) {
        enforceScanThread();
        var client = ScanUtil.findById(mScanManager.getBatchScanQueue(), scannerId);
        if (client == null) {
            Log.e(TAG, "Unexpectedly cannot find batch scan client for scannerId=" + scannerId);
            return;
        }
        mScanManager.flushBatchScanResults(client);
    }

    public void stopScan(int scannerId) {
        enforceScanThread();
        int regularScanQueueSize = mScanManager.getRegularScanQueue().size();
        int batchScanQueueSize = mScanManager.getBatchScanQueue().size();
        Log.d(
                TAG,
                ("stopScan(scannerId=" + scannerId + "): ")
                        + ("regularScanQueueSize=" + regularScanQueueSize)
                        + (", batchScanQueueSize=" + batchScanQueueSize));
        var appScanStats = mScannerMap.getAppScanStatsById(scannerId);
        if (appScanStats != null) {
            appScanStats.recordScanStop(scannerId);
        }
        mScanManager.stopScan(scannerId);
    }

    void stopScan(PendingIntent intent) {
        enforceScanThread();
        var app = mScannerMap.getByPendingIntent(intent);
        if (app == null) {
            Log.e(TAG, "stopScan(PendingIntent): App not found for intent=" + intent);
            return;
        }
        Log.v(TAG, "stopScan(PendingIntent): For " + app + " with scannerId=" + app.getScannerId());
        intent.removeCancelListener(mScanIntentCancelListener);
        stopScan(app.getScannerId());
        unregisterScanner(app.getScannerId());
    }

    int numHwTrackFiltersAvailable() {
        enforceScanThread();
        return mAdapterService.getTotalNumOfTrackableAdvertisements()
                - mScanManager.getCurrentUsedTrackingAdvertisement();
    }

    /**************************************************************************
     * PERIODIC SCANNING
     *************************************************************************/

    public void registerSync(
            BluetoothDevice device,
            int sid,
            int skip,
            int timeout,
            IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.startSync(device, sid, skip, timeout, callback);
    }

    public void registerSync(
            ScanResult scanResult, int skip, int timeout, IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.startSync(scanResult, skip, timeout, callback);
    }

    public void unregisterSync(IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.stopSync(callback);
    }

    public void transferSync(BluetoothDevice device, int serviceData, int syncHandle) {
        enforceScanThread();
        mPeriodicScanManager.transferSync(device, serviceData, syncHandle);
    }

    public void transferSetInfo(
            BluetoothDevice device,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.transferSetInfo(device, serviceData, advHandle, callback);
    }

    /**************************************************************************
     * THREADING
     *************************************************************************/

    void enforceScanThread() {
        if (Util.isInstrumentationTestMode()) return;

        if (!mHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Not on scan thread");
        }
    }

    private void enforceScanThreadIsNotUsed() {
        if (Util.isInstrumentationTestMode()) return;

        if (mHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Must NOT be on scan thread");
        }
    }

    public boolean isOnScanThread() {
        if (Util.isInstrumentationTestMode()) return false;
        return mHandler.getLooper().isCurrentThread();
    }

    public void doOnScanThread(Runnable r) {
        enforceScanThreadIsNotUsed();

        if (!mIsAvailable) return;

        final var posted =
                mHandler.post(
                        () -> {
                            if (mIsAvailable) {
                                r.run();
                            }
                        });
        if (!posted) {
            Log.w(TAG, "Failed to post async task\n" + Log.getStackTraceString(new Throwable()));
        }
    }

    public void forceRunSyncOnScanThread(Runnable r) {
        if (Util.isInstrumentationTestMode()) {
            r.run();
            return;
        }

        enforceScanThreadIsNotUsed();

        final var future = new CompletableFuture<>();
        final var posted =
                mHandler.postAtFrontOfQueue(
                        () -> {
                            r.run();
                            future.complete(null);
                        });
        if (!posted) {
            Log.w(TAG, "Failed to post sync task\n" + Log.getStackTraceString(new Throwable()));
            return;
        }
        try {
            future.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            Log.w(TAG, "Failed to complete sync task: " + e);
        }
    }

    <T> T fetchOnScanThread(Supplier<T> supplier, T defaultValue) {
        enforceScanThreadIsNotUsed();

        if (!mIsAvailable) return defaultValue;

        final var task =
                new FutureTask<>(
                        () -> {
                            if (!mIsAvailable) {
                                return defaultValue;
                            }
                            return supplier.get();
                        });
        if (!mHandler.post(task)) {
            Log.w(TAG, "Failed to post async task\n" + Log.getStackTraceString(new Throwable()));
            return defaultValue;
        }
        try {
            return task.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.w(TAG, "Failed to complete fetch sync task: " + e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            task.cancel(true);
        }
        return defaultValue;
    }

    public void dump(StringBuilder sb) {
        enforceScanThread();
        mScannerMap.dump(sb);
    }
}
