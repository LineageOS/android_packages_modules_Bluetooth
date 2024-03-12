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

import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.enforceBluetoothPrivilegedPermission;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.BluetoothAdapterProxy;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.ContextMap;
import com.android.bluetooth.gatt.GattObjectsFactory;
import com.android.bluetooth.gatt.GattServiceConfig;
import com.android.bluetooth.util.NumberUtils;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A helper class which contains all scan related functions extracted from {@link
 * com.android.bluetooth.gatt.GattService}. The purpose of this class is to preserve scan
 * functionality within GattService and provide the same functionality in a new scan dedicated
 * {@link com.android.bluetooth.btservice.ProfileService} when introduced.
 *
 * @hide
 */
public class TransitionalScanHelper {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final boolean VDBG = GattServiceConfig.VDBG;
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "ScanHelper";

    // Batch scan related constants.
    private static final int TRUNCATED_RESULT_SIZE = 11;
    static final int SCAN_FILTER_ENABLED = 1;
    static final int SCAN_FILTER_MODIFIED = 2;

    /** The default floor value for LE batch scan report delays greater than 0 */
    @VisibleForTesting static final long DEFAULT_REPORT_DELAY_FLOOR = 5000;

    private static final int NUM_SCAN_EVENTS_KEPT = 20;

    // onFoundLost related constants
    private static final int ADVT_STATE_ONFOUND = 0;
    private static final int ADVT_STATE_ONLOST = 1;

    private static final int ET_LEGACY_MASK = 0x10;

    /** List of our registered scanners. */
    // TODO(b/327849650): Remove as this class adds no value. Using generics this ways is considered
    //                    an anti-pattern.
    public static class ScannerMap extends ContextMap<IScannerCallback, PendingIntentInfo> {}

    /** Keep the arguments passed in for the PendingIntent. */
    public static class PendingIntentInfo {
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

    public interface TestModeAccessor {
        /** Indicates if bluetooth test mode is enabled. */
        boolean isTestModeEnabled();
    }

    private final PendingIntent.CancelListener mScanIntentCancelListener =
            new PendingIntent.CancelListener() {
                public void onCanceled(PendingIntent intent) {
                    Log.d(TAG, "scanning PendingIntent canceled");
                    stopScan(intent, mContext.getAttributionSource());
                }
            };

    private Context mContext;
    private TestModeAccessor mTestModeAccessor;

    private AppOpsManager mAppOps;
    private CompanionDeviceManager mCompanionManager;
    private PeriodicScanManager mPeriodicScanManager;
    private ScanManager mScanManager;
    private AdapterService mAdapterService;

    private ScannerMap mScannerMap = new ScannerMap();
    private String mExposureNotificationPackage;

    public ScannerMap getScannerMap() {
        return mScannerMap;
    }

    @VisibleForTesting
    public void setScannerMap(ScannerMap scannerMap) {
        mScannerMap = scannerMap;
    }

    /** Internal list of scan events to use with the proto */
    private final ArrayDeque<BluetoothMetricsProto.ScanEvent> mScanEvents =
            new ArrayDeque<>(NUM_SCAN_EVENTS_KEPT);

