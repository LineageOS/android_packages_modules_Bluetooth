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

package com.android.bluetooth.le_scan;

import static com.android.bluetooth.Utils.callbackToApp;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Manages Bluetooth LE Periodic scans */
public class PeriodicScanManager {
    private static final String TAG =
            ScanUtil.TAG_PREFIX + PeriodicScanManager.class.getSimpleName();

    @VisibleForTesting int mTempRegistrationId = -1;

    private final Map<IBinder, SyncInfo> mSyncs = new ConcurrentHashMap<>();
    private final Map<IBinder, SyncTransferInfo> mSyncTransfers =
            Collections.synchronizedMap(new HashMap<>());

    private final AdapterService mAdapterService;
    private final ScanController mScanController;
    private final PeriodicScanNativeInterface mNativeInterface;

    PeriodicScanManager(
            AdapterService service,
            ScanController scanController,
            PeriodicScanNativeInterface nativeInterface) {
        mAdapterService = requireNonNull(service);
        mScanController = scanController;
        var nativeCallback = new PeriodicScanNativeCallback(mAdapterService, this);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new PeriodicScanNativeInterface(nativeCallback));
        mNativeInterface.init();
    }

    void cleanup() {
        Log.i(TAG, "cleanup()");
        mNativeInterface.cleanup();
        mSyncs.clear();
        mTempRegistrationId = -1;
    }

    private record SyncTransferInfo(String address, IPeriodicAdvertisingCallback callback) {}

    private record SyncInfo(
            /* When id is negative, the registration is ongoing. When the registration finishes, id
             * becomes equal to sync_handle */
            Integer id,
            Integer advSid,
            String address,
            Integer skip,
            Integer timeout,
            ActionOnDeathRecipient deathRecipient,
            IPeriodicAdvertisingCallback callback) {}

    void onSyncStarted(
            int regId,
            int syncHandle,
            int sid,
            int addressType,
            String address,
            int phy,
            int interval,
            int status) {
        mScanController.enforceScanThread();
        List<IPeriodicAdvertisingCallback> callbacks = getAllCallbacks(regId);
        if (callbacks.isEmpty()) {
            Log.d(TAG, "onSyncStarted(): No callback found for regId=" + regId);
            mNativeInterface.stopSync(syncHandle);
            return;
        }

        synchronized (mSyncs) {
            Iterator<Map.Entry<IBinder, SyncInfo>> it = mSyncs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<IBinder, SyncInfo> e = it.next();
                if (e.getValue().id != regId) {
                    continue;
                }
                IPeriodicAdvertisingCallback callback = e.getValue().callback;
                if (status == 0) {
                    Log.d(TAG, "onSyncStarted(): Updating id with syncHandle=" + syncHandle);
                    e.setValue(
                            new SyncInfo(
                                    syncHandle,
                                    sid,
                                    address,
                                    e.getValue().skip,
                                    e.getValue().timeout,
                                    e.getValue().deathRecipient,
                                    callback));
                    callbackToApp(
                            () ->
                                    callback.onSyncEstablished(
                                            syncHandle,
                                            mAdapterService.getRemoteDevice(address, addressType),
                                            sid,
                                            e.getValue().skip,
                                            e.getValue().timeout,
                                            status));

                } else {
                    it.remove();
                    callbackToApp(
                            () ->
                                    callback.onSyncEstablished(
                                            syncHandle,
                                            mAdapterService.getRemoteDevice(address, addressType),
                                            sid,
                                            e.getValue().skip,
                                            e.getValue().timeout,
                                            status));
                    IBinder binder = e.getKey();
                    binder.unlinkToDeath(e.getValue().deathRecipient, 0);
                }
            }
        }
    }

    void onSyncReport(int syncHandle, int txPower, int rssi, int dataStatus, byte[] data) {
        mScanController.enforceScanThread();
        List<IPeriodicAdvertisingCallback> callbacks = getAllCallbacks(syncHandle);
        if (callbacks.isEmpty()) {
            Log.i(TAG, "onSyncReport(): No callback found for syncHandle=" + syncHandle);
            return;
        }
        for (IPeriodicAdvertisingCallback callback : callbacks) {
            PeriodicAdvertisingReport report =
                    new PeriodicAdvertisingReport(
                            syncHandle, txPower, rssi, dataStatus, ScanRecord.parseFromBytes(data));
            callbackToApp(() -> callback.onPeriodicAdvertisingReport(report));
        }
    }

    void onSyncLost(int syncHandle) {
        mScanController.enforceScanThread();
        List<IPeriodicAdvertisingCallback> callbacks = getAllCallbacks(syncHandle);
        if (callbacks.isEmpty()) {
            Log.i(TAG, "onSyncLost(): No callback found for syncHandle=" + syncHandle);
            return;
        }
        for (IPeriodicAdvertisingCallback callback : callbacks) {
            IBinder binder = callback.asBinder();
            synchronized (mSyncs) {
                mSyncs.remove(binder);
            }
            callbackToApp(() -> callback.onSyncLost(syncHandle));
        }
    }

    void onBigInfoReport(int syncHandle, boolean encrypted) {
        mScanController.enforceScanThread();
        List<IPeriodicAdvertisingCallback> callbacks = getAllCallbacks(syncHandle);
        if (callbacks.isEmpty()) {
            Log.i(TAG, "onBigInfoReport()No callback found for syncHandle=" + syncHandle);
            return;
        }
        for (IPeriodicAdvertisingCallback callback : callbacks) {
            callbackToApp(() -> callback.onBigInfoAdvertisingReport(syncHandle, encrypted));
        }
    }

    void startSync(
            ScanResult scanResult, int skip, int timeout, IPeriodicAdvertisingCallback callback) {
        mScanController.enforceScanThread();
        startSync(scanResult.getDevice(), scanResult.getAdvertisingSid(), skip, timeout, callback);
    }

    void startSync(
            BluetoothDevice device,
            int sid,
            int skip,
            int timeout,
            IPeriodicAdvertisingCallback callback) {
        mScanController.enforceScanThread();
        var deathRecipient = syncDeathRecipient(callback);
        IBinder binder = callback.asBinder();
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to periodic scanner death");
        }

        String address = device.getAddress();
        int addressType = device.getAddressType();
        Log.d(
                TAG,
                ("startSync for Device: " + address + " addressType: " + addressType)
                        + (" sid: " + sid));
        synchronized (mSyncs) {
            Map.Entry<IBinder, SyncInfo> entry = findMatchingSync(sid, address);
            if (entry != null) {
                // Found matching sync. Copy sync handle
                Log.d(TAG, "startSync(): Matching entry found");
                mSyncs.put(
                        binder,
                        new SyncInfo(
                                entry.getValue().id,
                                sid,
                                address,
                                entry.getValue().skip,
                                entry.getValue().timeout,
                                deathRecipient,
                                callback));
                if (entry.getValue().id >= 0) {
                    try {
                        callback.onSyncEstablished(
                                entry.getValue().id,
                                mAdapterService.getRemoteDevice(address, addressType),
                                sid,
                                entry.getValue().skip,
                                entry.getValue().timeout,
                                0 /*success*/);
                    } catch (RemoteException e) {
                        throw new IllegalArgumentException("Can't invoke callback");
                    }
                } else {
                    Log.d(TAG, "startSync(): Sync pending for same remote");
                }
                return;
            }
        }

        int cbId = --mTempRegistrationId;
        mSyncs.put(
                binder, new SyncInfo(cbId, sid, address, skip, timeout, deathRecipient, callback));

        Log.d(TAG, "startSync() - reg_id=" + cbId + ", callback: " + binder);
        mNativeInterface.startSync(sid, address, addressType, skip, timeout, cbId);
    }

    void stopSync(IPeriodicAdvertisingCallback callback) {
        mScanController.enforceScanThread();
        IBinder binder = callback.asBinder();
        Log.d(TAG, "stopSync(): Binder=" + binder);
        SyncInfo sync = null;
        synchronized (mSyncs) {
            sync = mSyncs.remove(binder);
        }
        if (sync == null) {
            Log.e(TAG, "stopSync(): No client found for callback");
            return;
        }

        Integer syncHandle = sync.id;
        binder.unlinkToDeath(sync.deathRecipient, 0);
        Log.d(TAG, "stopSync: " + syncHandle);

        synchronized (mSyncs) {
            Map.Entry<IBinder, SyncInfo> entry = findSync(syncHandle);
            if (entry != null) {
                Log.d(TAG, "stopSync(): Another app synced to same PA, not stopping sync");
                return;
            }
        }
        Log.d(TAG, "calling stopSyncNative: " + syncHandle.intValue());
        if (syncHandle < 0) {
            Log.i(TAG, "cancelSync(): Sync not established yet");
            mNativeInterface.cancelSync(sync.advSid, sync.address);
        } else {
            mNativeInterface.stopSync(syncHandle.intValue());
        }
    }

    void onSyncTransferredCallback(int paSource, int status, String bda) {
        mScanController.enforceScanThread();
        Map.Entry<IBinder, SyncTransferInfo> entry = findSyncTransfer(bda);
        if (entry == null) {
            return;
        }
        mSyncTransfers.remove(entry);
        IPeriodicAdvertisingCallback callback = entry.getValue().callback;
        try {
            callback.onSyncTransferred(mAdapterService.getRemoteDevice(bda), status);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't find callback for sync transfer");
        }
    }

    void transferSync(BluetoothDevice bda, int serviceData, int syncHandle) {
        mScanController.enforceScanThread();
        Log.d(TAG, "transferSync()");
        Map.Entry<IBinder, SyncInfo> entry = findSync(syncHandle);
        if (entry == null) {
            Log.d(TAG, "transferSync(): Callback not registered");
            return;
        }
        // check for duplicate transfers
        mSyncTransfers.put(
                entry.getKey(), new SyncTransferInfo(bda.getAddress(), entry.getValue().callback));
        mNativeInterface.syncTransfer(bda, serviceData, syncHandle);
    }

    void transferSetInfo(
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback) {
        mScanController.enforceScanThread();
        IBinder binder = callback.asBinder();
        Log.d(TAG, "transferSetInfo(): Binder=" + binder);
        try {
            binder.linkToDeath(syncDeathRecipient(callback), 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to periodic scanner death");
        }
        mSyncTransfers.put(binder, new SyncTransferInfo(bda.getAddress(), callback));
        mNativeInterface.transferSetInfo(bda, serviceData, advHandle);
    }

    void doOnScanThread(Runnable r) {
        mScanController.doOnScanThread(r);
    }

    private Map.Entry<IBinder, SyncTransferInfo> findSyncTransfer(String address) {
        return mSyncTransfers.entrySet().stream()
                .filter(e -> e.getValue().address.equals(address))
                .findFirst()
                .orElse(null);
    }

    private Map.Entry<IBinder, SyncInfo> findSync(int syncHandle) {
        return mSyncs.entrySet().stream()
                .filter(e -> e.getValue().id == syncHandle)
                .findFirst()
                .orElse(null);
    }

    private Map.Entry<IBinder, SyncInfo> findMatchingSync(int advSid, String address) {
        return mSyncs.entrySet().stream()
                .filter(e -> e.getValue().advSid == advSid && e.getValue().address.equals(address))
                .findFirst()
                .orElse(null);
    }

    private List<IPeriodicAdvertisingCallback> getAllCallbacks(int syncHandle) {
        return mSyncs.values().stream()
                .filter(v -> v.id == syncHandle)
                .map(v -> v.callback)
                .collect(Collectors.toList());
    }

    private ActionOnDeathRecipient syncDeathRecipient(IPeriodicAdvertisingCallback callback) {
        var message = "Unregistering advertising set for " + callback;
        Runnable onDeathAction = () -> doOnScanThread(() -> stopSync(callback));
        return new ActionOnDeathRecipient(TAG, message, onDeathAction);
    }
}
