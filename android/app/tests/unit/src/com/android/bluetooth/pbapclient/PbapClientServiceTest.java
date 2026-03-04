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

package com.android.bluetooth.pbapclient;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpPseRecord;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Test cases for {@link PbapClientService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mPackageManager;
    @Mock private Resources mResources;
    @Mock private SdpPseRecord mMockSdpRecord;
    @Mock private PbapClientContactsStorage mMockStorage;
    @Mock private PbapClientStateMachine mDeviceStateMachine;

    // Constants for SDP. Note that these values come from the native stack, but no centralized
    // constants exist for them as part of the various SDP APIs.
    public static final int SDP_SUCCESS = 0;
    public static final int SDP_FAILED = 1;
    public static final int SDP_BUSY = 2;

    // Constant for testing ACL disconnection events with a bad transport
    public static final int TRANSPORT_UNKNOWN = -1;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final BluetoothDevice mDevice = getTestDevice(56);
    private final Map<BluetoothDevice, PbapClientStateMachine> mDeviceMap =
            new HashMap<BluetoothDevice, PbapClientStateMachine>();

    private PbapClientService mService;
    private TestLooper mTestLooper;

    // NEW: Objects for new state machine implementation
    private PbapClientService.PbapClientStateMachineCallback mDeviceCallback;

    @Before
    public void setUp() throws Exception {
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());
        doReturn(mContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();

        doReturn(mResources).when(mAdapterService).getResources();
        doReturn(Utils.ACCOUNT_TYPE).when(mResources).getString(anyInt());

        // new for mock storage
        doAnswer(
                        invocation -> {
                            BluetoothDevice device = (BluetoothDevice) invocation.getArgument(0);
                            return Utils.getAccountForDevice(device);
                        })
                .when(mMockStorage)
                .getStorageAccountForDevice(any(BluetoothDevice.class));
        doReturn("").when(mMockStorage).dump();

        mTestLooper = new TestLooper();
        final var looper = mTestLooper.getLooper();
        mService = new PbapClientService(mAdapterService, mMockStorage, mDeviceMap, looper);
        mService.setAvailable(true);

        // new
        doReturn(STATE_CONNECTED).when(mDeviceStateMachine).getConnectionState();
        mDeviceMap.put(mDevice, mDeviceStateMachine);
        mDeviceCallback = mService.new PbapClientStateMachineCallback(mDevice);
    }

    @After
    public void tearDown() {
        mService.cleanup();
    }

    // *********************************************************************************************
    // * Incoming Events
    // *********************************************************************************************

    // PbapClientStateMachineCallback events from devices

    @Test
    public void onConnectionStateChanged_DisconnectedToConnecting_eventIgnored() {
        doReturn(STATE_CONNECTING).when(mDeviceStateMachine).getConnectionState();
        mDeviceCallback.onConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mDeviceMap.containsKey(mDevice)).isTrue();
    }

    @Test
    public void onConnectionStateChanged_ConnectingToConnected_eventIgnored() {
        doReturn(STATE_CONNECTED).when(mDeviceStateMachine).getConnectionState();
        mDeviceCallback.onConnectionStateChanged(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mDeviceMap.containsKey(mDevice)).isTrue();
    }

    @Test
    public void onConnectionStateChanged_ConnectingToDisconnected_deviceCleanedUp() {
        doReturn(STATE_DISCONNECTED).when(mDeviceStateMachine).getConnectionState();
        mDeviceCallback.onConnectionStateChanged(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mDeviceMap.containsKey(mDevice)).isFalse();
    }

    @Test
    public void onConnectionStateChanged_ConnectedToDisconnecting_eventIgnored() {
        doReturn(STATE_DISCONNECTING).when(mDeviceStateMachine).getConnectionState();
        mDeviceCallback.onConnectionStateChanged(STATE_CONNECTED, STATE_DISCONNECTING);
        assertThat(mDeviceMap.containsKey(mDevice)).isTrue();
    }

    @Test
    public void onConnectionStateChanged_DisconnectingToDisconnected_deviceCleanedUp() {
        doReturn(STATE_DISCONNECTED).when(mDeviceStateMachine).getConnectionState();
        mDeviceCallback.onConnectionStateChanged(STATE_DISCONNECTING, STATE_DISCONNECTED);
        assertThat(mDeviceMap.containsKey(mDevice)).isFalse();
    }

    // ACL state changes from AdapterService

    @Test
    public void testOnBrEdrAclDisconnected_forConnectedDevice_deviceCleanedUp() {
        mService.aclDisconnected(mDevice, TRANSPORT_BREDR);
        mTestLooper.dispatchAll();
        verify(mDeviceStateMachine, times(1)).disconnect();
    }

    @Test
    public void testOnBrEdrAclDisconnected_forDisconnectedDevice_eventDropped() {
        mDeviceMap.clear();
        mService.aclDisconnected(mDevice, TRANSPORT_BREDR);
        mTestLooper.dispatchAll();
        verify(mDeviceStateMachine, never()).disconnect();
    }

    @Test
    public void testOnLeAclDisconnected_forConnectedDevice_eventDropped() {
        mService.aclDisconnected(mDevice, TRANSPORT_LE);
        mTestLooper.dispatchAll();
        verify(mDeviceStateMachine, never()).disconnect();
    }

    @Test
    public void testOnUnknownAclDisconnected_forConnectedDevice_deviceCleanedUp() {
        mService.aclDisconnected(mDevice, TRANSPORT_UNKNOWN);
        mTestLooper.dispatchAll();
        verify(mDeviceStateMachine, never()).disconnect();
    }

    // HFP HF State changes

    @Test
    public void testOnHfpClientDisconnectedForConnectedDevice_callLogsCleanedUp() {
        mService.handleHeadsetClientConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        Account account = Utils.getAccountForDevice(mDevice);
        verify(mMockStorage, times(1)).removeCallHistory(eq(account));
    }

    @Test
    public void testOnHfpClientDisconnectedForDisconnectedDevice_callLogsCleanedUp() {
        mDeviceMap.clear();
        mService.handleHeadsetClientConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        Account account = Utils.getAccountForDevice(mDevice);
        verify(mMockStorage, times(1)).removeCallHistory(eq(account));
    }

    // SDP Events from AdapterService

    @Test
    public void testOnSdpRecordReceived_deviceConnected_eventForwarded() {
        mService.receiveSdpSearchRecord(
                mDevice, SDP_SUCCESS, mMockSdpRecord, BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, times(1))
                .onSdpResultReceived(eq(SDP_SUCCESS), any(PbapSdpRecord.class));
    }

    @Test
    public void testOnSdpResultReceived_deviceDisconnected_eventDropped() {
        mDeviceMap.clear();
        mService.receiveSdpSearchRecord(
                mDevice, SDP_SUCCESS, mMockSdpRecord, BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, never())
                .onSdpResultReceived(anyInt(), any(PbapSdpRecord.class));
    }

    @Test
    public void testOnSdpResultReceived_nullRecord_eventForwardedWithNullPbapRecord() {
        // Verify that a null SdpPseRecord is forwarded to the state machine as a null
        // PbapSdpRecord, rather than being dropped.
        mService.receiveSdpSearchRecord(mDevice, SDP_SUCCESS, null, BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, times(1)).onSdpResultReceived(eq(SDP_SUCCESS), eq(null));
    }

    @Test
    public void testOnSdpResultReceived_wrongUuid_eventDropped() {
        mService.receiveSdpSearchRecord(
                mDevice, SDP_SUCCESS, mMockSdpRecord, /* wrong */ BluetoothUuid.MNS);
        verify(mDeviceStateMachine, never())
                .onSdpResultReceived(anyInt(), any(PbapSdpRecord.class));
    }

    @Test
    public void testOnSdpResultReceived_statusFailed_eventForwarded() {
        mService.receiveSdpSearchRecord(
                mDevice, SDP_FAILED, mMockSdpRecord, /* wrong */ BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, times(1))
                .onSdpResultReceived(eq(SDP_FAILED), any(PbapSdpRecord.class));
    }

    @Test
    public void testOnSdpResultReceived_statusBusy_eventForwarded() {
        mService.receiveSdpSearchRecord(
                mDevice, SDP_BUSY, mMockSdpRecord, /* wrong */ BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, times(1))
                .onSdpResultReceived(eq(SDP_BUSY), any(PbapSdpRecord.class));
    }

    @Test
    public void testOnSdpResultReceived_statusBusyAndNullRecord_eventForwarded() {
        // Verify that a BUSY status with a null record is forwarded to the state machine. This is
        // critical for the state machine to be able to retry the SDP search.
        mService.receiveSdpSearchRecord(mDevice, SDP_BUSY, null, BluetoothUuid.PBAP_PSE);
        verify(mDeviceStateMachine, times(1)).onSdpResultReceived(eq(SDP_BUSY), eq(null));
    }

    // *********************************************************************************************
    // * API Methods
    // *********************************************************************************************

    // connect (policy allowed) -> connect/true
    @Test
    public void testConnect_onAllowedAndUnconnectedDevice_deviceCreatedAndIsConnecting() {
        mDeviceMap.clear();
        assertThat(mService.connect(mDevice)).isTrue();

        // Clean up and wait for it to complete
        PbapClientStateMachine sm = mDeviceMap.get(mDevice);
        assertThat(sm).isNotNull();

        sm.disconnect();
        mTestLooper.dispatchAll();
    }

    // connect (device null) -> false
    @Test
    public void testConnect_onNullDevice_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> mService.connect(null));
    }

    // connect (policy forbidden) -> false
    @Test
    public void testConnect_onForbiddenAndUnconnectedDevice_deviceNotCreated() {
        mDeviceMap.clear();
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(BluetoothDevice.class), anyInt());
        assertThat(mService.connect(mDevice)).isFalse();
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    // connect (policy unknown) -> false
    @Test
    public void testConnect_onUnknownAndUnconnectedDevice_deviceNotCreated() {
        mDeviceMap.clear();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), anyInt());
        assertThat(mService.connect(mDevice)).isFalse();
    }

    // connect (already connected) -> false
    @Test
    public void testConnect_onAllowedAndConnectedDevice_connectNotCalled() {
        // existing/previous connection setup in setUp()
        assertThat(mService.connect(mDevice)).isFalse();
    }

    // connect (at device limit) -> false
    @Test
    public void testConnect_onAllowedAndUnconnectedDeviceWithTenConnected_connectNotCalled() {
        // Create 10 connected devices
        for (int i = 1; i <= 10; i++) {
            BluetoothDevice remoteDevice = getTestDevice(i);
            mDeviceMap.put(remoteDevice, mDeviceStateMachine);
        }

        assertThat(mService.connect(mDevice)).isFalse();
    }

    // disconnect (device connected) -> disconnect/true
    @Test
    public void testDisconnect_onConnectedDevice_deviceDisconnectRequested() {
        assertThat(mService.disconnect(mDevice)).isTrue();
        verify(mDeviceStateMachine, times(1)).disconnect();
    }

    // disconnect (device DNE) -> false
    @Test
    public void testDisconnect_onUnknownDevice_deviceNotCreatedAndDisconnectNotCalled() {
        mDeviceMap.clear();
        assertThat(mService.disconnect(mDevice)).isFalse();
    }

    // disconnect (device null) -> false
    @Test
    public void testDisconnect_onNullDevice_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> mService.disconnect(null));
    }

    // getConnectedDevices (device connected) -> has devices
    @Test
    public void testGetConnectedDevices_oneDeviceConnected_returnsConnectedDevice() {
        doReturn(STATE_CONNECTED).when(mDeviceStateMachine).getConnectionState();
        assertThat(mService.getConnectedDevices())
                .isEqualTo(Arrays.asList(new BluetoothDevice[] {mDevice}));
    }

    // getConnectedDevices (no device connected) -> empty
    @Test
    public void testGetConnectedDevices_noDevicesConnected_returnsNoDevices() {
        doReturn(STATE_DISCONNECTED).when(mDeviceStateMachine).getConnectionState();
        assertThat(mService.getConnectedDevices()).isEmpty();
    }

    // getDevicesMatchingConnectionStates (connected, one device connected)
    @Test
    public void testGetDevicesMatchingConnectionStates_connectedWithDevice_returnsDevice() {
        doReturn(STATE_CONNECTED).when(mDeviceStateMachine).getConnectionState();
        assertThat(mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .isEqualTo(Arrays.asList(new BluetoothDevice[] {mDevice}));
    }

    // getDevicesMatchingConnectionStates (connected, no device connected) -> empty
    @Test
    public void testGetDevicesMatchingConnectionStates_connectedWithNoDevice_returnsEmptyList() {
        doReturn(STATE_DISCONNECTED).when(mDeviceStateMachine).getConnectionState();
        assertThat(mService.getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED}))
                .isEmpty();
    }

    // getConnectionState (device connected) -> has device
    @Test
    public void testGetConnectionState_onConnectedDevice_returnsConnected() {
        doReturn(STATE_CONNECTED).when(mDeviceStateMachine).getConnectionState();
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
    }

    // getConnectionState (device null) -> exception
    @Test
    public void testGetConnectionState_onNullDevice_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> mService.getConnectionState(null));
    }

    // getConnectionState (device DNE) -> disconnected
    @Test
    public void testGetConnectionState_onDeviceDoesNotExist_returnsDisconnected() {
        mDeviceMap.clear();
        assertThat(mService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    // setConnectionPolicy (allowed -> connect) -> connect/true

    @Test
    public void testSetConnectionPolicy_toAllowed_connectIssued() {
        assertThat(mService.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
    }

    // setConnectionPolicy (forbidden -> disconnect) -> discount/true
    @Test
    public void testSetConnectionPolicy_toForbidden_disconnectIssued() {
        assertThat(mService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN)).isTrue();
        verify(mDeviceStateMachine, times(1)).disconnect();
    }

    // setConnectionPolicy (device null) -> exception
    @Test
    public void testSetConnectionPolicy_onNullDevice_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mService.setConnectionPolicy(null, CONNECTION_POLICY_ALLOWED));
    }

    // getConnectionPolicy -> returns what we set in setup() (allowed)
    @Test
    public void testGetConnectionPolicy_onKnownDevice_returnsAllowed() {
        assertThat(mService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testDump() {
        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
        String dumpContents = sb.toString();
        assertThat(dumpContents).isNotNull();
        assertThat(dumpContents.length()).isNotEqualTo(0);
    }
}
