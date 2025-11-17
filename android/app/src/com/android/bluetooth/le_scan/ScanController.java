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

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.le_scan.BatchScanUtil.permittedResults;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_RESULT_TYPE_TRUNCATED;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.util.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.HexEncoding;

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
    private final BluetoothAdapter mAdapter;
    private final AppOpsManager mAppOps;
    private final CompanionDeviceManager mCompanionManager;
    private final ScanBinder mBinder;
    private final ScannerMap mScannerMap;
    private final ScanRadioStats mScanRadioStats;
    private final String mExposureNotificationPackage;
    private final Predicate<ScanResult> mLocationDenylistPredicate;

    // TODO(b/397863857) Used when `Flags.scanControllerThread()` is false. Delete on flag cleanup
    @Nullable private final Looper mMainLooper;

    private final HandlerThread mScanThread;
    private final Looper mScanLooper;
    // TODO(b/397863857) Used when `Flags.scanControllerThread()`. Remove @Nullable on flag cleanup
    @Nullable private final Handler mScanHandler;
    private final ScanManager mScanManager;
    private final ScanSuspendManager mScanSuspendManager;
    private final PeriodicScanManager mPeriodicScanManager;

    private volatile boolean mIsAvailable = true;
    private volatile boolean mTestModeEnabled = false;
    private volatile boolean mIsMsftAdvMonitorEnabled = false;
    private Handler mTestModeHandler;

    public ScanController(
            AdapterService service,
            ScanNativeInterface scanNativeInterface,
            PeriodicScanNativeInterface periodicScanNativeInterface,
            CompanionDeviceManager companionDeviceManager) {
        this(
                service,
                null,
                scanNativeInterface,
                null,
                periodicScanNativeInterface,
                new ScannerMap(),
                companionDeviceManager,
                null,
                TimeProvider.getSystemClock());
    }

    @VisibleForTesting
    ScanController(
            AdapterService service,
            ScanManager scanManager,
            ScanNativeInterface scanNativeInterface,
            PeriodicScanManager periodicScanManager,
            PeriodicScanNativeInterface periodicScanNativeInterface,
            ScannerMap scannerMap,
            CompanionDeviceManager companionDeviceManager,
            @Nullable Looper looper,
            TimeProvider timeProvider) {
        Log.i(TAG, "Created with Flags.scanControllerThread: " + Flags.scanControllerThread());
        mAdapterService = requireNonNull(service);
        mAdapter = mAdapterService.getSystemService(BluetoothManager.class).getAdapter();
        mAppOps = mAdapterService.getSystemService(AppOpsManager.class);
        mCompanionManager = companionDeviceManager;
        mBinder = new ScanBinder(mAdapterService, this);
        mScannerMap = scannerMap;
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
        if (!Flags.scanControllerThread()) {
            mMainLooper = mAdapterService.getMainLooper();
        } else {
            mMainLooper = null;
        }
        mScanThread = new HandlerThread("BluetoothScanManager");
        mScanThread.start();
        mScanLooper = requireNonNullElseGet(looper, mScanThread::getLooper);
        if (Flags.scanControllerThread()) {
            mScanHandler = new Handler(mScanLooper);
        } else {
            mScanHandler = null;
        }
        mScanManager =
                requireNonNullElseGet(
                        scanManager,
                        () ->
                                new ScanManager(
                                        mAdapterService,
                                        this,
                                        scanNativeInterface,
                                        mScanLooper,
                                        timeProvider));
        mScanSuspendManager = new ScanSuspendManager(this, mScanManager, mScanLooper);
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
        if (Flags.scanControllerThread()) {
            mScanHandler.removeCallbacksAndMessages(null);
        }
        forceRunSyncOnScanThread(
                () -> {
                    mBinder.cleanup();
                    mScannerMap.clear();
                    if (Flags.scanControllerThread()) {
                        mScanManager.cleanup();
                        mPeriodicScanManager.cleanup();
                        mScanThread.quitSafely();
                    } else {
                        mScanThread.quitSafely();
                        mScanManager.cleanup();
                        mPeriodicScanManager.cleanup();
                    }
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

    ScanRadioStats getScanRadioStats() {
        enforceScanThread();
        return mScanRadioStats;
    }

    /** Example raw beacons captured from a Blue Charm BC011 */
    private static final String[] TEST_MODE_BEACONS =
            new String[] {
                "020106",
                "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000",
                "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000",
            };

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
                final var looper = Flags.scanControllerThread() ? mScanLooper : mMainLooper;
                mTestModeHandler =
                        new Handler(looper) {
                            public void handleMessage(Message msg) {
                                synchronized (mTestModeLock) {
                                    if (!mTestModeEnabled) {
                                        return;
                                    }
                                    for (String test : TEST_MODE_BEACONS) {
                                        onScanResultInternal(
                                                0x1b,
                                                0x1,
                                                "DD:34:02:05:5C:4D",
                                                1,
                                                0,
                                                0xff,
                                                127,
                                                -54,
                                                0x0,
                                                HexEncoding.decode(test),
                                                "DD:34:02:05:5C:4E");
                                    }
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

    public record PendingIntentInfo(
            PendingIntent intent,
            ScanSettings settings,
            List<ScanFilter> filters,
            String callingPackage,
            int callingUid,
            int callingPid) {
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PendingIntentInfo)) {
                return false;
            }
            return intent.equals(((PendingIntentInfo) other).intent);
        }

        @Override
        public int hashCode() {
            return intent == null ? 0 : intent.hashCode();
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

    private void onScanResultInternal(
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
                "onScanResult() -"
                        + (" eventType=0x" + Integer.toHexString(eventType))
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

        BluetoothDevice device = mAdapter.getRemoteLeDevice(address, addressType);

        var noFilterMatchedClients = new ArrayList<ScanClient>();
        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            var app = mScannerMap.getById(client.getScannerId());
            if (app == null) {
                Log.v(TAG, "App is null for " + client + "; Skip");
                continue;
            }

            final ScanSettings settings = client.getSettings();
            final byte[] scanRecordData;
            boolean isScanResponse = (eventType & ET_SCAN_RESPONSE_MASK) != 0;
            boolean requiresScanResponse =
                    (eventType & ET_SCANNABLE_MASK) != 0
                            && !isScanResponse
                            && !mIsMsftAdvMonitorEnabled;

            if (Flags.supportPassiveScanning()
                    && ((settings.getScanType() == ScanSettings.SCAN_TYPE_ACTIVE
                                    && requiresScanResponse)
                            || (settings.getScanType() == ScanSettings.SCAN_TYPE_PASSIVE
                                    && isScanResponse))) {
                continue;
            }
            // This is for compatibility with applications that assume fixed size scan data.
            if (settings.getLegacy()) {
                if ((eventType & ET_LEGACY_MASK) == 0) {
                    // If this is legacy scan, but nonlegacy result - skip.
                    Log.v(TAG, "Legacy scan, non legacy result; Skip");
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
                    Log.i(TAG, "Location deny list for " + client + "; Skip");
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
                Log.v(TAG, "No permission for " + client + "; Skip");
                continue;
            }
            if (!matchesFilters(client, result, originalAddress)) {
                noFilterMatchedClients.add(client);
                continue;
            }

            final int callbackType = settings.getCallbackType();
            if (!(callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)) {
                Log.v(TAG, "Not CALLBACK_TYPE_ALL_MATCHES for " + client + "; Skip");
                continue;
            }

            try {
                app.getAppScanStats().addResults(client.getScannerId(), 1);
                if (app.getCallback() != null) {
                    app.getCallback().onScanResult(result);
                } else {
                    Log.v(TAG, "Callback null for " + client + "; Send results by pendingIntent");
                    List<ScanResult> results = new ArrayList<>(Arrays.asList(result));
                    sendResultsByPendingIntent(
                            app.getInfo(), results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                }
            } catch (RemoteException | PendingIntent.CanceledException e) {
                Log.e(TAG, "Exception: " + e);
                handleDeadScanClient(client);
            }
        }
        if (!noFilterMatchedClients.isEmpty()) {
            Log.v(TAG, "No filter match for " + noFilterMatchedClients + "; Skip");
        }
    }

    private void sendResultByPendingIntent(
            PendingIntentInfo pii, ScanResult result, int callbackType, ScanClient client) {
        List<ScanResult> results = new ArrayList<>(Arrays.asList(result));
        try {
            sendResultsByPendingIntent(pii, results, callbackType);
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
            PendingIntentInfo pii, List<ScanResult> results, int callbackType)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, new ArrayList<>(results));
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType);
        pii.intent.send(mAdapterService, 0, extrasIntent);
    }

    private void sendErrorByPendingIntent(PendingIntentInfo pii, int errorCode)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, errorCode);
        pii.intent.send(mAdapterService, 0, extrasIntent);
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
            Log.e(TAG, header + "ScannerApp not found in ScannerMap");
            return;
        }
        if (app.getCallback() != null) {
            callbackToApp(() -> app.getCallback().onScannerRegistered(status, scannerId));
        }
        if (status != ScanCallback.NO_ERROR) {
            if (Flags.scanRegisterAndStart()) {
                unregisterScanner(scannerId);
            } else {
                mScannerMap.remove(uuid);
            }
            return;
        }
        app.setId(scannerId);
        // TODO(b/455057044) Delete the comment below on flag cleanup
        // If app is callback based, setup a death recipient. App will initiate the start.
        // Otherwise, if PendingIntent based, start the scan directly.
        if (app.getCallback() != null) {
            var message = "Unregister " + scannerId + " for " + app;
            Runnable onDeathAction = () -> doOnScanThread(() -> handleDeadScanClient(scannerId));
            app.linkToDeath(new ActionOnDeathRecipient(TAG, message, onDeathAction));
            if (Flags.scanRegisterAndStart()) {
                if (app.isInternal()) {
                    startScanInternal(scannerId, app.getSettings(), app.getFilters());
                } else {
                    startScan(scannerId, app.getSettings(), app.getFilters(), app.getSource());
                }
            }
        } else {
            dispatchPendingIntentStartScan(scannerId, app);
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
        if (Flags.rssiScanFilter()) {
            ScanSettings settings = client.getSettings();
            if (scanResult.getRssi() < settings.getRssiThreshold()) {
                return false;
            }
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
            if (Flags.originalAddressFilterMatch()) {
                if (originalAddress != null
                        && originalAddress.equalsIgnoreCase(filter.getDeviceAddress())
                        && filter.matchesWithoutAddress(scanResult)) {
                    return true;
                }
            } else {
                if (originalAddress != null
                        && originalAddress.equalsIgnoreCase(filter.getDeviceAddress())) {
                    return true;
                }
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
        client.ifAppScanStatsPresent(stats -> stats.setAppDead(true));
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
            var app = mScannerMap.getById(scannerId);
            if (app == null) {
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
                mScanManager.callbackDone(scannerId, status);
                return;
            }

            if (app.getCallback() != null) {
                callbackToApp(() -> app.getCallback().onBatchScanResults(permittedResults));
                mScanManager.batchScanResultDelivered();
            } else {
                // PendingIntent based
                try {
                    sendResultsByPendingIntent(
                            app.getInfo(),
                            permittedResults,
                            ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Error sending result via PendingIntent: " + e);
                    handleDeadScanClient(client);
                }
            }
        } else {
            for (ScanClient client : mScanManager.getFullBatchScanQueue()) {
                // Deliver results for each client.
                deliverBatchScan(client, results);
            }
        }
        mScanManager.callbackDone(scannerId, status);
    }

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults) {
        var app = mScannerMap.getById(client.getScannerId());
        if (app == null) {
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
            app.getAppScanStats().addResults(client.getScannerId(), results.size());
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
                        app.getInfo(), results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
        } catch (RemoteException | PendingIntent.CanceledException e) {
            Log.e(TAG, "Exception: " + e);
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
        Log.d(
                TAG,
                "onTrackAdvFoundLost() -"
                        + (" scannerId=" + trackingInfo.scannerId())
                        + (", address=" + trackingInfo.address())
                        + (", addressType=" + trackingInfo.addressType())
                        + (", adv_state=" + trackingInfo.advState()));

        var app = mScannerMap.getById(trackingInfo.scannerId());
        if (app == null) {
            Log.e(TAG, "app is null");
            return;
        }

        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(trackingInfo.address(), trackingInfo.addressType());
        int advertiserState = trackingInfo.advState();
        ScanResult result =
                new ScanResult(
                        device,
                        ScanRecord.parseFromBytes(trackingInfo.getResult()),
                        trackingInfo.rssiValue(),
                        SystemClock.elapsedRealtimeNanos());

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.getScannerId() == trackingInfo.scannerId()) {
                ScanSettings settings = client.getSettings();
                if ((advertiserState == ADVT_STATE_ONFOUND)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                                != 0)) {
                    if (app.getCallback() != null) {
                        callbackToApp(() -> app.getCallback().onFoundOrLost(true, result));
                    } else {
                        sendResultByPendingIntent(
                                app.getInfo(),
                                result,
                                ScanSettings.CALLBACK_TYPE_FIRST_MATCH,
                                client);
                    }
                } else if ((advertiserState == ADVT_STATE_ONLOST)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                                != 0)) {
                    if (app.getCallback() != null) {
                        callbackToApp(() -> app.getCallback().onFoundOrLost(false, result));
                    } else {
                        sendResultByPendingIntent(
                                app.getInfo(),
                                result,
                                ScanSettings.CALLBACK_TYPE_MATCH_LOST,
                                client);
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
        Log.d(TAG, "onScanParamSetupCompleted() - scannerId=" + scannerId + ", status=" + status);
        var app = mScannerMap.getById(scannerId);
        if (app == null || app.getCallback() == null) {
            Log.e(TAG, "Advertise app or callback is null");
            return;
        }
    }

    // callback from ScanManager for dispatch of errors apps.
    void onScanManagerErrorCallback(int scannerId, int errorCode) {
        enforceScanThread();
        var app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, "App null");
            return;
        }
        if (app.getCallback() != null) {
            callbackToApp(() -> app.getCallback().onScanManagerErrorCallback(errorCode));
        } else {
            try {
                sendErrorByPendingIntent(app.getInfo(), errorCode);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Error sending error code via PendingIntent: " + e);
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
        }
    }

    // TODO(b/455057044) Delete on flag cleanup
    void registerScanner(
            IScannerCallback callback,
            WorkSource workSource,
            AttributionSource source,
            boolean hasPrivilegedPermission) {
        enforceScanThread();
        var uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        var appScanStats = mScannerMap.getAppScanStatsByUid(uid);
        if (appScanStats != null
                && appScanStats.isScanningTooFrequently()
                && !hasPrivilegedPermission) {
            Log.e(TAG, "registerScanner(): " + appScanStats + " is scanning too frequently");
            try {
                callback.onScannerRegistered(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
            return;
        }
        registerScannerInternal(callback, workSource, source);
    }

    void registerAndStartScan(
            IScannerCallback callback,
            WorkSource workSource,
            AttributionSource source,
            boolean hasPrivilegedPermission,
            ScanSettings settings,
            List<ScanFilter> filters) {
        enforceScanThread();
        var uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        var appScanStats = mScannerMap.getAppScanStatsByUid(uid);
        if (appScanStats != null
                && appScanStats.isScanningTooFrequently()
                && !hasPrivilegedPermission) {
            Log.e(TAG, "registerAndStartScan(): " + appScanStats + " is scanning too frequently");
            try {
                callback.onScannerRegistered(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
            return;
        }
        registerAndStartScan(
                uid, callback, workSource, source, settings, filters, /* isInternal */ false);
    }

    // TODO(b/455057044) Delete on flag cleanup
    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void registerScannerInternal(
            IScannerCallback callback, WorkSource workSource, AttributionSource source) {
        enforceScanThread();
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        final int pid = Flags.scanControllerThread() ? source.getPid() : Binder.getCallingPid();
        final var appName = Util.appNameOrUnknown(mAdapterService, uid);
        final var uuid = UUID.randomUUID();
        Log.d(
                TAG,
                ("registerScanner(): uid=" + uid + ", pid=" + uid + ", ")
                        + ("app=" + appName + ", UUID=" + uuid));
        mScannerMap.addWithCallback(
                uid, pid, appName, uuid, source, workSource, callback, mAdapterService, false);
        mScanManager.registerScanner(uuid);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void registerAndStartScanInternal(
            IScannerCallback callback,
            AttributionSource source,
            ScanSettings settings,
            List<ScanFilter> filters) {
        enforceScanThread();
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        registerAndStartScan(uid, callback, null, source, settings, filters, /* isInternal */ true);
    }

    private void registerAndStartScan(
            int uid,
            IScannerCallback callback,
            WorkSource workSource,
            AttributionSource source,
            ScanSettings settings,
            List<ScanFilter> filters,
            boolean isInternal) {
        final int pid = Flags.scanControllerThread() ? source.getPid() : Binder.getCallingPid();
        final var appName = Util.appNameOrUnknown(mAdapterService, uid);
        final var uuid = UUID.randomUUID();
        Log.d(
                TAG,
                ("registerAndStartScan(): uid=" + uid + ", pid=" + uid + ", app=" + appName)
                        + (", UUID=" + uuid + ", settings=" + ScanUtil.toStringShort(settings))
                        + (", filters=" + filters + ", isInternal=" + isInternal));
        mScannerMap.addWithCallback(
                uid,
                pid,
                appName,
                uuid,
                source,
                workSource,
                callback,
                settings,
                filters,
                mAdapterService,
                isInternal);
        mScanManager.registerScanner(uuid);
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

    // TODO(b/455057044) Make private on cleanup
    void startScan(
            int scannerId,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource source) {
        enforceScanThread();
        Log.d(TAG, "Start scan with filters");
        String callingPackage = source.getPackageName();
        settings = BatchScanUtil.enforceReportDelayFloor(settings);
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        mAppOps.checkPackage(uid, callingPackage);
        var hasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(mAdapterService, source, mTestModeEnabled);
        var isQApp = checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q);
        var userHandle = Binder.getCallingUserHandle();
        var hasLocationPermission = false; // Unacted upon if `hasDisavowedLocation` is true
        if (!hasDisavowedLocation) {
            if (isQApp) {
                hasLocationPermission =
                        Utils.checkCallerHasFineLocation(mAdapterService, source, userHandle);
            } else {
                hasLocationPermission =
                        Utils.checkCallerHasCoarseOrFineLocation(
                                mAdapterService, source, userHandle);
            }
        }
        var client =
                new ScanClient(
                        uid,
                        scannerId,
                        settings,
                        filters,
                        userHandle,
                        callingPackage.equals(mExposureNotificationPackage),
                        hasDisavowedLocation,
                        hasLocationPermission, // Unacted upon if `hasDisavowedLocation` is true
                        Util.checkCallerHasNetworkSettingsPermission(mAdapterService),
                        Util.checkCallerHasNetworkSetupWizardPermission(mAdapterService),
                        Util.checkCallerHasScanWithoutLocationPermission(mAdapterService),
                        getAssociatedDevices(callingPackage));
        dispatchStartScan(client);
    }

    // TODO(b/455057044) Make private on cleanup
    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void startScanInternal(int scannerId, ScanSettings settings, List<ScanFilter> filters) {
        enforceScanThread(); // TODO(b/455057044) Remove on cleanup
        // This ScanClient will be billed to the Bluetooth app due to its internal usage
        var client =
                new ScanClient(
                        Binder.getCallingUid(),
                        scannerId,
                        settings,
                        filters,
                        Binder.getCallingUserHandle(),
                        Util.checkCallerHasNetworkSettingsPermission(mAdapterService),
                        Util.checkCallerHasNetworkSetupWizardPermission(mAdapterService),
                        Util.checkCallerHasScanWithoutLocationPermission(mAdapterService));
        dispatchStartScan(client);
    }

    private void dispatchStartScan(ScanClient client) {
        var appScanStats = mScannerMap.getAppScanStatsById(client.getScannerId());
        if (appScanStats != null) {
            client.setAppScanStats(appScanStats);
            mScanManager.fetchAppForegroundState(client);
            boolean isCallbackScan = false;
            var app = mScannerMap.getById(client.getScannerId());
            if (app != null) {
                isCallbackScan = app.getCallback() != null;
            }
            appScanStats.recordScanStart(
                    client.getSettings(),
                    client.getFilters(),
                    client.isFiltered(),
                    isCallbackScan,
                    client.getScannerId(),
                    app == null ? null : app.getAttributionTag());
        }
        mScanManager.startScan(client);
    }

    void registerPiAndStartScan(
            PendingIntent pendingIntent,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource source) {
        enforceScanThread();
        var header = "registerPiAndStartScan(): ";
        settings = BatchScanUtil.enforceReportDelayFloor(settings);
        UUID uuid = UUID.randomUUID();
        String callingPackage = source.getPackageName();
        int callingUid = source.getUid();
        int callingPid = source.getPid();
        PendingIntentInfo piInfo =
                new PendingIntentInfo(
                        pendingIntent, settings, filters, callingPackage, callingUid, callingPid);
        Log.d(
                TAG,
                header
                        + ("UUID=" + uuid + " package=" + callingPackage)
                        + (" uid=" + callingUid + " pid=" + callingPid));

        // Don't start scan if the Pi scan already in mScannerMap.
        if (mScannerMap.getByPendingIntentInfo(pendingIntent) != null) {
            Log.d(TAG, header + "Ignoring since the same PI scan is already in ScannerMap");
            return;
        }

        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        var app =
                mScannerMap.addWithPendingIntent(
                        Util.appNameOrUnknown(mAdapterService, callingUid),
                        uuid,
                        UserHandle.getUserHandleForUid(uid),
                        source,
                        piInfo,
                        settings,
                        filters,
                        mAdapterService);
        mAppOps.checkPackage(uid, callingPackage);
        app.setEligibleForSanitizedExposureNotification(
                callingPackage.equals(mExposureNotificationPackage));
        app.setHasDisavowedLocation(
                Utils.hasDisavowedLocationForScan(mAdapterService, source, mTestModeEnabled));
        if (!app.getHasDisavowedLocation()) {
            try {
                if (checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q)) {
                    app.setHasLocationPermission(
                            Utils.checkCallerHasFineLocation(
                                    mAdapterService, source, app.getUserHandle()));
                } else {
                    app.setHasLocationPermission(
                            Utils.checkCallerHasCoarseOrFineLocation(
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

        mScanManager.registerScanner(uuid);
        // If this fails, we should stop the scan immediately.
        if (!pendingIntent.addCancelListener(Runnable::run, mScanIntentCancelListener)) {
            Log.d(TAG, header + "Stopping scan as the PI scan is already cancelled");
            stopScan(pendingIntent);
        }
    }

    @VisibleForTesting
    void dispatchPendingIntentStartScan(int scannerId, ScannerApp app) {
        final PendingIntentInfo piInfo = app.getInfo();
        var client = new ScanClient(scannerId, piInfo, app);
        var appScanStats = mScannerMap.getAppScanStatsById(scannerId);
        if (appScanStats != null) {
            client.setAppScanStats(appScanStats);
            mScanManager.fetchAppForegroundState(client);
            appScanStats.recordScanStart(
                    piInfo.settings,
                    piInfo.filters,
                    client.isFiltered(),
                    false,
                    scannerId,
                    app.getAttributionTag());
        }
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
        var app = mScannerMap.getByPendingIntentInfo(intent);
        if (app == null) {
            Log.e(TAG, "stopScan(PendingIntent): Cannot find app for intent=" + intent);
            return;
        }
        var scannerId = app.getId();
        Log.v(TAG, "stopScan(PendingIntent): For " + app + " with scannerId=" + scannerId);
        intent.removeCancelListener(mScanIntentCancelListener);
        stopScan(scannerId);
        unregisterScanner(scannerId);
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

    public void transferSync(BluetoothDevice bda, int serviceData, int syncHandle) {
        enforceScanThread();
        mPeriodicScanManager.transferSync(bda, serviceData, syncHandle);
    }

    public void transferSetInfo(
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.transferSetInfo(bda, serviceData, advHandle, callback);
    }

    int numHwTrackFiltersAvailable() {
        enforceScanThread();
        return mAdapterService.getTotalNumOfTrackableAdvertisements()
                - mScanManager.getCurrentUsedTrackingAdvertisement();
    }

    void enforceScanThread() {
        if (!Flags.scanControllerThread() || Utils.isInstrumentationTestMode()) return;

        if (!mScanHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Not on scan thread");
        }
    }

    private void enforceScanThreadIsNotUsed() {
        if (!Flags.scanControllerThread() || Utils.isInstrumentationTestMode()) return;

        if (mScanHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Must NOT be on scan thread");
        }
    }

    public boolean isOnScanThread() {
        if (!Flags.scanControllerThread() || Utils.isInstrumentationTestMode()) return false;
        return mScanHandler.getLooper().isCurrentThread();
    }

    public void doOnScanThread(Runnable r) {
        if (!Flags.scanControllerThread()) {
            r.run();
            return;
        }

        enforceScanThreadIsNotUsed();

        if (!mIsAvailable) return;

        final var posted =
                mScanHandler.post(
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
        if (!Flags.scanControllerThread() || Utils.isInstrumentationTestMode()) {
            r.run();
            return;
        }

        enforceScanThreadIsNotUsed();

        final var future = new CompletableFuture<>();
        final var posted =
                mScanHandler.postAtFrontOfQueue(
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
        if (!Flags.scanControllerThread()) {
            return supplier.get();
        }

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
        if (!mScanHandler.post(task)) {
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
        mScannerMap.dump(sb, mScanManager.getSettingsMap());
    }
}
