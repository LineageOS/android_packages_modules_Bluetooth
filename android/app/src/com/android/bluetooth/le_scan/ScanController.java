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

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.bluetooth.BluetoothUtils.extractBytes;

import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.flags.Flags.leaudioBassScanWithInternalScanController;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
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

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.BluetoothAdapterProxy;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.util.NumberUtils;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.HexEncoding;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ScanController {
    private static final String TAG = ScanController.class.getSimpleName();

    /** The default floor value for LE batch scan report delays greater than 0 */
    static final long DEFAULT_REPORT_DELAY_FLOOR = 5000L;

    // Batch scan related constants.
    private static final int TRUNCATED_RESULT_SIZE = 11;
    private static final int NUM_SCAN_EVENTS_KEPT = 20;

    // onFoundLost related constants
    @VisibleForTesting static final int ADVT_STATE_ONFOUND = 0;
    private static final int ADVT_STATE_ONLOST = 1;

    private static final int ET_LEGACY_MASK = 0x10;

    /** Example raw beacons captured from a Blue Charm BC011 */
    private static final String[] TEST_MODE_BEACONS =
            new String[] {
                "020106",
                "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000",
                "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000",
            };

    static class PendingIntentInfo {
        public PendingIntent intent;
        public ScanSettings settings;
        public List<ScanFilter> filters;
        public String callingPackage;
        public int callingUid;

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

    private final PendingIntent.CancelListener mScanIntentCancelListener =
            new PendingIntent.CancelListener() {
                public void onCanceled(PendingIntent intent) {
                    Log.d(TAG, "scanning PendingIntent canceled");
                    stopScanInternal(intent);
                }
            };

    private final BluetoothAdapter mAdapter;
    private final AdapterService mAdapterService;

    private final Map<Integer, Integer> mFilterIndexToMsftAdvMonitorMap = new HashMap<>();
    private final String mExposureNotificationPackage;

    private final AppOpsManager mAppOps;
    private final CompanionDeviceManager mCompanionManager;
    private final PeriodicScanManager mPeriodicScanManager;
    private final ScanManager mScanManager;

    private final ScanBinder mBinder;
    private final HandlerThread mScanThread;

    /** Internal list of scan events to use with the proto */
    private final Deque<BluetoothMetricsProto.ScanEvent> mScanEvents =
            new ArrayDeque<>(NUM_SCAN_EVENTS_KEPT);

    private final Predicate<ScanResult> mLocationDenylistPredicate;

    private ScannerMap mScannerMap = new ScannerMap();

    private boolean mIsAvailable;

    private volatile boolean mTestModeEnabled = false;
    private final Looper mMainLooper;
    private Handler mTestModeHandler;
    private final Object mTestModeLock = new Object();

    public ScanController(AdapterService adapterService) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdapterService = requireNonNull(adapterService);
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
        mMainLooper = adapterService.getMainLooper();
        mBinder = new ScanBinder(this);
        mIsAvailable = true;
        mScanThread = new HandlerThread("BluetoothScanManager");
        mScanThread.start();
        mAppOps = mAdapterService.getSystemService(AppOpsManager.class);
        mCompanionManager = mAdapterService.getSystemService(CompanionDeviceManager.class);
        mScanManager =
                ScanObjectsFactory.getInstance()
                        .createScanManager(
                                mAdapterService,
                                this,
                                BluetoothAdapterProxy.getInstance(),
                                mScanThread.getLooper());
        mPeriodicScanManager = ScanObjectsFactory.getInstance().createPeriodicScanManager();
    }

    public void stop() {
        Log.d(TAG, "stop()");
        mIsAvailable = false;
        mBinder.clearScanController();
        mScanThread.quitSafely();
        mScannerMap.clear();
        mScanManager.cleanup();
        mPeriodicScanManager.cleanup();
    }

    boolean isAvailable() {
        return mIsAvailable;
    }

    ScannerMap getScannerMap() {
        return mScannerMap;
    }

    @VisibleForTesting
    void setScannerMap(ScannerMap scannerMap) {
        mScannerMap = scannerMap;
    }

    /** Notify Scan manager of bluetooth profile connection state changes */
    public void notifyProfileConnectionStateChange(int profile, int fromState, int toState) {
        mScanManager.handleBluetoothProfileConnectionStateChanged(profile, fromState, toState);
    }

    public IBinder getBinder() {
        return mBinder;
    }

    public void setTestModeEnabled(boolean enableTestMode) {
        synchronized (mTestModeLock) {
            if (mTestModeHandler == null) {
                mTestModeHandler =
                        new Handler(mMainLooper) {
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

        AppScanStats.recordScanRadioResultCount();
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
                "onScanResult() - eventType=0x"
                        + Integer.toHexString(eventType)
                        + ", addressType="
                        + addressType
                        + ", address="
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", primaryPhy="
                        + primaryPhy
                        + ", secondaryPhy="
                        + secondaryPhy
                        + ", advertisingSid=0x"
                        + Integer.toHexString(advertisingSid)
                        + ", txPower="
                        + txPower
                        + ", rssi="
                        + rssi
                        + ", periodicAdvInt=0x"
                        + Integer.toHexString(periodicAdvInt)
                        + ", originalAddress="
                        + originalAddress);

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
            ScannerMap.ScannerApp app = mScannerMap.getById(client.mScannerId);
            if (app == null) {
                Log.v(TAG, "App is null; skip.");
                continue;
            }

            ScanSettings settings = client.mSettings;
            byte[] scanRecordData;
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

            if (client.mHasDisavowedLocation) {
                if (mLocationDenylistPredicate.test(result)) {
                    Log.i(TAG, "Skipping client " + client.mScannerId + " for location deny list");
                    continue;
                }
            }

            boolean hasPermission = hasScanResultPermission(client);
            if (!hasPermission) {
                for (String associatedDevice : client.mAssociatedDevices) {
                    if (associatedDevice.equalsIgnoreCase(address)) {
                        hasPermission = true;
                        break;
                    }
                }
            }
            if (!hasPermission && client.mEligibleForSanitizedExposureNotification) {
                ScanResult sanitized = getSanitizedExposureNotification(result);
                if (sanitized != null) {
                    hasPermission = true;
                    result = sanitized;
                }
            }
            boolean matchResult = matchesFilters(client, result, originalAddress);
            if (!hasPermission || !matchResult) {
                Log.v(
                        TAG,
                        "Skipping client: permission=" + hasPermission + " matches=" + matchResult);
                continue;
            }

            int callbackType = settings.getCallbackType();
            if (!(callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)) {
                Log.v(TAG, "Skipping client: CALLBACK_TYPE_ALL_MATCHES");
                continue;
            }

            try {
                app.mAppScanStats.addResult(client.mScannerId);
                if (app.mCallback != null) {
                    app.mCallback.onScanResult(result);
                } else {
                    Log.v(TAG, "Callback is null, sending scan results by pendingIntent");
                    // Send the PendingIntent
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
                stopScanInternal(client.mScannerId);
                unregisterScannerInternal(client.mScannerId);
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
    void onScannerRegistered(int status, int scannerId, long uuidLsb, long uuidMsb)
            throws RemoteException {
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(
                TAG,
                "onScannerRegistered() - UUID="
                        + uuid
                        + ", scannerId="
                        + scannerId
                        + ", status="
                        + status);

        // First check the callback map
        ScannerMap.ScannerApp cbApp = mScannerMap.getByUuid(uuid);
        if (cbApp != null) {
            if (status == 0) {
                cbApp.mId = scannerId;
                // If app is callback based, setup a death recipient. App will initiate the start.
                // Otherwise, if PendingIntent based, start the scan directly.
                if (cbApp.mCallback != null) {
                    cbApp.linkToDeath(new ScannerDeathRecipient(scannerId, cbApp.mName));
                } else {
                    continuePiStartScan(scannerId, cbApp);
                }
            } else {
                mScannerMap.remove(scannerId);
            }
            if (cbApp.mCallback != null) {
                cbApp.mCallback.onScannerRegistered(status, scannerId);
            }
        }
    }

    /** Determines if the given scan client has the appropriate permissions to receive callbacks. */
    private boolean hasScanResultPermission(final ScanClient client) {
        if (leaudioBassScanWithInternalScanController() && client.mIsInternalClient) {
            // Bypass permission check for internal clients
            return true;
        }
        if (client.mHasNetworkSettingsPermission
                || client.mHasNetworkSetupWizardPermission
                || client.mHasScanWithoutLocationPermission) {
            return true;
        }
        if (client.mHasDisavowedLocation) {
            return true;
        }
        return client.mHasLocationPermission
                && !Utils.blockedByLocationOff(mAdapterService, client.mUserHandle);
    }

    private List<ScanResult> permittedResults(final ScanClient client, Set<ScanResult> results) {
        if (hasScanResultPermission(client)) {
            return new ArrayList<>(results);
        }

        List<ScanResult> permittedResults = new ArrayList<>();
        for (ScanResult scanResult : results) {
            for (String associatedDevice : client.mAssociatedDevices) {
                if (associatedDevice.equalsIgnoreCase(scanResult.getDevice().getAddress())) {
                    permittedResults.add(scanResult);
                }
            }
        }
        return permittedResults;
    }

    // Check if a scan record matches a specific filters.
    private static boolean matchesFilters(ScanClient client, ScanResult scanResult) {
        return matchesFilters(client, scanResult, null);
    }

    // Check if a scan record matches a specific filters or original address
    private static boolean matchesFilters(
            ScanClient client, ScanResult scanResult, String originalAddress) {
        if (client.mFilters == null || client.mFilters.isEmpty()) {
            // TODO: Do we really wanna return true here?
            return true;
        }
        for (ScanFilter filter : client.mFilters) {
            // Need to check the filter matches, and the original address without changing the API
            if (filter.matches(scanResult)) {
                return true;
            }
            if (originalAddress != null
                    && originalAddress.equalsIgnoreCase(filter.getDeviceAddress())) {
                return true;
            }
        }
        return false;
    }

    private void handleDeadScanClient(ScanClient client) {
        if (client.mAppDied) {
            Log.w(TAG, "Already dead client " + client.mScannerId);
            return;
        }
        client.mAppDied = true;
        if (client.mStats != null) {
            client.mStats.isAppDead = true;
        }
        stopScanInternal(client.mScannerId);
    }

    /** Callback method for scan filter enablement/disablement. */
    void onScanFilterEnableDisabled(int action, int status, int clientIf) {
        Log.d(
                TAG,
                "onScanFilterEnableDisabled() - clientIf="
                        + clientIf
                        + ", status="
                        + status
                        + ", action="
                        + action);
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter params. */
    void onScanFilterParamsConfigured(int action, int status, int clientIf, int availableSpace) {
        Log.d(
                TAG,
                "onScanFilterParamsConfigured() - clientIf="
                        + clientIf
                        + ", status="
                        + status
                        + ", action="
                        + action
                        + ", availableSpace="
                        + availableSpace);
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter. */
    void onScanFilterConfig(
            int action, int status, int clientIf, int filterType, int availableSpace) {
        Log.d(
                TAG,
                "onScanFilterConfig() - clientIf="
                        + clientIf
                        + ", action = "
                        + action
                        + " status = "
                        + status
                        + ", filterType="
                        + filterType
                        + ", availableSpace="
                        + availableSpace);

        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of batch scan storage. */
    void onBatchScanStorageConfigured(int status, int clientIf) {
        Log.d(TAG, "onBatchScanStorageConfigured() - clientIf=" + clientIf + ", status=" + status);
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for start/stop of batch scan. */
    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped.
    void onBatchScanStartStopped(int startStopAction, int status, int clientIf) {
        Log.d(
                TAG,
                "onBatchScanStartStopped() - clientIf="
                        + clientIf
                        + ", status="
                        + status
                        + ", startStopAction="
                        + startStopAction);
        mScanManager.callbackDone(clientIf, status);
    }

    ScanClient findBatchScanClientById(int scannerId) {
        for (ScanClient client : mScanManager.getBatchScanQueue()) {
            if (client.mScannerId == scannerId) {
                return client;
            }
        }
        return null;
    }

    /** Callback method for batch scan reports */
    void onBatchScanReports(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData)
            throws RemoteException {
        // When in testing mode, ignore all real-world events
        if (mTestModeEnabled) return;

        AppScanStats.recordBatchScanRadioResultCount(numRecords);
        onBatchScanReportsInternal(status, scannerId, reportType, numRecords, recordData);
    }

    @VisibleForTesting
    void onBatchScanReportsInternal(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData)
            throws RemoteException {
        Log.d(
                TAG,
                "onBatchScanReports() - scannerId="
                        + scannerId
                        + ", status="
                        + status
                        + ", reportType="
                        + reportType
                        + ", numRecords="
                        + numRecords);

        Set<ScanResult> results = parseBatchScanResults(numRecords, reportType, recordData);
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
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

            if (client.mHasDisavowedLocation) {
                permittedResults.removeIf(mLocationDenylistPredicate);
            }
            if (permittedResults.isEmpty()) {
                mScanManager.callbackDone(scannerId, status);
                return;
            }

            if (app.mCallback != null) {
                app.mCallback.onBatchScanResults(permittedResults);
                mScanManager.batchScanResultDelivered();
            } else {
                // PendingIntent based
                try {
                    sendResultsByPendingIntent(
                            app.mInfo, permittedResults, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                } catch (PendingIntent.CanceledException e) {
                    Log.d(TAG, "Exception while sending result", e);
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

    private void sendBatchScanResults(
            ScannerMap.ScannerApp app, ScanClient client, List<ScanResult> results) {
        if (results.isEmpty()) {
            return;
        }
        try {
            if (app.mCallback != null) {
                if (mScanManager.isAutoBatchScanClientEnabled(client)) {
                    Log.d(TAG, "sendBatchScanResults() to onScanResult()" + client);
                    for (ScanResult result : results) {
                        app.mAppScanStats.addResult(client.mScannerId);
                        app.mCallback.onScanResult(result);
                    }
                } else {
                    Log.d(TAG, "sendBatchScanResults() to onBatchScanResults()" + client);
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

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults)
            throws RemoteException {
        ScannerMap.ScannerApp app = mScannerMap.getById(client.mScannerId);
        if (app == null) {
            return;
        }

        List<ScanResult> permittedResults = permittedResults(client, allResults);

        if (client.mFilters == null || client.mFilters.isEmpty()) {
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

    private Set<ScanResult> parseBatchScanResults(
            int numRecords, int reportType, byte[] batchRecord) {
        if (numRecords == 0) {
            return Collections.emptySet();
        }
        Log.d(TAG, "current time is " + SystemClock.elapsedRealtimeNanos());
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
            return parseTruncatedResults(numRecords, batchRecord);
        } else {
            return parseFullResults(numRecords, batchRecord);
        }
    }

    private Set<ScanResult> parseTruncatedResults(int numRecords, byte[] batchRecord) {
        Log.d(TAG, "batch record " + Arrays.toString(batchRecord));
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        long now = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < numRecords; ++i) {
            byte[] record =
                    extractBytes(batchRecord, i * TRUNCATED_RESULT_SIZE, TRUNCATED_RESULT_SIZE);
            byte[] address = extractBytes(record, 0, 6);
            Bytes.reverse(address);
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
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
        Log.d(TAG, "Batch record : " + Arrays.toString(batchRecord));
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        int position = 0;
        long now = SystemClock.elapsedRealtimeNanos();
        while (position < batchRecord.length) {
            byte[] address = extractBytes(batchRecord, position, 6);
            // TODO: remove temp hack.
            Bytes.reverse(address);
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
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
            Log.d(TAG, "ScanRecord : " + Arrays.toString(scanRecord));
            results.add(
                    new ScanResult(
                            device, ScanRecord.parseFromBytes(scanRecord), rssi, timestampNanos));
        }
        return results;
    }

    void onBatchScanThresholdCrossed(int clientIf) {
        Log.d(TAG, "onBatchScanThresholdCrossed() - clientIf=" + clientIf);
        flushPendingBatchResultsInternal(clientIf);
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

    void onTrackAdvFoundLost(AdvtFilterOnFoundOnLostInfo trackingInfo) throws RemoteException {
        Log.d(
                TAG,
                "onTrackAdvFoundLost() - scannerId= "
                        + trackingInfo.clientIf()
                        + " address = "
                        + trackingInfo.address()
                        + " addressType = "
                        + trackingInfo.addressType()
                        + " adv_state = "
                        + trackingInfo.advState());

        ScannerMap.ScannerApp app = mScannerMap.getById(trackingInfo.clientIf());
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
            if (client.mScannerId == trackingInfo.clientIf()) {
                ScanSettings settings = client.mSettings;
                if ((advertiserState == ADVT_STATE_ONFOUND)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                                != 0)) {
                    if (app.mCallback != null) {
                        app.mCallback.onFoundOrLost(true, result);
                    } else {
                        sendResultByPendingIntent(
                                app.mInfo, result, ScanSettings.CALLBACK_TYPE_FIRST_MATCH, client);
                    }
                } else if ((advertiserState == ADVT_STATE_ONLOST)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                                != 0)) {
                    if (app.mCallback != null) {
                        app.mCallback.onFoundOrLost(false, result);
                    } else {
                        sendResultByPendingIntent(
                                app.mInfo, result, ScanSettings.CALLBACK_TYPE_MATCH_LOST, client);
                    }
                } else {
                    Log.d(
                            TAG,
                            "Not reporting onlost/onfound : "
                                    + advertiserState
                                    + " scannerId = "
                                    + client.mScannerId
                                    + " callbackType "
                                    + settings.getCallbackType());
                }
            }
        }
    }

    /** Callback method for configuration of scan parameters. */
    void onScanParamSetupCompleted(int status, int scannerId) {
        Log.d(TAG, "onScanParamSetupCompleted() - scannerId=" + scannerId + ", status=" + status);
        ScannerMap.ScannerApp app = mScannerMap.getById(scannerId);
        if (app == null || app.mCallback == null) {
            Log.e(TAG, "Advertise app or callback is null");
            return;
        }
    }

    // callback from ScanManager for dispatch of errors apps.
    void onScanManagerErrorCallback(int scannerId, int errorCode) throws RemoteException {
        ScannerMap.ScannerApp app = mScannerMap.getById(scannerId);
        if (app == null) {
            Log.e(TAG, "App null");
            return;
        }
        if (app.mCallback != null) {
            app.mCallback.onScanManagerErrorCallback(errorCode);
        } else {
            try {
                sendErrorByPendingIntent(app.mInfo, errorCode);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Error sending error code via PendingIntent:" + e);
            }
        }
    }

    int msftMonitorHandleFromFilterIndex(int filterIndex) {
        if (!mFilterIndexToMsftAdvMonitorMap.containsKey(filterIndex)) {
            Log.e(TAG, "Monitor with filterIndex'" + filterIndex + "' does not exist");
            return -1;
        }
        return mFilterIndexToMsftAdvMonitorMap.get(filterIndex);
    }

    void onMsftAdvMonitorAdd(int filterIndex, int monitorHandle, int status) {
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

    void onMsftAdvMonitorEnable(int status) {
        if (status != 0) {
            Log.e(TAG, "Error enabling advertisement monitor");
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    @RequiresPermission(BLUETOOTH_SCAN)
    void registerScanner(
            IScannerCallback callback, WorkSource workSource, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper registerScanner")) {
            return;
        }

        enforceImpersonationPermissionIfNeeded(workSource);

        AppScanStats app = mScannerMap.getAppScanStatsByUid(Binder.getCallingUid());
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
        registerScannerInternal(callback, attributionSource, workSource);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void registerScannerInternal(
            IScannerCallback callback, AttributionSource attrSource, WorkSource workSource) {
        UUID uuid = UUID.randomUUID();
        Log.d(TAG, "registerScanner() - UUID=" + uuid);

        mScannerMap.add(uuid, attrSource, workSource, callback, mAdapterService, this);
        mScanManager.registerScanner(uuid);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void unregisterScanner(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper unregisterScanner")) {
            return;
        }

        unregisterScannerInternal(scannerId);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void unregisterScannerInternal(int scannerId) {
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

    @RequiresPermission(BLUETOOTH_SCAN)
    void startScan(
            int scannerId,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        Log.d(TAG, "start scan with filters");

        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "Starting GATT scan.")) {
            return;
        }

        enforcePrivilegedPermissionIfNeeded(settings);
        String callingPackage = attributionSource.getPackageName();
        settings = enforceReportDelayFloor(settings);
        enforcePrivilegedPermissionIfNeeded(filters);
        final ScanClient scanClient = new ScanClient(scannerId, settings, filters);
        scanClient.mUserHandle = Binder.getCallingUserHandle();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        scanClient.mEligibleForSanitizedExposureNotification =
                callingPackage.equals(mExposureNotificationPackage);

        scanClient.mHasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(
                        mAdapterService, attributionSource, mTestModeEnabled);

        scanClient.mIsQApp =
                checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q);
        if (!scanClient.mHasDisavowedLocation) {
            if (scanClient.mIsQApp) {
                scanClient.mHasLocationPermission =
                        Utils.checkCallerHasFineLocation(
                                mAdapterService, attributionSource, scanClient.mUserHandle);
            } else {
                scanClient.mHasLocationPermission =
                        Utils.checkCallerHasCoarseOrFineLocation(
                                mAdapterService, attributionSource, scanClient.mUserHandle);
            }
        }
        scanClient.mHasNetworkSettingsPermission =
                Utils.checkCallerHasNetworkSettingsPermission(mAdapterService);
        scanClient.mHasNetworkSetupWizardPermission =
                Utils.checkCallerHasNetworkSetupWizardPermission(mAdapterService);
        scanClient.mHasScanWithoutLocationPermission =
                Utils.checkCallerHasScanWithoutLocationPermission(mAdapterService);
        scanClient.mAssociatedDevices = getAssociatedDevices(callingPackage);

        startScan(scannerId, settings, filters, scanClient);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void startScanInternal(int scannerId, ScanSettings settings, List<ScanFilter> filters) {
        final ScanClient scanClient = new ScanClient(scannerId, settings, filters);
        scanClient.mIsInternalClient = true;
        scanClient.mUserHandle = Binder.getCallingUserHandle();
        scanClient.mEligibleForSanitizedExposureNotification = false;
        scanClient.mHasDisavowedLocation = false;
        scanClient.mIsQApp = true;
        scanClient.mHasNetworkSettingsPermission =
                Utils.checkCallerHasNetworkSettingsPermission(mAdapterService);
        scanClient.mHasNetworkSetupWizardPermission =
                Utils.checkCallerHasNetworkSetupWizardPermission(mAdapterService);
        scanClient.mHasScanWithoutLocationPermission =
                Utils.checkCallerHasScanWithoutLocationPermission(mAdapterService);
        scanClient.mAssociatedDevices = Collections.emptyList();

        startScan(scannerId, settings, filters, scanClient);
    }

    private void startScan(
            int scannerId, ScanSettings settings, List<ScanFilter> filters, ScanClient scanClient) {
        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        if (app != null) {
            scanClient.mStats = app;
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

    @RequiresPermission(BLUETOOTH_SCAN)
    void registerPiAndStartScan(
            PendingIntent pendingIntent,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        Log.d(TAG, "start scan with filters, for PendingIntent");

        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "Starting GATT scan.")) {
            return;
        }
        enforcePrivilegedPermissionIfNeeded(settings);
        settings = enforceReportDelayFloor(settings);
        enforcePrivilegedPermissionIfNeeded(filters);
        UUID uuid = UUID.randomUUID();
        String callingPackage = attributionSource.getPackageName();
        int callingUid = attributionSource.getUid();
        PendingIntentInfo piInfo = new PendingIntentInfo();
        piInfo.intent = pendingIntent;
        piInfo.settings = settings;
        piInfo.filters = filters;
        piInfo.callingPackage = callingPackage;
        piInfo.callingUid = callingUid;
        Log.d(
                TAG,
                "startScan(PI) -"
                        + (" UUID=" + uuid)
                        + (" Package=" + callingPackage)
                        + (" UID=" + callingUid));

        // Don't start scan if the Pi scan already in mScannerMap.
        if (mScannerMap.getByPendingIntentInfo(piInfo) != null) {
            Log.d(TAG, "Don't startScan(PI) since the same Pi scan already in mScannerMap.");
            return;
        }

        ScannerMap.ScannerApp app =
                mScannerMap.add(uuid, attributionSource, piInfo, mAdapterService, this);

        app.mUserHandle = UserHandle.getUserHandleForUid(Binder.getCallingUid());
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        app.mEligibleForSanitizedExposureNotification =
                callingPackage.equals(mExposureNotificationPackage);

        app.mHasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(
                        mAdapterService, attributionSource, mTestModeEnabled);

        if (!app.mHasDisavowedLocation) {
            try {
                if (checkCallerTargetSdk(mAdapterService, callingPackage, Build.VERSION_CODES.Q)) {
                    app.mHasLocationPermission =
                            Utils.checkCallerHasFineLocation(
                                    mAdapterService, attributionSource, app.mUserHandle);
                } else {
                    app.mHasLocationPermission =
                            Utils.checkCallerHasCoarseOrFineLocation(
                                    mAdapterService, attributionSource, app.mUserHandle);
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
            stopScan(pendingIntent, attributionSource);
        }
    }

    /** Start a scan with pending intent. */
    @VisibleForTesting
    void continuePiStartScan(int scannerId, ScannerMap.ScannerApp app) {
        final PendingIntentInfo piInfo = app.mInfo;
        final ScanClient scanClient =
                new ScanClient(scannerId, piInfo.settings, piInfo.filters, piInfo.callingUid);
        scanClient.mHasLocationPermission = app.mHasLocationPermission;
        scanClient.mUserHandle = app.mUserHandle;
        scanClient.mIsQApp =
                checkCallerTargetSdk(mAdapterService, app.mName, Build.VERSION_CODES.Q);
        scanClient.mEligibleForSanitizedExposureNotification =
                app.mEligibleForSanitizedExposureNotification;
        scanClient.mHasNetworkSettingsPermission = app.mHasNetworkSettingsPermission;
        scanClient.mHasNetworkSetupWizardPermission = app.mHasNetworkSetupWizardPermission;
        scanClient.mHasScanWithoutLocationPermission = app.mHasScanWithoutLocationPermission;
        scanClient.mAssociatedDevices = app.mAssociatedDevices;
        scanClient.mHasDisavowedLocation = app.mHasDisavowedLocation;

        AppScanStats scanStats = mScannerMap.getAppScanStatsById(scannerId);
        if (scanStats != null) {
            scanClient.mStats = scanStats;
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

    @RequiresPermission(BLUETOOTH_SCAN)
    void flushPendingBatchResults(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper flushPendingBatchResults")) {
            return;
        }
        flushPendingBatchResultsInternal(scannerId);
    }

    private void flushPendingBatchResultsInternal(int scannerId) {
        Log.d(TAG, "flushPendingBatchResultsInternal - scannerId=" + scannerId);
        mScanManager.flushBatchScanResults(new ScanClient(scannerId));
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void stopScan(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper stopScan")) {
            return;
        }
        stopScanInternal(scannerId);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    public void stopScanInternal(int scannerId) {
        int scanQueueSize =
                mScanManager.getBatchScanQueue().size() + mScanManager.getRegularScanQueue().size();
        Log.d(TAG, "stopScan() - queue size =" + scanQueueSize);

        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        if (app != null) {
            app.recordScanStop(scannerId);
        }

        mScanManager.stopScan(scannerId);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void stopScan(PendingIntent intent, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper stopScan")) {
            return;
        }
        stopScanInternal(intent);
    }

    /** Intended for internal use within the Bluetooth app. Bypass permission check */
    private void stopScanInternal(PendingIntent intent) {
        PendingIntentInfo pii = new PendingIntentInfo();
        pii.intent = intent;
        ScannerMap.ScannerApp app = mScannerMap.getByPendingIntentInfo(pii);
        Log.v(TAG, "stopScan(PendingIntent): app found = " + app);
        if (app != null) {
            intent.removeCancelListener(mScanIntentCancelListener);
            final int scannerId = app.mId;
            stopScanInternal(scannerId);
            // Also unregister the scanner
            unregisterScannerInternal(scannerId);
        }
    }

    /**************************************************************************
     * PERIODIC SCANNING
     *************************************************************************/
    @RequiresPermission(BLUETOOTH_SCAN)
    void registerSync(
            ScanResult scanResult,
            int skip,
            int timeout,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper registerSync")) {
            return;
        }
        mPeriodicScanManager.startSync(scanResult, skip, timeout, callback);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void unregisterSync(
            IPeriodicAdvertisingCallback callback, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper unregisterSync")) {
            return;
        }
        mPeriodicScanManager.stopSync(callback);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void transferSync(
            BluetoothDevice bda,
            int serviceData,
            int syncHandle,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper transferSync")) {
            return;
        }
        mPeriodicScanManager.transferSync(bda, serviceData, syncHandle);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    void transferSetInfo(
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper transferSetInfo")) {
            return;
        }
        mPeriodicScanManager.transferSetInfo(bda, serviceData, advHandle, callback);
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    int numHwTrackFiltersAvailable(AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mAdapterService, attributionSource, "ScanHelper numHwTrackFiltersAvailable")) {
            return 0;
        }
        return (mAdapterService.getTotalNumOfTrackableAdvertisements()
                - mScanManager.getCurrentUsedTrackingAdvertisement());
    }

    /**
     * DeathRecipient handler used to unregister applications that disconnect ungracefully (ie.
     * crash or forced close).
     */
    class ScannerDeathRecipient implements IBinder.DeathRecipient {
        int mScannerId;
        private String mPackageName;

        ScannerDeathRecipient(int scannerId, String packageName) {
            mScannerId = scannerId;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(
                    TAG,
                    "Binder is dead - unregistering scanner ("
                            + mPackageName
                            + " "
                            + mScannerId
                            + ")!");

            ScanClient client = getScanClient(mScannerId);
            if (client != null) {
                handleDeadScanClient(client);
            }
        }

        private ScanClient getScanClient(int clientIf) {
            for (ScanClient client : mScanManager.getRegularScanQueue()) {
                if (client.mScannerId == clientIf) {
                    return client;
                }
            }
            for (ScanClient client : mScanManager.getBatchScanQueue()) {
                if (client.mScannerId == clientIf) {
                    return client;
                }
            }
            return null;
        }
    }

    private boolean needsPrivilegedPermissionForScan(ScanSettings settings) {
        // BLE scan only mode needs special permission.
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            return true;
        }

        // Regular scan, no special permission.
        if (settings == null) {
            return false;
        }

        // Ambient discovery mode, needs privileged permission.
        if (settings.getScanMode() == ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY) {
            return true;
        }

        // Regular scan, no special permission.
        if (settings.getReportDelayMillis() == 0) {
            return false;
        }

        // Batch scan, truncated mode needs permission.
        return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED;
    }

    /*
     * The ScanFilter#setDeviceAddress API overloads are @SystemApi access methods.  This
     * requires that the permissions be BLUETOOTH_PRIVILEGED.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforcePrivilegedPermissionIfNeeded(List<ScanFilter> filters) {
        Log.d(TAG, "enforcePrivilegedPermissionIfNeeded(" + filters + ")");
        // Some 3p API cases may have null filters, need to allow
        if (filters != null) {
            for (ScanFilter filter : filters) {
                // The only case to enforce here is if there is an address
                // If there is an address, enforce if the correct combination criteria is met.
                if (filter.getDeviceAddress() != null) {
                    // At this point we have an address, that means a caller used the
                    // setDeviceAddress(address) public API for the ScanFilter
                    // We don't want to enforce if the type is PUBLIC and the IRK is null
                    // However, if we have a different type that means the caller used a new
                    // @SystemApi such as setDeviceAddress(address, type) or
                    // setDeviceAddress(address, type, irk) which are both @SystemApi and require
                    // permissions to be enforced
                    if (filter.getAddressType() == BluetoothDevice.ADDRESS_TYPE_PUBLIC
                            && filter.getIrk() == null) {
                        // Do not enforce
                    } else {
                        mAdapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
                    }
                }
            }
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforcePrivilegedPermissionIfNeeded(ScanSettings settings) {
        if (needsPrivilegedPermissionForScan(settings)) {
            mAdapterService.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }
    }

    // Enforce caller has UPDATE_DEVICE_STATS permission, which allows the caller to blame other
    // apps for Bluetooth usage. A {@link SecurityException} will be thrown if the caller app does
    // not have UPDATE_DEVICE_STATS permission.
    @RequiresPermission(UPDATE_DEVICE_STATS)
    private void enforceImpersonationPermission() {
        mAdapterService.enforceCallingOrSelfPermission(
                UPDATE_DEVICE_STATS, "Need UPDATE_DEVICE_STATS permission");
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforceImpersonationPermissionIfNeeded(WorkSource workSource) {
        if (workSource != null) {
            enforceImpersonationPermission();
        }
    }

    /**
     * Ensures the report delay is either 0 or at least the floor value (5000ms)
     *
     * @param settings are the scan settings passed into a request to start le scanning
     * @return the passed in ScanSettings object if the report delay is 0 or above the floor value;
     *     a new ScanSettings object with the report delay being the floor value if the original
     *     report delay was between 0 and the floor value (exclusive of both)
     */
    @VisibleForTesting
    ScanSettings enforceReportDelayFloor(ScanSettings settings) {
        if (settings.getReportDelayMillis() == 0) {
            return settings;
        }

        // Need to clear identity to pass device config permission check
        final long callerToken = Binder.clearCallingIdentity();
        try {
            long floor =
                    DeviceConfig.getLong(
                            DeviceConfig.NAMESPACE_BLUETOOTH,
                            "report_delay",
                            DEFAULT_REPORT_DELAY_FLOOR);

            if (settings.getReportDelayMillis() > floor) {
                return settings;
            } else {
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

    void addScanEvent(BluetoothMetricsProto.ScanEvent event) {
        synchronized (mScanEvents) {
            if (mScanEvents.size() == NUM_SCAN_EVENTS_KEPT) {
                mScanEvents.remove();
            }
            mScanEvents.add(event);
        }
    }

    public void dumpRegisterId(StringBuilder sb) {
        sb.append("  Scanner:\n");
        mScannerMap.dumpApps(sb, ProfileService::println);
    }

    public void dump(StringBuilder sb) {
        sb.append("GATT Scanner Map\n");
        mScannerMap.dump(sb);
    }

    public void dumpProto(BluetoothMetricsProto.BluetoothLog.Builder builder) {
        synchronized (mScanEvents) {
            builder.addAllScanEvent(mScanEvents);
        }
    }
}
