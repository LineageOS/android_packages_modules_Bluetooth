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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Intent;
import android.os.WorkSource;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link ScanBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScanBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private ScanController mScanController;

    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter()
                    .getAttributionSource();
    private final BluetoothDevice mDevice = getTestDevice(89);
    private ScanBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new ScanBinder(mScanController);
    }

    @Test
    public void registerScanner() {
        IScannerCallback callback = mock(IScannerCallback.class);
        WorkSource workSource = mock(WorkSource.class);

        mBinder.registerScanner(callback, workSource, mAttributionSource);
        verify(mScanController).registerScanner(callback, workSource, mAttributionSource);
    }

    @Test
    public void unregisterScanner() {
        int scannerId = 1;

        mBinder.unregisterScanner(scannerId, mAttributionSource);
        verify(mScanController).unregisterScanner(scannerId, mAttributionSource);
    }

    @Test
    public void startScan() {
        int scannerId = 1;
        ScanSettings settings = new ScanSettings.Builder().build();
        List<ScanFilter> filters = new ArrayList<>();

        mBinder.startScan(scannerId, settings, filters, mAttributionSource);
        verify(mScanController).startScan(scannerId, settings, filters, mAttributionSource);
    }

    @Test
    public void startScanForIntent() {
        PendingIntent intent =
                PendingIntent.getBroadcast(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        0,
                        new Intent(),
                        PendingIntent.FLAG_IMMUTABLE);
        ScanSettings settings = new ScanSettings.Builder().build();
        List<ScanFilter> filters = new ArrayList<>();

        mBinder.startScanForIntent(intent, settings, filters, mAttributionSource);
        verify(mScanController)
                .registerPiAndStartScan(intent, settings, filters, mAttributionSource);
    }

    @Test
    public void stopScan_withScannerId() {
        int scannerId = 1;

        mBinder.stopScan(scannerId, mAttributionSource);
        verify(mScanController).stopScan(scannerId, mAttributionSource);
    }

    @Test
    public void stopScan_withIntent() {
        PendingIntent intent =
                PendingIntent.getBroadcast(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        0,
                        new Intent(),
                        PendingIntent.FLAG_IMMUTABLE);

        mBinder.stopScanForIntent(intent, mAttributionSource);
        verify(mScanController).stopScan(intent, mAttributionSource);
    }

    @Test
    public void flushPendingBatchResults() {
        int scannerId = 1;

        mBinder.flushPendingBatchResults(scannerId, mAttributionSource);
        verify(mScanController).flushPendingBatchResults(scannerId, mAttributionSource);
    }

    @Test
    public void registerSync() {
        ScanResult scanResult = new ScanResult(mDevice, null, 0, 0);
        int skip = 1;
        int timeout = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mBinder.registerSync(scanResult, skip, timeout, callback, mAttributionSource);
        verify(mScanController)
                .registerSync(scanResult, skip, timeout, callback, mAttributionSource);
    }

    @Test
    public void unregisterSync() {
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mBinder.unregisterSync(callback, mAttributionSource);
        verify(mScanController).unregisterSync(callback, mAttributionSource);
    }

    @Test
    public void transferSync() {
        int serviceData = 1;
        int syncHandle = 2;

        mBinder.transferSync(mDevice, serviceData, syncHandle, mAttributionSource);
        verify(mScanController).transferSync(mDevice, serviceData, syncHandle, mAttributionSource);
    }

    @Test
    public void transferSetInfo() {
        int serviceData = 1;
        int advHandle = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mBinder.transferSetInfo(mDevice, serviceData, advHandle, callback, mAttributionSource);
        verify(mScanController)
                .transferSetInfo(mDevice, serviceData, advHandle, callback, mAttributionSource);
    }

    @Test
    public void numHwTrackFiltersAvailable() {
        mBinder.numHwTrackFiltersAvailable(mAttributionSource);
        verify(mScanController).numHwTrackFiltersAvailable(mAttributionSource);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
