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

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.BatteryStatsManager;
import android.os.WorkSource;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.TestUtils.FakeTimeProvider;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link AppScanStats}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppScanStatsTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BatteryStatsManager mBatteryStatsManager;
    @Mock private ScanController mMockScanController;
    @Mock private ScannerMap map;

    private final FakeTimeProvider mTimeProvider = new FakeTimeProvider();

    @Before
    public void setUp() {
        TestUtils.mockGetSystemService(
                mAdapterService, BatteryStatsManager.class, mBatteryStatsManager);
    }

    @Test
    public void constructor() {
        String name = "appName";
        WorkSource source = null;

        AppScanStats appScanStats =
                new AppScanStats(
                        name, source, map, mAdapterService, mMockScanController, mTimeProvider);

        assertThat(appScanStats.mScannerMap).isEqualTo(map);
        assertThat(appScanStats.mScanController).isEqualTo(mMockScanController);
        assertThat(appScanStats.isScanning()).isFalse();
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        String name = "appName";
        WorkSource source = null;

        AppScanStats appScanStats =
                new AppScanStats(
                        name, source, map, mAdapterService, mMockScanController, mTimeProvider);

        ScanSettings settings = new ScanSettings.Builder().build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName("TestName").build());
        boolean isFilterScan = false;
        boolean isCallbackScan = false;
        int scannerId = 0;

        appScanStats.recordScanStart(
                settings, filters, isFilterScan, isCallbackScan, scannerId, "tag");
        appScanStats.isRegistered = true;

        StringBuilder stringBuilder = new StringBuilder();
        appScanStats.dumpToString(stringBuilder);
    }
}