    /** */
    private final Predicate<ScanResult> mLocationDenylistPredicate =
            (scanResult) -> {
                final MacAddress parsedAddress =
                        MacAddress.fromString(scanResult.getDevice().getAddress());
                if (mAdapterService.getLocationDenylistMac().test(parsedAddress.toByteArray())) {
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

    public TransitionalScanHelper(Context context, TestModeAccessor testModeAccessor) {
        mContext = context;
        mTestModeAccessor = testModeAccessor;
    }

    /**
     * Starts the LE scanning component.
     *
     * @param looper for scan operations
     */
    public void start(Looper looper) {
        mExposureNotificationPackage = mContext.getString(R.string.exposure_notification_package);
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mCompanionManager = mContext.getSystemService(CompanionDeviceManager.class);
        mAdapterService = AdapterService.getAdapterService();
        mScanManager =
                GattObjectsFactory.getInstance()
                        .createScanManager(
                                mContext,
                                this,
                                mAdapterService,
                                BluetoothAdapterProxy.getInstance(),
                                looper);

        mPeriodicScanManager =
                GattObjectsFactory.getInstance().createPeriodicScanManager(mAdapterService);
    }

    /** Stops the scanning component. */
    public void stop() {
        mScannerMap.clear();
    }

    /** Cleans up the scanning component. */
    public void cleanup() {
        if (mScanManager != null) {
            mScanManager.cleanup();
        }
        if (mPeriodicScanManager != null) {
            mPeriodicScanManager.cleanup();
        }
    }

    /** Notifies scan manager of bluetooth profile connection state changes */
    public void notifyProfileConnectionStateChange(int profile, int fromState, int toState) {
        if (mScanManager == null) {
            Log.w(TAG, "scan manager is null");
            return;
        }
        mScanManager.handleBluetoothProfileConnectionStateChanged(profile, fromState, toState);
    }

    public int getCurrentUsedTrackingAdvertisement() {
        return mScanManager.getCurrentUsedTrackingAdvertisement();
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

    private ScanResult getSanitizedExposureNotification(ScanResult result) {
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
    public void onScanResult(
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
        if (mTestModeAccessor.isTestModeEnabled()) return;

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

    // TODO(b/327849650): Refactor to reduce the visibility of this method.
    public void onScanResultInternal(
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
        if (VDBG) {
            Log.d(
                    TAG,
                    "onScanResult() - eventType=0x"
                            + Integer.toHexString(eventType)
                            + ", addressType="
                            + addressType
                            + ", address="
                            + address
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
        }

        String identityAddress = mAdapterService.getIdentityAddress(address);
        if (!address.equals(identityAddress)) {
            if (VDBG) {
                Log.d(
                        TAG,
                        "found identityAddress of "
                                + address
                                + ", replace originalAddress as "
                                + identityAddress);
            }
            originalAddress = identityAddress;
        }

        byte[] legacyAdvData = Arrays.copyOfRange(advData, 0, 62);

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            ScannerMap.App app = mScannerMap.getById(client.scannerId);
            if (app == null) {
                if (VDBG) {
                    Log.d(TAG, "App is null; skip.");
                }
                continue;
            }

            BluetoothDevice device =
                    BluetoothAdapter.getDefaultAdapter().getRemoteLeDevice(address, addressType);

            ScanSettings settings = client.settings;
            byte[] scanRecordData;
            // This is for compatibility with applications that assume fixed size scan data.
            if (settings.getLegacy()) {
                if ((eventType & ET_LEGACY_MASK) == 0) {
                    // If this is legacy scan, but nonlegacy result - skip.
                    if (VDBG) {
                        Log.d(TAG, "Legacy scan, non legacy result; skip.");
                    }
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

            if (client.hasDisavowedLocation) {
                if (mLocationDenylistPredicate.test(result)) {
                    Log.i(TAG, "Skipping client for location deny list");
                    continue;
                }
            }

            boolean hasPermission = hasScanResultPermission(client);
            if (!hasPermission) {
                for (String associatedDevice : client.associatedDevices) {
                    if (associatedDevice.equalsIgnoreCase(address)) {
                        hasPermission = true;
                        break;
                    }
                }
            }
            if (!hasPermission && client.eligibleForSanitizedExposureNotification) {
                ScanResult sanitized = getSanitizedExposureNotification(result);
                if (sanitized != null) {
                    hasPermission = true;
                    result = sanitized;
                }
            }
            boolean matchResult = matchesFilters(client, result, originalAddress);
            if (!hasPermission || !matchResult) {
                if (VDBG) {
                    Log.d(
                            TAG,
                            "Skipping client: permission="
                                    + hasPermission
                                    + " matches="
                                    + matchResult);
                }
                continue;
            }

            int callbackType = settings.getCallbackType();
            if (!(callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)) {
                if (VDBG) {
                    Log.d(TAG, "Skipping client: CALLBACK_TYPE_ALL_MATCHES");
                }
                continue;
            }

            try {
                app.appScanStats.addResult(client.scannerId);
                if (app.callback != null) {
                    app.callback.onScanResult(result);
                } else {
                    // Send the PendingIntent
                    ArrayList<ScanResult> results = new ArrayList<>();
                    results.add(result);
                    sendResultsByPendingIntent(
                            app.info, results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                }
            } catch (RemoteException | PendingIntent.CanceledException e) {
                Log.e(TAG, "Exception: " + e);
                if (Flags.leScanFixRemoteException()) {
                    handleDeadScanClient(client);
                } else {
                    mScannerMap.remove(client.scannerId);
                    mScanManager.stopScan(client.scannerId);
                }
            }
        }
    }

    private void sendResultByPendingIntent(
            PendingIntentInfo pii, ScanResult result, int callbackType, ScanClient client) {
        ArrayList<ScanResult> results = new ArrayList<>();
        results.add(result);
        try {
            sendResultsByPendingIntent(pii, results, callbackType);
        } catch (PendingIntent.CanceledException e) {
            final long token = Binder.clearCallingIdentity();
            try {
                stopScan(client.scannerId, mContext.getAttributionSource());
                unregisterScanner(client.scannerId, mContext.getAttributionSource());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private void sendResultsByPendingIntent(
            PendingIntentInfo pii, ArrayList<ScanResult> results, int callbackType)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, results);
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType);
        pii.intent.send(mContext, 0, extrasIntent);
    }

    private void sendErrorByPendingIntent(PendingIntentInfo pii, int errorCode)
            throws PendingIntent.CanceledException {
        Intent extrasIntent = new Intent();
        extrasIntent.putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, errorCode);
        pii.intent.send(mContext, 0, extrasIntent);
    }

    /** Callback method for scanner registration. */
    public void onScannerRegistered(int status, int scannerId, long uuidLsb, long uuidMsb)
            throws RemoteException {
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        if (DBG) {
            Log.d(
                    TAG,
                    "onScannerRegistered() - UUID="
                            + uuid
                            + ", scannerId="
                            + scannerId
                            + ", status="
                            + status);
        }

        // First check the callback map
        ScannerMap.App cbApp = mScannerMap.getByUuid(uuid);
        if (cbApp != null) {
            if (status == 0) {
                cbApp.id = scannerId;
                // If app is callback based, setup a death recipient. App will initiate the start.
                // Otherwise, if PendingIntent based, start the scan directly.
                if (cbApp.callback != null) {
                    cbApp.linkToDeath(new ScannerDeathRecipient(scannerId, cbApp.name));
                } else {
                    continuePiStartScan(scannerId, cbApp);
                }
            } else {
                mScannerMap.remove(scannerId);
            }
            if (cbApp.callback != null) {
                cbApp.callback.onScannerRegistered(status, scannerId);
            }
        }
    }

    /** Determines if the given scan client has the appropriate permissions to receive callbacks. */
    private boolean hasScanResultPermission(final ScanClient client) {
        if (client.hasNetworkSettingsPermission
                || client.hasNetworkSetupWizardPermission
                || client.hasScanWithoutLocationPermission) {
            return true;
        }
        if (client.hasDisavowedLocation) {
            return true;
        }
        return client.hasLocationPermission
                && !Utils.blockedByLocationOff(mContext, client.userHandle);
    }

    // Check if a scan record matches a specific filters.
    private boolean matchesFilters(ScanClient client, ScanResult scanResult) {
        return matchesFilters(client, scanResult, null);
    }

    // Check if a scan record matches a specific filters or original address
    private boolean matchesFilters(
            ScanClient client, ScanResult scanResult, String originalAddress) {
        if (client.filters == null || client.filters.isEmpty()) {
            // TODO: Do we really wanna return true here?
            return true;
        }
        for (ScanFilter filter : client.filters) {
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
        if (client.appDied) {
            Log.w(TAG, "Already dead client " + client.scannerId);
            return;
        }
        client.appDied = true;
        stopScan(client.scannerId, mContext.getAttributionSource());
    }

    /** Callback method for scan filter enablement/disablement. */
    public void onScanFilterEnableDisabled(int action, int status, int clientIf) {
        if (DBG) {
            Log.d(
                    TAG,
                    "onScanFilterEnableDisabled() - clientIf="
                            + clientIf
                            + ", status="
                            + status
                            + ", action="
                            + action);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter params. */
    public void onScanFilterParamsConfigured(
            int action, int status, int clientIf, int availableSpace) {
        if (DBG) {
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
        }
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of scan filter. */
    public void onScanFilterConfig(
            int action, int status, int clientIf, int filterType, int availableSpace) {
        if (DBG) {
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
        }

        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for configuration of batch scan storage. */
    public void onBatchScanStorageConfigured(int status, int clientIf) {
        if (DBG) {
            Log.d(
                    TAG,
                    "onBatchScanStorageConfigured() - clientIf=" + clientIf + ", status=" + status);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    /** Callback method for start/stop of batch scan. */
    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped.
    public void onBatchScanStartStopped(int startStopAction, int status, int clientIf) {
        if (DBG) {
            Log.d(
                    TAG,
                    "onBatchScanStartStopped() - clientIf="
                            + clientIf
                            + ", status="
                            + status
                            + ", startStopAction="
                            + startStopAction);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    ScanClient findBatchScanClientById(int scannerId) {
        for (ScanClient client : mScanManager.getBatchScanQueue()) {
            if (client.scannerId == scannerId) {
                return client;
            }
        }
        return null;
    }

    /** Callback method for batch scan reports */
    public void onBatchScanReports(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData)
            throws RemoteException {
        // When in testing mode, ignore all real-world events
        if (mTestModeAccessor.isTestModeEnabled()) return;

        AppScanStats.recordBatchScanRadioResultCount(numRecords);
        onBatchScanReportsInternal(status, scannerId, reportType, numRecords, recordData);
    }

    @VisibleForTesting
    void onBatchScanReportsInternal(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData)
            throws RemoteException {
        if (DBG) {
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
        }

        Set<ScanResult> results = parseBatchScanResults(numRecords, reportType, recordData);
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
            // We only support single client for truncated mode.
            ScannerMap.App app = mScannerMap.getById(scannerId);
            if (app == null) {
                return;
            }

            ScanClient client = findBatchScanClientById(scannerId);
            if (client == null) {
                return;
            }

            ArrayList<ScanResult> permittedResults;
            if (hasScanResultPermission(client)) {
                permittedResults = new ArrayList<ScanResult>(results);
            } else {
                permittedResults = new ArrayList<ScanResult>();
                for (ScanResult scanResult : results) {
                    for (String associatedDevice : client.associatedDevices) {
                        if (associatedDevice.equalsIgnoreCase(
                                scanResult.getDevice().getAddress())) {
                            permittedResults.add(scanResult);
                        }
                    }
                }
                if (permittedResults.isEmpty()) {
                    return;
                }
            }

            if (client.hasDisavowedLocation) {
                permittedResults.removeIf(mLocationDenylistPredicate);
            }

            if (app.callback != null) {
                app.callback.onBatchScanResults(permittedResults);
            } else {
                // PendingIntent based
                try {
                    sendResultsByPendingIntent(
                            app.info, permittedResults, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                } catch (PendingIntent.CanceledException e) {
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
            ScannerMap.App app, ScanClient client, ArrayList<ScanResult> results) {
        try {
            if (app.callback != null) {
                if (mScanManager.isAutoBatchScanClientEnabled(client)) {
                    if (DBG) {
                        Log.d(TAG, "sendBatchScanResults() to onScanResult()" + client);
                    }
                    for (ScanResult result : results) {
                        app.appScanStats.addResult(client.scannerId);
                        app.callback.onScanResult(result);
                    }
                } else {
                    if (DBG) {
                        Log.d(TAG, "sendBatchScanResults() to onBatchScanResults()" + client);
                    }
                    app.callback.onBatchScanResults(results);
                }
            } else {
                sendResultsByPendingIntent(
                        app.info, results, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
        } catch (RemoteException | PendingIntent.CanceledException e) {
            Log.e(TAG, "Exception: " + e);
            if (Flags.leScanFixRemoteException()) {
                handleDeadScanClient(client);
            } else {
                mScannerMap.remove(client.scannerId);
                mScanManager.stopScan(client.scannerId);
            }
        }
    }

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults)
            throws RemoteException {
        ContextMap.App app = mScannerMap.getById(client.scannerId);
        if (app == null) {
            return;
        }

        ArrayList<ScanResult> permittedResults;
        if (hasScanResultPermission(client)) {
            permittedResults = new ArrayList<ScanResult>(allResults);
        } else {
            permittedResults = new ArrayList<ScanResult>();
            for (ScanResult scanResult : allResults) {
                for (String associatedDevice : client.associatedDevices) {
                    if (associatedDevice.equalsIgnoreCase(scanResult.getDevice().getAddress())) {
                        permittedResults.add(scanResult);
                    }
                }
            }
            if (permittedResults.isEmpty()) {
                return;
            }
        }

        if (client.filters == null || client.filters.isEmpty()) {
            sendBatchScanResults(app, client, permittedResults);
            // TODO: Question to reviewer: Shouldn't there be a return here?
        }
        // Reconstruct the scan results.
        ArrayList<ScanResult> results = new ArrayList<ScanResult>();
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
        if (DBG) {
            Log.d(TAG, "current time is " + SystemClock.elapsedRealtimeNanos());
        }
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
            return parseTruncatedResults(numRecords, batchRecord);
        } else {
            return parseFullResults(numRecords, batchRecord);
        }
    }

    private Set<ScanResult> parseTruncatedResults(int numRecords, byte[] batchRecord) {
        if (DBG) {
            Log.d(TAG, "batch record " + Arrays.toString(batchRecord));
        }
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        long now = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < numRecords; ++i) {
            byte[] record =
                    extractBytes(batchRecord, i * TRUNCATED_RESULT_SIZE, TRUNCATED_RESULT_SIZE);
            byte[] address = extractBytes(record, 0, 6);
            reverse(address);
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
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
        if (DBG) {
            Log.d(TAG, "Batch record : " + Arrays.toString(batchRecord));
        }
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        int position = 0;
        long now = SystemClock.elapsedRealtimeNanos();
        while (position < batchRecord.length) {
            byte[] address = extractBytes(batchRecord, position, 6);
            // TODO: remove temp hack.
            reverse(address);
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
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
            if (DBG) {
                Log.d(TAG, "ScanRecord : " + Arrays.toString(scanRecord));
            }
            results.add(
                    new ScanResult(
                            device, ScanRecord.parseFromBytes(scanRecord), rssi, timestampNanos));
        }
        return results;
    }

    // Reverse byte array.
    private void reverse(byte[] address) {
        int len = address.length;
        for (int i = 0; i < len / 2; ++i) {
            byte b = address[i];
            address[i] = address[len - 1 - i];
            address[len - 1 - i] = b;
        }
    }

    // Helper method to extract bytes from byte array.
    private static byte[] extractBytes(byte[] scanRecord, int start, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(scanRecord, start, bytes, 0, length);
        return bytes;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void onBatchScanThresholdCrossed(int clientIf) {
        if (DBG) {
            Log.d(TAG, "onBatchScanThresholdCrossed() - clientIf=" + clientIf);
        }
        flushPendingBatchResults(clientIf, mContext.getAttributionSource());
    }

    public AdvtFilterOnFoundOnLostInfo createOnTrackAdvFoundLostObject(
            int clientIf,
            int advPktLen,
            byte[] advPkt,
            int scanRspLen,
            byte[] scanRsp,
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
                advPktLen,
                advPkt,
                scanRspLen,
                scanRsp,
                filtIndex,
                advState,
                advInfoPresent,
                address,
                addrType,
                txPower,
                rssiValue,
                timeStamp);
    }

    public void onTrackAdvFoundLost(AdvtFilterOnFoundOnLostInfo trackingInfo)
            throws RemoteException {
        if (DBG) {
            Log.d(
                    TAG,
                    "onTrackAdvFoundLost() - scannerId= "
                            + trackingInfo.getClientIf()
                            + " address = "
                            + trackingInfo.getAddress()
                            + " adv_state = "
                            + trackingInfo.getAdvState());
        }

        ScannerMap.App app = mScannerMap.getById(trackingInfo.getClientIf());
        if (app == null || (app.callback == null && app.info == null)) {
            Log.e(TAG, "app or callback is null");
            return;
        }

        BluetoothDevice device =
                BluetoothAdapter.getDefaultAdapter().getRemoteDevice(trackingInfo.getAddress());
        int advertiserState = trackingInfo.getAdvState();
        ScanResult result =
                new ScanResult(
                        device,
                        ScanRecord.parseFromBytes(trackingInfo.getResult()),
                        trackingInfo.getRSSIValue(),
                        SystemClock.elapsedRealtimeNanos());

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.scannerId == trackingInfo.getClientIf()) {
                ScanSettings settings = client.settings;
                if ((advertiserState == ADVT_STATE_ONFOUND)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                                != 0)) {
                    if (app.callback != null) {
                        app.callback.onFoundOrLost(true, result);
                    } else {
                        sendResultByPendingIntent(
                                app.info, result, ScanSettings.CALLBACK_TYPE_FIRST_MATCH, client);
                    }
                } else if ((advertiserState == ADVT_STATE_ONLOST)
                        && ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                                != 0)) {
                    if (app.callback != null) {
                        app.callback.onFoundOrLost(false, result);
                    } else {
                        sendResultByPendingIntent(
                                app.info, result, ScanSettings.CALLBACK_TYPE_MATCH_LOST, client);
                    }
                } else {
                    if (DBG) {
                        Log.d(
                                TAG,
                                "Not reporting onlost/onfound : "
                                        + advertiserState
                                        + " scannerId = "
                                        + client.scannerId
                                        + " callbackType "
                                        + settings.getCallbackType());
                    }
                }
            }
        }
    }

    public void onScanParamSetupCompleted(int status, int scannerId) throws RemoteException {
        ContextMap.App app = mScannerMap.getById(scannerId);
        if (app == null || app.callback == null) {
            Log.e(TAG, "Advertise app or callback is null");
            return;
        }
        if (DBG) {
            Log.d(TAG, "onScanParamSetupCompleted : " + status);
        }
    }

    // callback from ScanManager for dispatch of errors apps.
    public void onScanManagerErrorCallback(int scannerId, int errorCode) throws RemoteException {
        ScannerMap.App app = mScannerMap.getById(scannerId);
        if (app == null || (app.callback == null && app.info == null)) {
            Log.e(TAG, "App or callback is null");
            return;
        }
        if (app.callback != null) {
            app.callback.onScanManagerErrorCallback(errorCode);
        } else {
            try {
                sendErrorByPendingIntent(app.info, errorCode);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Error sending error code via PendingIntent:" + e);
            }
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void registerScanner(
            IScannerCallback callback, WorkSource workSource, AttributionSource attributionSource)
            throws RemoteException {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper registerScanner")) {
            return;
        }

        UUID uuid = UUID.randomUUID();
        if (DBG) {
            Log.d(TAG, "registerScanner() - UUID=" + uuid);
        }

        enforceImpersonatationPermissionIfNeeded(workSource);

        AppScanStats app = mScannerMap.getAppScanStatsByUid(Binder.getCallingUid());
        if (app != null
                && app.isScanningTooFrequently()
                && !Utils.checkCallerHasPrivilegedPermission(mContext)) {
            Log.e(TAG, "App '" + app.appName + "' is scanning too frequently");
            callback.onScannerRegistered(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, -1);
            return;
        }

        mScannerMap.add(uuid, workSource, callback, null, mContext, this);
        mScanManager.registerScanner(uuid);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void unregisterScanner(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper unregisterScanner")) {
            return;
        }

        if (DBG) {
            Log.d(TAG, "unregisterScanner() - scannerId=" + scannerId);
        }
        mScannerMap.remove(scannerId);
        mScanManager.unregisterScanner(scannerId);
    }

    private List<String> getAssociatedDevices(String callingPackage) {
        if (mCompanionManager == null) {
            return Collections.emptyList();
        }

        List<String> macAddresses = new ArrayList();

        final long identity = Binder.clearCallingIdentity();
        try {
            for (AssociationInfo info : Utils.getCdmAssociations(mCompanionManager)) {
                if (info.getPackageName().equals(callingPackage)
                        && !info.isSelfManaged()
                        && info.getDeviceMacAddress() != null) {
                    macAddresses.add(info.getDeviceMacAddress().toString());
                }
            }
        } catch (SecurityException se) {
            // Not an app with associated devices
        } catch (Exception e) {
            Log.e(TAG, "Cannot check device associations for " + callingPackage, e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return macAddresses;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void startScan(
            int scannerId,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        if (DBG) {
            Log.d(TAG, "start scan with filters");
        }

        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "Starting GATT scan.")) {
            return;
        }

        enforcePrivilegedPermissionIfNeeded(settings);
        String callingPackage = attributionSource.getPackageName();
        settings = enforceReportDelayFloor(settings);
        enforcePrivilegedPermissionIfNeeded(filters);
        final ScanClient scanClient = new ScanClient(scannerId, settings, filters);
        scanClient.userHandle = Binder.getCallingUserHandle();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        scanClient.eligibleForSanitizedExposureNotification =
                callingPackage.equals(mExposureNotificationPackage);

        scanClient.hasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(
                        mContext, attributionSource, mTestModeAccessor.isTestModeEnabled());

        scanClient.isQApp = checkCallerTargetSdk(mContext, callingPackage, Build.VERSION_CODES.Q);
        if (!scanClient.hasDisavowedLocation) {
            if (scanClient.isQApp) {
                scanClient.hasLocationPermission =
                        Utils.checkCallerHasFineLocation(
                                mContext, attributionSource, scanClient.userHandle);
            } else {
                scanClient.hasLocationPermission =
                        Utils.checkCallerHasCoarseOrFineLocation(
                                mContext, attributionSource, scanClient.userHandle);
            }
        }
        scanClient.hasNetworkSettingsPermission =
                Utils.checkCallerHasNetworkSettingsPermission(mContext);
        scanClient.hasNetworkSetupWizardPermission =
                Utils.checkCallerHasNetworkSetupWizardPermission(mContext);
        scanClient.hasScanWithoutLocationPermission =
                Utils.checkCallerHasScanWithoutLocationPermission(mContext);
        scanClient.associatedDevices = getAssociatedDevices(callingPackage);

        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        ContextMap.App cbApp = mScannerMap.getById(scannerId);
        if (app != null) {
            scanClient.stats = app;
            boolean isFilteredScan = (filters != null) && !filters.isEmpty();
            boolean isCallbackScan = false;
            if (cbApp != null) {
                isCallbackScan = cbApp.callback != null;
            }
            app.recordScanStart(settings, filters, isFilteredScan, isCallbackScan, scannerId);
        }

        mScanManager.startScan(scanClient);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void registerPiAndStartScan(
            PendingIntent pendingIntent,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        if (DBG) {
            Log.d(TAG, "start scan with filters, for PendingIntent");
        }

        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "Starting GATT scan.")) {
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
        if (DBG) {
            Log.d(
                    TAG,
                    "startScan(PI) -"
                            + (" UUID=" + uuid)
                            + (" Package=" + callingPackage)
                            + (" UID=" + callingUid));
        }

        // Don't start scan if the Pi scan already in mScannerMap.
        if (mScannerMap.getByContextInfo(piInfo) != null) {
            Log.d(TAG, "Don't startScan(PI) since the same Pi scan already in mScannerMap.");
            return;
        }

        ContextMap.App app = mScannerMap.add(uuid, null, null, piInfo, mContext, this);

        app.mUserHandle = UserHandle.getUserHandleForUid(Binder.getCallingUid());
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        app.mEligibleForSanitizedExposureNotification =
                callingPackage.equals(mExposureNotificationPackage);

        app.mHasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(
                        mContext, attributionSource, mTestModeAccessor.isTestModeEnabled());

        if (!app.mHasDisavowedLocation) {
            try {
                if (checkCallerTargetSdk(mContext, callingPackage, Build.VERSION_CODES.Q)) {
                    app.hasLocationPermission =
                            Utils.checkCallerHasFineLocation(
                                    mContext, attributionSource, app.mUserHandle);
                } else {
                    app.hasLocationPermission =
                            Utils.checkCallerHasCoarseOrFineLocation(
                                    mContext, attributionSource, app.mUserHandle);
                }
            } catch (SecurityException se) {
                // No need to throw here. Just mark as not granted.
                app.hasLocationPermission = false;
            }
        }
        app.mHasNetworkSettingsPermission = Utils.checkCallerHasNetworkSettingsPermission(mContext);
        app.mHasNetworkSetupWizardPermission =
                Utils.checkCallerHasNetworkSetupWizardPermission(mContext);
        app.mHasScanWithoutLocationPermission =
                Utils.checkCallerHasScanWithoutLocationPermission(mContext);
        app.mAssociatedDevices = getAssociatedDevices(callingPackage);
        mScanManager.registerScanner(uuid);

        // If this fails, we should stop the scan immediately.
        if (!pendingIntent.addCancelListener(Runnable::run, mScanIntentCancelListener)) {
            Log.d(TAG, "scanning PendingIntent is already cancelled, stopping scan.");
            stopScan(pendingIntent, attributionSource);
        }
    }

    public void continuePiStartScan(int scannerId, ScannerMap.App app) {
        final PendingIntentInfo piInfo = app.info;
        final ScanClient scanClient =
                new ScanClient(scannerId, piInfo.settings, piInfo.filters, piInfo.callingUid);
        scanClient.hasLocationPermission = app.hasLocationPermission;
        scanClient.userHandle = app.mUserHandle;
        scanClient.isQApp = checkCallerTargetSdk(mContext, app.name, Build.VERSION_CODES.Q);
        scanClient.eligibleForSanitizedExposureNotification =
                app.mEligibleForSanitizedExposureNotification;
        scanClient.hasNetworkSettingsPermission = app.mHasNetworkSettingsPermission;
        scanClient.hasNetworkSetupWizardPermission = app.mHasNetworkSetupWizardPermission;
        scanClient.hasScanWithoutLocationPermission = app.mHasScanWithoutLocationPermission;
        scanClient.associatedDevices = app.mAssociatedDevices;
        scanClient.hasDisavowedLocation = app.mHasDisavowedLocation;

        AppScanStats scanStats = mScannerMap.getAppScanStatsById(scannerId);
        if (scanStats != null) {
            scanClient.stats = scanStats;
            boolean isFilteredScan = (piInfo.filters != null) && !piInfo.filters.isEmpty();
            scanStats.recordScanStart(
                    piInfo.settings, piInfo.filters, isFilteredScan, false, scannerId);
        }

        mScanManager.startScan(scanClient);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void flushPendingBatchResults(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper flushPendingBatchResults")) {
            return;
        }
        if (DBG) {
            Log.d(TAG, "flushPendingBatchResults - scannerId=" + scannerId);
        }
        mScanManager.flushBatchScanResults(new ScanClient(scannerId));
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void stopScan(int scannerId, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper stopScan")) {
            return;
        }
        int scanQueueSize =
                mScanManager.getBatchScanQueue().size() + mScanManager.getRegularScanQueue().size();
        if (DBG) {
            Log.d(TAG, "stopScan() - queue size =" + scanQueueSize);
        }

        AppScanStats app = mScannerMap.getAppScanStatsById(scannerId);
        if (app != null) {
            app.recordScanStop(scannerId);
        }

        mScanManager.stopScan(scannerId);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void stopScan(PendingIntent intent, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper stopScan")) {
            return;
        }
        PendingIntentInfo pii = new PendingIntentInfo();
        pii.intent = intent;
        ContextMap.App app = mScannerMap.getByContextInfo(pii);
        if (VDBG) {
            Log.d(TAG, "stopScan(PendingIntent): app found = " + app);
        }
        if (app != null) {
            intent.removeCancelListener(mScanIntentCancelListener);
            final int scannerId = app.id;
            stopScan(scannerId, attributionSource);
            // Also unregister the scanner
            unregisterScanner(scannerId, attributionSource);
        }
    }

    /**************************************************************************
     * PERIODIC SCANNING
     *************************************************************************/
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void registerSync(
            ScanResult scanResult,
            int skip,
            int timeout,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper registerSync")) {
            return;
        }
        mPeriodicScanManager.startSync(scanResult, skip, timeout, callback);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    public void unregisterSync(
            IPeriodicAdvertisingCallback callback, AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper unregisterSync")) {
            return;
        }
        mPeriodicScanManager.stopSync(callback);
    }

    public void transferSync(
            BluetoothDevice bda,
            int serviceData,
            int syncHandle,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper transferSync")) {
            return;
        }
        mPeriodicScanManager.transferSync(bda, serviceData, syncHandle);
    }

    public void transferSetInfo(
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        if (!Utils.checkScanPermissionForDataDelivery(
                mContext, attributionSource, "ScanHelper transferSetInfo")) {
            return;
        }
        mPeriodicScanManager.transferSetInfo(bda, serviceData, advHandle, callback);
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
            if (DBG) {
                Log.d(
                        TAG,
                        "Binder is dead - unregistering scanner ("
                                + mPackageName
                                + " "
                                + mScannerId
                                + ")!");
            }

            ScanClient client = getScanClient(mScannerId);
            if (client != null) {
                if (Flags.leScanFixRemoteException()) {
                    handleDeadScanClient(client);
                } else {
                    client.appDied = true;
                    stopScan(client.scannerId, mContext.getAttributionSource());
                }
            }
        }

        private ScanClient getScanClient(int clientIf) {
            for (ScanClient client : mScanManager.getRegularScanQueue()) {
                if (client.scannerId == clientIf) {
                    return client;
                }
            }
            for (ScanClient client : mScanManager.getBatchScanQueue()) {
                if (client.scannerId == clientIf) {
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
     * The {@link ScanFilter#setDeviceAddress} API overloads are @SystemApi access methods.  This
     * requires that the permissions be BLUETOOTH_PRIVILEGED.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforcePrivilegedPermissionIfNeeded(List<ScanFilter> filters) {
        if (DBG) {
            Log.d(TAG, "enforcePrivilegedPermissionIfNeeded(" + filters + ")");
        }
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
                        enforceBluetoothPrivilegedPermission(mContext);
                    }
                }
            }
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforcePrivilegedPermissionIfNeeded(ScanSettings settings) {
        if (needsPrivilegedPermissionForScan(settings)) {
            enforceBluetoothPrivilegedPermission(mContext);
        }
    }

    // Enforce caller has UPDATE_DEVICE_STATS permission, which allows the caller to blame other
    // apps for Bluetooth usage. A {@link SecurityException} will be thrown if the caller app does
    // not have UPDATE_DEVICE_STATS permission.
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    private void enforceImpersonatationPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.UPDATE_DEVICE_STATS,
                "Need UPDATE_DEVICE_STATS permission");
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforceImpersonatationPermissionIfNeeded(WorkSource workSource) {
        if (workSource != null) {
            enforceImpersonatationPermission();
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

    public void addScanEvent(BluetoothMetricsProto.ScanEvent event) {
        synchronized (mScanEvents) {
            if (mScanEvents.size() == NUM_SCAN_EVENTS_KEPT) {
                mScanEvents.remove();
            }
            mScanEvents.add(event);
        }
    }

    public void dumpProto(BluetoothMetricsProto.BluetoothLog.Builder builder) {
        synchronized (mScanEvents) {
            builder.addAllScanEvent(mScanEvents);
        }
    }
}
