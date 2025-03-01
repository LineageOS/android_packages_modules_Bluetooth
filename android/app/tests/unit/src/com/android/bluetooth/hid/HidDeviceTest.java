/*
 * Copyright 2017 The Android Open Source Project
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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyByte;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHidDeviceCallback;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HidDeviceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HidDeviceNativeInterface mNativeInterface;
    @Mock private IBluetoothHidDeviceCallback.Stub mCallback;
    @Mock private Binder mBinder;

    private static final byte[] SAMPLE_HID_REPORT = new byte[] {0x01, 0x00, 0x02};
    private static final byte SAMPLE_REPORT_ID = 0x05;
    private static final byte SAMPLE_REPORT_TYPE = 0x04;
    private static final byte SAMPLE_REPORT_ERROR = 0x02;
    private static final byte SAMPLE_BUFFER_SIZE = 100;

    private final BluetoothDevice mDevice = getTestDevice(87);

    private HidDeviceService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;
    private BluetoothHidDeviceAppSdpSettings mSettings;

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mockGetSystemService(mAdapterService, Context.ACTIVITY_SERVICE, ActivityManager.class);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mBinder).when(mCallback).asBinder();

        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        mService = new HidDeviceService(mAdapterService, mLooper.getLooper(), mNativeInterface);
        mService.setAvailable(true);

        // Force unregister app first
        mService.unregisterApp();

        // Dummy SDP settings
        mSettings =
                new BluetoothHidDeviceAppSdpSettings(
                        "Unit test",
                        "test",
                        "Android",
                        BluetoothHidDevice.SUBCLASS1_COMBO,
                        new byte[] {});

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
    }

    @After
    public void tearDown() {
        mService.cleanup();
        assertThat(HidDeviceService.getHidDeviceService()).isNull();
    }

    private void verifyConnectionStateIntent(int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState));
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcast(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(BLUETOOTH_CONNECT),
                        any(Bundle.class));
    }

    /** Test getting HidDeviceService: getHidDeviceService(). */
    @Test
    public void testGetHidDeviceService() {
        assertThat(HidDeviceService.getHidDeviceService()).isEqualTo(mService);
    }

    /**
     * Test the logic in registerApp and unregisterApp. Should get a callback
     * onApplicationStateChangedFromNative.
     */
    @Test
    public void testRegistration() throws RemoteException {
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        verify(mNativeInterface, never())
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        // Register app
        assertThat(mService.registerApp(mSettings, null, null, mCallback)).isTrue();
        verify(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, true);

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();

        verify(mNativeInterface).unregisterApp();

        mService.onApplicationStateChangedFromNative(mDevice, false);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, false);
    }

    /** Test the logic in sendReport(). This should fail when the app is not registered. */
    @Test
    public void testSendReport() throws RemoteException {
        doReturn(true).when(mNativeInterface).sendReport(anyInt(), any(byte[].class));
        // sendReport() should fail without app registered
        assertThat(mService.sendReport(mDevice, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)).isFalse();

        // Register app
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());
        assertThat(mService.registerApp(mSettings, null, null, mCallback)).isTrue();

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, true);

        // sendReport() should work when app is registered
        assertThat(mService.sendReport(mDevice, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)).isTrue();

        verify(mNativeInterface).sendReport(eq((int) SAMPLE_REPORT_ID), eq(SAMPLE_HID_REPORT));

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();
    }

    /** Test the logic in replyReport(). This should fail when the app is not registered. */
    @Test
    public void testReplyReport() throws RemoteException {
        doReturn(true).when(mNativeInterface).replyReport(anyByte(), anyByte(), any(byte[].class));
        // replyReport() should fail without app registered
        assertThat(
                        mService.replyReport(
                                mDevice, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT))
                .isFalse();

        // Register app
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());
        assertThat(mService.registerApp(mSettings, null, null, mCallback)).isTrue();

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, true);

        // replyReport() should work when app is registered
        assertThat(
                        mService.replyReport(
                                mDevice, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT))
                .isTrue();

        verify(mNativeInterface)
                .replyReport(eq(SAMPLE_REPORT_TYPE), eq(SAMPLE_REPORT_ID), eq(SAMPLE_HID_REPORT));

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();
    }

    /** Test the logic in reportError(). This should fail when the app is not registered. */
    @Test
    public void testReportError() throws RemoteException {
        doReturn(true).when(mNativeInterface).reportError(anyByte());
        // reportError() should fail without app registered
        assertThat(mService.reportError(mDevice, SAMPLE_REPORT_ERROR)).isFalse();

        // Register app
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());
        assertThat(mService.registerApp(mSettings, null, null, mCallback)).isTrue();

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, true);

        // reportError() should work when app is registered
        assertThat(mService.reportError(mDevice, SAMPLE_REPORT_ERROR)).isTrue();

        verify(mNativeInterface).reportError(eq(SAMPLE_REPORT_ERROR));

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    public void testOutgoingConnectDisconnectSuccess() {
        doReturn(true).when(mNativeInterface).connect(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnect();

        // Register app
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());
        mService.registerApp(mSettings, null, null, null);

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);

        // Send a connect request
        assertThat(mService.connect(mDevice)).isTrue();

        mService.onConnectStateChangedFromNative(
                mDevice, HidDeviceService.HAL_CONN_STATE_CONNECTING);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);

        mService.onConnectStateChangedFromNative(
                mDevice, HidDeviceService.HAL_CONN_STATE_CONNECTED);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .contains(mDevice);

        // Send a disconnect request
        assertThat(mService.disconnect(mDevice)).isTrue();

        mService.onConnectStateChangedFromNative(
                mDevice, HidDeviceService.HAL_CONN_STATE_DISCONNECTING);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTING);

        mService.onConnectStateChangedFromNative(
                mDevice, HidDeviceService.HAL_CONN_STATE_DISCONNECTED);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .doesNotContain(mDevice);

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();
    }

    /**
     * Test the logic in callback functions from native stack: onGetReport, onSetReport,
     * onSetProtocol, onInterruptData, onVirtualCableUnplug. The HID Device server should send the
     * callback to the user app.
     */
    @Test
    public void testCallbacks() throws RemoteException {
        doReturn(true)
                .when(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        verify(mNativeInterface, never())
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        // Register app
        assertThat(mService.registerApp(mSettings, null, null, mCallback)).isTrue();
        verify(mNativeInterface)
                .registerApp(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyByte(),
                        any(byte[].class),
                        isNull(),
                        isNull());

        // App registered
        mService.onApplicationStateChangedFromNative(mDevice, true);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, true);

        // Received callback: onGetReport
        mService.onGetReportFromNative(SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_BUFFER_SIZE);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback)
                .onGetReport(mDevice, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_BUFFER_SIZE);

        // Received callback: onSetReport
        mService.onSetReportFromNative(SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback)
                .onSetReport(mDevice, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT);

        // Received callback: onSetProtocol
        mService.onSetProtocolFromNative(BluetoothHidDevice.PROTOCOL_BOOT_MODE);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onSetProtocol(mDevice, BluetoothHidDevice.PROTOCOL_BOOT_MODE);

        // Received callback: onInterruptData
        mService.onInterruptDataFromNative(SAMPLE_REPORT_ID, SAMPLE_HID_REPORT);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onInterruptData(mDevice, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT);

        // Received callback: onVirtualCableUnplug
        mService.onVirtualCableUnplugFromNative();
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onVirtualCableUnplug(mDevice);

        // Unregister app
        doReturn(true).when(mNativeInterface).unregisterApp();
        assertThat(mService.unregisterApp()).isTrue();
        verify(mNativeInterface).unregisterApp();

        mService.onApplicationStateChangedFromNative(mDevice, false);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        verify(mCallback).onAppStatusChanged(mDevice, false);
    }
}
