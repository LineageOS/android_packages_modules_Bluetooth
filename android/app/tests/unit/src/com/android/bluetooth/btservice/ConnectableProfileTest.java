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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link ConnectableProfile}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ConnectableProfileTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private static final int TEST_PROFILE_ID = 99;

    @Mock private AdapterService mAdapterService;
    @Mock private ProfileService.IProfileServiceBinder mBinder;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private BluetoothDevice mDevice;

    private TestConnectableProfile mConnectableProfile;

    private class TestConnectableProfile extends ConnectableProfile {
        TestConnectableProfile(int id, AdapterService adapterService) {
            super(id, adapterService);
        }

        @Override
        protected IProfileServiceBinder initBinder() {
            return mBinder;
        }

        @Override
        public void cleanup() {
            // Nothing to do for test
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            return true;
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            return STATE_DISCONNECTED;
        }
    }

    @Before
    public void setUp() {
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        mConnectableProfile = new TestConnectableProfile(TEST_PROFILE_ID, mAdapterService);
    }

    @Test
    public void getProfileId_returnsCorrectId() {
        assertThat(mConnectableProfile.getProfileId()).isEqualTo(TEST_PROFILE_ID);
    }

    @Test
    public void getName_returnsClassName() {
        assertThat(mConnectableProfile.getName()).isEqualTo("TestConnectableProfile");
    }

    @Test
    public void getBinder_returnsBinderFromInitBinder() {
        assertThat(mConnectableProfile.getBinder()).isEqualTo(mBinder);
    }

    @Test
    public void connect_returnsFalse() {
        assertThat(mConnectableProfile.connect(mDevice)).isFalse();
    }

    @Test
    public void disconnect_returnsTrue() {
        assertThat(mConnectableProfile.disconnect(mDevice)).isTrue();
    }

    @Test
    public void getConnectionState_returnsStateDisconnect() {
        assertThat(mConnectableProfile.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void setConnectionPolicy_returnsFalse() {
        final var policyUnknown = BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
        assertThat(mConnectableProfile.setConnectionPolicy(mDevice, policyUnknown)).isFalse();
    }
}
