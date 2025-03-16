/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.content.pm.PackageManager;
import android.os.Binder;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link AdvertiserMap}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdvertiserMapTest {
    private static final String APP_NAME = "com.android.what.a.name";

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mMockPackageManager;

    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation().getTargetContext().getAttributionSource();

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);

        doReturn(mMockPackageManager).when(mAdapterService).getPackageManager();
        doReturn(APP_NAME).when(mMockPackageManager).getNameForUid(anyInt());
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void getByMethods() {
        AdvertiserMap advertiserMap = new AdvertiserMap();

        int id = 12345;
        advertiserMap.addAppAdvertiseStats(id, mAdapterService, mAttributionSource);

        AppAdvertiseStats stats = advertiserMap.getAppAdvertiseStatsById(id);
        assertThat(stats.mAppName).isEqualTo(APP_NAME);
    }

    @Test
    public void clear() {
        AdvertiserMap advertiserMap = new AdvertiserMap();

        int id = 12345;
        advertiserMap.addAppAdvertiseStats(id, mAdapterService, mAttributionSource);

        AppAdvertiseStats stats = advertiserMap.getAppAdvertiseStatsById(id);
        assertThat(stats.mAppName).isEqualTo(APP_NAME);

        advertiserMap.clear();
        assertThat(advertiserMap.getAppAdvertiseStatsById(id)).isNull();
    }

    @Test
    public void advertisingSetAndData() {
        AdvertiserMap advertiserMap = new AdvertiserMap();
        int id = 12345;
        AppAdvertiseStats appAdvertiseStats =
                spy(
                        new AppAdvertiseStats(
                                Binder.getCallingUid(), id, APP_NAME, mAttributionSource));
        advertiserMap.addAppAdvertiseStats(id, appAdvertiseStats);

        int duration = 60;
        int maxExtAdvEvents = 100;
        int instanceCount = 1;
        advertiserMap.enableAdvertisingSet(id, true, duration, maxExtAdvEvents);
        verify(appAdvertiseStats)
                .enableAdvertisingSet(true, duration, maxExtAdvEvents, instanceCount);

        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        advertiserMap.setAdvertisingData(id, advertiseData);
        verify(appAdvertiseStats).setAdvertisingData(advertiseData);

        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        advertiserMap.setScanResponseData(id, scanResponse);
        verify(appAdvertiseStats).setScanResponseData(scanResponse);

        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        advertiserMap.setAdvertisingParameters(id, parameters);
        verify(appAdvertiseStats).setAdvertisingParameters(parameters);

        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        advertiserMap.setPeriodicAdvertisingParameters(id, periodicParameters);
        verify(appAdvertiseStats).setPeriodicAdvertisingParameters(periodicParameters);

        AdvertiseData periodicData = new AdvertiseData.Builder().build();
        advertiserMap.setPeriodicAdvertisingData(id, periodicData);
        verify(appAdvertiseStats).setPeriodicAdvertisingData(periodicData);

        advertiserMap.onPeriodicAdvertiseEnabled(id, true);
        verify(appAdvertiseStats).onPeriodicAdvertiseEnabled(true);

        AppAdvertiseStats toBeRemoved = advertiserMap.getAppAdvertiseStatsById(id);
        assertThat(toBeRemoved).isNotNull();

        advertiserMap.removeAppAdvertiseStats(id);

        AppAdvertiseStats isRemoved = advertiserMap.getAppAdvertiseStatsById(id);
        assertThat(isRemoved).isNull();
    }

    @Test
    public void emptyStop_doesNotCrash() throws Exception {
        AdvertiserMap advertiserMap = new AdvertiserMap();

        int id = 12345;
        advertiserMap.recordAdvertiseStop(id);
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        StringBuilder sb = new StringBuilder();
        AdvertiserMap advertiserMap = new AdvertiserMap();

        int id = 12345;
        advertiserMap.addAppAdvertiseStats(id, mAdapterService, mAttributionSource);
        advertiserMap.recordAdvertiseStop(id);

        int idSecond = 54321;
        advertiserMap.addAppAdvertiseStats(idSecond, mAdapterService, mAttributionSource);
        advertiserMap.dump(sb);
    }
}
