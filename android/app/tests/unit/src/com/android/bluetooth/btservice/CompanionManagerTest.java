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

package com.android.bluetooth.btservice;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockAdapterServiceGetRemoteDevice;
import static com.android.bluetooth.TestUtils.mockContextGetBluetoothManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.HandlerThread;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link CompanionManager}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class CompanionManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private SharedPreferences mSharedPreferences;
    @Mock private SharedPreferences.Editor mEditor;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final BluetoothDevice mDevice = getTestDevice(123);

    private CompanionManager mCompanionManager;
    private HandlerThread mHandlerThread;

    @Before
    public void setUp() throws Exception {
        mockAdapterServiceGetRemoteDevice(mAdapterService, mDevice);
        // Start handler thread for this test
        mHandlerThread = new HandlerThread("CompanionManagerTestHandlerThread");
        mHandlerThread.start();
        // Mock the looper
        doReturn(mHandlerThread.getLooper()).when(mAdapterService).getMainLooper();
        // Mock SharedPreferences
        when(mSharedPreferences.edit()).thenReturn(mEditor);
        doReturn(mSharedPreferences)
                .when(mAdapterService)
                .getSharedPreferences(
                        eq(CompanionManager.COMPANION_INFO), eq(Context.MODE_PRIVATE));
        // Use the resources in the instrumentation instead of the mocked AdapterService
        when(mAdapterService.getResources()).thenReturn(mContext.getResources());
        mockContextGetBluetoothManager(mAdapterService);

        // Must be called to initialize services
        mCompanionManager = new CompanionManager(mAdapterService, null);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
    }

    @Test
    public void testLoadCompanionInfo_hasCompanionDeviceKey() {
        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_PRIMARY);
    }

    @Test
    public void testLoadCompanionInfo_noCompanionDeviceSetButHaveBondedDevices_shouldNotCrash() {
        BluetoothDevice[] devices = new BluetoothDevice[2];
        doReturn(devices).when(mAdapterService).getBondedDevices();
        doThrow(new IllegalArgumentException())
                .when(mSharedPreferences)
                .getInt(eq(CompanionManager.COMPANION_TYPE_KEY), anyInt());
        mCompanionManager.loadCompanionInfo();
    }

    @Test
    public void testIsCompanionDevice() {
        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_NONE);
        assertThat(mCompanionManager.isCompanionDevice(mDevice)).isTrue();

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_PRIMARY);
        assertThat(mCompanionManager.isCompanionDevice(mDevice)).isTrue();

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_SECONDARY);
        assertThat(mCompanionManager.isCompanionDevice(mDevice)).isTrue();
    }

    @Test
    public void testGetGattConnParameterPrimary() {
        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_PRIMARY);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_SECONDARY);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);

        loadCompanionInfoHelper(CompanionManager.COMPANION_TYPE_NONE);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
        checkReasonableConnParameterHelper(BluetoothGatt.CONNECTION_PRIORITY_DCK);
    }

    private void loadCompanionInfoHelper(int companionType) {
        final String address = mDevice.getAddress();
        doReturn(address)
                .when(mSharedPreferences)
                .getString(eq(CompanionManager.COMPANION_DEVICE_KEY), anyString());
        doReturn(companionType)
                .when(mSharedPreferences)
                .getInt(eq(CompanionManager.COMPANION_TYPE_KEY), anyInt());
        mCompanionManager.loadCompanionInfo();
    }

    private void checkReasonableConnParameterHelper(int priority) {
        // Max/Min values from the Bluetooth spec Version 5.3 | Vol 4, Part E | 7.8.18
        final int minInterval = 6; // 0x0006
        final int maxInterval = 3200; // 0x0C80
        final int minLatency = 0; // 0x0000
        final int maxLatency = 499; // 0x01F3

        int min =
                mCompanionManager.getGattConnParameters(
                        mDevice, CompanionManager.GATT_CONN_INTERVAL_MIN, priority);
        int max =
                mCompanionManager.getGattConnParameters(
                        mDevice, CompanionManager.GATT_CONN_INTERVAL_MAX, priority);
        int latency =
                mCompanionManager.getGattConnParameters(
                        mDevice, CompanionManager.GATT_CONN_LATENCY, priority);

        assertThat(max).isAtLeast(min);
        assertThat(max).isAtLeast(minInterval);
        assertThat(min).isAtLeast(minInterval);
        assertThat(max).isAtMost(maxInterval);
        assertThat(min).isAtMost(maxInterval);
        assertThat(latency).isAtLeast(minLatency);
        assertThat(latency).isAtMost(maxLatency);
    }
}
