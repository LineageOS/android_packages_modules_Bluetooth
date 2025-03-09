/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import android.app.ActivityManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Manages Bluetooth LE advertising operations. */
public class AdvertiseManager {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + AdvertiseManager.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    private final AdapterService mService;
    private final AdvertiseManagerNativeInterface mNativeInterface;
    private final AdvertiseBinder mAdvertiseBinder;
    private final AdvertiserMap mAdvertiserMap;
    private final ActivityManager mActivityManager;

    private final Map<IBinder, AdvertiserInfo> mAdvertisers = new HashMap<>();

    private final Handler mHandler;
    private volatile boolean mIsAvailable = true;
    @VisibleForTesting int mTempRegistrationId = -1;

    AdvertiseManager(AdapterService service, Looper advertiseLooper) {
        this(
                service,
                advertiseLooper,
                AdvertiseManagerNativeInterface.getInstance(),
                new AdvertiserMap());
    }

    @VisibleForTesting
    AdvertiseManager(
            AdapterService service,
            Looper advertiseLooper,
            AdvertiseManagerNativeInterface nativeInterface,
            AdvertiserMap advertiserMap) {
        Log.d(TAG, "advertise manager created");
        mService = service;
        mNativeInterface = nativeInterface;
        mAdvertiserMap = advertiserMap;
        mActivityManager = mService.getSystemService(ActivityManager.class);

        mNativeInterface.init(this);
        mHandler = new Handler(advertiseLooper);
        mAdvertiseBinder = new AdvertiseBinder(service, this);
    }

    void cleanup() {
        Log.d(TAG, "cleanup()");
        mIsAvailable = false;
        mHandler.removeCallbacksAndMessages(null);
        forceRunSyncOnAdvertiseThread(
                () -> {
                    mAdvertiserMap.clear();
                    mAdvertiseBinder.cleanup();
                    mNativeInterface.cleanup();
                    mAdvertisers.clear();
                });
    }

    void dump(StringBuilder sb) {
        forceRunSyncOnAdvertiseThread(() -> mAdvertiserMap.dump(sb));
    }

    AdvertiseBinder getBinder() {
        return mAdvertiseBinder;
    }

    static class AdvertiserInfo {
        /* When id is negative, the registration is ongoing. When the registration finishes, id
         * becomes equal to advertiser_id */
        public Integer id;
        public AdvertisingSetDeathRecipient deathRecipient;
        public IAdvertisingSetCallback callback;

        AdvertiserInfo(
                Integer id,
                AdvertisingSetDeathRecipient deathRecipient,
                IAdvertisingSetCallback callback) {
            this.id = id;
            this.deathRecipient = deathRecipient;
            this.callback = callback;
        }
    }

    private interface CallbackWrapper {
        void call() throws RemoteException;
    }

    IBinder toBinder(IAdvertisingSetCallback e) {
        return e.asBinder();
    }

    class AdvertisingSetDeathRecipient implements IBinder.DeathRecipient {
        public IAdvertisingSetCallback callback;
        private final String mPackageName;

        AdvertisingSetDeathRecipient(IAdvertisingSetCallback callback, String packageName) {
            this.callback = callback;
            this.mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder is dead - unregistering advertising set (" + mPackageName + ")!");
            doOnAdvertiseThread(() -> stopAdvertisingSet(callback));
        }
    }

    private Map.Entry<IBinder, AdvertiserInfo> findAdvertiser(int advertiserId) {
        Map.Entry<IBinder, AdvertiserInfo> entry = null;
        for (Map.Entry<IBinder, AdvertiserInfo> e : mAdvertisers.entrySet()) {
            if (e.getValue().id == advertiserId) {
                entry = e;
                break;
            }
        }
        return entry;
    }

