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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;

import static com.android.bluetooth.TestUtils.StaticMockitoRule;
import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.btservice.AdapterSuspend.BLUETOOTH_SUSPEND_DISCONNECT_ACL;
import static com.android.bluetooth.btservice.AdapterSuspend.BLUETOOTH_SUSPEND_SCAN_MODE_NONE;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemProperties;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link AdapterSuspend}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterSuspendTest {
    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Mock private AdapterNativeInterface mAdapterNativeInterface;
    @Mock private AdapterService mAdapterService;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final DeviceStateManager mDeviceStateManager =
            mContext.getSystemService(DeviceStateManager.class);
    private final DisplayManager mDisplayManager = mContext.getSystemService(DisplayManager.class);
    private final PowerManager mPowerManager = mContext.getSystemService(PowerManager.class);

    private TestLooper mTestLooper;
    private AdapterSuspend mAdapterSuspend;

    @Before
    public void setUp() {
        doReturn(mAdapterNativeInterface).when(mAdapterService).getNative();

        mTestLooper = new TestLooper();

        mockSystemPropertyGet(BLUETOOTH_SUSPEND_DISCONNECT_ACL, true);
        mockSystemPropertyGet(BLUETOOTH_SUSPEND_SCAN_MODE_NONE, true);
        mAdapterSuspend =
                spy(
                        new AdapterSuspend(
                                mAdapterService,
                                mTestLooper.getLooper(),
                                mDeviceStateManager,
                                mPowerManager,
                                mDisplayManager));
    }

    @Test
    public void testSuspend() throws Exception {
        doReturn(SCAN_MODE_CONNECTABLE).when(mAdapterService).getScanMode();
        mAdapterSuspend.handleSuspend(true);

        verify(mAdapterService).setScanMode(eq(SCAN_MODE_NONE), eq("handleSuspend"));
        verify(mAdapterNativeInterface).setDefaultEventMaskExcept(anyLong(), anyLong());
        verify(mAdapterNativeInterface).clearEventFilter();
        verify(mAdapterNativeInterface).clearFilterAcceptList();
        verify(mAdapterNativeInterface).disconnectAllAcls();
    }

    @Test
    public void testResume() throws Exception {
        mAdapterSuspend.setLastScanModeForTest(SCAN_MODE_CONNECTABLE);
        doReturn(SCAN_MODE_NONE).when(mAdapterService).getScanMode();
        mAdapterSuspend.handleResume();

        verify(mAdapterNativeInterface).setDefaultEventMaskExcept(0, 0);
        verify(mAdapterNativeInterface).clearEventFilter();
        verify(mAdapterNativeInterface).restoreFilterAcceptList();
        verify(mAdapterService).setScanMode(eq(SCAN_MODE_CONNECTABLE), eq("handleResume"));
    }
}
