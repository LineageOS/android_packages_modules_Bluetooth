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

package com.android.bluetooth.sap;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Looper;

import androidx.test.filters.MediumTest;
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
public class SapServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private SapService mService;

    @Before
    public void setUp() {
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mService = new SapService(mAdapterService);
        mService.setAvailable(true);
    }

    @After
    public void tearDown() {
        mService.cleanup();
        assertThat(SapService.getSapService()).isNull();
    }

    @Test
    public void testGetSapService() {
        assertThat(mService).isEqualTo(SapService.getSapService());
        assertThat(mService.getConnectedDevices()).isEmpty();
    }

    /** Test get connection policy for BluetoothDevice */
    @Test
    public void testGetConnectionPolicy() {
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.SAP))
                .thenReturn(CONNECTION_POLICY_UNKNOWN);
        assertThat(mService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_UNKNOWN);

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.SAP))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        assertThat(mService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_FORBIDDEN);

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.SAP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);

        assertThat(mService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testGetRemoteDevice() {
        assertThat(mService.getRemoteDevice()).isNull();
    }

    @Test
    public void testGetRemoteDeviceName() {
        assertThat(SapService.getRemoteDeviceName()).isNull();
    }

    @Test
    public void testReceiver_ConnectionAccessReplyIntent_shouldNotCrash() {
        Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        intent.putExtra(
                BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE, BluetoothDevice.REQUEST_TYPE_SIM_ACCESS);
        mService.mSapReceiver.onReceive(null, intent);
    }
}