    void onAdvertisingSetStarted(int regId, int advertiserId, int txPower, int status) {
        Log.d(
                TAG,
                "onAdvertisingSetStarted() - regId="
                        + regId
                        + ", advertiserId="
                        + advertiserId
                        + ", status="
                        + status);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(regId);

        if (entry == null) {
            Log.i(TAG, "onAdvertisingSetStarted() - no callback found for regId " + regId);
            // Advertising set was stopped before it was properly registered.
            mNativeInterface.stopAdvertisingSet(advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        if (status == 0) {
            entry.setValue(
                    new AdvertiserInfo(advertiserId, entry.getValue().deathRecipient, callback));

            mAdvertiserMap.setAdvertiserIdByRegId(regId, advertiserId);
        } else {
            IBinder binder = entry.getKey();
            binder.unlinkToDeath(entry.getValue().deathRecipient, 0);
            mAdvertisers.remove(binder);

            AppAdvertiseStats stats = mAdvertiserMap.getAppAdvertiseStatsById(regId);
            if (stats != null) {
                stats.recordAdvertiseStop(mAdvertisers.size());
                stats.recordAdvertiseErrorCount(status);
            }
            mAdvertiserMap.removeAppAdvertiseStats(regId);
        }

        sendToCallback(
                advertiserId,
                () ->
                        callback.onAdvertisingSetStarted(
                                mAdvertiseBinder, advertiserId, txPower, status));
    }

    void onAdvertisingEnabled(int advertiserId, boolean enable, int status) {
        Log.d(
                TAG,
                "onAdvertisingSetEnabled() - advertiserId="
                        + advertiserId
                        + ", enable="
                        + enable
                        + ", status="
                        + status);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(
                    TAG,
                    "onAdvertisingSetEnable() - no callback found for advertiserId "
                            + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId, () -> callback.onAdvertisingEnabled(advertiserId, enable, status));

        if (!enable && status != 0) {
            AppAdvertiseStats stats = mAdvertiserMap.getAppAdvertiseStatsById(advertiserId);
            if (stats != null) {
                stats.recordAdvertiseStop(mAdvertisers.size());
            }
        }
    }

    private void fetchAppForegroundState(int id) {
        PackageManager packageManager = mService.getPackageManager();
        if (mActivityManager == null || packageManager == null) {
            return;
        }
        int appUid = Binder.getCallingUid();
        String[] packages = packageManager.getPackagesForUid(appUid);
        if (packages == null || packages.length == 0) {
            return;
        }
        int importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
        for (String packageName : packages) {
            importance = Math.min(importance, mActivityManager.getPackageImportance(packageName));
        }
        AppAdvertiseStats stats = mAdvertiserMap.getAppAdvertiseStatsById(id);
        if (stats != null) {
            stats.setAppImportance(importance);
        }
    }

    void startAdvertisingSet(
            AdvertisingSetParameters parameters,
            AdvertiseData advertiseData,
            AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters,
            AdvertiseData periodicData,
            int duration,
            int maxExtAdvEvents,
            int serverIf,
            IAdvertisingSetCallback callback,
            AttributionSource attrSource) {
        checkThread();
        // If we are using an isolated server, force usage of an NRPA
        if (serverIf != 0
                && parameters.getOwnAddressType()
                        != AdvertisingSetParameters.ADDRESS_TYPE_RANDOM_NON_RESOLVABLE) {
            Log.w(TAG, "Cannot advertise an isolated GATT server using a resolvable address");
            try {
                callback.onAdvertisingSetStarted(
                        mAdvertiseBinder,
                        0x00,
                        0x00,
                        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR);
            } catch (RemoteException exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
            return;
        }

        int appUid = Binder.getCallingUid();
        String packageName = null;
        if (mService.getPackageManager() != null) {
            packageName = mService.getPackageManager().getNameForUid(appUid);
        }
        if (packageName == null) {
            packageName = "Unknown package name (UID: " + appUid + ")";
        }
        AdvertisingSetDeathRecipient deathRecipient =
                new AdvertisingSetDeathRecipient(callback, packageName);
        IBinder binder = toBinder(callback);
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to advertiser's death");
        }

        String deviceName = mService.getName();
        try {
            byte[] advDataBytes = AdvertiseHelper.advertiseDataToBytes(advertiseData, deviceName);
            byte[] scanResponseBytes =
                    AdvertiseHelper.advertiseDataToBytes(scanResponse, deviceName);
            byte[] periodicDataBytes =
                    AdvertiseHelper.advertiseDataToBytes(periodicData, deviceName);

            int cbId = --mTempRegistrationId;
            mAdvertisers.put(binder, new AdvertiserInfo(cbId, deathRecipient, callback));

            Log.d(TAG, "startAdvertisingSet() - reg_id=" + cbId + ", callback: " + binder);

            mAdvertiserMap.addAppAdvertiseStats(cbId, mService, attrSource);
            fetchAppForegroundState(cbId);
            mAdvertiserMap.recordAdvertiseStart(
                    cbId,
                    parameters,
                    advertiseData,
                    scanResponse,
                    periodicParameters,
                    periodicData,
                    duration,
                    maxExtAdvEvents);

            mNativeInterface.startAdvertisingSet(
                    parameters,
                    advDataBytes,
                    scanResponseBytes,
                    periodicParameters,
                    periodicDataBytes,
                    duration,
                    maxExtAdvEvents,
                    cbId,
                    serverIf);

        } catch (IllegalArgumentException e) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
                callback.onAdvertisingSetStarted(
                        mAdvertiseBinder,
                        0x00,
                        0x00,
                        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
            } catch (RemoteException exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
        }
    }

    void onOwnAddressRead(int advertiserId, int addressType, String address) {
        Log.d(TAG, "onOwnAddressRead() advertiserId=" + advertiserId);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "onOwnAddressRead() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId, () -> callback.onOwnAddressRead(advertiserId, addressType, address));
    }

    void getOwnAddress(int advertiserId) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "getOwnAddress() - bad advertiserId " + advertiserId);
            return;
        }
        mNativeInterface.getOwnAddress(advertiserId);
    }

    void stopAdvertisingSet(IAdvertisingSetCallback callback) {
        checkThread();
        IBinder binder = toBinder(callback);
        Log.d(TAG, "stopAdvertisingSet() " + binder);

        AdvertiserInfo adv = mAdvertisers.remove(binder);
        if (adv == null) {
            Log.e(TAG, "stopAdvertisingSet() - no client found for callback");
            return;
        }

        Integer advertiserId = adv.id;
        binder.unlinkToDeath(adv.deathRecipient, 0);

        if (advertiserId < 0) {
            Log.i(TAG, "stopAdvertisingSet() - advertiser not finished registration yet");
            // Advertiser will be freed once initiated in onAdvertisingSetStarted()
            return;
        }

        mNativeInterface.stopAdvertisingSet(advertiserId);

        try {
            callback.onAdvertisingSetStopped(advertiserId);
        } catch (RemoteException e) {
            Log.i(TAG, "error sending onAdvertisingSetStopped callback", e);
        }

        mAdvertiserMap.recordAdvertiseStop(advertiserId);
    }

    void enableAdvertisingSet(int advertiserId, boolean enable, int duration, int maxExtAdvEvents) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "enableAdvertisingSet() - bad advertiserId " + advertiserId);
            return;
        }
        fetchAppForegroundState(advertiserId);
        mNativeInterface.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);

        mAdvertiserMap.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);
    }

    void setAdvertisingData(int advertiserId, AdvertiseData data) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setAdvertisingData() - bad advertiserId " + advertiserId);
            return;
        }
        String deviceName = mService.getName();
        try {
            mNativeInterface.setAdvertisingData(
                    advertiserId, AdvertiseHelper.advertiseDataToBytes(data, deviceName));

            mAdvertiserMap.setAdvertisingData(advertiserId, data);
        } catch (IllegalArgumentException e) {
            try {
                onAdvertisingDataSet(
                        advertiserId, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
            } catch (Exception exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
        }
    }

    void setScanResponseData(int advertiserId, AdvertiseData data) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setScanResponseData() - bad advertiserId " + advertiserId);
            return;
        }
        String deviceName = mService.getName();
        try {
            mNativeInterface.setScanResponseData(
                    advertiserId, AdvertiseHelper.advertiseDataToBytes(data, deviceName));

            mAdvertiserMap.setScanResponseData(advertiserId, data);
        } catch (IllegalArgumentException e) {
            try {
                onScanResponseDataSet(
                        advertiserId, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
            } catch (Exception exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
        }
    }

    void setAdvertisingParameters(int advertiserId, AdvertisingSetParameters parameters) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setAdvertisingParameters() - bad advertiserId " + advertiserId);
            return;
        }
        mNativeInterface.setAdvertisingParameters(advertiserId, parameters);

        mAdvertiserMap.setAdvertisingParameters(advertiserId, parameters);
    }

    void setPeriodicAdvertisingParameters(
            int advertiserId, PeriodicAdvertisingParameters parameters) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingParameters() - bad advertiserId " + advertiserId);
            return;
        }
        mNativeInterface.setPeriodicAdvertisingParameters(advertiserId, parameters);

        mAdvertiserMap.setPeriodicAdvertisingParameters(advertiserId, parameters);
    }

    void setPeriodicAdvertisingData(int advertiserId, AdvertiseData data) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingData() - bad advertiserId " + advertiserId);
            return;
        }
        String deviceName = mService.getName();
        try {
            mNativeInterface.setPeriodicAdvertisingData(
                    advertiserId, AdvertiseHelper.advertiseDataToBytes(data, deviceName));

            mAdvertiserMap.setPeriodicAdvertisingData(advertiserId, data);
        } catch (IllegalArgumentException e) {
            try {
                onPeriodicAdvertisingDataSet(
                        advertiserId, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
            } catch (Exception exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
        }
    }

    void setPeriodicAdvertisingEnable(int advertiserId, boolean enable) {
        checkThread();
        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingEnable() - bad advertiserId " + advertiserId);
            return;
        }
        mNativeInterface.setPeriodicAdvertisingEnable(advertiserId, enable);
    }

    void onAdvertisingDataSet(int advertiserId, int status) {
        checkThread();
        Log.d(TAG, "onAdvertisingDataSet() advertiserId=" + advertiserId + ", status=" + status);

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingDataSet() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(advertiserId, () -> callback.onAdvertisingDataSet(advertiserId, status));
    }

    void onScanResponseDataSet(int advertiserId, int status) {
        checkThread();
        Log.d(TAG, "onScanResponseDataSet() advertiserId=" + advertiserId + ", status=" + status);

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onScanResponseDataSet() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(advertiserId, () -> callback.onScanResponseDataSet(advertiserId, status));
    }

    void onAdvertisingParametersUpdated(int advertiserId, int txPower, int status) {
        Log.d(
                TAG,
                "onAdvertisingParametersUpdated() advertiserId="
                        + advertiserId
                        + ", txPower="
                        + txPower
                        + ", status="
                        + status);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingParametersUpdated() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId,
                () -> callback.onAdvertisingParametersUpdated(advertiserId, txPower, status));
    }

    void onPeriodicAdvertisingParametersUpdated(int advertiserId, int status) {
        Log.d(
                TAG,
                "onPeriodicAdvertisingParametersUpdated() advertiserId="
                        + advertiserId
                        + ", status="
                        + status);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(
                    TAG,
                    "onPeriodicAdvertisingParametersUpdated() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId,
                () -> callback.onPeriodicAdvertisingParametersUpdated(advertiserId, status));
    }

    void onPeriodicAdvertisingDataSet(int advertiserId, int status) {
        Log.d(
                TAG,
                "onPeriodicAdvertisingDataSet() advertiserId="
                        + advertiserId
                        + ", status="
                        + status);
        checkThread();

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onPeriodicAdvertisingDataSet() - bad advertiserId " + advertiserId);
            return;
        }

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId, () -> callback.onPeriodicAdvertisingDataSet(advertiserId, status));
    }

    void onPeriodicAdvertisingEnabled(int advertiserId, boolean enable, int status) {
        Log.d(
                TAG,
                "onPeriodicAdvertisingEnabled() advertiserId="
                        + advertiserId
                        + ", status="
                        + status);

        Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingSetEnable() - bad advertiserId " + advertiserId);
            return;
        }
        checkThread();

        IAdvertisingSetCallback callback = entry.getValue().callback;
        sendToCallback(
                advertiserId,
                () -> callback.onPeriodicAdvertisingEnabled(advertiserId, enable, status));

        AppAdvertiseStats stats = mAdvertiserMap.getAppAdvertiseStatsById(advertiserId);
        if (stats != null) {
            stats.onPeriodicAdvertiseEnabled(enable);
        }
    }

    void doOnAdvertiseThread(Runnable r) {
        if (mIsAvailable) {
            if (Flags.advertiseThread()) {
                boolean posted =
                        mHandler.post(
                                () -> {
                                    if (mIsAvailable) {
                                        r.run();
                                    }
                                });
                if (!posted) {
                    Log.w(TAG, "Unable to post async task");
                }
            } else {
                r.run();
            }
        }
    }

    private void forceRunSyncOnAdvertiseThread(Runnable r) {
        if (!Flags.advertiseThread()) {
            r.run();
            return;
        }
        final CompletableFuture<Void> future = new CompletableFuture<>();
        boolean posted =
                mHandler.postAtFrontOfQueue(
                        () -> {
                            r.run();
                            future.complete(null);
                        });
        if (!posted) {
            Log.w(TAG, "Unable to post sync task");
            return;
        }
        try {
            future.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            Log.w(TAG, "Unable to complete sync task: " + e);
        }
    }

    private void checkThread() {
        if (Flags.advertiseThread()
                && !mHandler.getLooper().isCurrentThread()
                && !Utils.isInstrumentationTestMode()) {
            throw new IllegalStateException("Not on advertise thread");
        }
    }

    private static void sendToCallback(int advertiserId, CallbackWrapper wrapper) {
        try {
            wrapper.call();
        } catch (RemoteException e) {
            Log.i(TAG, "RemoteException in callback for advertiserId: " + advertiserId);
        }
    }
}
