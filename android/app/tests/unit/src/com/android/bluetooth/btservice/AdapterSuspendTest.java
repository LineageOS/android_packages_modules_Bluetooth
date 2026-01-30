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
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.btservice.AdapterSuspend.AWAKE;
import static com.android.bluetooth.btservice.AdapterSuspend.BLUETOOTH_SUSPEND_DISCONNECT_ACL;
import static com.android.bluetooth.btservice.AdapterSuspend.BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT;
import static com.android.bluetooth.btservice.AdapterSuspend.BLUETOOTH_SUSPEND_SCAN_MODE_NONE;
import static com.android.bluetooth.btservice.AdapterSuspend.DEEP_SLEEP;
import static com.android.bluetooth.btservice.AdapterSuspend.SHALLOW_SLEEP;
import static com.android.bluetooth.btservice.RemoteDevices.AclLinkSpec;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.AdvertiseManager;
import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.hid.HidHostService;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test cases for {@link AdapterSuspend}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterSuspendTest {
    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterNativeInterface mAdapterNativeInterface;
    @Mock private AdapterService mAdapterService;
    @Mock private AdvertiseManager mAdvertiseManager;
    @Mock private BluetoothDevice mBluetoothDevice;
    @Mock private GattService mGattService;
    @Mock private RemoteDevices mRemoteDevices;
    @Mock private HidHostService mHidHostService;

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
        doReturn(Optional.of(mGattService)).when(mAdapterService).getGattService();
        doReturn(mAdvertiseManager).when(mGattService).getAdvertiseManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        doReturn(Optional.of(mHidHostService)).when(mAdapterService).getHidHostService();

        mTestLooper = new TestLooper();

        mockSystemPropertyGet(BLUETOOTH_SUSPEND_DISCONNECT_ACL, true);
        mockSystemPropertyGet(BLUETOOTH_SUSPEND_SCAN_MODE_NONE, true);
        mockSystemPropertyGet(BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT, true);
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
    @DisableFlags(Flags.FLAG_ADAPTER_SUSPEND_DISCOVERABILITY)
    public void testSuspendWithoutFlagSuspendDiscoverability() throws Exception {
        doReturn(SCAN_MODE_CONNECTABLE).when(mAdapterService).getScanMode();
        mAdapterSuspend.handleSuspend(true);

        verify(mAdapterService).setScanMode(eq(SCAN_MODE_NONE), eq("handleSuspend"));
        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(mAdapterNativeInterface).setDefaultEventMaskExcept(anyLong(), anyLong());
            verify(mAdapterNativeInterface).disconnectAllAcls();
            verify(mAdapterNativeInterface).clearFilterAcceptList();
        } else {
            verify(mAdapterNativeInterface).setSuspendState(true);
        }
        verify(mAdapterNativeInterface).clearEventFilter();
    }

    @Test
    @DisableFlags(Flags.FLAG_ADAPTER_SUSPEND_DISCOVERABILITY)
    public void testResumeWithoutFlagSuspendDiscoverability() throws Exception {
        doReturn(SCAN_MODE_NONE).when(mAdapterService).getScanMode();
        mAdapterSuspend.setLastScanModeForTest(SCAN_MODE_CONNECTABLE);
        mAdapterSuspend.handleResume();

        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(mAdapterNativeInterface).setDefaultEventMaskExcept(0, 0);
            verify(mAdapterNativeInterface).restoreFilterAcceptList();
        } else {
            verify(mAdapterNativeInterface).setSuspendState(false);
        }
        verify(mAdapterNativeInterface).clearEventFilter();
        verify(mAdapterService).setScanMode(eq(SCAN_MODE_CONNECTABLE), eq("handleResume"));
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_DISCOVERABILITY)
    public void testSuspendWithFlagSuspendDiscoverability() throws Exception {
        mAdapterSuspend.handleSuspend(true);

        verify(mAdapterService).setSuspendState(true);
        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(mAdapterNativeInterface).setDefaultEventMaskExcept(anyLong(), anyLong());
            verify(mAdapterNativeInterface).disconnectAllAcls();
            verify(mAdapterNativeInterface).clearFilterAcceptList();
        } else {
            verify(mAdapterNativeInterface).setSuspendState(true);
        }
        verify(mAdapterNativeInterface).clearEventFilter();
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_DISCOVERABILITY)
    public void testResumeWithFlagSuspendDiscoverability() throws Exception {
        mAdapterSuspend.handleResume();

        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(mAdapterNativeInterface).setDefaultEventMaskExcept(0, 0);
            verify(mAdapterNativeInterface).restoreFilterAcceptList();
        } else {
            verify(mAdapterNativeInterface).setSuspendState(false);
        }
        verify(mAdapterNativeInterface).clearEventFilter();
        verify(mAdapterService).setSuspendState(false);
    }

    @Test
    public void testAudioReconnect() throws Exception {
        List<BluetoothDevice> nullDevices = new ArrayList<>(Arrays.asList(null, null));
        List<BluetoothDevice> activeDevices = new ArrayList<>(Arrays.asList(mBluetoothDevice));

        // It's possible that getActiveDevices returns list of nulls.
        // Make sure we save the actual active device, and not the nulls.
        doReturn(nullDevices).when(mAdapterService).getActiveDevices(BluetoothProfile.A2DP);
        doReturn(activeDevices).when(mAdapterService).getActiveDevices(BluetoothProfile.LE_AUDIO);
        mAdapterSuspend.handleSuspend(true);

        // It is possible to call handleSuspend twice.
        // Make sure we don't accidentally overwrite the saved device with an empty list.
        doReturn(nullDevices).when(mAdapterService).getActiveDevices(BluetoothProfile.LE_AUDIO);
        mAdapterSuspend.handleSuspend(true);

        // Verify we initiate reconnection attempt on resume.
        mAdapterSuspend.handleResume();
        verify(mAdapterService).connectAllEnabledProfiles(mBluetoothDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    public void testAdvertisementPauseAndResume() throws Exception {
        mAdapterSuspend.handleSuspend(true);
        verify(mAdvertiseManager).enterSuspend();
        mAdapterSuspend.handleResume();
        verify(mAdvertiseManager).exitSuspend();
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    public void testTwoTasksDisconnectionThenAdvertisement() throws Exception {
        List<BluetoothDevice> audioDevices = new ArrayList<>(Arrays.asList(mBluetoothDevice));
        doReturn(audioDevices)
                .when(mAdapterService)
                .getConnectedDevicesForProfile(BluetoothProfile.HEARING_AID);

        mAdapterSuspend.handleSuspend(true);
        verify(mAdapterService).acquireWakeLock(any());

        // When disconnection task is done, wakelock is not yet released.
        mAdapterSuspend.profileConnectionStateChanged(
                BluetoothProfile.HEARING_AID,
                mBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        verify(mAdapterService, never()).releaseWakeLock(any());

        // Wakelock is released when both tasks are done.
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService).releaseWakeLock(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    public void testTwoTasksAdvertisementThenDisconnection() throws Exception {
        List<BluetoothDevice> audioDevices = new ArrayList<>(Arrays.asList(mBluetoothDevice));
        doReturn(audioDevices)
                .when(mAdapterService)
                .getConnectedDevicesForProfile(BluetoothProfile.HEARING_AID);

        mAdapterSuspend.handleSuspend(true);
        verify(mAdapterService).acquireWakeLock(any());

        // When advertisement task is done, wakelock is not yet released.
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService, never()).releaseWakeLock(any());

        // Wakelock is released when both tasks are done.
        mAdapterSuspend.profileConnectionStateChanged(
                BluetoothProfile.HEARING_AID,
                mBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        verify(mAdapterService).releaseWakeLock(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    public void testSuspendWaitAclDisconnection() throws Exception {
        AclLinkSpec linkSpec = new AclLinkSpec(mBluetoothDevice, TRANSPORT_LE);
        doReturn(Set.of(linkSpec)).when(mRemoteDevices).getConnectedDevices();

        mAdapterSuspend.handleSuspend(false);
        verify(mAdapterService).acquireWakeLock(any());

        // Lock isn't released because ACL is still connected.
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService, never()).releaseWakeLock(any());

        // Lock isn't released if there are more devices to disconnect.
        mAdapterSuspend.aclDisconnected(mBluetoothDevice, TRANSPORT_BREDR);
        verify(mAdapterService, never()).releaseWakeLock(any());

        // Lock is released if there are no more devices to disconnect.
        // Here we need to change the return value of the mocked getConnectedDevices.
        doReturn(Set.of()).when(mRemoteDevices).getConnectedDevices();
        mAdapterSuspend.aclDisconnected(mBluetoothDevice, TRANSPORT_LE);
        verify(mAdapterService).releaseWakeLock(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    public void testSuspendButNoAcl() throws Exception {
        doReturn(Set.of()).when(mRemoteDevices).getConnectedDevices();

        mAdapterSuspend.handleSuspend(false);
        verify(mAdapterService).acquireWakeLock(any());

        // Lock is immediately released since there are no devices to disconnect.
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService).releaseWakeLock(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    public void testNotSuspendingButAclIsDisconnected() throws Exception {
        doReturn(Set.of()).when(mRemoteDevices).getConnectedDevices();

        // No suspend related behavior shall be triggered since we're not suspending.
        mAdapterSuspend.aclDisconnected(mBluetoothDevice, TRANSPORT_BREDR);
        verify(mAdapterService, never()).releaseWakeLock(any());
        verify(mAdapterService, never()).acquireWakeLock(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    public void testNonWakeableSuspendWithLeHid() throws Exception {
        List<BluetoothDevice> hidDevices = new ArrayList<>(Arrays.asList(mBluetoothDevice));
        doReturn(hidDevices)
                .when(mAdapterService)
                .getConnectedDevicesForProfile(BluetoothProfile.HID_HOST);
        doReturn(TRANSPORT_LE).when(mHidHostService).getPreferredTransport(mBluetoothDevice);

        // Setting a "non-wakeable by HID" suspend, this causes deep sleep
        mAdapterSuspend.handleSuspend(false);
        verify(mAdapterService).acquireWakeLock(any());

        // Lock isn't released, waiting for HoGP disconnection.
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService, never()).releaseWakeLock(any());

        // Lock is released once profile is disconnected
        mAdapterSuspend.profileConnectionStateChanged(
                BluetoothProfile.HID_HOST,
                mBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED);
        verify(mAdapterService).releaseWakeLock(any());
        verify(mHidHostService).onSuspendStateChange(DEEP_SLEEP);

        mAdapterSuspend.handleResume();
        verify(mHidHostService).onSuspendStateChange(AWAKE);
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    public void testWakeableSuspendWithClassicHid() throws Exception {
        List<BluetoothDevice> hidDevices = new ArrayList<>(Arrays.asList(mBluetoothDevice));
        doReturn(hidDevices)
                .when(mAdapterService)
                .getConnectedDevicesForProfile(BluetoothProfile.HID_HOST);
        doReturn(TRANSPORT_BREDR).when(mHidHostService).getPreferredTransport(mBluetoothDevice);

        // Setting a "wakeable by HID" suspend, this causes shallow sleep
        mAdapterSuspend.handleSuspend(true);
        verify(mAdapterService).acquireWakeLock(any());

        // BREDR HID shouldn't block profile disconnection, so lock is released
        mAdapterSuspend.advertiseSuspendReady();
        verify(mAdapterService).releaseWakeLock(any());
        verify(mHidHostService).onSuspendStateChange(SHALLOW_SLEEP);

        mAdapterSuspend.handleResume();
        verify(mHidHostService).onSuspendStateChange(AWAKE);
    }
}
