/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.pan;

import static android.bluetooth.BluetoothPan.PAN_ROLE_NONE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.UserManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.pan.PanService.BluetoothPanDevice;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link PanService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class PanServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private PanNativeCallback panNativeCallback;
    @Mock private PanNativeInterface mNativeInterface;
    @Mock private UserManager mUserManager;

    private static final byte[] REMOTE_DEVICE_ADDRESS_AS_ARRAY = new byte[] {0, 0, 0, 0, 0, 0};
    private static final int TIMEOUT_MS = 5_000;

    private final BluetoothDevice mRemoteDevice = getTestDevice(0);
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private PanService mService;
    private TestLooper mTestLooper;

    @Before
    public void setUp() {
        doReturn(mContext.getResources()).when(mAdapterService).getResources();
        mockGetSystemService(mAdapterService, TetheringManager.class);

        mTestLooper = new TestLooper();
        mService =
                new PanService(
                        mAdapterService,
                        panNativeCallback,
                        mNativeInterface,
                        mUserManager,
                        mTestLooper.getLooper());
        mService.setAvailable(true);
    }

    @After
    public void tearDown() {
        mService.cleanup();
    }

    @Test
    public void connect_whenGuestUser_returnsFalse() {
        doReturn(true).when(mUserManager).isGuestUser();
        assertThat(mService.connect(mRemoteDevice)).isFalse();
    }

    @Test
    public void connect_inConnectedState_returnsFalse() {
        doReturn(false).when(mUserManager).isGuestUser();
        mService.mPanDevices.put(
                mRemoteDevice,
                new BluetoothPanDevice(STATE_CONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE));

        assertThat(mService.connect(mRemoteDevice)).isFalse();
    }

    @Test
    public void connect() {
        doReturn(false).when(mUserManager).isGuestUser();
        mService.mPanDevices.put(
                mRemoteDevice,
                new BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE));

        assertThat(mService.connect(mRemoteDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, timeout(TIMEOUT_MS)).connect(any());
    }

    @Test
    public void disconnect_returnsTrue() {
        assertThat(mService.disconnect(mRemoteDevice)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, timeout(TIMEOUT_MS)).disconnect(any());
    }

    @Test
    public void convertHalState() {
        assertThat(PanNativeCallback.convertHalState(PanNativeCallback.CONN_STATE_CONNECTED))
                .isEqualTo(STATE_CONNECTED);
        assertThat(PanNativeCallback.convertHalState(PanNativeCallback.CONN_STATE_CONNECTING))
                .isEqualTo(STATE_CONNECTING);
        assertThat(PanNativeCallback.convertHalState(PanNativeCallback.CONN_STATE_DISCONNECTED))
                .isEqualTo(STATE_DISCONNECTED);
        assertThat(PanNativeCallback.convertHalState(PanNativeCallback.CONN_STATE_DISCONNECTING))
                .isEqualTo(STATE_DISCONNECTING);
        assertThat(PanNativeCallback.convertHalState(-24664)) // illegal value
                .isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void dump() {
        mService.mPanDevices.put(
                mRemoteDevice,
                new BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE));

        mService.dump(new StringBuilder());
    }

    @Test
    public void onConnectStateChanged_doesNotCrash() {
        mService.onConnectStateChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, 1, 2, 3, 4);
    }

    @Test
    public void onConnectStateChanged_doesNotCrashAfterCleanup() {
        mService.cleanup();
        mService.onConnectStateChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, 1, 2, 3, 4);
    }

    @Test
    public void onControlStateChanged_doesNotCrash() {
        mService.onControlStateChanged(1, 2, 3, "ifname");
    }

    @Test
    public void setConnectionPolicy_whenDatabaseManagerRefuses_returnsFalse() {
        doReturn(false).when(mAdapterService).setProfileConnectionPolicy(any(), anyInt(), anyInt());
        int connectionPolicy = CONNECTION_POLICY_ALLOWED;

        assertThat(mService.setConnectionPolicy(mRemoteDevice, connectionPolicy)).isFalse();
    }

    @Test
    public void setConnectionPolicy_returnsTrue() {
        doReturn(true).when(mAdapterService).setProfileConnectionPolicy(any(), anyInt(), anyInt());

        assertThat(mService.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, timeout(TIMEOUT_MS)).connect(any());

        assertThat(mService.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        mTestLooper.dispatchAll();
        verify(mNativeInterface, timeout(TIMEOUT_MS)).disconnect(any());
    }

    @Test
    public void connectState_constructor() {
        int state = 1;
        int error = 2;
        int localRole = 3;
        int remoteRole = 4;

        PanService.ConnectState connectState =
                new PanService.ConnectState(
                        REMOTE_DEVICE_ADDRESS_AS_ARRAY, state, error, localRole, remoteRole);

        assertThat(connectState.addr).isEqualTo(REMOTE_DEVICE_ADDRESS_AS_ARRAY);
        assertThat(connectState.state).isEqualTo(state);
        assertThat(connectState.error).isEqualTo(error);
        assertThat(connectState.local_role).isEqualTo(localRole);
        assertThat(connectState.remote_role).isEqualTo(remoteRole);
    }

    @Test
    public void tetheringCallback_onError_clearsPanDevices() {
        mService.mIsTethering = true;
        mService.mPanDevices.put(
                mRemoteDevice,
                new BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE));
        TetheringInterface iface = new TetheringInterface(TETHERING_BLUETOOTH, "iface");

        mService.mTetheringCallback.onError(iface, TETHER_ERROR_SERVICE_UNAVAIL);

        assertThat(mService.mPanDevices).isEmpty();
        assertThat(mService.mIsTethering).isFalse();
    }
}
