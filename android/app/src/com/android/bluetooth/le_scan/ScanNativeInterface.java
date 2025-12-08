/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** BLE Scan Native Interface to/from JNI. */
public class ScanNativeInterface {
    private final ScanController mScanController;

    private CountDownLatch mLatch = new CountDownLatch(1);

    ScanNativeInterface(ScanController scanController) {
        mScanController = scanController;
    }

    void init() {
        initializeNative();
    }

    void cleanup() {
        cleanupNative();
    }

    private void doOnScanThread(Runnable r) {
        mScanController.doOnScanThread(r);
    }

    /* Native methods */
    private native void initializeNative();

    private native void cleanupNative();

    /************************** Regular scan related native methods **************************/
    private native void registerScannerNative(long appUuidLsb, long appUuidMsb);

    private native void unregisterScannerNative(int scannerId);

    private native void scanNative(boolean start);

    private native void gattSetScanParametersNative(
            int clientIf1m,
            int scanInterval1m,
            int scanWindow1m,
            int clientIfCoded,
            int scanIntervalCoded,
            int scanWindowCoded,
            int scanPhy);

    /************************** Filter related native methods ********************************/
    private native void scanFilterAddNative(
            int clientId, ScanFilterQueue.Entry[] entries, int filterIndex);

    private native void scanFilterParamAddNative(FilterParams filtValue);

    // Note this effectively remove scan filters for ALL clients.
    private native void scanFilterParamClearAllNative(int clientIf);

    private native void scanFilterParamDeleteNative(int clientIf, int filtIndex);

    private native void scanFilterClearNative(int clientIf, int filterIndex);

    private native void scanFilterEnableNative(int clientIf, boolean enable);

    /************************** MSFT scan related native methods *****************************/
    private native boolean isMsftSupportedNative();

    private native void msftAdvMonitorAddNative(
            MsftAdvMonitor.Monitor msft_adv_monitor,
            MsftAdvMonitor.Pattern[] msft_adv_monitor_patterns,
            MsftAdvMonitor.Address msft_adv_monitor_address,
            int filter_index);

    private native void msftAdvMonitorRemoveNative(int filter_index, int monitor_handle);

    private native void msftAdvMonitorEnableNative(boolean enable);

    /************************** Batch related native methods *********************************/
    private native void configBatchScanStorageNative(
            int clientIf,
            int maxFullReportsPercent,
            int maxTruncatedReportsPercent,
            int notifyThresholdPercent);

    private native void startBatchScanNative(
            int clientIf,
            int scanMode,
            int scanIntervalUnit,
            int scanWindowUnit,
            int addressType,
            int discardRule);

    private native void stopBatchScanNative(int clientIf);

    private native void readScanReportsNative(int clientIf, int scanType);

    /** Register BLE scanner */
    void registerScanner(long appUuidLsb, long appUuidMsb) {
        registerScannerNative(appUuidLsb, appUuidMsb);
    }

    /** Unregister BLE scanner */
    void unregisterScanner(int scannerId) {
        unregisterScannerNative(scannerId);
    }

    /** Enable/disable BLE scan */
    void scan(boolean start) {
        scanNative(start);
    }

    /** Configure BLE scan parameters */
    void gattSetScanParameters(
            int clientIf1m,
            int scanInterval1m,
            int scanWindow1m,
            int clientIfCoded,
            int scanIntervalCoded,
            int scanWindowCoded,
            int scanPhy) {
        gattSetScanParametersNative(
                clientIf1m,
                scanInterval1m,
                scanWindow1m,
                clientIfCoded,
                scanIntervalCoded,
                scanWindowCoded,
                scanPhy);
    }

    /** Add BLE scan filter */
    void scanFilterAdd(int clientId, ScanFilterQueue.Entry[] entries, int filterIndex) {
        scanFilterAddNative(clientId, entries, filterIndex);
    }

    /** Add BLE scan filter parameters */
    void scanFilterParamAdd(FilterParams filtValue) {
        scanFilterParamAddNative(filtValue);
    }

    /** Clear all BLE scan filter parameters */
    // Note this effectively remove scan filters for ALL clients.
    void scanFilterParamClearAll(int clientIf) {
        scanFilterParamClearAllNative(clientIf);
    }

    /** Delete BLE scan filter parameters */
    void scanFilterParamDelete(int clientIf, int filtIndex) {
        scanFilterParamDeleteNative(clientIf, filtIndex);
    }

    /** Clear BLE scan filter */
    void scanFilterClear(int clientIf, int filterIndex) {
        scanFilterClearNative(clientIf, filterIndex);
    }

    /** Enable/disable BLE scan filter */
    void scanFilterEnable(int clientIf, boolean enable) {
        scanFilterEnableNative(clientIf, enable);
    }

    /** Check if MSFT HCI extension is supported */
    boolean isMsftSupported() {
        return isMsftSupportedNative();
    }

