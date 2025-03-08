/*
 * Copyright 2022 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link AppAdvertiseStats}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppAdvertiseStatsTest {
    private static final String TAG = AppAdvertiseStatsTest.class.getSimpleName();

    private CountDownLatch mLatch;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private MetricsLogger mMetricsLogger;

    @Captor ArgumentCaptor<Long> mAdvDurationCaptor;

    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation().getTargetContext().getAttributionSource();

    @Before
    public void setUp() throws Exception {
        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        mLatch = new CountDownLatch(1);
        assertThat(mLatch).isNotNull();
    }

    @After
    public void tearDown() throws Exception {
        MetricsLogger.setInstanceForTesting(null);
    }

    private void testSleep(long millis) {
        try {
            mLatch.await(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Latch await", e);
        }
    }

    @Test
    public void recordAdvertiseStart() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty();

        int duration = 1;
        int maxExtAdvEvents = 2;
        int instanceCount = 3;

        appAdvertiseStats.recordAdvertiseStart(duration, maxExtAdvEvents, instanceCount);

        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        AdvertiseData periodicData = new AdvertiseData.Builder().build();

        appAdvertiseStats.recordAdvertiseStart(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                instanceCount);

        int numOfExpectedRecords = 2;

        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords);
    }

    @Test
    public void recordAdvertiseStop() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        int duration = 1;
        int maxExtAdvEvents = 2;
        int instanceCount = 3;

        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty();

        appAdvertiseStats.recordAdvertiseStart(duration, maxExtAdvEvents, instanceCount);

        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        AdvertiseData periodicData = new AdvertiseData.Builder().build();

        appAdvertiseStats.recordAdvertiseStart(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                instanceCount);

        appAdvertiseStats.recordAdvertiseStop(instanceCount);

        int numOfExpectedRecords = 2;

        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords);
    }

    @Test
    public void enableAdvertisingSet() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        int duration = 1;
        int maxExtAdvEvents = 2;
        int instanceCount = 3;

        assertThat(appAdvertiseStats.mAdvertiserRecords).isEmpty();

        appAdvertiseStats.enableAdvertisingSet(true, duration, maxExtAdvEvents, instanceCount);
        appAdvertiseStats.enableAdvertisingSet(false, duration, maxExtAdvEvents, instanceCount);

        int numOfExpectedRecords = 1;

        assertThat(appAdvertiseStats.mAdvertiserRecords).hasSize(numOfExpectedRecords);
    }

    @Test
    public void setAdvertisingData() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        appAdvertiseStats.setAdvertisingData(advertiseData);

        appAdvertiseStats.setAdvertisingData(advertiseData);
    }

    @Test
    public void setScanResponseData() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        appAdvertiseStats.setScanResponseData(scanResponse);

        appAdvertiseStats.setScanResponseData(scanResponse);
    }

    @Test
    public void setAdvertisingParameters() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        appAdvertiseStats.setAdvertisingParameters(parameters);
    }

    @Test
    public void setPeriodicAdvertisingParameters() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        appAdvertiseStats.setPeriodicAdvertisingParameters(periodicParameters);
    }

    @Test
    public void setPeriodicAdvertisingData() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        AdvertiseData periodicData = new AdvertiseData.Builder().build();
        appAdvertiseStats.setPeriodicAdvertisingData(periodicData);

        appAdvertiseStats.setPeriodicAdvertisingData(periodicData);
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        StringBuilder sb = new StringBuilder();

        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);

        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        AdvertiseData periodicData = new AdvertiseData.Builder().build();
        int duration = 1;
        int maxExtAdvEvents = 2;
        int instanceCount = 3;

        appAdvertiseStats.recordAdvertiseStart(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                instanceCount);

        AppAdvertiseStats.dumpToString(sb, appAdvertiseStats);
    }

    @Test
    @EnableFlags(Flags.FLAG_BLE_SCAN_ADV_METRICS_REDESIGN)
    public void testAdvertiseCounterMetrics() {
        int appUid = 0;
        int id = 1;
        String name = "name";

        AppAdvertiseStats appAdvertiseStats =
                new AppAdvertiseStats(appUid, id, name, mAttributionSource);
        // Set app importance as Foreground Service for the stats
        appAdvertiseStats.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);

        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder().setConnectable(true).build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        AdvertiseData periodicData = new AdvertiseData.Builder().build();
        int duration = 1;
        int maxExtAdvEvents = 2;
        int instanceCount = 3;
        final long advTestDuration = 100;

        appAdvertiseStats.recordAdvertiseStart(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                instanceCount);
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_ENABLE), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_CONNECTABLE_ENABLE), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_PERIODIC_ENABLE), eq((long) 1));
        verify(mMetricsLogger)
                .logAdvStateChanged(
                        new int[] {appUid},
                        new String[] {name},
                        true,
                        BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_INTERVAL__INTERVAL_LOW,
                        BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_TX_POWER__TX_POWER_MEDIUM,
                        true,
                        true,
                        false,
                        true,
                        instanceCount,
                        0,
                        IMPORTANCE_FOREGROUND_SERVICE);
        Mockito.clearInvocations(mMetricsLogger);

        // Wait for adv test duration
        testSleep(advTestDuration);

        appAdvertiseStats.recordAdvertiseStop(instanceCount);
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_DISABLE), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_CONNECTABLE_DISABLE), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_COUNT_PERIODIC_DISABLE), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_TOTAL_1M), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_CONNECTABLE_1M), eq((long) 1));
        verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_ADV_DURATION_COUNT_PERIODIC_1M), eq((long) 1));
        verify(mMetricsLogger)
                .logAdvStateChanged(
                        eq(new int[] {appUid}),
                        eq(new String[] {name}),
                        eq(false),
                        eq(BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_INTERVAL__INTERVAL_LOW),
                        eq(BluetoothStatsLog.LE_ADV_STATE_CHANGED__ADV_TX_POWER__TX_POWER_MEDIUM),
                        eq(true),
                        eq(true),
                        eq(false),
                        eq(true),
                        eq(instanceCount),
                        mAdvDurationCaptor.capture(),
                        eq(IMPORTANCE_FOREGROUND_SERVICE));
        long capturedAppScanDuration = mAdvDurationCaptor.getValue();
        Log.d(TAG, "capturedDuration: " + capturedAppScanDuration);
        assertThat(capturedAppScanDuration).isAtLeast(advTestDuration);
    }
}
