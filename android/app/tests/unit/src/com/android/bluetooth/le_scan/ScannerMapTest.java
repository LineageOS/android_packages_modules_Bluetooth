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

package com.android.bluetooth.le_scan;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;

import android.app.PendingIntent;
import android.bluetooth.le.IScannerCallback;
import android.content.AttributionSource;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.UUID;

/** Test cases for {@link ScannerMap}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScannerMapTest {
    private static final String APP_NAME = "com.android.what.a.name";
    private static final int UID = 12345;
    private static final int SCANNER_ID = 321;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mMockPackageManager;
    @Mock private ScanController mMockScanController;
    @Mock private IScannerCallback mMockScannerCallback;
    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation().getTargetContext().getAttributionSource();

    @Spy private BluetoothMethodProxy mMapMethodProxy = BluetoothMethodProxy.getInstance();

    @Before
    public void setUp() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(mMapMethodProxy);
        TestUtils.setAdapterService(mAdapterService);
        doReturn(mMockPackageManager).when(mAdapterService).getPackageManager();
        doReturn(APP_NAME).when(mMockPackageManager).getNameForUid(anyInt());
    }

    @After
    public void tearDown() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(null);
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void getByMethodsWithPii() {
        ScannerMap scannerMap = new ScannerMap();
        PendingIntent intent =
                PendingIntent.getBroadcast(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        0,
                        new Intent(),
                        PendingIntent.FLAG_IMMUTABLE);
        ScanController.PendingIntentInfo info =
                new ScanController.PendingIntentInfo(intent, null, null, APP_NAME, UID);
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(
                        uuid, mAttributionSource, info, mAdapterService, mMockScanController);
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByUuid = scannerMap.getByUuid(uuid);
        assertThat(scannerMapByUuid.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByName = scannerMap.getByName(APP_NAME).get(0);
        assertThat(scannerMapByName.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByPii = scannerMap.getByPendingIntentInfo(intent);
        assertThat(scannerMapByPii.mName).isEqualTo(APP_NAME);

        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull();
        assertThat(scannerMap.getAppScanStatsByUid(UID)).isNotNull();
    }

    @Test
    public void getByMethodsWithoutPii() {
        ScannerMap scannerMap = new ScannerMap();
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(
                        uuid,
                        mAttributionSource,
                        null,
                        mMockScannerCallback,
                        mAdapterService,
                        mMockScanController);
        int appUid = Binder.getCallingUid();
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);
        assertThat(scannerMapById.mCallback).isEqualTo(mMockScannerCallback);

        ScannerMap.ScannerApp scannerMapByUuid = scannerMap.getByUuid(uuid);
        assertThat(scannerMapByUuid.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByName = scannerMap.getByName(APP_NAME).get(0);
        assertThat(scannerMapByName.mName).isEqualTo(APP_NAME);

        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull();
        assertThat(scannerMap.getAppScanStatsByUid(appUid)).isNotNull();
    }

    @Test
    public void removeById() {
        ScannerMap scannerMap = new ScannerMap();
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(
                        uuid,
                        mAttributionSource,
                        null,
                        mMockScannerCallback,
                        mAdapterService,
                        mMockScanController);
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);

        scannerMap.remove(SCANNER_ID);
        assertThat(scannerMap.getById(SCANNER_ID)).isNull();
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        StringBuilder sb = new StringBuilder();
        ScannerMap scannerMap = new ScannerMap();
        scannerMap.add(
                UUID.randomUUID(),
                mAttributionSource,
                null,
                mMockScannerCallback,
                mAdapterService,
                mMockScanController);
        scannerMap.dump(sb);
        scannerMap.dumpApps(sb, ProfileService::println);
    }
}
