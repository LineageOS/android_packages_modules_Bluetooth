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

import static java.util.Objects.requireNonNull;

import android.util.Log;

import com.android.bluetooth.profile.NativeInterface;

public class ScanNativeInterface extends NativeInterface<ScanNativeCallback> {
    private static final String TAG =
            ScanUtil.TAG_PREFIX + ScanNativeInterface.class.getSimpleName();

    ScanNativeInterface(ScanNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    void init() {
        initializeNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    private native void initializeNative();

    private native void cleanupNative();

    /************************** Regular scan related native methods **************************/
    private native void registerScannerNative(long appUuidLsb, long appUuidMsb);

    private native void unregisterScannerNative(int scannerId);

    private native void scanNative(boolean start);

    private native void setScanParametersNative(
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

    private native void scanFilterParamDeleteNative(int clientIf, int filtIndex);

    private native void scanFilterClearNative(int clientIf, int filterIndex);

    private native void scanFilterEnableNative(int clientIf, boolean enable);

    /************************** MSFT scan related native methods *****************************/
    private native boolean isMsftSupportedNative();

    private native void msftAdvMonitorAddNative(
            MsftAdvMonitor.Monitor msft_adv_monitor,
            MsftAdvMonitor.Pattern[] msft_adv_monitor_patterns,
            MsftAdvMonitor.Uuid msft_adv_monitor_uuid,
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

    void registerScanner(long appUuidLsb, long appUuidMsb) {
        registerScannerNative(appUuidLsb, appUuidMsb);
    }

    void unregisterScanner(int scannerId) {
        unregisterScannerNative(scannerId);
    }

    void scan(boolean start, String caller) {
        Log.d(TAG, "Scan=(" + (start ? "START" : "STOP") + "), caller=(" + caller + ")");
        scanNative(start);
    }

    /** Configure BLE scan parameters */
    void setScanParameters(
            int clientIf1m,
            int scanInterval1m,
            int scanWindow1m,
            int clientIfCoded,
            int scanIntervalCoded,
            int scanWindowCoded,
            int scanPhy) {
        setScanParametersNative(
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
            MsftAdvMonitor.Uuid msft_adv_monitor_uuid,
            MsftAdvMonitor.Address msft_adv_monitor_address,
            int filter_index) {
        msftAdvMonitorAddNative(
                msft_adv_monitor,
                msft_adv_monitor_patterns,
                msft_adv_monitor_uuid,
                msft_adv_monitor_address,
                filter_index);
    }

    /** Remove a MSFT Advertisement Monitor */
    void msftAdvMonitorRemove(int filterIndex, int monitorHandle) {
        msftAdvMonitorRemoveNative(filterIndex, monitorHandle);
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
}
