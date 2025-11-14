/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothQualityReport;
import android.bluetooth.BluetoothStatusCodes;
import android.util.Log;

import com.android.bluetooth.Utils;

/** Native interface to BQR */
public class BluetoothQualityReportNativeInterface {
    private static final String TAG = BluetoothQualityReportNativeInterface.class.getSimpleName();

    private final AdapterService mAdapterService;

    BluetoothQualityReportNativeInterface(AdapterService adapterService) {
        mAdapterService = requireNonNull(adapterService);
    }

    /**
     * Initializes the native interface.
     *
     * <p>priorities to configure.
     */
    void init() {
        initNative();
    }

    /** Cleanup the native interface. */
    void cleanup() {
        cleanupNative();
    }

    /** Callback from the native stack back into the Java framework. */
    private void bqrDeliver(
            byte[] remoteAddr, int lmpVer, int lmpSubVer, int manufacturerId, byte[] bqrRawData) {
        String remoteAddress = Utils.getAddressStringFromByte(remoteAddr);

        if (remoteAddress == null) {
            Log.e(TAG, "bqrDeliver failed: remoteAddress is null");
            return;
        }

        BluetoothDevice device = mAdapterService.getRemoteDevice(remoteAddress);
        BluetoothClass remoteClass = new BluetoothClass(mAdapterService.getRemoteClass(device));
        BluetoothQualityReport bqr;
        try {
            bqr =
                    new BluetoothQualityReport.Builder(bqrRawData)
                            .setRemoteAddress(remoteAddress)
                            .setLmpVersion(lmpVer)
                            .setLmpSubVersion(lmpSubVer)
                            .setManufacturerId(manufacturerId)
                            .setRemoteName(mAdapterService.getRemoteName(device))
                            .setBluetoothClass(remoteClass)
                            .build();
            Log.i(TAG, bqr.toString());
        } catch (Exception e) {
            Log.e(TAG, "bqrDeliver failed: failed to create BluetoothQualityReport", e);
            return;
        }

        try {
            int status = mAdapterService.bluetoothQualityReportReadyCallback(device, bqr);
            if (status != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "bluetoothQualityReportReadyCallback failed, status: " + status);
            }
        } catch (Exception e) {
            Log.e(TAG, "bqrDeliver failed: bluetoothQualityReportReadyCallback error", e);
            return;
        }
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void cleanupNative();
}
