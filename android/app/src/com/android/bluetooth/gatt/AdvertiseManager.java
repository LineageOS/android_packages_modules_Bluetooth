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

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.getMaxTxPowerDbm;
import static com.android.bluetooth.gatt.AdvertiseHelper.advertiseDataToBytes;

import static java.util.Objects.requireNonNullElseGet;

import android.app.ActivityManager;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Manages Bluetooth LE advertising operations. */
public class AdvertiseManager {
    private static final String TAG = GattUtil.TAG_PREFIX + AdvertiseManager.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    private final Map<IBinder, AdvertiserInfo> mAdvertisers = new HashMap<>();

    private final AdapterService mAdapterService;
    private final GattService mGattService;
    private final AdvertiseManagerNativeInterface mNativeInterface;
    private final AdvertiseBinder mAdvertiseBinder;
    private final AdvertiserMap mAdvertiserMap;
    private final ActivityManager mActivityManager;
    private final Handler mHandler;
    private final Optional<AdvertiseSuspendManager> mAdvertiseSuspendManager;

    private volatile boolean mIsAvailable = true;
    @VisibleForTesting int mTempRegistrationId = -1;

    AdvertiseManager(
            AdapterService adapterService,
            GattService gattService,
            AdvertiseManagerNativeInterface nativeInterface,
            Looper advertiseLooper) {
        this(adapterService, gattService, nativeInterface, advertiseLooper, new AdvertiserMap());
    }