    /** Add a MSFT Advertisement Monitor */
    void msftAdvMonitorAdd(
            MsftAdvMonitor.Monitor msft_adv_monitor,
            MsftAdvMonitor.Pattern[] msft_adv_monitor_patterns,
            MsftAdvMonitor.Address msft_adv_monitor_address,
            int filter_index) {
        msftAdvMonitorAddNative(
                msft_adv_monitor,
                msft_adv_monitor_patterns,
                msft_adv_monitor_address,
                filter_index);
    }

    /** Remove a MSFT Advertisement Monitor */
    void msftAdvMonitorRemove(int filter_index) {
        final int monitor_handle =
                mScanController.fetchOnScanThread(
                        () -> mScanController.msftMonitorHandleFromFilterIndex(filter_index), -1);
        if (monitor_handle < 0) return;
        msftAdvMonitorRemoveNative(filter_index, monitor_handle);
    }

    /** Enable a MSFT Advertisement Monitor */
    void msftAdvMonitorEnable(boolean enable) {
        msftAdvMonitorEnableNative(enable);
    }

    /** Configure BLE batch scan storage */
    void configBatchScanStorage(
            int clientIf,
            int maxFullReportsPercent,
            int maxTruncatedReportsPercent,
            int notifyThresholdPercent) {
        configBatchScanStorageNative(
                clientIf,
                maxFullReportsPercent,
                maxTruncatedReportsPercent,
                notifyThresholdPercent);
    }

    /** Enable BLE batch scan with the parameters */
    void startBatchScan(
            int clientIf,
            int scanMode,
            int scanIntervalUnit,
            int scanWindowUnit,
            int addressType,
            int discardRule) {
        startBatchScanNative(
                clientIf, scanMode, scanIntervalUnit, scanWindowUnit, addressType, discardRule);
    }

    /** Disable BLE batch scan */
    void stopBatchScan(int clientIf) {
        stopBatchScanNative(clientIf);
    }

    /** Read BLE batch scan reports */
    void readScanReports(int clientIf, int scanType) {
        readScanReportsNative(clientIf, scanType);
    }

    void callbackDone() {
        mLatch.countDown();
    }

    void resetCountDownLatch() {
        mLatch = new CountDownLatch(1);
    }

    // Returns true if mLatch reaches 0, false if timeout or interrupted.
    boolean waitForCallback(int timeoutMs) {
        try {
            return mLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    /* Callbacks */

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
        doOnScanThread(
                () ->
                        mScanController.onScanResult(
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
                                originalAddress));
    }

    void onScannerRegistered(int status, int scannerId, long uuidLsb, long uuidMsb) {
        doOnScanThread(
                () -> mScanController.onScannerRegistered(status, scannerId, uuidLsb, uuidMsb));
    }

    void onScanFilterEnableDisabled(int action, int status, int clientIf) {
        doOnScanThread(() -> mScanController.onScanFilterEnableDisabled(action, status, clientIf));
    }

    void onScanFilterParamsConfigured(int action, int status, int clientIf, int availableSpace) {
        doOnScanThread(
                () ->
                        mScanController.onScanFilterParamsConfigured(
                                action, status, clientIf, availableSpace));
    }

    void onScanFilterConfig(
            int action, int status, int clientIf, int filterType, int availableSpace) {
        doOnScanThread(
                () ->
                        mScanController.onScanFilterConfig(
                                action, status, clientIf, filterType, availableSpace));
    }

    void onBatchScanStorageConfigured(int status, int clientIf) {
        doOnScanThread(() -> mScanController.onBatchScanStorageConfigured(status, clientIf));
    }

    void onBatchScanStartStopped(int startStopAction, int status, int clientIf) {
        doOnScanThread(
                () -> mScanController.onBatchScanStartStopped(startStopAction, status, clientIf));
    }

    void onBatchScanReports(
            int status, int scannerId, int reportType, int numRecords, byte[] recordData) {
        doOnScanThread(
                () ->
                        mScanController.onBatchScanReports(
                                status, scannerId, reportType, numRecords, recordData));
    }

    void onBatchScanThresholdCrossed(int clientIf) {
        doOnScanThread(() -> mScanController.onBatchScanThresholdCrossed(clientIf));
    }

    @Nullable
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
        return mScanController.createOnTrackAdvFoundLostObject(
                clientIf,
                advPacketLen,
                advPacket,
                scanResponseLen,
                scanResponse,
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
        doOnScanThread(() -> mScanController.onTrackAdvFoundLost(trackingInfo));
    }

    void onScanParamSetupCompleted(int status, int scannerId) {
        doOnScanThread(() -> mScanController.onScanParamSetupCompleted(status, scannerId));
    }

    void onMsftAdvMonitorAdd(int filter_index, int monitor_handle, int status) {
        doOnScanThread(
                () -> mScanController.onMsftAdvMonitorAdd(filter_index, monitor_handle, status));
    }

    void onMsftAdvMonitorRemove(int filter_index, int status) {
        doOnScanThread(() -> mScanController.onMsftAdvMonitorRemove(filter_index, status));
    }

    void onMsftAdvMonitorEnable(boolean enable, int status) {
        doOnScanThread(() -> mScanController.onMsftAdvMonitorEnable(enable, status));
    }
}
