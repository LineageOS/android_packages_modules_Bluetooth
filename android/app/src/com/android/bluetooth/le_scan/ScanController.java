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

import static android.bluetooth.BluetoothUtils.extractBytes;

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.getSystemClock;
import static com.android.bluetooth.le_scan.ScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS;
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
import android.provider.DeviceConfig;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.util.NumberUtils;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.HexEncoding;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String TAG = ScanController.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    // Batch scan related constants.
    private static final int TRUNCATED_RESULT_SIZE = 11;

    // onFoundLost related constants
    @VisibleForTesting static final int ADVT_STATE_ONFOUND = 0;
    private static final int ADVT_STATE_ONLOST = 1;

    private static final int ET_SCANNABLE_MASK = 0x02;
    private static final int ET_SCAN_RESPONSE_MASK = 0x08;
    private static final int ET_LEGACY_MASK = 0x10;

    private final PendingIntent.CancelListener mScanIntentCancelListener =
            intent -> {
                Log.d(TAG, "scanning PendingIntent canceled");
                stopScan(intent);
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
                getSystemClock());
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
        return mScannerMap;
    }

    ScanRadioStats getScanRadioStats() {
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
        Log.d(TAG, "onDisplayChanged() screen on: " + screenOn);
        mScanManager.onDisplayChanged(screenOn);
    }

    /** onSystemSuspendChanged notifies ScanSuspendManager when the system suspends and resumes. */
    public void onSystemSuspendChanged(boolean suspended) {
        Log.d(TAG, "onSystemSuspendChanged() suspended: " + suspended);
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

    record PendingIntentInfo(
            PendingIntent intent,
            ScanSettings settings,
            List<ScanFilter> filters,
            String callingPackage,
            int callingUid) {
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

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    // EN format defined here:
    // https://blog.google/documents/70/Exposure_Notification_-_Bluetooth_Specification_v1.2.2.pdf
    private static final byte[] EXPOSURE_NOTIFICATION_FLAGS_PREAMBLE =
            new byte[] {
                // size 2, flag field, flags byte (value is not important)
                (byte) 0x02, (byte) 0x01
            };

    private static final int EXPOSURE_NOTIFICATION_FLAGS_LENGTH = 0x2 + 1;
    private static final byte[] EXPOSURE_NOTIFICATION_PAYLOAD_PREAMBLE =
            new byte[] {
                // size 3, complete 16 bit UUID, EN UUID
                (byte) 0x03, (byte) 0x03, (byte) 0x6F, (byte) 0xFD,
                // size 23, data for 16 bit UUID, EN UUID
                (byte) 0x17, (byte) 0x16, (byte) 0x6F, (byte) 0xFD,
                // ...payload
            };
    private static final int EXPOSURE_NOTIFICATION_PAYLOAD_LENGTH = 0x03 + 0x17 + 2;

    private static boolean arrayStartsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (prefix[i] != array[i]) {
                return false;
            }
        }
        return true;
    }

    private static ScanResult getSanitizedExposureNotification(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        // Remove the flags part of the payload, if present
        if (record.getBytes().length > EXPOSURE_NOTIFICATION_FLAGS_LENGTH
                && arrayStartsWith(record.getBytes(), EXPOSURE_NOTIFICATION_FLAGS_PREAMBLE)) {
            record =
                    ScanRecord.parseFromBytes(
                            Arrays.copyOfRange(
                                    record.getBytes(),
                                    EXPOSURE_NOTIFICATION_FLAGS_LENGTH,
                                    record.getBytes().length));
        }

        if (record.getBytes().length != EXPOSURE_NOTIFICATION_PAYLOAD_LENGTH) {
            return null;
        }
        if (!arrayStartsWith(record.getBytes(), EXPOSURE_NOTIFICATION_PAYLOAD_PREAMBLE)) {
            return null;
        }

        return new ScanResult(null, 0, 0, 0, 0, 0, result.getRssi(), 0, record, 0);
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
        String identityAddress = Utils.getBrEdrAddress(address, mAdapterService);

        if (!address.equals(identityAddress)) {
            Log.v(
                    TAG,
                    "found identityAddress of "
                            + address
                            + ", replace originalAddress as "
                            + identityAddress);
            originalAddress = identityAddress;
        }

        byte[] legacyAdvData = Arrays.copyOfRange(advData, 0, 62);

        BluetoothDevice device = mAdapter.getRemoteLeDevice(address, addressType);

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            ScannerMap.ScannerApp app = mScannerMap.getById(client.getScannerId());
            if (app == null) {
                Log.v(TAG, "App is null; skip.");
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
                    Log.v(TAG, "Legacy scan, non legacy result; skip.");
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
                    Log.i(TAG, "Skipping " + client + " for location deny list");
                    continue;
                }
            }

            var hasPermission = hasScanResultPermission(client);
            if (!hasPermission) {
                for (String associatedDevice : client.getAssociatedDevices()) {
                    if (associatedDevice.equalsIgnoreCase(address)) {
                        hasPermission = true;
                        break;
                    }
                }
            }
            if (!hasPermission && client.isEligibleForSanitizedExposureNotification()) {
                ScanResult sanitized = getSanitizedExposureNotification(result);
                if (sanitized != null) {
                    hasPermission = true;
                    result = sanitized;
                }
            }
            if (!hasPermission) {
                Log.v(TAG, "Skipping client: No permission");
                continue;
            }
            if (!matchesFilters(client, result, originalAddress)) {
                Log.v(TAG, "Skipping client: No filter match");
                continue;
            }

            final int callbackType = settings.getCallbackType();
            if (!(callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)) {
                Log.v(TAG, "Skipping client: Not CALLBACK_TYPE_ALL_MATCHES");
                continue;
            }

            try {
                app.mAppScanStats.addResult(client.getScannerId());
                if (app.mCallback != null) {
                    app.mCallback.onScanResult(result);
                } else {
                    Log.v(TAG, "Callback is null, sending scan results by pendingIntent");
                    List<ScanResult> results = new ArrayList<>(Arrays.asList(result));
                    sendResultsByPendingIntent(
                            app.mInfo, results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                }
            } catch (RemoteException | PendingIntent.CanceledException e) {
                Log.e(TAG, "Exception: " + e);
                handleDeadScanClient(client);
            }
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
    void onScannerRegistered(int status, int scannerId, long uuidLsb, long uuidMsb) {
        enforceScanThread();
        final var uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(
                TAG,
                "onScannerRegistered() -"
                        + (" UUID=" + uuid)
                        + (", scannerId=" + scannerId)
                        + (", status=" + status));

        // First check the callback map
        ScannerMap.ScannerApp scannerApp = mScannerMap.getByUuid(uuid);
        if (scannerApp == null) {
            return;
        }
        if (scannerApp.mCallback != null) {
            callbackToApp(() -> scannerApp.mCallback.onScannerRegistered(status, scannerId));
        }
        if (status != ScanCallback.NO_ERROR) {
            mScannerMap.remove(uuid);
            return;
        }
        scannerApp.mId = scannerId;
        // If app is callback based, setup a death recipient. App will initiate the start.
        // Otherwise, if PendingIntent based, start the scan directly.
        if (scannerApp.mCallback != null) {
            scannerApp.linkToDeath(new ScannerDeathRecipient(scannerId, scannerApp.mName));
        } else {
            continuePiStartScan(scannerId, scannerApp);
        }
    }

    /** Determines if the given scan client has the appropriate permissions to receive callbacks. */
    private boolean hasScanResultPermission(final ScanClient client) {
        if (client.isInternalClient()) {
            // Bypass permission check for internal clients
            return true;
        }
        if (client.getHasNetworkSettingsPermission()
                || client.getHasNetworkSetupWizardPermission()
                || client.getHasScanWithoutLocationPermission()
                || client.getHasDisavowedLocation()) {
            return true;
        }
        return client.getHasLocationPermission()
                && !Utils.blockedByLocationOff(mAdapterService, client.getUserHandle());
    }

    private List<ScanResult> permittedResults(final ScanClient client, Set<ScanResult> results) {
        if (hasScanResultPermission(client)) {
            return new ArrayList<>(results);
        }

        List<ScanResult> permittedResults = new ArrayList<>();
        for (ScanResult scanResult : results) {
            for (String associatedDevice : client.getAssociatedDevices()) {
                if (associatedDevice.equalsIgnoreCase(scanResult.getDevice().getAddress())) {
                    permittedResults.add(scanResult);
                }
            }
        }
        return permittedResults;
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
        if (client.getFilters().isEmpty()) {
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

    private void handleDeadScanClient(ScanClient client) {
        if (client.getAppDied()) {
            Log.w(TAG, "Already dead " + client);
            return;
        }
        client.setAppDied(true);
        client.getAppScanStats().ifPresent(stats -> stats.mIsAppDead = true);
        stopScan(client.getScannerId());
    }

    /** Callback method for scan filter enablement/disablement. */
    void onScanFilterEnableDisabled(int action, int status, int clientIf) {
        enforceScanThread();
        Log.d(
                TAG,
                "onScanFilterEnableDisabled() -"
                        + (" clientIf=" + clientIf)
                        + (", status=" + status)
                        + (", action=" + action));
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter params. */
    void onScanFilterParamsConfigured(int action, int status, int clientIf, int availableSpace) {
        enforceScanThread();
        Log.d(
                TAG,
                "onScanFilterParamsConfigured() -"
                        + (" clientIf=" + clientIf)
                        + (", status=" + status)
                        + (", action=" + action)
                        + (", availableSpace=" + availableSpace));
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter. */
    void onScanFilterConfig(
            int action, int status, int clientIf, int filterType, int availableSpace) {
        enforceScanThread();
        Log.d(
                TAG,
                "onScanFilterConfig() -"
                        + (" clientIf=" + clientIf)
                        + (", action= " + action)
                        + (" status= " + status)
                        + (", filterType=" + filterType)
                        + (", availableSpace=" + availableSpace));
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of batch scan storage. */
    void onBatchScanStorageConfigured(int status, int clientIf) {
        enforceScanThread();
        Log.d(TAG, "onBatchScanStorageConfigured() - clientIf=" + clientIf + ", status=" + status);
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for start/stop of batch scan. */
    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped.
    void onBatchScanStartStopped(int startStopAction, int status, int clientIf) {
        enforceScanThread();
        Log.d(
                TAG,
                "onBatchScanStartStopped() -"
                        + (" clientIf=" + clientIf)
                        + (", status=" + status)
                        + (", startStopAction=" + startStopAction));
        mScanManager.callbackDone(clientIf, status);
    }

    private ScanClient findScanClientById(int clientIf) {
        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.getScannerId() == clientIf) {
                return client;
            }
        }
        for (ScanClient client : mScanManager.getBatchScanQueue()) {
            if (client.getScannerId() == clientIf) {
                return client;
            }
        }
        return null;
    }

    private ScanClient findBatchScanClientById(int scannerId) {
        for (ScanClient client : mScanManager.getBatchScanQueue()) {
            if (client.getScannerId() == scannerId) {
                return client;
            }
        }
        return null;
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
        Log.d(
                TAG,
                "onBatchScanReports() -"
                        + (" scannerId=" + scannerId)
                        + (", status=" + status)
                        + (", reportType=" + reportType)
                        + (", numRecords=" + numRecords));

        Set<ScanResult> results = parseBatchScanResults(numRecords, reportType, recordData);
        if (reportType == SCAN_RESULT_TYPE_TRUNCATED) {
            // We only support single client for truncated mode.
            ScannerMap.ScannerApp app = mScannerMap.getById(scannerId);
            if (app == null) {
                return;
            }

            ScanClient client = findBatchScanClientById(scannerId);
            if (client == null) {
                return;
            }

            List<ScanResult> permittedResults = permittedResults(client, results);

            if (client.getHasDisavowedLocation()) {
                permittedResults.removeIf(mLocationDenylistPredicate);
            }
            if (permittedResults.isEmpty()) {
                mScanManager.callbackDone(scannerId, status);
                return;
            }

            if (app.mCallback != null) {
                callbackToApp(() -> app.mCallback.onBatchScanResults(permittedResults));
                mScanManager.batchScanResultDelivered();
            } else {
                // PendingIntent based
                try {
                    sendResultsByPendingIntent(
                            app.mInfo, permittedResults, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
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

    private Set<ScanResult> parseBatchScanResults(
            int numRecords, int reportType, byte[] batchRecord) {
        if (numRecords == 0) {
            return Collections.emptySet();
        }
        Log.d(
                TAG,
                ("Parsing " + numRecords + " batch scan results at " + Utils.getLocalTimeString())
                        + (" (elapsed: " + SystemClock.elapsedRealtime() + "ms)"));
        if (reportType == SCAN_RESULT_TYPE_TRUNCATED) {
            return parseTruncatedResults(numRecords, batchRecord);
        } else {
            return parseFullResults(numRecords, batchRecord);
        }
    }

    private Set<ScanResult> parseTruncatedResults(int numRecords, byte[] batchRecord) {
        Set<ScanResult> results = new HashSet<>(numRecords);
        long now = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < numRecords; ++i) {
            byte[] record =
                    extractBytes(batchRecord, i * TRUNCATED_RESULT_SIZE, TRUNCATED_RESULT_SIZE);
            byte[] address = extractBytes(record, 0, 6);
            Utils.reverse(address);
            BluetoothDevice device =
                    mAdapterService.getRemoteDevice(Utils.getAddressStringFromByte(address));
            int rssi = record[8];
            long timestampNanos = now - parseTimestampNanos(extractBytes(record, 9, 2));
            results.add(
                    new ScanResult(
                            device, ScanRecord.parseFromBytes(new byte[0]), rssi, timestampNanos));
        }
        return results;
    }

    @VisibleForTesting
    long parseTimestampNanos(byte[] data) {
        long timestampUnit = NumberUtils.littleEndianByteArrayToInt(data);
        // Timestamp is in every 50 ms.
        return TimeUnit.MILLISECONDS.toNanos(timestampUnit * 50);
    }

    private Set<ScanResult> parseFullResults(int numRecords, byte[] batchRecord) {
        Set<ScanResult> results = new HashSet<>(numRecords);
        int position = 0;
        long now = SystemClock.elapsedRealtimeNanos();
        while (position < batchRecord.length) {
            byte[] address = extractBytes(batchRecord, position, 6);
            // TODO: remove temp hack.
            Utils.reverse(address);
            BluetoothDevice device =
                    mAdapterService.getRemoteDevice(Utils.getAddressStringFromByte(address));
            position += 6;
            // Skip address type.
            position++;
            // Skip tx power level.
            position++;
            int rssi = batchRecord[position++];
            long timestampNanos = now - parseTimestampNanos(extractBytes(batchRecord, position, 2));
            position += 2;

            // Combine advertise packet and scan response packet.
            int advertisePacketLen = batchRecord[position++];
            byte[] advertiseBytes = extractBytes(batchRecord, position, advertisePacketLen);
            position += advertisePacketLen;
            int scanResponsePacketLen = batchRecord[position++];
            byte[] scanResponseBytes = extractBytes(batchRecord, position, scanResponsePacketLen);
            position += scanResponsePacketLen;
            byte[] scanRecord = new byte[advertisePacketLen + scanResponsePacketLen];
            System.arraycopy(advertiseBytes, 0, scanRecord, 0, advertisePacketLen);
            System.arraycopy(
                    scanResponseBytes, 0, scanRecord, advertisePacketLen, scanResponsePacketLen);
            results.add(
                    new ScanResult(
                            device, ScanRecord.parseFromBytes(scanRecord), rssi, timestampNanos));
        }
        return results;
    }

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults) {
        ScannerMap.ScannerApp app = mScannerMap.getById(client.getScannerId());
        if (app == null) {
            return;
        }

        List<ScanResult> permittedResults = permittedResults(client, allResults);

        if (client.getFilters().isEmpty()) {
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

    private void sendBatchScanResults(
            ScannerMap.ScannerApp app, ScanClient client, List<ScanResult> results) {
        if (results.isEmpty()) {
            return;
        }
        try {
            app.mAppScanStats.addResults(client.getScannerId(), results.size());
            if (app.mCallback != null) {
                if (ScanUtil.isAutoBatchScanClientEnabled(client)) {
                    Log.d(TAG, "sendBatchScanResults() to onScanResult() for " + client);
                    for (ScanResult result : results) {
                        app.mCallback.onScanResult(result);
                    }
                } else {
                    Log.d(TAG, "sendBatchScanResults() to onBatchScanResults() for " + client);
                    app.mCallback.onBatchScanResults(results);
                }
            } else {
                sendResultsByPendingIntent(
                        app.mInfo, results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
        } catch (RemoteException | PendingIntent.CanceledException e) {
            Log.e(TAG, "Exception: " + e);
            handleDeadScanClient(client);
        }
        mScanManager.batchScanResultDelivered();
    }

    void onBatchScanThresholdCrossed(int clientIf) {
        enforceScanThread();
        Log.d(TAG, "onBatchScanThresholdCrossed() - clientIf=" + clientIf);
        flushPendingBatchResults(clientIf);
    }

    AdvtFilterOnFoundOnLostInfo createOnTrackAdvFoundLostObject(
            int clientIf,
            int advPacketLen,
            byte[] advPacket,
            int scanResponseLen,
            byte[] scanResponse,
            int filtIndex,
            int advState,
            int advInfoPresent,
            String address,
            int addrType,
            int txPower,
            int rssiValue,
            int timeStamp) {
        return new AdvtFilterOnFoundOnLostInfo(
                clientIf,
                advPacketLen,
                ByteString.copyFrom(advPacket),
                scanResponseLen,
                ByteString.copyFrom(scanResponse),
                filtIndex,
                advState,
                advInfoPresent,
                address,
                addrType,
                txPower,
                rssiValue,
                timeStamp);
    }

    void onTrackAdvFoundLost(AdvtFilterOnFoundOnLostInfo trackingInfo) {
        enforceScanThread();
        Log.d(
                TAG,
                "onTrackAdvFoundLost() -"
                        + (" scannerId=" + trackingInfo.clientIf())
                        + (", address=" + trackingInfo.address())
                        + (", addressType=" + trackingInfo.addressType())
                        + (", adv_state=" + trackingInfo.advState()));

        final ScannerMap.ScannerApp app = mScannerMap.getById(trackingInfo.clientIf());
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
            if (client.getScannerId() == trackingInfo.clientIf()) {
                ScanSettings settings = client.getSettings();
                if ((advertiserState == ADVT_STATE_ONFOUND)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                                != 0)) {
                    if (app.mCallback != null) {
                        callbackToApp(() -> app.mCallback.onFoundOrLost(true, result));
                    } else {
                        sendResultByPendingIntent(
                                app.mInfo, result, ScanSettings.CALLBACK_TYPE_FIRST_MATCH, client);
                    }
                } else if ((advertiserState == ADVT_STATE_ONLOST)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                                != 0)) {
                    if (app.mCallback != null) {
                        callbackToApp(() -> app.mCallback.onFoundOrLost(false, result));
                    } else {
                        sendResultByPendingIntent(
                                app.mInfo, result, ScanSettings.CALLBACK_TYPE_MATCH_LOST, client);
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
        final ScannerMap.ScannerApp app = mScannerMap.getById(scannerId);
        if (app == null || app.mCallback == null) {
            Log.e(TAG, "Advertise app or callback is null");
            return;
        }
    }

    // callback from ScanManager for dispatch of errors apps.
    void onScanManagerErrorCallback(int scannerId, int errorCode) {
        enforceScanThread();
        final ScannerMap.ScannerApp app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, "App null");
            return;
        }
        if (app.mCallback != null) {
            callbackToApp(() -> app.mCallback.onScanManagerErrorCallback(errorCode));
        } else {
            try {
                sendErrorByPendingIntent(app.mInfo, errorCode);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Error sending error code via PendingIntent: " + e);
                ScanClient client = findScanClientById(scannerId);
                if (client != null) {
                    handleDeadScanClient(client);
                }
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

    /**************************************************************************
     * Scan functions - Shared CLIENT/SERVER
     *************************************************************************/

    void registerScanner(
            IScannerCallback callback, WorkSource workSource, AttributionSource source) {
        enforceScanThread();
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        final AppScanStats app = mScannerMap.getAppScanStatsByUid(uid);
        if (app != null
                && app.isScanningTooFrequently()
                && !Utils.checkCallerHasPrivilegedPermission(mAdapterService)) {
            Log.e(TAG, "App '" + app.mAppName + "' is scanning too frequently");
            try {
                callback.onScannerRegistered(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
            return;
        }
        registerScannerInternal(callback, workSource, source);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void registerScannerInternal(
            IScannerCallback callback, WorkSource workSource, AttributionSource source) {
        enforceScanThread();
        final var uuid = UUID.randomUUID();
        Log.d(TAG, "registerScanner() - UUID=" + uuid);
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        mScannerMap.addWithCallback(uuid, source, workSource, uid, callback, mAdapterService, this);
        mScanManager.registerScanner(uuid);
    }

    public void unregisterScanner(int scannerId) {
        enforceScanThread();
        Log.d(TAG, "unregisterScanner() - scannerId=" + scannerId);
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

    void startScan(
            int scannerId,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource source) {
        enforceScanThread();
        Log.d(TAG, "Start scan with filters");
        String callingPackage = source.getPackageName();
        settings = enforceReportDelayFloor(settings);
        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        final ScanClient scanClient =
                new ScanClient(scannerId, settings, filters, uid, Binder.getCallingUserHandle());
        mAppOps.checkPackage(uid, callingPackage);
        scanClient.setEligibleForSanitizedExposureNotification(
                callingPackage.equals(mExposureNotificationPackage));
        scanClient.setHasDisavowedLocation(
                Utils.hasDisavowedLocationForScan(mAdapterService, source, mTestModeEnabled));
        scanClient.setQApp(
                checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q));
        if (!scanClient.getHasDisavowedLocation()) {
            if (scanClient.isQApp()) {
                scanClient.setHasLocationPermission(
                        Utils.checkCallerHasFineLocation(
                                mAdapterService, source, scanClient.getUserHandle()));
            } else {
                scanClient.setHasLocationPermission(
                        Utils.checkCallerHasCoarseOrFineLocation(
                                mAdapterService, source, scanClient.getUserHandle()));
            }
        }
        scanClient.setHasNetworkSettingsPermission(
                Utils.checkCallerHasNetworkSettingsPermission(mAdapterService));
        scanClient.setHasNetworkSetupWizardPermission(
                Utils.checkCallerHasNetworkSetupWizardPermission(mAdapterService));
        scanClient.setHasScanWithoutLocationPermission(
                Utils.checkCallerHasScanWithoutLocationPermission(mAdapterService));
        scanClient.setAssociatedDevices(getAssociatedDevices(callingPackage));

        startScan(scannerId, settings, filters, scanClient);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void startScanInternal(int scannerId, ScanSettings settings, List<ScanFilter> filters) {
        enforceScanThread();
        // This ScanClient will be billed to the Bluetooth app due to its internal usage
        final ScanClient scanClient =
                new ScanClient(
                        scannerId,
                        settings,
                        filters,
                        Binder.getCallingUid(),
                        Binder.getCallingUserHandle(),
                        true);
        scanClient.setQApp(true);
        scanClient.setHasNetworkSettingsPermission(
                Utils.checkCallerHasNetworkSettingsPermission(mAdapterService));
        scanClient.setHasNetworkSetupWizardPermission(
                Utils.checkCallerHasNetworkSetupWizardPermission(mAdapterService));
        scanClient.setHasScanWithoutLocationPermission(
                Utils.checkCallerHasScanWithoutLocationPermission(mAdapterService));
        scanClient.setAssociatedDevices(Collections.emptyList());

        startScan(scannerId, settings, filters, scanClient);
    }

    private void startScan(
            int scannerId, ScanSettings settings, List<ScanFilter> filters, ScanClient scanClient) {
        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        if (app != null) {
            scanClient.setAppScanStats(Optional.of(app));
            mScanManager.fetchAppForegroundState(scanClient);
            boolean isFilteredScan = (filters != null) && !filters.isEmpty();
            boolean isCallbackScan = false;

            ScannerMap.ScannerApp cbApp = mScannerMap.getById(scannerId);
            if (cbApp != null) {
                isCallbackScan = cbApp.mCallback != null;
            }
            app.recordScanStart(
                    settings,
                    filters,
                    isFilteredScan,
                    isCallbackScan,
                    scannerId,
                    cbApp == null ? null : cbApp.mAttributionTag);
        }
        mScanManager.startScan(scanClient);
    }

    void registerPiAndStartScan(
            PendingIntent pendingIntent,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource source) {
        enforceScanThread();
        Log.d(TAG, "Register pendingIntent with filters and start scan");
        settings = enforceReportDelayFloor(settings);
        UUID uuid = UUID.randomUUID();
        String callingPackage = source.getPackageName();
        int callingUid = source.getUid();
        PendingIntentInfo piInfo =
                new PendingIntentInfo(pendingIntent, settings, filters, callingPackage, callingUid);
        Log.d(
                TAG,
                "startScan(PI) -"
                        + (" UUID=" + uuid)
                        + (" Package=" + callingPackage)
                        + (" UID=" + callingUid));

        // Don't start scan if the Pi scan already in mScannerMap.
        if (mScannerMap.getByPendingIntentInfo(pendingIntent) != null) {
            Log.d(TAG, "Don't startScan(PI) since the same Pi scan already in mScannerMap.");
            return;
        }

        final int uid = Flags.scanControllerThread() ? source.getUid() : Binder.getCallingUid();
        ScannerMap.ScannerApp app =
                mScannerMap.addWithPendingIntent(
                        uuid,
                        UserHandle.getUserHandleForUid(uid),
                        source,
                        piInfo,
                        mAdapterService,
                        this);
        mAppOps.checkPackage(uid, callingPackage);
        app.mEligibleForSanitizedExposureNotification =
                callingPackage.equals(mExposureNotificationPackage);
        app.mHasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(mAdapterService, source, mTestModeEnabled);
        if (!app.mHasDisavowedLocation) {
            try {
                if (checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q)) {
                    app.mHasLocationPermission =
                            Utils.checkCallerHasFineLocation(
                                    mAdapterService, source, app.mUserHandle);
                } else {
                    app.mHasLocationPermission =
                            Utils.checkCallerHasCoarseOrFineLocation(
                                    mAdapterService, source, app.mUserHandle);
                }
            } catch (SecurityException se) {
                // No need to throw here. Just mark as not granted.
                app.mHasLocationPermission = false;
            }
        }
        app.mHasNetworkSettingsPermission =
                Utils.checkCallerHasNetworkSettingsPermission(mAdapterService);
        app.mHasNetworkSetupWizardPermission =
                Utils.checkCallerHasNetworkSetupWizardPermission(mAdapterService);
        app.mHasScanWithoutLocationPermission =
                Utils.checkCallerHasScanWithoutLocationPermission(mAdapterService);
        app.mAssociatedDevices = getAssociatedDevices(callingPackage);

        mScanManager.registerScanner(uuid);
        // If this fails, we should stop the scan immediately.
        if (!pendingIntent.addCancelListener(Runnable::run, mScanIntentCancelListener)) {
            Log.d(TAG, "scanning PendingIntent is already cancelled, stopping scan.");
            stopScan(pendingIntent);
        }
    }

    /** Start a scan with pending intent. */
    @VisibleForTesting
    void continuePiStartScan(int scannerId, ScannerMap.ScannerApp app) {
        final PendingIntentInfo piInfo = app.mInfo;
        final ScanClient scanClient =
                new ScanClient(
                        scannerId,
                        piInfo.settings,
                        piInfo.filters,
                        piInfo.callingUid,
                        app.mUserHandle);
        scanClient.setHasLocationPermission(app.mHasLocationPermission);
        scanClient.setQApp(checkCallerTargetSdk(mAdapterService, app.mName, Build.VERSION_CODES.Q));
        scanClient.setEligibleForSanitizedExposureNotification(
                app.mEligibleForSanitizedExposureNotification);
        scanClient.setHasNetworkSettingsPermission(app.mHasNetworkSettingsPermission);
        scanClient.setHasNetworkSetupWizardPermission(app.mHasNetworkSetupWizardPermission);
        scanClient.setHasScanWithoutLocationPermission(app.mHasScanWithoutLocationPermission);
        scanClient.setAssociatedDevices(
                app.mAssociatedDevices == null ? Collections.emptyList() : app.mAssociatedDevices);
        scanClient.setHasDisavowedLocation(app.mHasDisavowedLocation);

        AppScanStats scanStats = mScannerMap.getAppScanStatsById(scannerId);
        if (scanStats != null) {
            scanClient.setAppScanStats(Optional.of(scanStats));
            mScanManager.fetchAppForegroundState(scanClient);
            boolean isFilteredScan = (piInfo.filters != null) && !piInfo.filters.isEmpty();
            scanStats.recordScanStart(
                    piInfo.settings,
                    piInfo.filters,
                    isFilteredScan,
                    false,
                    scannerId,
                    app.mAttributionTag);
        }
        mScanManager.startScan(scanClient);
    }

    void flushPendingBatchResults(int scannerId) {
        enforceScanThread();
        final var scanClient = findBatchScanClientById(scannerId);
        if (scanClient == null) {
            Log.e(TAG, "Unexpectedly cannot find batch scan client for scannerId=" + scannerId);
            return;
        }
        mScanManager.flushBatchScanResults(scanClient);
    }

    public void stopScan(int scannerId) {
        enforceScanThread();
        final int scanQueueSize =
                mScanManager.getBatchScanQueue().size() + mScanManager.getRegularScanQueue().size();
        Log.d(TAG, "stopScan() - queue size =" + scanQueueSize);

        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        if (app != null) {
            app.recordScanStop(scannerId);
        }
        mScanManager.stopScan(scannerId);
    }

    void stopScan(PendingIntent intent) {
        enforceScanThread();
        ScannerMap.ScannerApp app = mScannerMap.getByPendingIntentInfo(intent);
        Log.v(TAG, "stopScan(PendingIntent): app found = " + app);
        if (app != null) {
            intent.removeCancelListener(mScanIntentCancelListener);
            final int scannerId = app.mId;
            stopScan(scannerId);
            // Also unregister the scanner
            unregisterScanner(scannerId);
        }
    }

    /**************************************************************************
     * PERIODIC SCANNING
     *************************************************************************/

    void registerSync(
            ScanResult scanResult, int skip, int timeout, IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.startSync(scanResult, skip, timeout, callback);
    }

    void unregisterSync(IPeriodicAdvertisingCallback callback) {
        enforceScanThread();
        mPeriodicScanManager.stopSync(callback);
    }

    void transferSync(BluetoothDevice bda, int serviceData, int syncHandle) {
        enforceScanThread();
        mPeriodicScanManager.transferSync(bda, serviceData, syncHandle);
    }

    void transferSetInfo(
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

    /**
     * DeathRecipient handler used to unregister applications that disconnect ungracefully (ie.
     * crash or forced close).
     */
    class ScannerDeathRecipient implements IBinder.DeathRecipient {
        private final int mScannerId;
        private final String mPackageName;

        ScannerDeathRecipient(int scannerId, String packageName) {
            mScannerId = scannerId;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(
                    TAG,
                    "Binder is dead - unregistering scanner -"
                            + (" packageName=" + mPackageName)
                            + (", scannerId=" + mScannerId));

            ScanClient client = findScanClientById(mScannerId);
            if (client != null) {
                handleDeadScanClient(client);
            }
        }
    }

    /**
     * Ensures the report delay is either 0 or at least the floor value.
     *
     * @see ScanUtil#DEFAULT_REPORT_DELAY_FLOOR_MS
     * @param settings are the scan settings passed into a request to start le scanning
     * @return the passed in ScanSettings object if the report delay is 0 or above the floor value;
     *     a new ScanSettings object with the report delay being the floor value if the original
     *     report delay was between 0 and the floor value (exclusive of both)
     */
    @VisibleForTesting
    ScanSettings enforceReportDelayFloor(ScanSettings settings) {
        final long originalDelay = settings.getReportDelayMillis();
        if (originalDelay == 0) {
            Log.d(TAG, "enforceReportDelayFloor(): Report delay is 0, skipping floor enforcement.");
            return settings;
        }

        // Need to clear identity to pass device config permission check
        final long callerToken = Binder.clearCallingIdentity();
        try {
            final long floor =
                    DeviceConfig.getLong(
                            DeviceConfig.NAMESPACE_BLUETOOTH,
                            "report_delay",
                            DEFAULT_REPORT_DELAY_FLOOR_MS);
            if (originalDelay >= floor) {
                Log.d(
                        TAG,
                        "enforceReportDelayFloor(): Report delay "
                                + originalDelay
                                + "ms is above or equal to floor "
                                + floor
                                + "ms, no changes.");
                return settings;
            } else {
                Log.d(
                        TAG,
                        "enforceReportDelayFloor(): Enforcing floor: original delay "
                                + originalDelay
                                + "ms is below floor, setting to "
                                + floor
                                + "ms.");
                return new ScanSettings.Builder()
                        .setCallbackType(settings.getCallbackType())
                        .setLegacy(settings.getLegacy())
                        .setMatchMode(settings.getMatchMode())
                        .setNumOfMatches(settings.getNumOfMatches())
                        .setPhy(settings.getPhy())
                        .setReportDelay(floor)
                        .setScanMode(settings.getScanMode())
                        .setScanResultType(settings.getScanResultType())
                        .build();
            }
        } finally {
            Binder.restoreCallingIdentity(callerToken);
        }
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
        if (!Flags.scanControllerThread()) {
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