    @VisibleForTesting
    AdvertiseManager(
            AdapterService adapterService,
            GattService gattService,
            AdvertiseManagerNativeInterface nativeInterface,
            Looper advertiseLooper,
            AdvertiserMap advertiserMap) {
        Log.d(TAG, "Created");
        mAdapterService = adapterService;
        mGattService = gattService;
        var nativeCallback = new AdvertiseManagerNativeCallback(mAdapterService, this);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new AdvertiseManagerNativeInterface(nativeCallback));
        mAdvertiserMap = advertiserMap;
        mActivityManager = mAdapterService.getSystemService(ActivityManager.class);
        mNativeInterface.init();
        mHandler = new Handler(advertiseLooper);
        mAdvertiseBinder = new AdvertiseBinder(mAdapterService, mGattService, this);
        mAdvertiseSuspendManager =
                mAdapterService
                        .getAdapterSuspend()
                        .filter(adapterSuspend -> adapterSuspend.isPauseAdvertisementEnabled())
                        .map(adapterSuspend -> new AdvertiseSuspendManager(this, adapterSuspend));
    }

    /** Called by AdapterSuspend. We need to prepare for suspend by pausing all advertisements. */
    public void enterSuspend() {
        mAdvertiseSuspendManager.ifPresent(manager -> doOnAdvertiseThread(manager::enterSuspend));
    }

    /** Called by AdapterSuspend. We should re-enable paused advertisements. */
    public void exitSuspend() {
        mAdvertiseSuspendManager.ifPresent(manager -> doOnAdvertiseThread(manager::exitSuspend));
    }

    void cleanup() {
        Log.i(TAG, "cleanup()");
        if (Flags.gattThread()) {
            enforceThread();

            mIsAvailable = false;
            mHandler.removeCallbacksAndMessages(null);
            mAdvertiserMap.clear();
            mAdvertiseBinder.cleanup();
            mNativeInterface.cleanup();
            mAdvertisers.clear();
            mAdvertiseSuspendManager.ifPresent(AdvertiseSuspendManager::cleanup);
        } else {
            mIsAvailable = false;
            mHandler.removeCallbacksAndMessages(null);
            forceRunSyncOnAdvertiseThread(
                    () -> {
                        mAdvertiserMap.clear();
                        mAdvertiseBinder.cleanup();
                        mNativeInterface.cleanup();
                        mAdvertisers.clear();
                        mAdvertiseSuspendManager.ifPresent(AdvertiseSuspendManager::cleanup);
                    });
        }
    }

    void dump(StringBuilder sb) {
        forceRunSyncOnAdvertiseThread(() -> mAdvertiserMap.dump(sb));
    }

    AdvertiseBinder getBinder() {
        return mAdvertiseBinder;
    }

    private record AdvertiserInfo(
            /* When id is negative, the registration is ongoing. When the registration finishes, id
             * becomes equal to advertiser_id */
            Integer id, ActionOnDeathRecipient deathRecipient, IAdvertisingSetCallback callback) {}

    private Map.Entry<IBinder, AdvertiserInfo> findAdvertiser(int advertiserId) {
        return mAdvertisers.entrySet().stream()
                .filter(e -> e.getValue().id == advertiserId)
                .findFirst()
                .orElse(null);
    }

    void onAdvertisingSetStarted(int regId, int advertiserId, int txPower, int status) {
        Log.d(
                TAG,
                ("onAdvertisingSetStarted(): regId=" + regId + ", advertiserId=" + advertiserId)
                        + (", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(regId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingSetStarted(): No callback found for regId " + regId);
            // Advertising set was stopped before it was properly registered.
            mAdvertiseSuspendManager.ifPresent(
                    manager -> {
                        manager.onAdvertisingSetStarted(regId, advertiserId, status);
                        manager.onStopAdvertisingSet(advertiserId);
                    });
            mNativeInterface.stopAdvertisingSet(advertiserId);
            return;
        }

        final var advertiserInfo = entry.getValue();
        final var deathRecipient = advertiserInfo.deathRecipient;
        final var callback = advertiserInfo.callback;

        if (status == 0) {
            entry.setValue(new AdvertiserInfo(advertiserId, deathRecipient, callback));
            mAdvertiserMap.setAdvertiserIdByRegId(regId, advertiserId);
        } else {
            final var binder = entry.getKey();
            binder.unlinkToDeath(deathRecipient, 0);
            mAdvertisers.remove(binder);

            final var appAdvertiseStats = mAdvertiserMap.getAppAdvertiseStatsById(regId);
            if (appAdvertiseStats != null) {
                appAdvertiseStats.recordAdvertiseStop(mAdvertisers.size());
                appAdvertiseStats.recordAdvertiseErrorCount(status);
            }
            mAdvertiserMap.removeAppAdvertiseStats(regId);
        }

        mAdvertiseSuspendManager.ifPresent(
                manager -> manager.onAdvertisingSetStarted(regId, advertiserId, status));

        callbackToApp(() -> callback.onAdvertisingSetStarted(advertiserId, txPower, status));
    }

    void onAdvertisingEnabled(int advertiserId, boolean enable, int status) {
        Log.d(
                TAG,
                ("onAdvertisingSetEnabled(): advertiserId=" + advertiserId + ", enable=" + enable)
                        + (", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(
                    TAG,
                    "onAdvertisingSetEnable(): No callback found for advertiserId=" + advertiserId);
            return;
        }

        if (!enable && status != 0) {
            final var appAdvertiseStats = mAdvertiserMap.getAppAdvertiseStatsById(advertiserId);
            if (appAdvertiseStats != null) {
                appAdvertiseStats.recordAdvertiseStop(mAdvertisers.size());
            }
        }

        var shouldFireCallback =
                mAdvertiseSuspendManager
                        .map(manager -> manager.onAdvertisingEnabled(advertiserId, enable, status))
                        .orElse(true);
        if (shouldFireCallback) {
            final var callback = entry.getValue().callback;
            callbackToApp(() -> callback.onAdvertisingEnabled(advertiserId, enable, status));
        }
    }

    private void fetchAppForegroundState(int uid, int id) {
        final var packageManager = mAdapterService.getPackageManager();
        if (mActivityManager == null || packageManager == null) {
            return;
        }
        String[] packages = packageManager.getPackagesForUid(uid);
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

    private boolean shouldQueueCommand(String cmdName) {
        if (mAdvertiseSuspendManager
                .filter(AdvertiseSuspendManager::shouldQueueCommand)
                .isPresent()) {
            Log.i(TAG, "Suspending! Queue command [" + cmdName + "] and return early.");
            return true;
        }
        return false;
    }

    void startAdvertisingSet(
            AdvertisingSetParameters parameters,
            AdvertiseData advertiseData,
            AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters,
            AdvertiseData periodicData,
            int duration,
            int maxExtAdvEvents,
            IBluetoothGattServerCallback gattServerCallback,
            IAdvertisingSetCallback callback,
            AttributionSource source) {
        enforceThread();

        if (shouldQueueCommand("startAdvertisingSet")) {
            mAdvertiseSuspendManager
                    .get()
                    .queueStartAdvertisingSet(
                            parameters,
                            advertiseData,
                            scanResponse,
                            periodicParameters,
                            periodicData,
                            duration,
                            maxExtAdvEvents,
                            gattServerCallback,
                            callback,
                            source);
            return;
        }

        // If we are using an isolated server, force usage of an NRPA
        int serverIf = 0;
        if (gattServerCallback != null) {
            var serverApp = mGattService.getServerMap().getByCallbackId(gattServerCallback);
            if (serverApp == null) {
                Log.w(TAG, "startAdvertisingSet(" + gattServerCallback + "): App not registered");
            } else {
                serverIf = serverApp.getId();
            }
        }
        if (serverIf != 0
                && parameters.getOwnAddressType()
                        != AdvertisingSetParameters.ADDRESS_TYPE_RANDOM_NON_RESOLVABLE) {
            Log.w(TAG, "Cannot advertise an isolated GATT server using a resolvable address");
            try {
                callback.onAdvertisingSetStarted(
                        0x00, 0x00, AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR);
            } catch (RemoteException exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
            return;
        }

        int uid = Flags.gattThread() ? source.getUid() : Binder.getCallingUid();
        var appName = Util.appNameOrUnknown(mAdapterService, uid);
        var message = "Unregistering advertising set (" + appName + ")!";
        Runnable onDeathAction = () -> doOnAdvertiseThread(() -> stopAdvertisingSet(callback));
        var deathRecipient = new ActionOnDeathRecipient(TAG, message, onDeathAction);
        final var binder = callback.asBinder();
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to advertiser's death");
        }

        final String deviceName = mAdapterService.getName();
        try {
            final byte[] advDataBytes = advertiseDataToBytes(advertiseData, deviceName);
            final byte[] scanResponseBytes = advertiseDataToBytes(scanResponse, deviceName);
            final byte[] periodicDataBytes = advertiseDataToBytes(periodicData, deviceName);

            final int cbId = --mTempRegistrationId;
            mAdvertisers.put(binder, new AdvertiserInfo(cbId, deathRecipient, callback));
            mAdvertiseSuspendManager.ifPresent(
                    manager ->
                            manager.onStartAdvertisingSet(cbId, duration, maxExtAdvEvents, source));

            Log.d(TAG, "startAdvertisingSet(): reg_id=" + cbId + ", callback: " + binder);

            mAdvertiserMap.addAppAdvertiseStats(uid, appName, cbId, source);
            fetchAppForegroundState(uid, cbId);
            mAdvertiserMap.recordAdvertiseStart(
                    cbId,
                    parameters,
                    advertiseData,
                    scanResponse,
                    periodicParameters,
                    periodicData,
                    duration,
                    maxExtAdvEvents);

            // Advertising parameters will be logged above using requested parameters
            parameters = adjustTxPower(parameters);
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
                        0x00, 0x00, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
            } catch (RemoteException exception) {
                Log.e(TAG, "Failed to callback:" + Log.getStackTraceString(exception));
            }
        }
    }

    private static AdvertisingSetParameters adjustTxPower(AdvertisingSetParameters params) {
        int systemMax = getMaxTxPowerDbm();
        if (params.getTxPowerLevel() > systemMax) {
            AdvertisingSetParameters.Builder builder =
                    new AdvertisingSetParameters.Builder()
                            .setConnectable(params.isConnectable())
                            .setDiscoverable(params.isDiscoverable())
                            .setScannable(params.isScannable())
                            .setLegacyMode(params.isLegacy())
                            .setAnonymous(params.isAnonymous())
                            .setIncludeTxPower(params.includeTxPower())
                            .setPrimaryPhy(params.getPrimaryPhy())
                            .setSecondaryPhy(params.getSecondaryPhy())
                            .setInterval(params.getInterval())
                            .setTxPowerLevel(systemMax)
                            .setOwnAddressType(params.getOwnAddressType())
                            .setDirected(params.isDirected())
                            .setHighDutyCycle(params.isHighDutyCycle())
                            .setPeerAddressType(params.getPeerAddressType());
            if (params.getPeerAddress() != null) {
                builder.setPeerAddress(params.getPeerAddress());
            }
            return builder.build();
        }
        return params;
    }

    void onOwnAddressRead(int advertiserId, int addressType, String address) {
        Log.d(TAG, "onOwnAddressRead(): advertiserId=" + advertiserId);
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "onOwnAddressRead(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onOwnAddressRead(advertiserId, addressType, address));
    }

    void getOwnAddress(int advertiserId) {
        enforceThread();
        if (shouldQueueCommand("getOwnAddress")) {
            mAdvertiseSuspendManager.get().queueGetOwnAddress(advertiserId);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "getOwnAddress(): Bad advertiserId=" + advertiserId);
            return;
        }
        mNativeInterface.getOwnAddress(advertiserId);
    }

    void stopAdvertisingSet(IAdvertisingSetCallback callback) {
        enforceThread();
        if (shouldQueueCommand("stopAdvertisingSet")) {
            mAdvertiseSuspendManager.get().queueStopAdvertisingSet(callback);
            return;
        }

        final var binder = callback.asBinder();
        Log.d(TAG, "stopAdvertisingSet(): " + binder);

        final var advertiserInfo = mAdvertisers.remove(binder);
        if (advertiserInfo == null) {
            Log.e(TAG, "stopAdvertisingSet(): No client found for callback");
            return;
        }

        final var advertiserId = advertiserInfo.id;
        binder.unlinkToDeath(advertiserInfo.deathRecipient, 0);

        if (advertiserId < 0) {
            Log.i(TAG, "stopAdvertisingSet(): Advertiser not finished registration yet");
            // Advertiser will be freed once initiated in onAdvertisingSetStarted()
            return;
        }

        mAdvertiseSuspendManager.ifPresent(manager -> manager.onStopAdvertisingSet(advertiserId));
        mNativeInterface.stopAdvertisingSet(advertiserId);

        try {
            callback.onAdvertisingSetStopped(advertiserId);
        } catch (RemoteException e) {
            Log.i(TAG, "Error calling callback.onAdvertisingSetStopped(" + advertiserId + ")", e);
        }

        mAdvertiserMap.recordAdvertiseStop(advertiserId);
    }

    void enableAdvertisingSet(
            int advertiserId,
            boolean enable,
            int duration,
            int maxExtAdvEvents,
            AttributionSource source) {
        enforceThread();
        if (shouldQueueCommand("enableAdvertisingSet")) {
            mAdvertiseSuspendManager
                    .get()
                    .queueEnableAdvertisingSet(
                            advertiserId, enable, duration, maxExtAdvEvents, source);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "enableAdvertisingSet(): Bad advertiserId=" + advertiserId);
            return;
        }

        mAdvertiseSuspendManager.ifPresent(manager -> manager.onEnableAdvertisingSet(advertiserId));

        int uid = Flags.gattThread() ? source.getUid() : Binder.getCallingUid();
        fetchAppForegroundState(uid, advertiserId);
        mNativeInterface.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);
        mAdvertiserMap.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);
    }

    void setAdvertisingData(int advertiserId, AdvertiseData data) {
        enforceThread();
        if (shouldQueueCommand("setAdvertisingData")) {
            mAdvertiseSuspendManager.get().queueSetAdvertisingData(advertiserId, data);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setAdvertisingData(): Bad advertiserId=" + advertiserId);
            return;
        }
        final String deviceName = mAdapterService.getName();
        try {
            mNativeInterface.setAdvertisingData(
                    advertiserId, advertiseDataToBytes(data, deviceName));
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
        enforceThread();
        if (shouldQueueCommand("setScanResponseData")) {
            mAdvertiseSuspendManager.get().queueSetScanResponseData(advertiserId, data);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setScanResponseData(): Bad advertiserId=" + advertiserId);
            return;
        }
        final String deviceName = mAdapterService.getName();
        try {
            mNativeInterface.setScanResponseData(
                    advertiserId, advertiseDataToBytes(data, deviceName));
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
        enforceThread();
        if (shouldQueueCommand("setAdvertisingParameters")) {
            mAdvertiseSuspendManager.get().queueSetAdvertisingParameters(advertiserId, parameters);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setAdvertisingParameters(): Bad advertiserId=" + advertiserId);
            return;
        }
        parameters = adjustTxPower(parameters);
        mNativeInterface.setAdvertisingParameters(advertiserId, parameters);
        mAdvertiserMap.setAdvertisingParameters(advertiserId, parameters);
    }

    void setPeriodicAdvertisingParameters(
            int advertiserId, PeriodicAdvertisingParameters parameters) {
        enforceThread();
        if (shouldQueueCommand("setPeriodicAdvertisingParameters")) {
            mAdvertiseSuspendManager
                    .get()
                    .queueSetPeriodicAdvertisingParameters(advertiserId, parameters);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingParameters(): Bad advertiserId=" + advertiserId);
            return;
        }
        mNativeInterface.setPeriodicAdvertisingParameters(advertiserId, parameters);
        mAdvertiserMap.setPeriodicAdvertisingParameters(advertiserId, parameters);
    }

    void setPeriodicAdvertisingData(int advertiserId, AdvertiseData data) {
        enforceThread();
        if (shouldQueueCommand("setPeriodicAdvertisingData")) {
            mAdvertiseSuspendManager.get().queueSetPeriodicAdvertisingData(advertiserId, data);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingData(): Bad advertiserId=" + advertiserId);
            return;
        }
        final String deviceName = mAdapterService.getName();
        try {
            mNativeInterface.setPeriodicAdvertisingData(
                    advertiserId, advertiseDataToBytes(data, deviceName));
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
        enforceThread();
        if (shouldQueueCommand("setPeriodicAdvertisingEnable")) {
            mAdvertiseSuspendManager.get().queueSetPeriodicAdvertisingEnable(advertiserId, enable);
            return;
        }

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.w(TAG, "setPeriodicAdvertisingEnable(): Bad advertiserId=" + advertiserId);
            return;
        }
        mNativeInterface.setPeriodicAdvertisingEnable(advertiserId, enable);
    }

    void onAdvertisingDataSet(int advertiserId, int status) {
        enforceThread();
        Log.d(TAG, "onAdvertisingDataSet(): advertiserId=" + advertiserId + ", status=" + status);

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingDataSet(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onAdvertisingDataSet(advertiserId, status));
    }

    void onScanResponseDataSet(int advertiserId, int status) {
        enforceThread();
        Log.d(TAG, "onScanResponseDataSet(): advertiserId=" + advertiserId + ", status=" + status);

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onScanResponseDataSet(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onScanResponseDataSet(advertiserId, status));
    }

    void onAdvertisingParametersUpdated(int advertiserId, int txPower, int status) {
        Log.d(
                TAG,
                ("onAdvertisingParametersUpdated(): advertiserId=" + advertiserId)
                        + (", txPower=" + txPower + ", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingParametersUpdated(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onAdvertisingParametersUpdated(advertiserId, txPower, status));
    }

    void onPeriodicAdvertisingParametersUpdated(int advertiserId, int status) {
        Log.d(
                TAG,
                ("onPeriodicAdvertisingParametersUpdated(): advertiserId=" + advertiserId)
                        + (", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(
                    TAG,
                    "onPeriodicAdvertisingParametersUpdated(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onPeriodicAdvertisingParametersUpdated(advertiserId, status));
    }

    void onPeriodicAdvertisingDataSet(int advertiserId, int status) {
        Log.d(
                TAG,
                ("onPeriodicAdvertisingDataSet(): advertiserId=" + advertiserId)
                        + (", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onPeriodicAdvertisingDataSet(): Bad advertiserId=" + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onPeriodicAdvertisingDataSet(advertiserId, status));
    }

    void onPeriodicAdvertisingEnabled(int advertiserId, boolean enable, int status) {
        Log.d(
                TAG,
                ("onPeriodicAdvertisingEnabled(): advertiserId=" + advertiserId)
                        + (", status=" + status));
        enforceThread();

        final Map.Entry<IBinder, AdvertiserInfo> entry = findAdvertiser(advertiserId);
        if (entry == null) {
            Log.i(TAG, "onAdvertisingSetEnable(): Bad advertiserId " + advertiserId);
            return;
        }

        final var callback = entry.getValue().callback;
        callbackToApp(() -> callback.onPeriodicAdvertisingEnabled(advertiserId, enable, status));

        final var appAdvertiseStats = mAdvertiserMap.getAppAdvertiseStatsById(advertiserId);
        if (appAdvertiseStats != null) {
            appAdvertiseStats.onPeriodicAdvertiseEnabled(enable);
        }
    }

    void doOnAdvertiseThread(Runnable r) {
        if (!mIsAvailable) return;

        final var posted =
                mHandler.post(
                        () -> {
                            if (mIsAvailable) {
                                r.run();
                            }
                        });
        if (!posted) {
            Log.w(TAG, "Unable to post async task to the handler");
        }
    }

    private void forceRunSyncOnAdvertiseThread(Runnable r) {
        if (Util.isInstrumentationTestMode()) {
            r.run();
            return;
        }

        final CompletableFuture<Void> future = new CompletableFuture<>();
        final var posted =
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

    private void enforceThread() {
        if (Util.isInstrumentationTestMode()) return;

        if (!mHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Not on advertise thread");
        }
    }
}
