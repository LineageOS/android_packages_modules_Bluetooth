/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothScan;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.os.WorkSource;
import android.util.Log;

import java.util.List;

class ScanBinder extends IBluetoothScan.Stub {
    private static final String TAG = ScanBinder.class.getSimpleName();

    private ScanController mScanController;

    ScanBinder(ScanController scanController) {
        mScanController = scanController;
    }

    @Override
    public void registerScanner(
            IScannerCallback callback, WorkSource workSource, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.registerScanner(callback, workSource, attributionSource);
    }

    @Override
    public void unregisterScanner(int scannerId, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.unregisterScanner(scannerId, attributionSource);
    }

    @Override
    public void startScan(
            int scannerId,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.startScan(scannerId, settings, filters, attributionSource);
    }

    @Override
    public void startScanForIntent(
            PendingIntent intent,
            ScanSettings settings,
            List<ScanFilter> filters,
            AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.registerPiAndStartScan(intent, settings, filters, attributionSource);
    }

    @Override
    public void stopScan(int scannerId, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.stopScan(scannerId, attributionSource);
    }

    @Override
    public void stopScanForIntent(PendingIntent intent, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.stopScan(intent, attributionSource);
    }

    @Override
    public void flushPendingBatchResults(int scannerId, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.flushPendingBatchResults(scannerId, attributionSource);
    }

    @Override
    public void registerSync(
            ScanResult scanResult,
            int skip,
            int timeout,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.registerSync(scanResult, skip, timeout, callback, attributionSource);
    }

    @Override
    public void unregisterSync(
            IPeriodicAdvertisingCallback callback, AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.unregisterSync(callback, attributionSource);
    }

    @Override
    public void transferSync(
            BluetoothDevice bda,
            int serviceData,
            int syncHandle,
            AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.transferSync(bda, serviceData, syncHandle, attributionSource);
    }

    @Override
    public void transferSetInfo(
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            IPeriodicAdvertisingCallback callback,
            AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return;
        }
        scanController.transferSetInfo(bda, serviceData, advHandle, callback, attributionSource);
    }

    @Override
    public int numHwTrackFiltersAvailable(AttributionSource attributionSource) {
        ScanController scanController = getScanController();
        if (scanController == null) {
            return 0;
        }
        return scanController.numHwTrackFiltersAvailable(attributionSource);
    }

    void clearScanController() {
        mScanController = null;
    }

    private ScanController getScanController() {
        ScanController controller = mScanController;
        if (controller != null && controller.isAvailable()) {
            return controller;
        }
        Log.e(TAG, "getScanController() - ScanController requested, but not available!");
        return null;
    }
}
