/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.bluetooth.map;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.telephony.TelephonyManager;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothMapServiceTest {
    private BluetoothMapService mService = null;
    private final BluetoothDevice mDevice = getTestDevice(32);
    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;

    @Before
    public void setUp() {
        doReturn(mTargetContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mTargetContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mTargetContext.getResources()).when(mAdapterService).getResources();

        mockGetSystemService(mAdapterService, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        mockGetSystemService(mAdapterService, Context.ALARM_SERVICE, AlarmManager.class);

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        mService = new BluetoothMapService(mAdapterService);
        mService.setAvailable(true);
    }

    @After
    public void tearDown() {
        mService.cleanup();
        assertThat(BluetoothMapService.getBluetoothMapService()).isNull();
    }

    @Test
    public void initialize() {
        assertThat(BluetoothMapService.getBluetoothMapService()).isNotNull();
    }

    @Test
    public void getDevicesMatchingConnectionStates_whenNoDeviceIsConnected_returnsEmptyList() {
        when(mAdapterService.getBondedDevices()).thenReturn(new BluetoothDevice[] {mDevice});

        assertThat(mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .isEmpty();
    }

    @Test
    public void getNextMasId_isInRange() {
        int masId = mService.getNextMasId();
        assertThat(masId).isAtMost(0xff);
        assertThat(masId).isAtLeast(1);
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }
}
