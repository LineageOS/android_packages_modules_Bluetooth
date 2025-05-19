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

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;

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
    private TestLooper mTestLooper;
    private DeviceStateManager mDeviceStateManager;
    private DisplayManager mDisplayManager;
    private PowerManager mPowerManager;
    private AdapterSuspend mAdapterSuspend;

    static final String BLUETOOTH_SUSPEND_DISCONNECT_ACL =
            "bluetooth.power.suspend.disconnect_acl.enabled";
    static final String BLUETOOTH_SUSPEND_SCAN_MODE_NONE =
            "bluetooth.power.suspend.scan_mode_none.enabled";

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Mock private AdapterNativeInterface mAdapterNativeInterface;
    @Mock private AdapterService mAdapterService;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mTestLooper = new TestLooper();
        mDeviceStateManager = context.getSystemService(DeviceStateManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        doReturn(mAdapterNativeInterface).when(mAdapterService).getNative();

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
        mAdapterSuspend.setPropertyForTest(BLUETOOTH_SUSPEND_DISCONNECT_ACL, true);
        mAdapterSuspend.setPropertyForTest(BLUETOOTH_SUSPEND_SCAN_MODE_NONE, true);
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
        mAdapterSuspend.setPropertyForTest(BLUETOOTH_SUSPEND_DISCONNECT_ACL, true);
        mAdapterSuspend.setPropertyForTest(BLUETOOTH_SUSPEND_SCAN_MODE_NONE, true);
        mAdapterSuspend.setLastScanModeForTest(SCAN_MODE_CONNECTABLE);
        doReturn(SCAN_MODE_NONE).when(mAdapterService).getScanMode();
        mAdapterSuspend.handleResume();

        verify(mAdapterNativeInterface).setDefaultEventMaskExcept(0, 0);
        verify(mAdapterNativeInterface).clearEventFilter();
        verify(mAdapterNativeInterface).restoreFilterAcceptList();
        verify(mAdapterService).setScanMode(eq(SCAN_MODE_CONNECTABLE), eq("handleResume"));
    }
}
