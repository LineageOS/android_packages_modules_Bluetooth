/*
 * Copyright 2024 The Android Open Source Project
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

import static com.android.bluetooth.Utils.getSystemClock;

import android.os.Looper;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.BluetoothAdapterProxy;

/** Factory class for object initialization to help with unit testing */
public class ScanObjectsFactory {
    private static final String TAG = ScanObjectsFactory.class.getSimpleName();

    private static ScanObjectsFactory sInstance;
    private static final Object INSTANCE_LOCK = new Object();

    private ScanObjectsFactory() {}

    /**
     * Get the singleton instance of object factory
     *
     * @return the singleton instance, guaranteed not null
     */
    public static ScanObjectsFactory getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new ScanObjectsFactory();
            }
        }
        return sInstance;
    }

    /**
     * Allow unit tests to substitute ScanObjectsFactory with a test instance
     *
     * @param objectsFactory a test instance of the ScanObjectsFactory
     */
    public static void setInstanceForTesting(ScanObjectsFactory objectsFactory) {
        Utils.enforceInstrumentationTestMode();
        synchronized (INSTANCE_LOCK) {
            Log.d(TAG, "setInstanceForTesting(), set to " + objectsFactory);
            sInstance = objectsFactory;
        }
    }

    public ScanNativeInterface getScanNativeInterface() {
        return ScanNativeInterface.getInstance();
    }

    /**
     * Create an instance of ScanManager
     *
     * @param adapterService an AdapterService instance
     * @param scanController a ScanController instance
     * @param bluetoothAdapterProxy a bluetoothAdapterProxy instance
     * @param looper the looper to be used for processing messages
     * @return the created ScanManager instance
     */
    public ScanManager createScanManager(
            AdapterService adapterService,
            ScanController scanController,
            BluetoothAdapterProxy bluetoothAdapterProxy,
            Looper looper) {
        return new ScanManager(
                adapterService, scanController, bluetoothAdapterProxy, looper, getSystemClock());
    }

    public PeriodicScanManager createPeriodicScanManager() {
        return new PeriodicScanManager();
    }
}
