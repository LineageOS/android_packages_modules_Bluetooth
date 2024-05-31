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
package com.android.bluetooth.hid;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Looper;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HidHostServiceTest {
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();

    private HidHostService mService;
    private BluetoothDevice mTestDevice;

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HidHostNativeInterface mNativeInterface;

    @Before
    public void setUp() throws Exception {
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        HidHostNativeInterface.setInstance(mNativeInterface);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mService = new HidHostService(mAdapterService);
        mService.start();
        mService.setAvailable(true);

        // Get a device for testing
        mTestDevice = TestUtils.getTestDevice(mAdapter, 0);
    }

    @After
    public void tearDown() throws Exception {
        mService.stop();
        mService.cleanup();
        HidHostNativeInterface.setInstance(null);
        mService = HidHostService.getHidHostService();
        Assert.assertNull(mService);
    }

    @Test
    public void testInitialize() {
        Assert.assertNotNull(HidHostService.getHidHostService());
    }

    /** Test okToConnect method using various test cases */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mTestDevice, BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mTestDevice, BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                true);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mTestDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                true);
        testOkToConnectCase(mTestDevice, BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(
                mTestDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mTestDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mTestDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mTestDevice, badBondState, badPriorityValue, false);
    }

    @Test
    public void testDumpDoesNotCrash() {
        mService.dump(new StringBuilder());
    }

    /**
     * Helper function to test okToConnect() method.
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, could be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int priority, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HID_HOST))
                .thenReturn(priority);

        // Test when the AdapterService is in non-quiet mode.
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        Assert.assertEquals(expected, mService.okToConnect(device));

        // Test when the AdapterService is in quiet mode.
        doReturn(true).when(mAdapterService).isQuietModeEnabled();
        Assert.assertEquals(false, mService.okToConnect(device));
    }
}
